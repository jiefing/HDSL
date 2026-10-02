package com.hdsl.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: installs real public packages and boots only disposable, isolated profiles. */
@EnabledIfSystemProperty(named = "hdsl.integration", matches = "true")
class RuntimeIntegrationTest {
    @Test void oldAndCurrentRuntimeBootAndManageLocalPlugin() throws Exception {
        Path root = Path.of(".test-data/compatibility").toAbsolutePath().normalize();
        Files.createDirectories(root);
        RuntimeService service = new RuntimeService(root, "https://registry.npmjs.org", "http://127.0.0.1:7890");
        List<String> versions = List.of(System.getProperty("hdsl.integration.versions", "0.1.0-rc.6,0.2.0-rc.2").split(","));
        StringBuilder report = new StringBuilder("# Runtime compatibility integration\n\nDate: " + LocalDate.now() + "\n\n");
        for (String version : versions) {
            System.out.println("INTEGRATION install " + version);
            service.install(version, message -> { if (message.startsWith("Progress:") || message.contains("ERR_") || message.contains("已固定") || message.startsWith("Done in")) System.out.println(message); });
            System.out.println("INTEGRATION probe " + version);
            Capabilities caps = service.inspect(version, System.out::println);
            assertTrue(caps.profileSelection()); assertTrue(caps.pluginManagement()); assertTrue(caps.supports("--port"));
            Path instance = root.resolve("instances").resolve("test-" + version + "-" + UUID.randomUUID().toString().substring(0, 8));
            Path home = instance.resolve("dsh-home"), workspace = instance.resolve("workspace");
            Files.createDirectories(workspace); Files.createDirectories(home); Files.createDirectories(instance.resolve("agents-home"));
            int port; try (var socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
            List<String> command = service.launchCommand(version, "web", port, home, workspace, System.out::println);
            ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true)
                    .redirectOutput(instance.resolve("synthetic-runtime.log").toFile());
            service.configureEnvironment(builder.environment(), home, workspace);
            Process process = builder.start();
            int observedStatus = 0;
            try {
                boolean ready = false;
                HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) { }
                }).build();
                for (int i = 0; i < 90 && process.isAlive(); i++) {
                    try {
                        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
                        observedStatus = response.statusCode();
                        if (observedStatus == 200 || observedStatus == 401 || observedStatus == 403) { ready = true; break; }
                    } catch (IOException ignored) { }
                    Thread.sleep(1000);
                }
                assertTrue(ready, version + " did not become HTTP ready; isolated log: " + instance.resolve("synthetic-runtime.log"));
                System.out.println("INTEGRATION HTTP " + observedStatus + " " + version + " port=" + port);
            } finally {
                for (ProcessHandle child : process.descendants().toList().reversed()) child.destroyForcibly();
                process.destroyForcibly(); process.waitFor();
            }
            PluginService plugins = new PluginService(service);
            List<PluginEntry> builtins = plugins.list(version, "web", home, workspace, System.out::println);
            assertTrue(builtins.stream().anyMatch(PluginEntry::official));
            Path localPlugin = root.resolve("fixtures/local plugin"); Files.createDirectories(localPlugin);
            Files.writeString(localPlugin.resolve("package.json"), """
                    {"name":"hdsl-compat-fixture","version":"1.0.0","private":true,"dsh":{"bundle":{"patch":"./cordis.patch.yml"},"futureMetadata":{"preserve":true}}}
                    """);
            Files.writeString(localPlugin.resolve("cordis.patch.yml"), "[]\n");
            plugins.mutate("add", "file:" + localPlugin, version, "web", home, workspace, System.out::println);
            assertTrue(plugins.list(version, "web", home, workspace, null).stream().anyMatch(e -> e.id().equals("hdsl-compat-fixture")));
            plugins.mutate("remove", "hdsl-compat-fixture", version, "web", home, workspace, System.out::println);
            assertFalse(plugins.list(version, "web", home, workspace, null).stream().anyMatch(e -> e.id().equals("hdsl-compat-fixture")));
            report.append("- ").append(version).append(": installed; capability help confirmed; HTTP ").append(observedStatus)
                    .append("; native plugin list and local package add/remove passed. Instance: ").append(instance).append("\n");
            Files.writeString(root.resolve("integration-report.md"), report.toString());
            Files.writeString(root.resolve("integration-" + version + ".md"), report.toString());
        }
    }
}
