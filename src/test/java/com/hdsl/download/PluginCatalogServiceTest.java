package com.hdsl.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hdsl.download.PluginCatalogService.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class PluginCatalogServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private URI base;
    private final Map<String, Handler> routes = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorization = new CopyOnWriteArrayList<>();
    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws Exception; }

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "catalog-fixture"); t.setDaemon(true); return t; });
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            String auth = exchange.getRequestHeaders().getFirst("Authorization"); if (auth != null) authorization.add(auth);
            try { routes.getOrDefault(exchange.getRequestURI().getRawPath(), e -> respond(e, 404, "{}".getBytes(StandardCharsets.UTF_8))).handle(exchange); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Exception ignored) { /* Cancellation deliberately closes in-flight fixture streams. */ }
            finally { exchange.close(); }
        });
        server.start(); base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    private PluginCatalogService service() { return service(Duration.ofSeconds(2), 2 * 1024 * 1024); }
    private PluginCatalogService service(Duration timeout, int limit) {
        return new PluginCatalogService(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), base, base, timeout, limit);
    }
    private void route(String path, Object data) throws Exception {
        byte[] bytes = JSON.writeValueAsBytes(data); routes.put(path, e -> respond(e, 200, bytes));
    }
    private static void respond(HttpExchange e, int status, byte[] bytes) throws IOException {
        e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes);
    }
    private void repo(String repository, ObjectNode pkg) throws Exception {
        route("/repos/" + repository, Map.of("full_name", repository, "default_branch", "main", "topics", List.of("dsh-plugin")));
        route("/repos/" + repository + "/contents/package.json", encoded(JSON.writeValueAsBytes(pkg)));
    }
    private static Map<String, Object> encoded(byte[] bytes) {
        return Map.of("type", "file", "encoding", "base64", "size", bytes.length, "content", Base64.getEncoder().encodeToString(bytes));
    }
    private static ObjectNode pkg(String name) {
        ObjectNode p = JSON.createObjectNode().put("name", name).put("version", "1.2.3");
        p.putObject("dsh").putObject("bundle").put("patch", "./cordis.patch.yml"); return p;
    }
    private static ObjectNode published(String name, String repository, String directory) {
        ObjectNode p = pkg(name); ObjectNode source = p.putObject("repository").put("type", "git").put("url", "git+https://github.com/" + repository + ".git");
        if (!directory.isEmpty()) source.put("directory", directory);
        p.putObject("dist").put("tarball", "https://registry.npmjs.org/plugin/-/plugin-1.2.3.tgz").put("shasum", "1".repeat(40)); return p;
    }
    private void npm(String name, ObjectNode pkg) throws Exception { route("/" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8) + "/latest", pkg); }
    private void search(long total, boolean incomplete, List<?> entries) throws Exception {
        route("/search/repositories", Map.of("total_count", total, "incomplete_results", incomplete, "items", entries));
    }
    private static Map<String, Object> result(String repo) {
        return Map.of("full_name", repo, "name", repo.substring(repo.indexOf('/') + 1), "description", "Synthetic plugin", "stargazers_count", 7, "html_url", "https://evil.example/spoof");
    }

    @Test void searchUsesTopicLiteralKeywordsAndDoesNotResolveEveryRepository() throws Exception {
        search(24, false, List.of(result("acme/plugin")));
        var page = service().search("中文 OR plugin", 2, false);
        assertEquals(2, page.page()); assertFalse(page.hasNext()); assertEquals(24, page.total());
        assertEquals(UNRESOLVED, page.entries().getFirst().status()); assertEquals("", page.entries().getFirst().spec());
        assertEquals("https://github.com/acme/plugin", page.entries().getFirst().url());
        assertEquals(1, requests.size());
        String query = URLDecoder.decode(requests.getFirst(), StandardCharsets.UTF_8);
        assertTrue(query.contains("topic:dsh-plugin")); assertTrue(query.contains("\"OR\"")); assertTrue(query.contains("per_page=12&page=2"));
        assertTrue(authorization.isEmpty());
    }

    @Test void malformedSearchAndQualifierInjectionAreRejectedBeforeNetworking() {
        var service = service();
        for (String query : List.of("topic:other", "repo:evil/name", "a\nOR b", "x".repeat(161)))
            assertThrows(IllegalArgumentException.class, () -> service.search(query, 1, false));
        assertThrows(IllegalArgumentException.class, () -> service.search("", 0, false));
        assertThrows(IllegalArgumentException.class, () -> service.search("", 85, false));
        assertTrue(requests.isEmpty());
    }

    @Test void searchBoundsPaginationAndFlagsIncompleteResults() throws Exception {
        search(17000, true, List.of(result("acme/plugin")));
        var last = service().search("", 84, true);
        assertFalse(last.hasNext()); assertTrue(last.warning().contains("1000")); assertTrue(last.warning().contains("部分"));
    }

    @Test void refreshFailurePreservesOnlyMatchingSearchCache() throws Exception {
        search(1, false, List.of(result("acme/plugin"))); var service = service();
        assertFalse(service.search("needle", 1, false).fromCache());
        assertTrue(service.search("needle", 1, false).fromCache()); assertEquals(1, requests.size());
        routes.put("/search/repositories", e -> { e.getResponseHeaders().set("X-RateLimit-Reset", "1800000000"); respond(e, 403, "untrusted body must not appear".getBytes(StandardCharsets.UTF_8)); });
        var cached = service.search("needle", 1, true);
        assertTrue(cached.fromCache()); assertEquals("acme/plugin", cached.entries().getFirst().repo());
        assertTrue(cached.warning().contains("403")); assertFalse(cached.warning().contains("untrusted"));
        assertThrows(IOException.class, () -> service.search("other", 1, true));
    }

    @Test void confirmsScopedPublishedBundleWithExactVersionWithoutExecutingScripts() throws Exception {
        ObjectNode source = pkg("@acme/plugin"); source.putObject("scripts").put("postinstall", "this-command-must-never-run");
        repo("acme/plugin", source); npm("@acme/plugin", published("@acme/plugin", "acme/plugin", ""));
        var detail = service().resolve("acme/plugin", false);
        assertEquals(INSTALLABLE, detail.status()); assertEquals(1, detail.targets().size());
        var target = detail.targets().getFirst(); assertEquals("@acme/plugin@1.2.3", target.spec());
        assertEquals("package.json", target.packagePath()); assertTrue(target.evidenceUrl().startsWith("https://registry.npmjs.org/"));
        assertTrue(detail.message().contains("未执行插件")); assertTrue(authorization.isEmpty());
        assertEquals(3, requests.size());
    }

    @Test void topicAndPackageKeywordsDoNotMakeAnAwesomeListInstallable() throws Exception {
        ObjectNode source = JSON.createObjectNode().put("name", "awesome-dsh-plugin"); source.putArray("keywords").add("dsh-plugin");
        repo("acme/list", source);
        var detail = service().resolve("acme/list", false);
        assertEquals(VIEW_PROJECT, detail.status()); assertTrue(detail.targets().isEmpty());
        assertFalse(requests.stream().anyMatch(p -> p.contains("/latest")));
    }

    @Test void absentTopicAndArchivedRepositoriesCannotBeInstalled() throws Exception {
        for (Map<String, Object> metadata : List.of(Map.of("full_name", "acme/plugin", "default_branch", "main", "topics", List.of()),
                Map.of("full_name", "acme/plugin", "archived", true, "topics", List.of("dsh-plugin")))) {
            route("/repos/acme/plugin", metadata);
            assertEquals(VIEW_PROJECT, service().resolve("acme/plugin", false).status());
        }
        assertEquals(2, requests.size());
    }

    @Test void publicationRequiresMatchingPackageRepoBundlePathAndTrustedDist() throws Exception {
        repo("acme/plugin", pkg("plugin"));
        List<ObjectNode> invalid = new ArrayList<>();
        invalid.add(published("other", "acme/plugin", ""));
        invalid.add(published("plugin", "someone/else", ""));
        ObjectNode noBundle = published("plugin", "acme/plugin", ""); noBundle.remove("dsh"); invalid.add(noBundle);
        ObjectNode escaping = published("plugin", "acme/plugin", ""); ((ObjectNode) escaping.path("dsh").path("bundle")).put("patch", "../outside.yml"); invalid.add(escaping);
        invalid.add(published("plugin", "acme/plugin", "packages/wrong"));
        ObjectNode evilDist = published("plugin", "acme/plugin", ""); ((ObjectNode) evilDist.path("dist")).put("tarball", "https://registry.npmjs.org.evil.example/x"); invalid.add(evilDist);
        ObjectNode insecure = published("plugin", "acme/plugin", ""); ((ObjectNode) insecure.path("dist")).put("tarball", "http://registry.npmjs.org/x"); invalid.add(insecure);
        ObjectNode noHash = published("plugin", "acme/plugin", ""); ((ObjectNode) noHash.path("dist")).remove("shasum"); invalid.add(noHash);
        invalid.add(published("plugin", "acme/plugin", "").put("private", true));
        invalid.add(published("plugin", "acme/plugin", "").put("deprecated", "Do not use"));
        invalid.add(published("plugin", "acme/plugin", "").put("version", "--evil"));
        for (ObjectNode publication : invalid) {
            npm("plugin", publication); var result = service().resolve("acme/plugin", false);
            assertEquals(VIEW_PROJECT, result.status(), publication.toString()); assertTrue(result.targets().isEmpty());
        }
    }

    @Test void unpublishedPackageRemainsViewOnly() throws Exception {
        repo("acme/plugin", pkg("plugin")); var details = service().resolve("acme/plugin", false);
        assertEquals(VIEW_PROJECT, details.status()); assertTrue(details.message().contains("尚未发布"));
    }

    @Test void resolvesOnlyDeclaredMonorepoPackagesAndHonorsExclusions() throws Exception {
        ObjectNode root = JSON.createObjectNode().put("private", true); root.putObject("workspaces").putArray("packages").add("packages/*").add("!packages/ignored");
        repo("acme/mono", root);
        route("/repos/acme/mono/git/trees/main", Map.of("truncated", false, "tree", List.of(
                treeEntry("packages/plugin/package.json", 1, "100644"), treeEntry("unrelated/package.json", 2, "100644"),
                treeEntry("packages/ignored/package.json", 3, "100644"), treeEntry("packages/link/package.json", 4, "120000"))));
        route("/repos/acme/mono/git/blobs/" + sha(1), encoded(JSON.writeValueAsBytes(pkg("mono-plugin"))));
        npm("mono-plugin", published("mono-plugin", "acme/mono", "packages/plugin"));
        var detail = service().resolve("acme/mono", false);
        assertEquals(INSTALLABLE, detail.status()); assertEquals("packages/plugin/package.json", detail.targets().getFirst().packagePath());
        assertFalse(requests.stream().anyMatch(p -> p.endsWith(sha(2)) || p.endsWith(sha(3)) || p.endsWith(sha(4))));
    }
    private static String sha(int value) { return String.format("%040x", value); }
    private static Map<String, String> treeEntry(String path, int sha, String mode) { return Map.of("path", path, "type", "blob", "mode", mode, "sha", sha(sha)); }

    @Test void supportsPnpmWorkspaceAndRejectsUnconfirmedRegistryDirectory() throws Exception {
        repo("acme/mono", JSON.createObjectNode().put("private", true));
        route("/repos/acme/mono/contents/pnpm-workspace.yaml", encoded("packages:\n  - 'plugins/*'\n".getBytes(StandardCharsets.UTF_8)));
        route("/repos/acme/mono/git/trees/main", Map.of("tree", List.of(treeEntry("plugins/foo/package.json", 1, "100644"))));
        route("/repos/acme/mono/git/blobs/" + sha(1), encoded(JSON.writeValueAsBytes(pkg("foo"))));
        npm("foo", published("foo", "acme/mono", ""));
        assertEquals(VIEW_PROJECT, service().resolve("acme/mono", false).status());
        npm("foo", published("foo", "acme/mono", "plugins/foo"));
        assertEquals(INSTALLABLE, service().resolve("acme/mono", false).status());
    }

    @Test void unsupportedWorkspaceExclusionDoesNotAccidentallyIncludePackages() throws Exception {
        ObjectNode root = JSON.createObjectNode().put("private", true);
        root.putArray("workspaces").add("packages/*").add("!packages/{ignored,test}"); repo("acme/mono", root);
        var result = service().resolve("acme/mono", false);
        assertEquals(VIEW_PROJECT, result.status()); assertTrue(result.message().contains("无法安全解析"));
        assertFalse(requests.stream().anyMatch(path -> path.contains("/git/trees/")));
    }

    @Test void recursiveWorkspaceWildcardAlsoMatchesZeroIntermediateDirectories() throws Exception {
        ObjectNode root = JSON.createObjectNode().put("private", true); root.putArray("workspaces").add("plugins/**/thing"); repo("acme/mono", root);
        route("/repos/acme/mono/git/trees/main", Map.of("tree", List.of(treeEntry("plugins/thing/package.json", 1, "100644"))));
        route("/repos/acme/mono/git/blobs/" + sha(1), encoded(JSON.writeValueAsBytes(pkg("thing"))));
        npm("thing", published("thing", "acme/mono", "plugins/thing"));
        assertEquals(INSTALLABLE, service().resolve("acme/mono", false).status());
    }

    @Test void monorepoScanHasAPackageCountLimitAndReportsTruncation() throws Exception {
        ObjectNode root = JSON.createObjectNode().put("private", true); root.putArray("workspaces").add("packages/*"); repo("acme/mono", root);
        List<Map<String, String>> entries = new ArrayList<>();
        for (int i = 0; i < 14; i++) { entries.add(treeEntry(String.format("packages/p%02d/package.json", i), i + 1, "100644"));
            route("/repos/acme/mono/git/blobs/" + sha(i + 1), encoded("{\"private\":true}".getBytes(StandardCharsets.UTF_8))); }
        route("/repos/acme/mono/git/trees/main", Map.of("truncated", true, "tree", entries));
        var result = service().resolve("acme/mono", false);
        assertEquals(VIEW_PROJECT, result.status()); assertEquals(12, requests.stream().filter(p -> p.contains("/git/blobs/")).count());
        assertTrue(result.message().contains("12")); assertTrue(result.message().contains("截断"));
    }

    @Test void rejectsSymlinkMetadataAndUnsafeYamlWithoutExecutingAnything() throws Exception {
        repo("acme/plugin", pkg("plugin"));
        route("/repos/acme/plugin/contents/package.json", Map.of("type", "symlink", "target", "https://evil.example/"));
        assertEquals(ERROR, service().resolve("acme/plugin", false).status());
        repo("acme/mono", JSON.createObjectNode().put("private", true));
        route("/repos/acme/mono/contents/pnpm-workspace.yaml", encoded("!!java.net.URL ['https://evil.example/']".getBytes(StandardCharsets.UTF_8)));
        assertEquals(ERROR, service().resolve("acme/mono", false).status());
    }

    @Test void failedMetadataRefreshRetainsPreviouslyConfirmedTargets() throws Exception {
        repo("acme/plugin", pkg("plugin")); npm("plugin", published("plugin", "acme/plugin", "")); var service = service();
        var first = service.resolve("acme/plugin", false); assertEquals(INSTALLABLE, first.status());
        routes.put("/repos/acme/plugin", e -> respond(e, 429, "do not expose me".getBytes(StandardCharsets.UTF_8)));
        var fallback = service.resolve("acme/plugin", true);
        assertTrue(fallback.fromCache()); assertEquals(first.targets(), fallback.targets()); assertTrue(fallback.message().contains("429"));
        assertFalse(fallback.message().contains("do not expose"));
    }

    @Test void oversizedAndMalformedResponsesHaveBoundedSafeErrors() throws Exception {
        routes.put("/search/repositories", e -> respond(e, 200, new byte[4096]));
        IOException size = assertThrows(IOException.class, () -> service(Duration.ofSeconds(1), 1024).search("", 1, true));
        assertTrue(size.getMessage().contains("大小"), size.getMessage());
        routes.put("/search/repositories", e -> respond(e, 200, "INVALID_UNTRUSTED_MARKER".getBytes(StandardCharsets.UTF_8)));
        IOException json = assertThrows(IOException.class, () -> service().search("", 1, true));
        assertFalse(json.getMessage().contains("INVALID_UNTRUSTED_MARKER"));
    }

    @Test void neverFollowsRedirectsAndRejectsNonLoopbackTestOrigins() throws Exception {
        routes.put("/search/repositories", e -> { e.getResponseHeaders().set("Location", base.resolve("unexpected").toString()); respond(e, 302, new byte[0]); });
        assertTrue(assertThrows(IOException.class, () -> service().search("", 1, true)).getMessage().contains("重定向"));
        assertEquals(1, requests.size());
        assertThrows(IllegalArgumentException.class, () -> new PluginCatalogService(HttpClient.newHttpClient(), URI.create("http://example.com/"), base, Duration.ofSeconds(1), 1024));
        assertThrows(IllegalArgumentException.class, () -> new PluginCatalogService(URI.create("http://user:pass@localhost:7890")));
        assertThrows(IllegalArgumentException.class, () -> service().resolve("acme/../evil", false));
    }

    @Test void fullBodyTimeoutAndCancellationAreBounded() throws Exception {
        CountDownLatch headers = new CountDownLatch(1);
        routes.put("/search/repositories", e -> { e.sendResponseHeaders(200, 0); e.getResponseBody().write('{'); e.getResponseBody().flush(); headers.countDown(); Thread.sleep(3000); });
        long started = System.nanoTime();
        assertTrue(assertThrows(IOException.class, () -> service(Duration.ofMillis(180), 1024).search("", 1, true)).getMessage().contains("超时"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
        AtomicBoolean cancel = new AtomicBoolean();
        CountDownLatch secondHeaders = new CountDownLatch(1);
        routes.put("/search/repositories", e -> { e.sendResponseHeaders(200, 0); e.getResponseBody().write('{'); e.getResponseBody().flush(); secondHeaders.countDown(); Thread.sleep(3000); });
        try (var worker = Executors.newSingleThreadExecutor()) {
            Future<?> pending = worker.submit(() -> service().search("", 1, true, cancel::get));
            assertTrue(secondHeaders.await(1, TimeUnit.SECONDS)); cancel.set(true);
            ExecutionException error = assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedIOException.class, error.getCause());
        }
    }

    @Test void cancellationDoesNotReturnCachedResults() throws Exception {
        search(1, false, List.of(result("acme/plugin"))); var service = service(); service.search("", 1, false);
        assertThrows(InterruptedIOException.class, () -> service.search("", 1, false, () -> true));
        assertEquals(1, requests.size());
    }
}
