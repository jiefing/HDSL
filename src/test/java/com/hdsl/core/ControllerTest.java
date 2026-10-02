package com.hdsl.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hdsl.pack.PackService;
import com.hdsl.runtime.RuntimeService;
import com.hdsl.ui.UiState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Uses real instance/pack persistence, with only the external runtime boundary replaced. */
class ControllerTest {
    @TempDir Path temp;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OLD = "0.1.0-rc.6", NEW = "0.2.0-rc.2";

    private static final class FakeRuntime extends RuntimeService {
        final Path root;
        final List<String> installs = new CopyOnWriteArrayList<>();
        final List<RestoreCall> restores = new CopyOnWriteArrayList<>();
        boolean failRestore;
        CountDownLatch entered, release;
        FakeRuntime(Path root) { super(root, "https://example.invalid", ""); this.root = root.toAbsolutePath().normalize(); }
        @Override public List<String> installedVersions() { return List.of(NEW, OLD); }
        @Override public synchronized void install(String version, Consumer<String> log) { installs.add(version); }
        @Override public void restoreProfile(Path home, String profile, Path workspace, boolean allowLockRefresh, Consumer<String> log) throws IOException {
            restores.add(new RestoreCall(home, profile, workspace, allowLockRefresh));
            assertTrue(home.toAbsolutePath().normalize().startsWith(root.resolve("instances")), "Dependency restoration must use the final instance path");
            assertEquals(home.resolveSibling("workspace"), workspace);
            assertTrue(Files.isRegularFile(home.resolve("profiles").resolve(profile).resolve("package.json")));
            assertTrue(allowLockRefresh, "Pack imports must permit targeted outdated-lockfile refresh");
            Files.writeString(home.resolve("profiles").resolve(profile).resolve("restored.fixture"), "fake dependency restoration only");
            if (entered != null) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Fixture release timed out"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Fixture restoration cancelled", error); }
            }
            if (failRestore) throw new IOException("Synthetic dependency restore failure");
        }
        @Override public void restoreProfile(Path home, String profile, Path workspace, Consumer<String> log) throws IOException {
            throw new AssertionError("Controller must explicitly select the staged import lockfile policy");
        }
    }
    private record RestoreCall(Path home, String profile, Path workspace, boolean allowLockRefresh) { }

    private static int unusedPort() throws IOException { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static UiState run(Controller controller, String action, Map<String, String> args) throws Exception {
        return controller.dispatch(action, args).get(15, TimeUnit.SECONDS);
    }
    private Instance seed(Path root, String id, String name, String selectedProfile, String... profiles) throws Exception {
        Instance instance = new Instance(id, name, OLD, selectedProfile, unusedPort(), "");
        try (InstanceStore store = new InstanceStore(root)) { store.add(instance); }
        Path home = root.resolve("instances").resolve(id).resolve("dsh-home");
        Files.createDirectories(home);
        Path workspace = home.resolveSibling("workspace"); Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("original-work.fixture"), "existing instance workspace");
        for (String profile : profiles) writeProfile(home, profile);
        return instance;
    }
    private void writeProfile(Path home, String profile) throws Exception {
        Path directory = home.resolve("profiles").resolve(profile); Files.createDirectories(directory.resolve("data"));
        ObjectNode pkg = JSON.createObjectNode().put("name", "fixture-profile").put("private", true).put("futureField", "preserve");
        pkg.putObject("dependencies"); pkg.putObject("dsh").putObject("profile").putArray("bundles").add("@deepseek-ai/dsh-base");
        JSON.writeValue(directory.resolve("package.json").toFile(), pkg);
        Files.writeString(directory.resolve("data/identity.fixture"), profile);
        Files.writeString(directory.resolve("cordis.patch.yml"), "fixtureOnly: true\n");
    }
    private Path fixturePack(String profile) throws Exception {
        Path home = temp.resolve("pack-source-" + UUID.randomUUID()); writeProfile(home, profile);
        Path archive = temp.resolve("fixture-" + UUID.randomUUID() + ".dspack");
        new PackService().exportProfile(home, profile, OLD, "fixture-pack", "1.0.0", archive, ignored -> {});
        return archive;
    }
    private static UiState.InstanceItem item(UiState state, String id) { return state.instances().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow(); }
    private static Map<String, String> hashes(Path directory) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) result.put(directory.relativize(path).toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        return result;
    }

    @Test void chineseDisplayNameExportsAndCopiesWithCanonicalPackId() throws Exception {
        Path root = temp.resolve("launcher"); Instance original = seed(root, "original", "中文助手 Alpha", "web", "web");
        Path sourceDirectory = root.resolve("instances/original"); Map<String, String> before = hashes(sourceDirectory);
        FakeRuntime runtime = new FakeRuntime(root);
        try (Controller controller = new Controller(root, runtime)) {
            Path output = temp.resolve("中文导出.dspack");
            UiState exported = run(controller, "exportPack", Map.of("id", original.id(), "mode", "profile", "path", output.toString()));
            assertTrue(Files.isRegularFile(output));
            assertTrue(new PackService().inspect(output).name().matches("[a-z0-9]+(?:-[a-z0-9]+)*"));
            assertEquals(original.name(), item(exported, original.id()).name());
            UiState copied = run(controller, "copyInstance", Map.of("id", original.id(), "name", "中文副本 Beta"));
            assertEquals(2, copied.instances().size());
            UiState.InstanceItem duplicate = copied.currentInstance();
            assertNotEquals(original.id(), duplicate.id()); assertEquals("中文副本 Beta", duplicate.name());
            assertNotEquals(original.port(), duplicate.port()); assertEquals("web", duplicate.profile());
            assertEquals(1, runtime.restores.size()); assertTrue(Files.exists(Path.of(duplicate.path()).resolve("dsh-home/profiles/web/restored.fixture")));
            assertEquals(before, hashes(sourceDirectory), "Export/copy must leave all existing instance files unchanged");
        }
    }

    @Test void changingVersionAndProfileCopiesRequestedProfileAndKeepsOriginal() throws Exception {
        Path root = temp.resolve("launcher"); Instance original = seed(root, "original", "研究环境", "profileA", "profileA", "profileB");
        Path originalDirectory = root.resolve("instances/original"); Map<String, String> before = hashes(originalDirectory);
        FakeRuntime runtime = new FakeRuntime(root);
        try (Controller controller = new Controller(root, runtime)) {
            UiState state = run(controller, "saveInstance", Map.of("id", original.id(), "name", "新版 B", "version", NEW, "profile", "profileB", "port", Integer.toString(original.port())));
            assertEquals(2, state.instances().size()); UiState.InstanceItem created = state.currentInstance();
            assertNotEquals(original.id(), created.id()); assertEquals(NEW, created.version()); assertEquals("profileB", created.profile());
            assertEquals("profileB", Files.readString(Path.of(created.path()).resolve("dsh-home/profiles/profileB/data/identity.fixture")));
            assertEquals("profileB", runtime.restores.getFirst().profile()); assertEquals(List.of(NEW), runtime.installs);
            UiState.InstanceItem old = item(state, original.id()); assertEquals(original.name(), old.name()); assertEquals(OLD, old.version()); assertEquals("profileA", old.profile()); assertEquals(original.port(), old.port());
            assertEquals(before, hashes(originalDirectory));
        }
    }

    @Test void importRestoresAtFinalPathAndRegistersOnlyAfterSuccess() throws Exception {
        Path root = temp.resolve("launcher"); FakeRuntime runtime = new FakeRuntime(root);
        runtime.entered = new CountDownLatch(1); runtime.release = new CountDownLatch(1);
        Path archive = fixturePack("研究");
        try (Controller controller = new Controller(root, runtime)) {
            CompletableFuture<UiState> importing = controller.dispatch("importPack", Map.of("path", archive.toString(), "name", "导入实例"));
            try {
                assertTrue(runtime.entered.await(10, TimeUnit.SECONDS));
                assertTrue(controller.snapshot().instances().isEmpty(), "Do not publish before dependency restoration succeeds");
                RestoreCall call = runtime.restores.getFirst();
                assertTrue(call.home().startsWith(root.resolve("instances"))); assertTrue(Files.exists(call.home()));
                runtime.release.countDown();
                UiState result = importing.get(15, TimeUnit.SECONDS); assertEquals(1, result.instances().size());
                assertEquals(call.home(), Path.of(result.currentInstance().path()).resolve("dsh-home"));
                assertEquals("研究", result.currentInstance().profile());
            } finally { runtime.release.countDown(); }
        }
    }

    @Test void failedRestorationDoesNotRegisterAndRetainsOnlyFailedImportInTrash() throws Exception {
        Path root = temp.resolve("launcher"); Instance existing = seed(root, "existing", "Existing", "web", "web");
        Path source = root.resolve("instances/existing"); Map<String, String> before = hashes(source);
        FakeRuntime runtime = new FakeRuntime(root); runtime.failRestore = true;
        Path archive = fixturePack("imported");
        try (Controller controller = new Controller(root, runtime)) {
            ExecutionException error = assertThrows(ExecutionException.class, () -> run(controller, "importPack", Map.of("path", archive.toString())));
            assertTrue(error.getCause().getMessage().contains("Synthetic dependency restore failure"));
            UiState state = controller.snapshot(); assertEquals(1, state.instances().size()); assertEquals(existing.id(), state.currentInstanceId());
            assertEquals(before, hashes(source));
            assertFalse(Files.exists(runtime.restores.getFirst().home()));
            try (var paths = Files.list(root.resolve("trash"))) {
                List<Path> trash = paths.toList(); assertEquals(1, trash.size()); assertTrue(trash.getFirst().getFileName().toString().startsWith("failed-import-"));
                assertTrue(Files.isRegularFile(trash.getFirst().resolve("dsh-home/profiles/imported/package.json")));
                assertTrue(Files.isRegularFile(trash.getFirst().resolve("dsh-home/profiles/imported/restored.fixture")));
            }
            try (var paths = Files.list(root.resolve("instances"))) { assertEquals(List.of(source), paths.toList()); }
            try (var paths = Files.list(root.resolve("cache/pack-staging"))) { assertTrue(paths.findAny().isEmpty()); }
        }
    }

    @Test void concurrentSelectionDuringUpgradeCannotChangeUnrelatedInstance() throws Exception {
        Path root = temp.resolve("launcher"); Instance source = seed(root, "source", "Source", "profileA", "profileA", "profileB");
        Instance unrelated = seed(root, "other", "保持原样", "web", "web");
        Map<String, String> beforeSource = hashes(root.resolve("instances/source")), beforeOther = hashes(root.resolve("instances/other"));
        FakeRuntime runtime = new FakeRuntime(root); runtime.entered = new CountDownLatch(1); runtime.release = new CountDownLatch(1);
        try (Controller controller = new Controller(root, runtime)) {
            run(controller, "selectInstance", Map.of("id", source.id()));
            CompletableFuture<UiState> upgrading = controller.dispatch("saveInstance", Map.of("id", source.id(), "name", "New Version B", "version", NEW, "profile", "profileB", "port", Integer.toString(source.port())));
            try {
                assertTrue(runtime.entered.await(10, TimeUnit.SECONDS));
                UiState selection = run(controller, "selectInstance", Map.of("id", unrelated.id())); assertEquals(unrelated.id(), selection.currentInstanceId());
                runtime.release.countDown(); UiState state = upgrading.get(15, TimeUnit.SECONDS);
                assertEquals(3, state.instances().size());
                UiState.InstanceItem unchanged = item(state, unrelated.id()); assertEquals(unrelated.name(), unchanged.name()); assertEquals(unrelated.profile(), unchanged.profile()); assertEquals(unrelated.port(), unchanged.port()); assertEquals(OLD, unchanged.version());
                assertEquals("profileB", state.currentInstance().profile()); assertEquals(NEW, state.currentInstance().version());
                assertEquals(beforeSource, hashes(root.resolve("instances/source"))); assertEquals(beforeOther, hashes(root.resolve("instances/other")));
            } finally { runtime.release.countDown(); }
        }
    }

    @Test void blankWebCopyNeedsNoPackOrRuntimeInstallation() throws Exception {
        Path root = temp.resolve("launcher"); FakeRuntime runtime = new FakeRuntime(root);
        try (Controller controller = new Controller(root, runtime)) {
            UiState created = run(controller, "createInstance", Map.of("name", "空白实例", "version", OLD, "profile", "web"));
            UiState.InstanceItem source = created.currentInstance();
            UiState copied = run(controller, "copyInstance", Map.of("id", source.id(), "name", "空白副本"));
            assertEquals(2, copied.instances().size()); assertNotEquals(source.id(), copied.currentInstanceId());
            assertEquals("空白副本", copied.currentInstance().name()); assertEquals("web", copied.currentInstance().profile());
            assertTrue(runtime.restores.isEmpty()); assertTrue(runtime.installs.isEmpty());
            assertTrue(Files.isDirectory(Path.of(copied.currentInstance().path()).resolve("workspace")));
            assertEquals(source.name(), item(copied, source.id()).name());
        }
    }

    @Test void missingCustomProfileCannotBeCreatedOrSaved() throws Exception {
        Path root = temp.resolve("launcher"); FakeRuntime runtime = new FakeRuntime(root);
        try (Controller controller = new Controller(root, runtime)) {
            ExecutionException create = assertThrows(ExecutionException.class, () -> run(controller, "createInstance", Map.of("name", "Missing", "version", OLD, "profile", "not-initialized")));
            assertTrue(create.getCause().getMessage().contains("web")); assertTrue(controller.snapshot().instances().isEmpty());
            UiState created = run(controller, "createInstance", Map.of("name", "Original", "version", OLD, "profile", "web")); UiState.InstanceItem original = created.currentInstance();
            assertThrows(ExecutionException.class, () -> run(controller, "saveInstance", Map.of("id", original.id(), "name", "Should Not Change", "version", NEW, "profile", "not-initialized", "port", Integer.toString(original.port()))));
            UiState unchanged = controller.snapshot(); assertEquals(1, unchanged.instances().size()); assertEquals(original.name(), unchanged.currentInstance().name()); assertEquals(OLD, unchanged.currentInstance().version());
            assertTrue(runtime.restores.isEmpty()); assertTrue(runtime.installs.isEmpty());
        }
    }
}
