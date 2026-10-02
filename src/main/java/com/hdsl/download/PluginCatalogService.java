package com.hdsl.download;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.hdsl.runtime.SemVer;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Public metadata only: never reads authentication files, downloads packages, or executes plugin code. */
public final class PluginCatalogService {
    public static final int PAGE_SIZE = 12;
    private static final int SEARCH_LIMIT = 1000, MAX_PACKAGES = 12, MAX_FILE_BYTES = 256 * 1024;
    private static final URI GITHUB = URI.create("https://api.github.com/"), NPM = URI.create("https://registry.npmjs.org/");
    private static final long CACHE_NANOS = Duration.ofMinutes(5).toNanos();
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(80).maxStringLength(1024 * 1024).maxNumberLength(100).build()).build());
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern PACKAGE = Pattern.compile("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*");

    public enum Status { UNRESOLVED, INSTALLABLE, VIEW_PROJECT, ERROR }
    public record CatalogEntry(String repo, String name, String description, int stars, String url, String spec, Status status) { }
    public record CatalogPage(List<CatalogEntry> entries, long total, int page, boolean hasNext, boolean fromCache, String warning) {
        public CatalogPage { entries = List.copyOf(entries); }
    }
    public record InstallTarget(String name, String version, String spec, String packagePath, String evidenceUrl) { }
    public record PluginDetails(String repo, List<InstallTarget> targets, Status status, String message, boolean fromCache) {
        public PluginDetails { targets = List.copyOf(targets); }
    }
    private record Cached<T>(T value, long time) { }
    private final HttpClient http;
    private final URI github, npm;
    private final Duration timeout;
    private final int maxBytes;
    private final Map<String, Cached<CatalogPage>> pages = lru(64);
    private final Map<String, Cached<PluginDetails>> details = lru(128);

    public PluginCatalogService(URI proxy) {
        this(client(proxy), GITHUB, NPM, Duration.ofSeconds(15), 2 * 1024 * 1024);
    }
    public PluginCatalogService() { this(null); }

    /** Package-private endpoint injection is restricted to loopback HTTP fixtures or the production origins. */
    PluginCatalogService(HttpClient http, URI github, URI npm, Duration timeout, int maxBytes) {
        this.http = Objects.requireNonNull(http);
        if (http.followRedirects() != HttpClient.Redirect.NEVER) throw new IllegalArgumentException("目录请求禁止自动重定向。");
        this.github = endpoint(github, GITHUB); this.npm = endpoint(npm, NPM);
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(60)) > 0 || maxBytes < 256 || maxBytes > 4 * 1024 * 1024)
            throw new IllegalArgumentException("目录请求边界无效。");
        this.timeout = timeout; this.maxBytes = maxBytes;
    }

    public CatalogPage search(String query, int page, boolean refresh) throws IOException { return search(query, page, refresh, () -> false); }
    public CatalogPage search(String query, int page, boolean refresh, BooleanSupplier cancelled) throws IOException {
        String words = query == null ? "" : query.strip();
        if (words.length() > 160 || !words.matches("[\\p{L}\\p{M}\\p{N} @._/\\-]*"))
            throw new IllegalArgumentException("搜索词限 160 字符，可用文字、数字、空格和 @ . _ / -；不接受 GitHub 查询指令。");
        if (page < 1 || page > (SEARCH_LIMIT + PAGE_SIZE - 1) / PAGE_SIZE) throw new IllegalArgumentException("页码超出 GitHub 搜索范围。");
        String key = words.toLowerCase(Locale.ROOT) + "\n" + page;
        check(cancelled); Cached<CatalogPage> old = pages.get(key);
        if (!refresh && fresh(old)) return cachedPage(old.value, "");
        String q = "topic:dsh-plugin archived:false";
        for (String word : words.split(" +")) if (!word.isBlank()) q += " \"" + word + "\"";
        if (!words.isEmpty()) q += " in:name,description,readme";
        try {
            JsonNode root = getJson(github.resolve("search/repositories?q=" + enc(q) + "&sort=stars&order=desc&per_page=" + PAGE_SIZE + "&page=" + page), cancelled, deadline(), false);
            if (!root.path("items").isArray() || !root.path("total_count").canConvertToLong() || root.path("items").size() > PAGE_SIZE)
                throw new IOException("GitHub 返回的目录格式无效。");
            List<CatalogEntry> entries = new ArrayList<>(); Set<String> seen = new HashSet<>();
            for (JsonNode item : root.path("items")) {
                String repo = item.path("full_name").asText("");
                if (!validRepo(repo) || item.path("private").asBoolean() || item.path("archived").asBoolean() || !seen.add(repo.toLowerCase(Locale.ROOT))) continue;
                entries.add(new CatalogEntry(repo, clean(item.path("name").asText(repo), 120), clean(item.path("description").asText(""), 600),
                        Math.max(0, item.path("stargazers_count").asInt()), "https://github.com/" + repo, "", Status.UNRESOLVED));
            }
            long total = Math.max(0, root.path("total_count").asLong());
            String warning = total > SEARCH_LIMIT ? "GitHub 每次搜索最多提供前 1000 个结果，请用关键词缩小范围。" : "";
            if (root.path("incomplete_results").asBoolean()) warning = join(warning, "GitHub 返回了部分结果，可稍后刷新。");
            CatalogPage result = new CatalogPage(entries, total, page, (long) page * PAGE_SIZE < Math.min(total, SEARCH_LIMIT), false, warning);
            check(cancelled); pages.put(key, new Cached<>(result, System.nanoTime())); return result;
        } catch (InterruptedIOException cancelledError) { throw cancelledError; }
        catch (IOException error) {
            if (old != null) return cachedPage(old.value, error.getMessage() + " 已保留此搜索页的缓存。");
            throw error;
        }
    }

    public PluginDetails resolve(String repository, boolean refresh) throws IOException { return resolve(repository, refresh, () -> false); }
    public PluginDetails resolve(String repository, boolean refresh, BooleanSupplier cancelled) throws IOException {
        if (!validRepo(repository)) throw new IllegalArgumentException("需要有效的 GitHub owner/repository。");
        check(cancelled); String key = repository.toLowerCase(Locale.ROOT); Cached<PluginDetails> old = details.get(key);
        if (!refresh && fresh(old)) return cachedDetails(old.value, "");
        try {
            PluginDetails result = inspect(repository, cancelled, deadline());
            check(cancelled); details.put(key, new Cached<>(result, System.nanoTime())); return result;
        } catch (InterruptedIOException cancelledError) { throw cancelledError; }
        catch (IOException error) {
            if (old != null) return cachedDetails(old.value, error.getMessage() + " 已保留上次核验结果；所示版本可能不是最新。");
            return new PluginDetails(repository, List.of(), Status.ERROR, error.getMessage(), false);
        }
    }

    private PluginDetails inspect(String repo, BooleanSupplier cancel, long deadline) throws IOException {
        JsonNode repository = getJson(github.resolve("repos/" + repo), cancel, deadline, false);
        if (!repo.equalsIgnoreCase(repository.path("full_name").asText()) || repository.path("private").asBoolean())
            throw new IOException("GitHub 返回的仓库身份不符。");
        if (repository.path("archived").asBoolean()) return view(repo, "仓库已归档，请查看项目说明。");
        boolean topic = false;
        for (JsonNode value : repository.path("topics")) if (value.asText().equals("dsh-plugin")) topic = true;
        if (!topic) return view(repo, "该仓库未声明 dsh-plugin 话题，请查看项目。");
        String branch = repository.path("default_branch").asText("");
        if (branch.isBlank() || branch.length() > 200 || branch.chars().anyMatch(c -> c < 32)) throw new IOException("仓库默认分支无效。");
        byte[] rootBytes = file(repo, "package.json", branch, cancel, deadline);
        JsonNode root = rootBytes == null ? MissingNode.getInstance() : json(rootBytes);
        if (!root.isMissingNode() && !root.isObject()) throw new IOException("仓库 package.json 格式无效。");
        List<String> messages = new ArrayList<>(), patterns = new ArrayList<>();
        addPatterns(root.path("workspaces").isObject() ? root.path("workspaces").path("packages") : root.path("workspaces"), patterns, messages);
        if (patterns.isEmpty() && (root.isMissingNode() || root.path("private").asBoolean() || !bundle(root))) {
            byte[] yaml = file(repo, "pnpm-workspace.yaml", branch, cancel, deadline);
            if (yaml != null) {
                LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(10);
                options.setNestingDepthLimit(30); options.setCodePointLimit(MAX_FILE_BYTES);
                try {
                    Object data = new Yaml(new SafeConstructor(options)).load(new String(yaml, StandardCharsets.UTF_8));
                    if (data instanceof Map<?, ?> map && map.get("packages") instanceof List<?> list)
                        addPatterns(JSON.valueToTree(list), patterns, messages);
                } catch (RuntimeException invalid) { throw new IOException("pnpm workspace 声明格式无效。"); }
            }
        }
        LinkedHashMap<String, JsonNode> manifests = new LinkedHashMap<>();
        if (bundle(root) && !root.path("private").asBoolean()) manifests.put("package.json", root);
        if (!patterns.isEmpty()) {
            JsonNode tree = getJson(github.resolve("repos/" + repo + "/git/trees/" + enc(branch) + "?recursive=1"), cancel, deadline, false);
            if (!tree.path("tree").isArray() || tree.path("tree").size() > 25000) throw new IOException("仓库目录树超出可检查范围。");
            if (tree.path("truncated").asBoolean()) messages.add("GitHub 目录树被截断，仅核验已返回且符合 workspace 声明的包。");
            TreeMap<String, String> candidates = new TreeMap<>();
            for (JsonNode entry : tree.path("tree")) {
                String path = entry.path("path").asText("");
                if (!path.endsWith("/package.json") || !safePath(path) || !entry.path("type").asText().equals("blob") ||
                        !Set.of("100644", "100755").contains(entry.path("mode").asText())) continue;
                if (!matchesWorkspace(path.substring(0, path.length() - "/package.json".length()), patterns)) continue;
                String sha = entry.path("sha").asText("");
                if (sha.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) candidates.put(path, sha);
            }
            if (candidates.size() > MAX_PACKAGES) messages.add("workspace 较大，本次仅检查前 " + MAX_PACKAGES + " 个包；其余请查看项目。");
            int count = 0;
            for (var entry : candidates.entrySet()) {
                if (count++ >= MAX_PACKAGES) break;
                JsonNode blob = getJson(github.resolve("repos/" + repo + "/git/blobs/" + entry.getValue()), cancel, deadline, false);
                JsonNode pkg = json(decode(blob));
                if (bundle(pkg) && !pkg.path("private").asBoolean()) manifests.put(entry.getKey(), pkg);
            }
        }
        if (manifests.isEmpty()) return new PluginDetails(repo, List.of(), Status.VIEW_PROJECT,
                join("未发现可确认的公开 dsh.bundle.patch 包；话题标签本身不代表可安装插件。", String.join(" ", messages)), false);
        List<InstallTarget> targets = new ArrayList<>(); Set<String> names = new HashSet<>();
        for (var entry : manifests.entrySet()) {
            check(cancel); String name = entry.getValue().path("name").asText("");
            if (name.length() > 214 || !PACKAGE.matcher(name).matches() || !names.add(name)) { messages.add("忽略无效或重复的 npm 包名。"); continue; }
            JsonNode published = getJson(npm.resolve(enc(name) + "/latest"), cancel, deadline, true);
            String reason = published.isMissingNode() ? "npm 尚未发布或无 latest 版本" : publicationProblem(repo, entry.getKey(), name, published);
            if (!reason.isEmpty()) { messages.add(name + "：" + reason + "。"); continue; }
            String version = published.path("version").asText();
            targets.add(new InstallTarget(name, version, name + "@" + version, entry.getKey(), "https://registry.npmjs.org/" + enc(name) + "/" + enc(version)));
        }
        String message = targets.isEmpty() ? "未确认可直接安装的 npm 目标，请查看项目。" : "已核对仓库声明、npm 发布与 dsh.bundle.patch；未执行插件或验证运行兼容性。";
        return new PluginDetails(repo, targets, targets.isEmpty() ? Status.VIEW_PROJECT : Status.INSTALLABLE, join(message, String.join(" ", messages)), false);
    }

    private static String publicationProblem(String repo, String path, String name, JsonNode pkg) {
        if (!name.equals(pkg.path("name").asText()) || !SemVer.isVersion(pkg.path("version").asText())) return "npm 包名或版本无效";
        if (pkg.path("private").asBoolean() || !bundle(pkg)) return "已发布版本没有可确认的 dsh.bundle.patch 声明";
        if (!pkg.path("deprecated").asText("").isBlank()) return "npm 已将此版本标记为弃用";
        JsonNode source = pkg.path("repository");
        String repository = source.isTextual() ? source.asText() : source.path("url").asText("");
        if (!repo.equalsIgnoreCase(repositoryName(repository))) return "npm 发布的仓库地址与当前项目不符";
        String directory = source.isObject() ? source.path("directory").asText("") : "";
        while (directory.startsWith("./")) directory = directory.substring(2);
        String expected = path.equals("package.json") ? "" : path.substring(0, path.length() - "/package.json".length());
        if (!directory.equals(expected)) return "npm 发布的 workspace 路径未确认";
        if (!trustedHttps(pkg.path("dist").path("tarball").asText(""), "registry.npmjs.org")) return "npm 压缩包地址不在官方 HTTPS 源";
        String integrity = pkg.path("dist").path("integrity").asText("");
        if (!integrity.matches("sha512-[A-Za-z0-9+/]{86}==") && !pkg.path("dist").path("shasum").asText("").matches("[0-9a-fA-F]{40}"))
            return "npm 发布信息缺少可识别的完整性摘要";
        return "";
    }

    private byte[] file(String repo, String path, String branch, BooleanSupplier cancel, long deadline) throws IOException {
        JsonNode result = getJson(github.resolve("repos/" + repo + "/contents/" + path + "?ref=" + enc(branch)), cancel, deadline, true);
        if (result.isMissingNode()) return null;
        if (!result.path("type").asText().equals("file") || result.has("target") || result.hasNonNull("submodule_git_url"))
            throw new IOException("仓库元数据必须是普通文件。");
        return decode(result);
    }
    private static byte[] decode(JsonNode file) throws IOException {
        if (!file.path("encoding").asText().equals("base64") || file.path("size").asLong(-1) < 0 || file.path("size").asLong() > MAX_FILE_BYTES)
            throw new IOException("仓库元数据文件过大或编码不受支持。");
        try {
            byte[] decoded = Base64.getDecoder().decode(file.path("content").asText("").replace("\n", "").replace("\r", ""));
            if (decoded.length > MAX_FILE_BYTES || decoded.length != file.path("size").asLong()) throw new IOException("仓库元数据大小与声明不符。");
            return decoded;
        } catch (IllegalArgumentException malformed) { throw new IOException("仓库元数据编码无效。"); }
    }
    private static boolean bundle(JsonNode pkg) {
        String patch = pkg.path("dsh").path("bundle").path("patch").asText("");
        while (patch.startsWith("./")) patch = patch.substring(2);
        return pkg.isObject() && !patch.isBlank() && safePath(patch);
    }
    private static void addPatterns(JsonNode node, List<String> patterns, List<String> warnings) {
        if (!node.isMissingNode() && !node.isNull() && !node.isArray()) { warnings.add("workspace 声明形式暂不支持。"); return; }
        boolean unsupported = false;
        for (JsonNode item : node) {
            String pattern = item.isTextual() ? item.asText() : "";
            if (pattern.startsWith("./")) pattern = pattern.substring(2);
            if (patterns.size() >= 64) { warnings.add("workspace 模式超过 64 条，无法完整核验排除规则。"); unsupported = true; break; }
            if (!pattern.matches("!?[A-Za-z0-9_@.*/-]{1,200}") || pattern.contains("..") || pattern.startsWith("/") || pattern.startsWith("!/"))
                { warnings.add("有无法安全解析的 workspace 模式，本次不自动解析这些工作区包，请查看项目。"); unsupported = true; }
            else patterns.add(pattern.replaceAll("/+$", ""));
        }
        // Dropping an unsupported exclusion while honoring inclusions would expose unintended packages.
        if (unsupported) patterns.clear();
    }
    private static boolean matchesWorkspace(String directory, List<String> patterns) {
        boolean included = false;
        for (String p : patterns) {
            boolean exclude = p.startsWith("!"); String pattern = exclude ? p.substring(1) : p;
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*') {
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                        if (i + 2 < pattern.length() && pattern.charAt(i + 2) == '/') { regex.append("(?:.*/)?"); i += 2; }
                        else { regex.append(".*"); i++; }
                    }
                    else regex.append("[^/]*");
                } else regex.append(Pattern.quote(String.valueOf(c)));
            }
            if (directory.matches(regex + "$")) { if (exclude) return false; included = true; }
        }
        return included;
    }
    private static boolean safePath(String path) {
        if (path.isBlank() || path.length() > 512 || path.startsWith("/") || path.contains("\\") || path.contains(":") || path.chars().anyMatch(c -> c < 32 || c == 127)) return false;
        for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equals("node_modules")) return false;
        return true;
    }
    private static String repositoryName(String source) {
        String value = source;
        if (value.startsWith("github:")) value = "https://github.com/" + value.substring(7);
        if (value.startsWith("git+")) value = value.substring(4);
        if (value.startsWith("git@github.com:")) value = "https://github.com/" + value.substring(15);
        try {
            URI uri = URI.create(value);
            if (!"github.com".equalsIgnoreCase(uri.getHost()) || uri.getPort() != -1 || !Set.of("https", "ssh", "git").contains(uri.getScheme())) return "";
            if (uri.getUserInfo() != null && !(uri.getScheme().equals("ssh") && uri.getUserInfo().equals("git"))) return "";
            String path = uri.getPath().replaceFirst("^/", "").replaceFirst("\\.git/?$", "").replaceFirst("/$", "");
            return validRepo(path) ? path : "";
        } catch (IllegalArgumentException invalid) { return ""; }
    }

    private JsonNode getJson(URI uri, BooleanSupplier cancel, long deadline, boolean missingAllowed) throws IOException {
        HttpResponse<byte[]> response = request(uri, cancel, deadline);
        if (response.statusCode() == 404 && missingAllowed) return MissingNode.getInstance();
        if (response.statusCode() != 200) throw new IOException(httpError(response, uri.getHost().equals(github.getHost()) && uri.getPort() == github.getPort()));
        return json(response.body());
    }
    private HttpResponse<byte[]> request(URI uri, BooleanSupplier cancel, long deadline) throws IOException {
        check(cancel);
        if (!sameOrigin(uri, github) && !sameOrigin(uri, npm)) throw new IOException("拒绝访问目录之外的地址。");
        long requestDeadline = Math.min(deadline, System.nanoTime() + timeout.toNanos());
        if (System.nanoTime() >= requestDeadline) throw new IOException("插件目录查询超时，请稍后重试。");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json")
                .header("User-Agent", "HDSL-PluginCatalog").header("X-GitHub-Api-Version", "2022-11-28").GET().build();
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, info -> new LimitedBody(maxBytes, info.headers().firstValueAsLong("Content-Length").orElse(-1)));
        try {
            while (true) {
                check(cancel); long remaining = requestDeadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("插件目录查询超时，请检查代理或稍后重试。");
                try { return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); }
                catch (TimeoutException pending) { /* Re-check cancellation and the full-body deadline. */ }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("插件目录查询已取消。");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof BodyLimitException) throw new IOException("插件目录响应超过大小限制。");
            if (error.getCause() instanceof HttpTimeoutException) throw new IOException("插件目录查询超时，请检查代理或稍后重试。");
            throw new IOException("插件目录连接失败，请检查网络和代理。");
        } finally { if (!future.isDone()) future.cancel(true); }
    }
    private static String httpError(HttpResponse<?> response, boolean github) {
        int status = response.statusCode(); String service = github ? "GitHub" : "npm";
        if (status == 401) return service + " 公开接口拒绝未登录请求；本目录不读取 GitHub CLI 凭据。";
        if (status == 403 || status == 429) {
            String reset = "";
            try { long epoch = Long.parseLong(response.headers().firstValue("X-RateLimit-Reset").orElse(""));
                if (epoch > 0 && epoch < Instant.now().plus(Duration.ofDays(7)).getEpochSecond()) reset = " 额度重置时间：" + Instant.ofEpochSecond(epoch) + "。";
            } catch (RuntimeException ignored) { }
            return service + " 公开接口限流或拒绝访问（HTTP " + status + "），请稍后刷新。" + reset;
        }
        if (status >= 300 && status < 400) return service + " 返回重定向，目录未跟随该地址，请查看项目。";
        return service + " 查询失败（HTTP " + status + "）。";
    }
    private static JsonNode json(byte[] data) throws IOException {
        try { JsonNode value = JSON.readTree(data); if (value == null) throw new IOException(); return value; }
        catch (IOException malformed) { throw new IOException("插件目录元数据不是有效 JSON。"); }
    }
    private static boolean validRepo(String repo) { return repo != null && REPO.matcher(repo).matches() && !Set.of(".", "..").contains(repo.substring(repo.indexOf('/') + 1)); }
    private static boolean trustedHttps(String value, String host) {
        try { URI u = URI.create(value); return u.getScheme().equals("https") && host.equalsIgnoreCase(u.getHost()) && u.getUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443) && u.getFragment() == null; }
        catch (RuntimeException invalid) { return false; }
    }
    private static boolean sameOrigin(URI a, URI b) { return Objects.equals(a.getScheme(), b.getScheme()) && Objects.equals(a.getHost(), b.getHost()) && a.getPort() == b.getPort() && a.getUserInfo() == null; }
    private static URI endpoint(URI uri, URI production) {
        if (uri.equals(production)) return uri;
        if (!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1", "[::1]").contains(uri.getHost()) || uri.getPort() < 1 ||
                uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().equals("/")) throw new IllegalArgumentException("测试端点必须是本地回环 HTTP 地址。");
        return uri;
    }
    private static HttpClient client(URI proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER);
        if (proxy != null) {
            if (!("http".equals(proxy.getScheme()) || "https".equals(proxy.getScheme())) || proxy.getHost() == null || proxy.getUserInfo() != null || proxy.getQuery() != null || proxy.getFragment() != null ||
                    (proxy.getPort() != -1 && (proxy.getPort() < 1 || proxy.getPort() > 65535))) throw new IllegalArgumentException("代理需要不含凭据的 HTTP(S) 地址。");
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort() < 0 ? 80 : proxy.getPort())));
        }
        return builder.build();
    }
    private static void check(BooleanSupplier cancel) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || (cancel != null && cancel.getAsBoolean())) throw new InterruptedIOException("插件目录查询已取消。");
    }
    private static long deadline() { return System.nanoTime() + Duration.ofSeconds(60).toNanos(); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String clean(String text, int max) { String clean = text.replaceAll("[\\p{Cc}\\p{Cf}]", " ").strip(); return clean.length() > max ? clean.substring(0, max) : clean; }
    private static String join(String a, String b) { return b.isBlank() ? a : a.isBlank() ? b : a + " " + b; }
    private static boolean fresh(Cached<?> cached) { return cached != null && System.nanoTime() - cached.time < CACHE_NANOS; }
    private static CatalogPage cachedPage(CatalogPage value, String warning) { return new CatalogPage(value.entries, value.total, value.page, value.hasNext, true, join(value.warning, warning)); }
    private static PluginDetails cachedDetails(PluginDetails value, String warning) { return new PluginDetails(value.repo, value.targets, value.status, join(value.message, warning), true); }
    private static PluginDetails view(String repo, String message) { return new PluginDetails(repo, List.of(), Status.VIEW_PROJECT, message, false); }
    private static <T> Map<String, T> lru(int capacity) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, T> eldest) { return size() > capacity; }
        });
    }
    private static final class BodyLimitException extends IOException { }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit; private Flow.Subscription subscription;
        LimitedBody(int limit, long declared) { this.limit = limit; if (declared > limit) result.completeExceptionally(new BodyLimitException()); }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; if (result.isDone()) value.cancel(); else value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int size = buffer.remaining();
                if (size > limit - bytes.size()) { subscription.cancel(); result.completeExceptionally(new BodyLimitException()); return; }
                byte[] part = new byte[size]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
