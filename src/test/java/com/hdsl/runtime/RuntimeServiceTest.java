package com.hdsl.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeServiceTest {
    @TempDir Path root;
    private static final String OLD_HELP = """
            Usage: dsh [options] [command] [args...]
            Options:
              -V, --version       output version
              --profile <name>    profile
              --patch <path>      overlay
              --dump-config       show config
            Commands:
              web [options]       boot web
              plugin [options]    pnpm manager
            """;
    private static final String NEW_HELP = """
            Usage: dsh [--profile] <name> [options] [app-args...]
                   dsh plugin --profile <name> <pnpm-args...>
            Options:
              --profile <name>    profile
              --from-default-profile <name> create profile
              --dump-config-schema show schemas
              --future-feature <value> supported new launcher flag
            Examples:
              dsh web --help
              dsh web --undocumented-example
            """;
    private static final String WEB_HELP = """
            Usage: dsh web [options]
            Options:
              -p, --port <port>   port
              --host <address>    bind host
            """;

    @Test void detectsOldAndNewCliWithoutVersionRules() throws Exception {
        for (String help : List.of(OLD_HELP, NEW_HELP)) {
            Capabilities caps = Capabilities.fromHelp("9.77.0-future", help, WEB_HELP, true);
            assertTrue(caps.profileSelection()); assertTrue(caps.pluginManagement()); assertTrue(caps.supports("--port"));
            assertFalse(caps.supports("--undocumented-example"));
            assertEquals(List.of("node", "dsh.js", "--profile", "web", "--port", "4321", "--host", "127.0.0.1"),
                    RuntimeService.assembleLaunch(List.of("node", "dsh.js"), caps, "web", 4321));
        }
        assertTrue(Capabilities.fromHelp("99.0.0", NEW_HELP, WEB_HELP, true).supports("--future-feature"));
    }

    @Test void unknownCapabilitiesFailInsteadOfGuessing() {
        Capabilities caps = Capabilities.fromHelp("99.0.0", "New app usage: harness serve", "", false);
        assertFalse(caps.profileSelection()); assertFalse(caps.pluginManagement()); assertFalse(caps.supports("--port"));
        assertThrows(IOException.class, () -> RuntimeService.assembleLaunch(List.of("node"), caps, "web", 3080));
        Capabilities noPort = Capabilities.fromHelp("0.1.0", OLD_HELP, "", false);
        assertThrows(IOException.class, () -> RuntimeService.assembleLaunch(List.of("node"), noPort, "web", 3080));
    }

    @Test void ordersNumericPrereleasesAndStableVersionsCorrectly() {
        List<String> versions = new ArrayList<>(List.of("0.1.0-rc.2", "0.1.0", "0.1.0-rc.10", "0.2.0-rc.1", "0.1.0-alpha.9", "10.0.0", "2.0.0"));
        versions.sort(SemVer.DESCENDING);
        assertEquals(List.of("10.0.0", "2.0.0", "0.2.0-rc.1", "0.1.0", "0.1.0-rc.10", "0.1.0-rc.2", "0.1.0-alpha.9"), versions);
    }

    @Test void rejectsVersionAndProfileTraversal() {
        RuntimeService service = service();
        for (String version : List.of("..", "../evil", "latest", "1.0.0/../../x", "1.0.0 & calc"))
            assertThrows(IllegalArgumentException.class, () -> service.runtimeDirectory(version));
        assertThrows(IllegalArgumentException.class, () -> RuntimeService.validateProfile("../web"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeService.validateProfile("web & calc"));
        assertDoesNotThrow(() -> RuntimeService.validateProfile("中文 测试 profile"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeService.validateProfile("CON.txt"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeService.validateProfile("trailing."));
    }

    @Test void identifiesOnlyExplicitSameReleaseDependencyCohort() throws Exception {
        var metadata = RuntimeService.JSON.readTree("""
                {"dependencies":{"@deepseek-ai/dsh-base":"^0.1.0-rc.6","@deepseek-ai/dsh-web-app":"~0.1.0-rc.6",
                "@deepseek-ai/cordis":"^4.0.1","external-lib":"0.1.0-rc.6","@deepseek-ai/dsh-older":"^0.1.0-rc.2"},
                "peerDependencies":{"@deepseek-ai/dsh-peer":"0.1.0-rc.6"}}
                """);
        assertEquals(Set.of("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "@deepseek-ai/dsh-peer"),
                RuntimeService.cohortDependencies(metadata, "0.1.0-rc.6"));
    }

    @Test void executesActualBinMetadataAndIsolatesHelpProbes() throws Exception {
        RuntimeService service = service(); requireNode(service);
        Path pkg = fixture("9.9.0", "entry/renamed.mjs");
        Files.writeString(pkg.resolve("entry/renamed.mjs"), """
                import fs from 'node:fs';
                if (!process.env.DSH_HOME.includes('probes') || !process.env.DSH_AGENTS_HOME.includes('probes')) process.exit(32);
                const help = process.argv.includes('--profile') ? 'Usage: dsh web\\nOptions:\\n  --port <port> Port\\n  --host <address> Host' : 'Usage: dsh\\nOptions:\\n  --profile <name> Profile\\nCommands:\\n  plugin [args] Manage';
                console.log(help);
                """);
        assertEquals(List.of("9.9.0"), service.installedVersions());
        Capabilities caps = service.inspect("9.9.0", null);
        assertTrue(caps.appHelpVerified()); assertTrue(caps.supports("--port"));
        List<String> command = service.launchCommand("9.9.0", "web", 3001, null);
        assertEquals(pkg.resolve("entry/renamed.mjs").toString(), command.get(1));
        assertFalse(command.contains("cmd.exe"));
    }

    @Test void honorsExplicitLocalCompatibilityFlagsAndInvalidatesCache() throws Exception {
        RuntimeService service = service(); requireNode(service);
        Path pkg = fixture("8.0.0", "bin.mjs");
        Files.writeString(pkg.resolve("bin.mjs"), "console.log('Usage: dsh\\n  --profile <name> Profile')");
        assertThrows(IOException.class, () -> service.launchCommand("8.0.0", "web", 3001, null));
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/compatibility.json"), """
                {"versions":{"8.0.0":{"appOptions":["--port"],"launcherOptions":["--future-option"]}}}
                """);
        assertTrue(service.launchCommand("8.0.0", "web", 3001, null).contains("--port"));
        assertTrue(service.inspect("8.0.0", null).supports("--future-option"));
    }

    @Test void rejectsBinOutsidePackage() throws Exception {
        Path pkg = fixture("1.0.0", "../escape.js");
        assertThrows(IOException.class, () -> service().executable("1.0.0"));
    }

    @Test void environmentKeepsAllWritesUnderIsolatedDirectories() {
        RuntimeService service = service(); requireNode(service);
        Map<String, String> env = new HashMap<>(Map.of("Path", "original-path"));
        service.configureEnvironment(env, root.resolve("instances/demo/dsh-home"), root.resolve("instances/demo/workspace"));
        assertEquals(root.resolve("instances/demo/agents-home").toString(), env.get("DSH_AGENTS_HOME"));
        assertEquals("DISABLED", env.get("DSH_TELEMETRY_MODE"));
        assertFalse(env.containsKey("Path")); assertTrue(env.get("PATH").endsWith("original-path"));
    }

    @Test void customProfileProbeCopiesOnlyBundleAndDependencyMetadata() throws Exception {
        RuntimeService service = service(); requireNode(service);
        Path pkg = fixture("7.0.0", "bin.mjs");
        Files.writeString(pkg.resolve("bin.mjs"), """
                import fs from 'node:fs'; import path from 'node:path';
                const name = process.argv[process.argv.indexOf('--profile')+1];
                if(name === 'custom') {
                  const dir = path.join(process.env.DSH_HOME,'profiles/custom');
                  const metadata = JSON.parse(fs.readFileSync(path.join(dir,'package.json'),'utf8'));
                  if(metadata.unrelated || fs.existsSync(path.join(dir,'cordis.patch.yml'))) process.exit(44);
                  if(!fs.existsSync(path.join(dir,'node_modules/marker.txt'))) process.exit(45);
                  console.log('Usage: custom\\nOptions:\\n  --port <port> Port');
                } else console.log('Usage: dsh\\nOptions:\\n  --profile <name> Profile\\n  --port <port> Port\\nCommands:\\n  plugin [args] Manage');
                """);
        Path home = root.resolve("instance/dsh-home"), original = home.resolve("profiles/custom");
        Files.createDirectories(original.resolve("node_modules"));
        Files.writeString(original.resolve("node_modules/marker.txt"), "fixture");
        Files.writeString(original.resolve("package.json"), "{\"unrelated\":\"not-for-probe\",\"dependencies\":{},\"dsh\":{\"profile\":{\"bundles\":[]}}}");
        Files.writeString(original.resolve("cordis.patch.yml"), "fixture-not-copied: true");
        List<String> command = service.launchCommand("7.0.0", "custom", 3012, home, root.resolve("workspace"), null);
        assertTrue(command.contains("custom")); assertTrue(command.contains("--port"));
    }

    @Test void externalDataDirectoryStillUsesPackagedTools() throws Exception {
        Path application = root.resolve("published"), data = root.resolve("external-data");
        Files.createDirectories(application.resolve("tools/node"));
        Files.createDirectories(application.resolve("tools/pnpm/bin"));
        Path node = application.resolve("tools/node/node.exe"), pnpm = application.resolve("tools/pnpm/bin/pnpm.cjs");
        Files.writeString(node, "fixture"); Files.writeString(pnpm, "fixture");
        String prior = System.getProperty("jpackage.app-path");
        try {
            System.setProperty("jpackage.app-path", application.resolve("HDSL.exe").toString());
            RuntimeService service = new RuntimeService(data, null, null);
            assertEquals(node, service.nodeExecutable()); assertEquals(pnpm, service.pnpmScript());
            assertEquals(data.resolve("runtimes/1.0.0"), service.runtimeDirectory("1.0.0"));
        } finally {
            if (prior == null) System.clearProperty("jpackage.app-path"); else System.setProperty("jpackage.app-path", prior);
        }
    }

    @Test void stalePackLockCanOnlyBeRebuiltWithExplicitImportOptIn() throws Exception {
        RuntimeService service = service(); requireNode(service);
        Path pnpm = root.resolve("tools/pnpm/bin/pnpm.cjs"); Files.createDirectories(pnpm.getParent());
        Files.writeString(pnpm, """
                const fs=require('node:fs');
                fs.appendFileSync('arguments.jsonl',JSON.stringify(process.argv.slice(2))+'\\n');
                if(process.env.NPM_CONFIG_MANAGE_PACKAGE_MANAGER_VERSIONS !== 'false') process.exit(77);
                if(process.argv.includes('--frozen-lockfile')) { console.error('ERR_PNPM_OUTDATED_LOCKFILE'); process.exit(1); }
                fs.writeFileSync('pnpm-lock.yaml','rebuilt from verified manifest');
                """);
        Path home = root.resolve("import/dsh-home"), profile = home.resolve("profiles/web"); Files.createDirectories(profile);
        Files.writeString(profile.resolve("package.json"), "{\"dependencies\":{}}");
        Files.writeString(profile.resolve("pnpm-lock.yaml"), "original fixture lock");
        assertThrows(java.io.IOException.class, () -> service.restoreProfile(home, "web", root.resolve("workspace"), null));
        assertEquals("original fixture lock", Files.readString(profile.resolve("pnpm-lock.yaml")));
        assertFalse(Files.exists(profile.resolve(".hdsl-original-pnpm-lock.yaml")));
        service.restoreProfile(home, "web", root.resolve("workspace"), true, null);
        assertEquals("original fixture lock", Files.readString(profile.resolve(".hdsl-original-pnpm-lock.yaml")));
        assertEquals("rebuilt from verified manifest", Files.readString(profile.resolve("pnpm-lock.yaml")));
        String commands = Files.readString(profile.resolve("arguments.jsonl"));
        assertTrue(commands.contains("--ignore-scripts")); assertTrue(commands.contains("--config.ignore-pnpmfile=true"));
        assertTrue(commands.contains("--store-dir="));
        Files.writeString(pnpm, "console.error('ERR_PNPM_TARBALL_INTEGRITY'); process.exit(1)");
        assertThrows(java.io.IOException.class, () -> service.restoreProfile(home, "web", root.resolve("workspace"), true, null));
        assertEquals("rebuilt from verified manifest", Files.readString(profile.resolve("pnpm-lock.yaml")));
    }

    private RuntimeService service() { return new RuntimeService(root, "https://registry.npmjs.org", ""); }
    private void requireNode(RuntimeService service) {
        try { service.nodeExecutable(); } catch (IllegalStateException e) { Assumptions.abort("Node.js is required for executable fixture tests"); }
    }
    private Path fixture(String version, String bin) throws Exception {
        Path pkg = service().runtimeDirectory(version).resolve("node_modules/@deepseek-ai/dsh");
        Files.createDirectories(pkg);
        Files.createDirectories(pkg.resolve(bin).getParent());
        Files.writeString(pkg.resolve("package.json"), "{\"name\":\"@deepseek-ai/dsh\",\"version\":\"" + version + "\",\"bin\":{\"dsh\":\"" + bin + "\"}}");
        if (!bin.contains("..")) Files.writeString(pkg.resolve(bin), "");
        return pkg;
    }
}
