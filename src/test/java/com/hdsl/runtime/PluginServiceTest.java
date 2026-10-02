package com.hdsl.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PluginServiceTest {
    @TempDir Path root;

    @Test void listsFuturePluginMetadataWithoutRewritingManifest() throws Exception {
        Path home = root.resolve("home"), profile = home.resolve("profiles/web");
        Files.createDirectories(profile.resolve("node_modules/example-plugin"));
        String json = """
                {"dependencies":{"example-plugin":"github:test/project#branch"},
                 "futurePluginMetadata":{"opaque":[1,2,3]},
                 "dsh":{"profile":{"future":"keep","bundles":["@deepseek-ai/dsh-base","example-plugin",{"name":"future-bundle","unknown":true}]}}}
                """;
        Files.writeString(profile.resolve("package.json"), json);
        Files.writeString(profile.resolve("node_modules/example-plugin/package.json"), """
                {"name":"example-plugin","version":"5.0.0","dsh":{"bundle":{"patch":["a.yml","b.yml"],"future":true}}}
                """);
        PluginService plugins = new PluginService(new RuntimeService(root, null, null));
        List<PluginEntry> entries = plugins.list("0.2.0-rc.2", "web", home, root.resolve("workspace"), null);
        assertEquals(3, entries.size());
        assertEquals("5.0.0", entries.stream().filter(e -> e.id().equals("example-plugin")).findFirst().orElseThrow().version());
        assertEquals(json, Files.readString(profile.resolve("package.json")));
        assertTrue(entries.stream().anyMatch(e -> e.id().equals("future-bundle")));
    }

    @Test void nonexistentProfileListDoesNotInitializeOrMutate() throws Exception {
        Path home = root.resolve("empty-home");
        PluginService plugins = new PluginService(new RuntimeService(root, null, null));
        assertTrue(plugins.list("0.1.0-rc.6", "web", home, root, null).isEmpty());
        assertFalse(Files.exists(home));
    }

    @Test void anchorsLocalPathsButPreservesGitAndRegistryCoordinates() {
        Path workspace = root.resolve("project space");
        assertEquals("file:" + workspace.resolve("plugin folder"), PluginService.anchorLocalSpec("file:./plugin folder", workspace));
        assertEquals("github:some/project#main", PluginService.anchorLocalSpec("github:some/project#main", workspace));
        assertEquals("@scope/plugin@next", PluginService.anchorLocalSpec("@scope/plugin@next", workspace));
    }

    @Test void cannotConfusePackageWithPnpmFlagOrRemoveOutsidePackageNames() {
        PluginService plugins = new PluginService(new RuntimeService(root, null, null));
        assertThrows(IllegalArgumentException.class, () -> plugins.mutate("add", "--dir=C:/", "1.0.0", "web", root, root, null));
        assertThrows(IllegalArgumentException.class, () -> plugins.mutate("remove", "../../x", "1.0.0", "web", root, root, null));
        assertFalse(PluginService.validPackageName("@scope/../../x"));
    }

    @Test void packageSpecWithSpacesAndMetacharactersRemainsOneArgument() throws Exception {
        RuntimeService runtimes = new RuntimeService(root, null, null);
        try { runtimes.nodeExecutable(); } catch (IllegalStateException e) { Assumptions.abort("Requires Node.js for executable fixture"); }
        Path cli = runtimes.runtimeDirectory("1.0.0").resolve("node_modules/@deepseek-ai/dsh");
        Files.createDirectories(cli);
        Files.writeString(cli.resolve("package.json"), "{\"name\":\"@deepseek-ai/dsh\",\"version\":\"1.0.0\",\"bin\":\"bin.mjs\"}");
        Files.writeString(cli.resolve("bin.mjs"), """
                import fs from 'node:fs'; import path from 'node:path'; import {spawnSync} from 'node:child_process';
                if (process.argv.includes('--help')) { console.log('Usage: dsh\\n  --profile <name> Profile\\n  --port <port> Port\\n  plugin [args] Manage'); }
                else {
                  const dir = path.join(process.env.DSH_HOME, 'profiles/web'); fs.mkdirSync(dir,{recursive:true});
                  if (!fs.existsSync(path.join(dir,'package.json'))) fs.writeFileSync(path.join(dir,'package.json'),JSON.stringify({future:{keep:1},dependencies:{},dsh:{profile:{bundles:[]}}}));
                  fs.appendFileSync(path.join(dir,'native-args.jsonl'),JSON.stringify(process.argv.slice(2))+'\\n');
                  const child = spawnSync('pnpm', process.argv.slice(5), {cwd:dir, shell:process.platform==='win32',stdio:'inherit'});
                  if (child.error) throw child.error;
                  process.exit(child.status ?? 1);
                }
                """);
        Path pnpm = root.resolve("tools/pnpm/bin/pnpm.cjs"); Files.createDirectories(pnpm.getParent());
        Files.writeString(pnpm, "require('node:fs').writeFileSync('observed-args.json',JSON.stringify(process.argv.slice(2)))");
        String spec = "file:" + root.resolve("local plugin & literal");
        Path home = root.resolve("home"), workspace = root.resolve("workspace");
        new PluginService(runtimes).mutate("add", spec, "1.0.0", "web", home, workspace, null);
        var args = RuntimeService.JSON.readTree(home.resolve("profiles/web/observed-args.json").toFile());
        assertEquals("add", args.get(0).asText()); assertEquals(spec, args.get(1).asText());
        assertTrue(args.toString().contains("--config.ignore-scripts=true")); assertTrue(args.toString().contains("--config.ignore-pnpmfile=true"));
        assertTrue(Files.readString(home.resolve("profiles/web/native-args.jsonl")).contains("literal"));
        assertEquals(1, RuntimeService.JSON.readTree(home.resolve("profiles/web/package.json").toFile()).path("future").path("keep").asInt());
    }
}
