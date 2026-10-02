package com.hdsl.pack;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.hdsl.runtime.RuntimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit network opt-in. Installs public package data; package hooks and plugin execution stay disabled. */
@EnabledIfSystemProperty(named = "hdsl.packDependencyIntegration", matches = "true")
class PackDependencyIntegrationTest {
    @Test void realDesktopPackRestoresPinnedDependenciesAndRefreshesOnlyOutdatedLock() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Path fixtures = Path.of(".test-data/real-packs").toAbsolutePath().normalize();
        Path archive = fixtures.resolve("desktop-pack-1.0.0.dspack");
        JsonNode index = json.readTree(Files.readAllBytes(fixtures.resolve("market-index.json")));
        JsonNode entry = null;
        for (JsonNode candidate : index.path("modpacks")) if (candidate.path("id").asText().equals("1900992335.desktop-pack")) entry = candidate;
        assertNotNull(entry); assertEquals(entry.path("size").asLong(), Files.size(archive)); assertEquals(entry.path("sha256").asText(), SafeArchive.sha(archive));
        Path runRoot = Files.createDirectories(Path.of(".test-data/real-pack-install-" + UUID.randomUUID().toString().substring(0, 8)).toAbsolutePath());
        Path home = runRoot.resolve("instances/desktop/dsh-home"), workspace = home.resolveSibling("workspace");
        Files.createDirectories(workspace);
        Path logFile = runRoot.resolve("restore.log");
        Consumer<String> log = message -> {
            String line = message.replaceAll("(?i)(token|password|secret|authorization)(\\s*[:=]\\s*)[^\\s,]+", "$1$2[redacted]");
            try { Files.writeString(logFile, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (java.io.IOException error) { throw new UncheckedIOException(error); }
            if (line.contains("ERR_") || line.contains("锁文件") || line.startsWith("Done in") || line.startsWith("Progress:")) System.out.println(line);
        };
        ObjectNode report = json.createObjectNode();
        report.put("startedAt", Instant.now().toString()).put("runDirectory", runRoot.toString());
        report.set("sourceIndexEntry", entry.deepCopy()); report.put("archiveSha256", SafeArchive.sha(archive));
        report.put("dependencyHooksEnabled", false).put("pluginCodeExecuted", false).put("harnessStarted", false);
        report.put("proxy", "http://127.0.0.1:7890").put("registry", "https://registry.npmjs.org");
        System.out.println("REAL_PACK_RUN_DIRECTORY=" + runRoot);
        try {
            PackService packs = new PackService(); PackInspection inspection = packs.inspect(archive);
            packs.extract(inspection, home, log);
            Path profile = home.resolve("profiles").resolve(inspection.defaultProfile()), lock = profile.resolve("pnpm-lock.yaml");
            String originalLockSha = SafeArchive.sha(lock);
            Path runtimeRoot = Path.of(".test-data/compatibility").toAbsolutePath().normalize();
            RuntimeService runtime = new RuntimeService(runtimeRoot, "https://registry.npmjs.org", "http://127.0.0.1:7890");
            report.put("runtimeToolRoot", runtimeRoot.toString()).put("profileDirectory", profile.toString());
            report.put("originalLockSha256", originalLockSha);
            runtime.restoreProfile(home, inspection.defaultProfile(), workspace, true, log);
            Path backup = profile.resolve(".hdsl-original-pnpm-lock.yaml");
            assertTrue(Files.isRegularFile(backup), "This real sample has a known importer mismatch and requires targeted lock refresh");
            assertEquals(originalLockSha, SafeArchive.sha(backup));
            report.put("originalLockRetained", true).put("refreshedLockSha256", SafeArchive.sha(lock));
            String logText = Files.readString(logFile);
            assertTrue(logText.contains("ERR_PNPM_OUTDATED_LOCKFILE")); assertTrue(logText.contains(".hdsl-original-pnpm-lock.yaml"));
            report.put("outdatedLockErrorObserved", true);
            LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(30); options.setCodePointLimit(4 * 1024 * 1024);
            Object lockData = new Yaml(new SafeConstructor(options)).load(Files.readString(lock));
            JsonNode currentLock = json.valueToTree(lockData);
            JsonNode pkg = json.readTree(Files.readAllBytes(profile.resolve("package.json")));
            ArrayNode dependencies = report.putArray("directDependencies");
            for (var it = pkg.path("dependencies").fields(); it.hasNext();) {
                var dependency = it.next(); String name = dependency.getKey(), expected = dependency.getValue().asText();
                Path metadata = profile.resolve("node_modules").resolve(name).resolve("package.json");
                assertTrue(Files.isRegularFile(metadata), "Installed dependency metadata must resolve through final links: " + name);
                JsonNode installed = json.readTree(Files.readAllBytes(metadata));
                assertEquals(expected, installed.path("version").asText(), "The manifest pins the direct dependency: " + name);
                assertEquals(expected, currentLock.path("importers").path(".").path("dependencies").path(name).path("specifier").asText());
                ObjectNode detail = dependencies.addObject().put("name", name).put("version", installed.path("version").asText());
                detail.put("resolvedPackageJson", metadata.toRealPath().toString()); detail.put("resolutionVerified", true);
                JsonNode patch = installed.path("dsh").path("bundle").path("patch");
                if (patch.isTextual()) {
                    Path patchFile = metadata.getParent().resolve(patch.asText()).normalize();
                    detail.put("bundlePatchExists", Files.isRegularFile(patchFile));
                    assertTrue(Files.isRegularFile(patchFile), "Declared bundle patch should be present: " + name);
                }
            }
            assertEquals(4, dependencies.size());
            report.put("status", "dependency-restoration-passed");
            report.put("scope", "Archive integrity, inspect/extract, real pnpm restore with hooks disabled, old lock backup, targeted outdated-lock refresh, exact direct dependency versions and filesystem resolution. Plugin behavior and Harness launch were not exercised.");
        } catch (Throwable error) {
            report.put("status", "failed").put("errorType", error.getClass().getName()).put("error", Objects.toString(error.getMessage(), ""));
            throw error;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            Files.writeString(runRoot.resolve("dependency-report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        }
    }
}
