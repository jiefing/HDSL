package com.hdsl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class RuntimeAndPluginSmokeTest {
    public static void main(String[] args) throws Exception {
        testInstanceConstructorsAndPortAllocation();
        testEnsurePortArgumentAuthoritative();
        testCommandLineOwnershipEvidence();
        testOfficialPluginClassification();
        testPluginSearchMatching();
        testPluginCacheSerialization();
        testSafeProcessOwnershipHelpers();
        System.out.println("ALL_RUNTIME_AND_PLUGIN_SMOKE_TESTS_PASSED");
    }

    private static void testInstanceConstructorsAndPortAllocation() throws Exception {
        // 5-arg constructor (backward compatibility with earlier tests)
        Class<?> instanceType = Class.forName("com.hdsl.Launcher$Instance");
        Constructor<?> constructor5 = instanceType.getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class);
        constructor5.setAccessible(true);
        Object inst5 = constructor5.newInstance("test-5", "Test 5", "0.1.0-rc.6", "web", "");
        int port5 = (int) instanceType.getDeclaredField("port").get(inst5);
        if (port5 != 3080) throw new AssertionError("Expected default port 3080 for 5-arg constructor, got: " + port5);

        // 6-arg constructor
        Constructor<?> constructor6 = instanceType.getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class, int.class);
        constructor6.setAccessible(true);
        Object inst6 = constructor6.newInstance("test-6", "Test 6", "0.1.0-rc.6", "web", "", 3090);
        int port6 = (int) instanceType.getDeclaredField("port").get(inst6);
        if (port6 != 3090) throw new AssertionError("Expected port 3090 for 6-arg constructor, got: " + port6);

        // Test parsePort reflection
        Constructor<Launcher> launcherConstructor = Launcher.class.getDeclaredConstructor();
        launcherConstructor.setAccessible(true);
        Launcher launcher = launcherConstructor.newInstance();

        Method parsePort = Launcher.class.getDeclaredMethod("parsePort", String.class, Set.class);
        parsePort.setAccessible(true);

        Set<Integer> used = new HashSet<>(Arrays.asList(3080, 3081));
        int allocated1 = (int) parsePort.invoke(launcher, null, used);
        if (allocated1 != 3082) throw new AssertionError("Expected port 3082 when 3080 and 3081 are used, got: " + allocated1);

        int allocatedSpecified = (int) parsePort.invoke(launcher, "4000", used);
        if (allocatedSpecified != 4000) throw new AssertionError("Expected specified port 4000, got: " + allocatedSpecified);

        int allocatedConflict = (int) parsePort.invoke(launcher, "3080", used);
        if (allocatedConflict != 3082) throw new AssertionError("Expected conflicting port to migrate to 3082, got: " + allocatedConflict);

        System.out.println("INSTANCE_PORT_ALLOCATION_OK port5=" + port5 + " port6=" + port6 + " allocated=" + allocated1);
    }

    private static void testEnsurePortArgumentAuthoritative() {
        // Appends port when missing
        String cmd1 = Launcher.ensurePortArgument("dsh.cmd --profile web", 3080);
        if (!cmd1.equals("dsh.cmd --profile web --port 3080")) {
            throw new AssertionError("Expected 'dsh.cmd --profile web --port 3080', got: " + cmd1);
        }

        // Replaces conflicting port (space form) with authoritative assigned port
        String cmd2 = Launcher.ensurePortArgument("dsh.cmd --profile web --port 3099", 3080);
        if (!cmd2.equals("dsh.cmd --profile web --port 3080")) {
            throw new AssertionError("Expected conflicting port 3099 to be replaced by 3080, got: " + cmd2);
        }

        // Replaces conflicting port (equals form) with authoritative assigned port
        String cmd3 = Launcher.ensurePortArgument("dsh.cmd --profile web --port=4000", 3085);
        if (!cmd3.equals("dsh.cmd --profile web --port=3085")) {
            throw new AssertionError("Expected conflicting port=4000 to be replaced by port=3085, got: " + cmd3);
        }

        // Handles empty or null command
        String cmd4 = Launcher.ensurePortArgument(null, 3080);
        if (!cmd4.equals("--port 3080")) {
            throw new AssertionError("Expected '--port 3080' for null command, got: " + cmd4);
        }

        System.out.println("ENSURE_PORT_ARGUMENT_AUTHORITATIVE_OK");
    }

    private static void testCommandLineOwnershipEvidence() {
        String instanceId = "instance-alpha";
        String workspace = "/home/user/hdsl/instances/instance-alpha/workspace";
        String home = "/home/user/hdsl/instances/instance-alpha/dsh-home";
        String runtime = "/home/user/hdsl/runtimes/0.1.0-rc.6";
        int port = 3080;

        // 1. Correct local dsh + correct port = true
        String correctCmd = "/home/user/hdsl/runtimes/0.1.0-rc.6/node_modules/.bin/dsh.cmd --profile web --port 3080";
        if (!Launcher.matchesInstanceOwnershipEvidence(correctCmd, instanceId, workspace, home, runtime, port)) {
            throw new AssertionError("Expected true for correct runtime command with matching port");
        }

        // 2. Same local dsh + different port = false
        String diffPortCmd = "/home/user/hdsl/runtimes/0.1.0-rc.6/node_modules/.bin/dsh.cmd --profile web --port 3081";
        if (Launcher.matchesInstanceOwnershipEvidence(diffPortCmd, instanceId, workspace, home, runtime, port)) {
            throw new AssertionError("Expected false for same runtime but different port 3081 when expecting 3080");
        }

        // 3. Generic dsh/deepseek text without local paths = false
        String genericCmd = "node /opt/global/dsh.js run deepseek --port 3080";
        if (Launcher.matchesInstanceOwnershipEvidence(genericCmd, instanceId, workspace, home, runtime, port)) {
            throw new AssertionError("Expected false for generic dsh/deepseek without instance/runtime evidence");
        }

        // 4. Unrelated command with matching port = false
        String unrelatedCmd = "python3 -m http.server --port 3080";
        if (Launcher.matchesInstanceOwnershipEvidence(unrelatedCmd, instanceId, workspace, home, runtime, port)) {
            throw new AssertionError("Expected false for unrelated python server with port 3080");
        }

        // 5. Workspace evidence + correct port = true
        String wsCmd = "node app.js --cwd /home/user/hdsl/instances/instance-alpha/workspace --port=3080";
        if (!Launcher.matchesInstanceOwnershipEvidence(wsCmd, instanceId, workspace, home, runtime, port)) {
            throw new AssertionError("Expected true for workspace evidence with matching port");
        }

        // 6. Instance ID evidence + correct port = true
        String idCmd = "cmd.exe /c start-instance.bat instance-alpha --port 3080";
        if (!Launcher.matchesInstanceOwnershipEvidence(idCmd, instanceId, workspace, home, runtime, port)) {
            throw new AssertionError("Expected true for instance-id evidence with matching port");
        }

        // 7. Instance ID evidence + wrong port = false
        String idWrongPortCmd = "cmd.exe /c start-instance.bat instance-alpha --port 3099";
        if (Launcher.matchesInstanceOwnershipEvidence(idWrongPortCmd, instanceId, workspace, home, runtime, port)) {
            throw new AssertionError("Expected false for instance-id evidence with wrong port");
        }

        System.out.println("COMMAND_LINE_OWNERSHIP_EVIDENCE_OK");
    }

    private static void testOfficialPluginClassification() {
        Launcher.PluginInfo official1 = new Launcher.PluginInfo("web-app", "@deepseek-ai/dsh-web-app", "0.1.0", true, false);
        Launcher.PluginInfo official2 = new Launcher.PluginInfo("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-base", "0.1.0", true, false);
        Launcher.PluginInfo community1 = new Launcher.PluginInfo("my-plugin", "my-custom-plugin", "1.0.0", true, true);
        Launcher.PluginInfo community2 = new Launcher.PluginInfo("scoped", "@other-scope/tool", "1.0.0", true, true);

        if (!Launcher.isOfficialPlugin(official1)) throw new AssertionError("Expected official1 to be official");
        if (!Launcher.isOfficialPlugin(official2)) throw new AssertionError("Expected official2 to be official");
        if (Launcher.isOfficialPlugin(community1)) throw new AssertionError("Expected community1 NOT to be official");
        if (Launcher.isOfficialPlugin(community2)) throw new AssertionError("Expected community2 NOT to be official");

        System.out.println("OFFICIAL_PLUGIN_CLASSIFICATION_OK");
    }

    private static void testPluginSearchMatching() {
        Launcher.PluginInfo info = new Launcher.PluginInfo("deepseek-core", "@deepseek-ai/core-engine", "0.2.1-beta.3", true, false);

        if (!Launcher.pluginMatches(info, "CORE")) throw new AssertionError("Search failed on uppercase substring");
        if (!Launcher.pluginMatches(info, "beta.3")) throw new AssertionError("Search failed on version substring");
        if (!Launcher.pluginMatches(info, "@deepseek-ai")) throw new AssertionError("Search failed on package prefix");
        if (!Launcher.pluginMatches(info, "deepseek-core")) throw new AssertionError("Search failed on id");
        if (Launcher.pluginMatches(info, "nonexistent-keyword")) throw new AssertionError("Search matched nonexistent keyword");
        if (!Launcher.pluginMatches(info, "")) throw new AssertionError("Search failed on empty query");
        if (!Launcher.pluginMatches(info, null)) throw new AssertionError("Search failed on null query");

        System.out.println("PLUGIN_SEARCH_MATCHING_OK");
    }

    private static void testPluginCacheSerialization() throws Exception {
        Constructor<Launcher> launcherConstructor = Launcher.class.getDeclaredConstructor();
        launcherConstructor.setAccessible(true);
        Launcher launcher = launcherConstructor.newInstance();

        Class<?> instanceType = Class.forName("com.hdsl.Launcher$Instance");
        Constructor<?> constructor6 = instanceType.getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class, int.class);
        constructor6.setAccessible(true);
        Object inst = constructor6.newInstance("cache-test-inst", "Cache Test", "0.1.0-rc.6", "web", "", 3080);

        Method writePluginCache = Launcher.class.getDeclaredMethod("writePluginCache", instanceType, List.class);
        writePluginCache.setAccessible(true);
        Method loadPluginCache = Launcher.class.getDeclaredMethod("loadPluginCache", instanceType);
        loadPluginCache.setAccessible(true);

        List<Launcher.PluginInfo> sample = Arrays.asList(
                new Launcher.PluginInfo("bundle-1", "@deepseek-ai/dsh-web-app", "0.1.0-rc.6", true, false),
                new Launcher.PluginInfo("custom\twith\ttab", "custom-plugin-name", "1.2.3\nrev2", true, true)
        );

        writePluginCache.invoke(launcher, inst, sample);

        var pluginCacheField = Launcher.class.getDeclaredField("pluginCache");
        pluginCacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, List<Launcher.PluginInfo>> pluginCache = (Map<String, List<Launcher.PluginInfo>>) pluginCacheField.get(launcher);
        pluginCache.clear();

        loadPluginCache.invoke(launcher, inst);

        List<Launcher.PluginInfo> loaded = pluginCache.get("cache-test-inst");
        if (loaded == null || loaded.size() != 2) throw new AssertionError("Expected 2 cached plugin items, got: " + loaded);
        if (!loaded.get(0).name.equals("@deepseek-ai/dsh-web-app")) throw new AssertionError("Name mismatch: " + loaded.get(0).name);
        if (!loaded.get(1).id.equals("custom\twith\ttab")) throw new AssertionError("Base64 tab-safe ID mismatch: " + loaded.get(1).id);
        if (!loaded.get(1).version.equals("1.2.3\nrev2")) throw new AssertionError("Base64 newline-safe version mismatch: " + loaded.get(1).version);
        if (!loaded.get(1).managed) throw new AssertionError("Managed flag mismatch");

        System.out.println("PLUGIN_CACHE_SERIALIZATION_OK items=" + loaded.size());
    }

    private static void testSafeProcessOwnershipHelpers() throws Exception {
        Constructor<Launcher> launcherConstructor = Launcher.class.getDeclaredConstructor();
        launcherConstructor.setAccessible(true);
        Launcher launcher = launcherConstructor.newInstance();

        Class<?> instanceType = Class.forName("com.hdsl.Launcher$Instance");
        Constructor<?> constructor6 = instanceType.getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class, int.class);
        constructor6.setAccessible(true);
        Object inst = constructor6.newInstance("unowned-probe", "Unowned Probe", "0.1.0-rc.6", "web", "", 65432);

        Method computeState = Launcher.class.getDeclaredMethod("computeInstanceState", instanceType);
        computeState.setAccessible(true);
        Object state = computeState.invoke(launcher, inst);

        if (!state.toString().equals("STOPPED")) {
            throw new AssertionError("Expected STOPPED for inactive port, got: " + state);
        }

        System.out.println("SAFE_PROCESS_OWNERSHIP_HELPERS_OK initial_state=" + state);
    }
}
