package com.hdsl.pack;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.archivers.tar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackServiceTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final PackService service = new PackService();

    private ObjectNode profile(int version) {
        ObjectNode m = json.createObjectNode().put("manifestVersion", version).put("type", "profile").put("name", "sample-pack").put("version", "1.0.0").put("profileName", "研究").put("dshVersion", "0.1.1-rc.2");
        m.putArray("bundles").add("@deepseek-ai/dsh-base").add("some-plugin").add("future-plugin");
        m.putObject("dependencies").put("some-plugin", "1.2.3");
        m.putObject("futureOptionalMetadata").put("preserve", true);
        return m;
    }
    private ObjectNode home() {
        ObjectNode m = profile(5); m.put("type", "dshhome").put("defaultProfile", "研究");
        ObjectNode unit = json.createObjectNode(); unit.set("bundles", m.remove("bundles")); unit.set("dependencies", m.remove("dependencies"));
        unit.put("futureUnitField", "preserved");
        m.remove("profileName"); m.putObject("profiles").set("研究", unit);
        ObjectNode second = json.createObjectNode(); second.putArray("bundles").add("@deepseek-ai/dsh-base"); second.putObject("dependencies");
        ((ObjectNode)m.path("profiles")).set("secondary", second); return m;
    }
    private LinkedHashMap<String, byte[]> entries(ObjectNode manifest, int container) throws Exception {
        LinkedHashMap<String, byte[]> e = new LinkedHashMap<>();
        e.put("dspack.json", json.writeValueAsBytes(json.createObjectNode().put("format", "dspack").put("version", container)));
        e.put("manifest.json", json.writeValueAsBytes(manifest)); return e;
    }
    private Path zip(Map<String, byte[]> entries) throws Exception {
        Path path = temp.resolve(UUID.randomUUID() + ".dspack");
        try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var e : entries.entrySet()) { out.putNextEntry(new ZipEntry(e.getKey())); out.write(e.getValue()); out.closeEntry(); }
        }
        return path;
    }
    private byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private JsonNode read(Path path) throws Exception { return json.readTree(Files.readAllBytes(path)); }
    private byte[] tarball(boolean unsafeLink) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var out = new TarArchiveOutputStream(new GZIPOutputStream(bytes))) {
            byte[] pkg = this.bytes("{\"name\":\"some-plugin\",\"version\":\"1.2.3\"}");
            var entry = new TarArchiveEntry("package/package.json"); entry.setSize(pkg.length); out.putArchiveEntry(entry); out.write(pkg); out.closeArchiveEntry();
            if (unsafeLink) {
                var link = new TarArchiveEntry("package/link", TarConstants.LF_SYMLINK); link.setLinkName("../../outside"); out.putArchiveEntry(link); out.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test void importsV5ProfileWithManifestAuthorityAndHomeMapping() throws Exception {
        ObjectNode m = profile(5); m.put("patch", "example: true\n");
        var e = entries(m, 3);
        e.put("package.json", bytes("{\"unknownFutureFlag\":42,\"dependencies\":{\"bad\":\"9\"},\"dsh\":{\"other\":true,\"profile\":{\"unknown\":\"keep\",\"bundles\":[\"wrong\"]}}}"));
        e.put("home/skills/中文/SKILL.md", bytes("# safe skill"));
        e.put("overrides/data/example.txt", bytes("hello"));
        PackInspection i = service.inspect(zip(e)); Path home = temp.resolve("新实例");
        service.extract(i, home, s -> {});
        JsonNode pkg = read(home.resolve("profiles/研究/package.json"));
        assertEquals(m.path("bundles"), pkg.at("/dsh/profile/bundles"));
        assertEquals(m.path("dependencies"), pkg.path("dependencies"));
        assertEquals(42, pkg.path("unknownFutureFlag").asInt()); assertEquals("keep", pkg.at("/dsh/profile/unknown").asText());
        assertTrue(Files.exists(home.resolve("skills/中文/SKILL.md")));
        assertTrue(Files.exists(home.resolve("profiles/研究/cordis.patch.yml")));
        assertTrue(read(home.resolve(".hdsl-pack-origin.json")).at("/futureOptionalMetadata/preserve").asBoolean());
    }
    @Test void importsHistoricV2ContainerV4Manifest() throws Exception {
        ObjectNode manifest = profile(4); manifest.put("profileName", "desktop");
        var i = service.inspect(zip(entries(manifest, 2)));
        service.extract(i, temp.resolve("old"), s -> {}); assertEquals("desktop", i.defaultProfile());
        assertTrue(Files.isRegularFile(temp.resolve("old/profiles/desktop/package.json")), "Reserved CLI profile warnings must not prevent file import or rename the profile");
        assertTrue(i.warnings().stream().anyMatch(w -> w.contains("Electron") && w.contains("不会自动更名")));
    }
    @Test void homeMappingKeepsProfilesAndUnknownPackageFields() throws Exception {
        var e = entries(home(), 3);
        e.put("overrides/profiles/研究/package.json", bytes("{\"extension\":true}"));
        e.put("package.json", bytes("{\"wrongDefaultSnapshot\":true}"));
        e.put("overrides/skills/demo/SKILL.md", bytes("skill"));
        Path output = temp.resolve("home"); var i = service.inspect(zip(e)); service.extract(i, output, s -> {});
        assertEquals(List.of("研究", "secondary"), i.profiles());
        assertTrue(read(output.resolve("profiles/研究/package.json")).path("extension").asBoolean());
        assertTrue(Files.exists(output.resolve("profiles/secondary/package.json")));
        assertTrue(Files.exists(output.resolve("skills/demo/SKILL.md")));
    }
    @Test void unknownVersionsAndAmbiguousFormatsAreRejected() throws Exception {
        assertThrows(IOException.class, () -> service.inspect(zip(entries(profile(6), 3))));
        assertThrows(IOException.class, () -> service.inspect(zip(entries(profile(5), 99))));
        var e = entries(profile(5), 3); e.put("dsh-snapshot.json", bytes("{}"));
        assertThrows(IOException.class, () -> service.inspect(zip(e)));
    }
    @Test void rejectsHistoricalContainerWithNewLayout() throws Exception {
        var e = entries(profile(4), 2); e.put("home/skills/demo.txt", bytes("x"));
        assertThrows(IOException.class, () -> service.inspect(zip(e)));
    }
    @Test void rejectsTraversalDriveUncReservedAndCaseCollision() throws Exception {
        for (String path : List.of("overrides/../../escape", "C:/evil", "//server/share", "overrides\\bad", "overrides/NUL.txt", "overrides/bad.", "overrides/a:b", "overrides/a/../x")) {
            var e = entries(profile(5), 3); e.put(path, bytes("x"));
            assertThrows(IOException.class, () -> service.inspect(zip(e)), path);
        }
        var e = entries(profile(5), 3); e.put("overrides/Case/x", bytes("x")); e.put("overrides/case/y", bytes("y"));
        assertThrows(IOException.class, () -> service.inspect(zip(e)));
    }
    @Test void rejectsFileDirectoryMappingCollision() throws Exception {
        var e = entries(profile(5), 3); e.put("overrides/data", bytes("file")); e.put("overrides/data/file", bytes("child"));
        var fileDirectoryCollision = e; assertThrows(IOException.class, () -> service.inspect(zip(fileDirectoryCollision)));
        e = entries(profile(5), 3); e.put("home/profiles/研究/example", bytes("one")); e.put("overrides/example", bytes("two"));
        var duplicateMapping = e; assertThrows(IOException.class, () -> service.inspect(zip(duplicateMapping)));
    }
    @Test void rejectsZipSymlinks() throws Exception {
        Path path = temp.resolve("symlink.zip");
        try (var out = new ZipArchiveOutputStream(path)) {
            for (var e : entries(profile(5), 3).entrySet()) { var entry = new ZipArchiveEntry(e.getKey()); out.putArchiveEntry(entry); out.write(e.getValue()); out.closeArchiveEntry(); }
            var link = new ZipArchiveEntry("overrides/link"); link.setUnixMode(0120777); out.putArchiveEntry(link); out.write(bytes("../../escape")); out.closeArchiveEntry();
        }
        assertThrows(IOException.class, () -> service.inspect(path));
    }
    @Test void rejectsHighlyCompressedBomb() throws Exception {
        var e = entries(profile(5), 3); e.put("overrides/bomb", new byte[2 * 1024 * 1024]);
        assertThrows(IOException.class, () -> service.inspect(zip(e)));
    }
    @Test void skipsKnownPrivateFilesByPath() throws Exception {
        var e = entries(profile(5), 3);
        for (String privatePath : List.of("home/.credentials.yaml", "home/settings.yaml", "overrides/.env", "home/sessions/session.json", "home/.ssh/id_ed25519", "overrides/.npmrc")) e.put(privatePath, bytes("SYNTHETIC-PRIVATE"));
        var i = service.inspect(zip(e)); Path output = temp.resolve("private-filter"); service.extract(i, output, s -> {});
        assertTrue(i.warnings().stream().anyMatch(w -> w.contains("跳过 6")));
        assertFalse(Files.exists(output.resolve("settings.yaml"))); assertFalse(Files.exists(output.resolve("profiles/研究/.env")));
    }
    @Test void preventsOverwriteAndPreviewArchiveReplacement() throws Exception {
        var i = service.inspect(zip(entries(profile(5), 3))); Path output = temp.resolve("existing"); Files.createDirectories(output); Files.writeString(output.resolve("keep"), "original");
        assertThrows(IOException.class, () -> service.extract(i, output, s -> {})); assertEquals("original", Files.readString(output.resolve("keep")));
        Files.write(i.archive(), bytes("different")); assertThrows(IOException.class, () -> service.extract(i, temp.resolve("replaced"), s -> {})); assertFalse(Files.exists(temp.resolve("replaced")));
    }
    @Test void verifiesVendorHashAndMapsTarballToFileDependency() throws Exception {
        ObjectNode m = profile(5); byte[] tarball = tarball(false);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tarball));
        m.putObject("vendored").putObject("some-plugin").put("version", "1.2.3").put("path", "vendor/plugin.tgz").put("sha256", hash).put("size", tarball.length);
        var e = entries(m, 3); e.put("vendor/plugin.tgz", tarball); var i = service.inspect(zip(e)); Path out = temp.resolve("vendor"); service.extract(i, out, s -> {});
        String dependency = read(out.resolve("profiles/研究/package.json")).at("/dependencies/some-plugin").asText();
        assertEquals("file:vendor-blobs/" + hash + ".tgz", dependency); assertArrayEquals(tarball, Files.readAllBytes(out.resolve("profiles/研究/" + dependency.substring(5))));
        e.put("vendor/plugin.tgz", bytes("tampered")); assertThrows(IOException.class, () -> service.inspect(zip(e)));
    }
    @Test void rejectsSameSizeVendorTamperingAndUnregisteredFiles() throws Exception {
        ObjectNode m = profile(5); String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes("good")));
        m.putObject("vendored").putObject("some-plugin").put("version", "1.2.3").put("path", "vendor/plugin.tgz").put("sha256", hash).put("size", 4);
        var e = entries(m, 3); e.put("vendor/plugin.tgz", bytes("evil")); assertThrows(IOException.class, () -> service.inspect(zip(e)));
        var unregistered = entries(profile(5), 3); unregistered.put("vendor/unregistered.tgz", bytes("abc")); assertThrows(IOException.class, () -> service.inspect(zip(unregistered)));
    }
    @Test void rejectsHashValidTarballWithUnsafeLink() throws Exception {
        ObjectNode m = profile(5); byte[] tarball = tarball(true);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tarball));
        m.putObject("vendored").putObject("some-plugin").put("version", "1.2.3").put("path", "vendor/plugin.tgz").put("sha256", hash).put("size", tarball.length);
        var e = entries(m, 3); e.put("vendor/plugin.tgz", tarball);
        IOException error = assertThrows(IOException.class, () -> service.inspect(zip(e)));
        assertTrue(error.getMessage().contains("tarball"));
    }
    @Test void rejectsMissingLocalDependenciesAndVendorDialect() throws Exception {
        ObjectNode m = profile(5); ((ObjectNode)m.path("dependencies")).put("some-plugin", "file:missing.tgz"); assertThrows(IOException.class, () -> service.inspect(zip(entries(m, 3))));
        ((ObjectNode)m.path("dependencies")).put("some-plugin", "file:../../external"); assertThrows(IOException.class, () -> service.inspect(zip(entries(m, 3))));
        ((ObjectNode)m.path("dependencies")).put("some-plugin", "vendor:plugin.tgz"); assertThrows(IOException.class, () -> service.inspect(zip(entries(m, 3))));
    }
    @Test void preservesGitCoordinateMapping() throws Exception {
        ObjectNode m = profile(5); ((ObjectNode)m.path("dependencies")).put("github:owner/plugin-repo", "abcdef123");
        Path out = temp.resolve("git"); service.extract(service.inspect(zip(entries(m, 3))), out, s -> {});
        assertEquals("github:owner/plugin-repo#abcdef123", read(out.resolve("profiles/研究/package.json")).at("/dependencies/plugin-repo").asText());
    }
    private LinkedHashMap<String, byte[]> snapshot(ObjectNode metadata) throws Exception {
        LinkedHashMap<String, byte[]> e = new LinkedHashMap<>(); e.put("dsh-snapshot.json", json.writeValueAsBytes(metadata));
        e.put("profiles/demo/profile.yaml", bytes("name: 'Demo title'\ndshVersion: '0.1.1-rc.2'\n"));
        e.put("profiles/demo/package.json", bytes("{\"dsh\":{\"profile\":{\"bundles\":[]}},\"dependencies\":{}}"));
        e.put("profiles/demo/node_modules/.pnpm/pkg/index.js", bytes("synthetic code not executed")); return e;
    }
    private ObjectNode snapshotMeta() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT); ObjectNode m = json.createObjectNode().put("format", 1).put("platform", os.contains("win") ? "win32" : os.contains("mac") ? "darwin" : "linux");
        m.putArray("links"); m.putArray("warnings"); return m;
    }
    @Test void snapshotUsesDirectoryIdAndPreservesInstalledFiles() throws Exception {
        PackInspection i = service.inspect(zip(snapshot(snapshotMeta()))); assertEquals("demo", i.defaultProfile()); assertEquals("0.1.1-rc.2", i.dshVersion());
        Path out = temp.resolve("snapshot"); service.extract(i, out, s -> {}); assertTrue(Files.exists(out.resolve("profiles/demo/node_modules/.pnpm/pkg/index.js")));
    }
    @Test void rejectsCrossPlatformSnapshotsAndExternalLinks() throws Exception {
        var m = snapshotMeta(); m.put("platform", "unsupported-os"); assertThrows(IOException.class, () -> service.inspect(zip(snapshot(m))));
        for (String target : List.of("C:/outside", "../../../../outside", "//server/share", "../missing")) {
            var meta = snapshotMeta(); ((ArrayNode)meta.path("links")).addObject().put("path", "profiles/demo/link").put("target", target);
            assertThrows(IOException.class, () -> service.inspect(zip(snapshot(meta))), target);
        }
    }
    @Test void acceptsInternalSnapshotLinksAtInspectionAndRejectsCycles() throws Exception {
        var m = snapshotMeta(); ((ArrayNode)m.path("links")).addObject().put("path", "profiles/demo/node_modules/pkg").put("target", ".pnpm/pkg");
        var i = service.inspect(zip(snapshot(m))); assertTrue(i.warnings().stream().anyMatch(w -> w.contains("符号链接")));
        var cyclic = snapshotMeta(); ((ArrayNode)cyclic.path("links")).addObject().put("path", "profiles/demo/node_modules/loop").put("target", "..");
        assertThrows(IOException.class, () -> service.inspect(zip(snapshot(cyclic))));
    }
    @Test void shareExportRoundTripsContentAndDocumentsOmittedConfiguration() throws Exception {
        ObjectNode m = profile(5); m.put("patch", "example: true\n");
        var e = entries(m, 3); e.put("package.json", bytes("{\"futurePackageField\":true}")); e.put("home/skills/demo/SKILL.md", bytes("# skill"));
        Path first = temp.resolve("original"); service.extract(service.inspect(zip(e)), first, s -> {});
        Files.writeString(first.resolve(".credentials.yaml"), "SYNTHETIC-SECRET"); Files.writeString(first.resolve("settings.yaml"), "SYNTHETIC-CONFIG");
        Path exported = temp.resolve("shared.dspack"); service.exportProfile(first, "研究", "0.1.1-rc.2", "shared-pack", "1.0.0", exported, s -> {});
        var i = service.inspect(exported); assertTrue(i.warnings().stream().anyMatch(w -> w.contains("不是完整备份")));
        Path second = temp.resolve("roundtrip"); service.extract(i, second, s -> {});
        assertEquals(m.path("bundles"), read(second.resolve("profiles/研究/package.json")).at("/dsh/profile/bundles"));
        assertTrue(read(second.resolve("profiles/研究/package.json")).path("futurePackageField").asBoolean());
        assertTrue(read(second.resolve(".hdsl-pack-origin.json")).at("/futureOptionalMetadata/preserve").asBoolean());
        assertFalse(Files.exists(second.resolve("profiles/研究/cordis.patch.yml"))); assertFalse(Files.exists(second.resolve("settings.yaml"))); assertFalse(Files.exists(second.resolve(".credentials.yaml")));
        assertEquals("# skill", Files.readString(second.resolve("skills/demo/SKILL.md")));
    }
    @Test void homeShareExportPreservesProfileUnitExtensionsAndDefaults() throws Exception {
        Path first = temp.resolve("home-source"); service.extract(service.inspect(zip(entries(home(), 3))), first, s -> {});
        Path exported = temp.resolve("home.dspack"); service.exportHome(first, "研究", "0.1.1-rc.2", "home-pack", "1.0.0", exported, s -> {});
        var i = service.inspect(exported); assertEquals("研究", i.defaultProfile()); assertEquals("preserved", i.manifest().at("/profiles/研究/futureUnitField").asText());
        service.extract(i, temp.resolve("home-roundtrip"), s -> {});
    }
    @Test void exportCannotOverwriteOrWriteInsideSource() throws Exception {
        Path home = temp.resolve("source"); service.extract(service.inspect(zip(entries(profile(5), 3))), home, s -> {});
        assertThrows(IOException.class, () -> service.exportProfile(home, "研究", "0.1.1-rc.2", "sample", "1.0.0", home.resolve("bad.dspack"), s -> {}));
        Path existing = temp.resolve("exists.dspack"); Files.writeString(existing, "keep");
        assertThrows(IOException.class, () -> service.exportProfile(home, "研究", "0.1.1-rc.2", "sample", "1.0.0", existing, s -> {})); assertEquals("keep", Files.readString(existing));
    }
    @Test void rejectsResourceDownloadWithUnsafeSchemeAndMachineDestination() throws Exception {
        ObjectNode m = profile(5); ObjectNode resource = m.putArray("files").addObject().put("path", "data/safe.bin").put("sha256", "0".repeat(64)).put("size", 1); resource.putArray("urls").add("file:///C:/private");
        assertThrows(IOException.class, () -> service.inspect(zip(entries(m, 3))));
        resource.putArray("urls").add("https://example.invalid/safe"); resource.put("path", "package.json");
        assertThrows(IOException.class, () -> service.inspect(zip(entries(m, 3))));
    }
    @Test void snapshotUnknownVersionAndYamlAdapterFailExplicitly() throws Exception {
        var m = snapshotMeta(); m.put("format", 2); assertThrows(IOException.class, () -> service.inspect(zip(snapshot(m))));
        assertThrows(IOException.class, () -> service.inspect(zip(Map.of("dsh-pack.yaml", bytes("name: test")))));
    }
    @Test void callerCannotMutateInspectionManifest() throws Exception {
        var i = service.inspect(zip(entries(profile(5), 3))); ((ObjectNode)i.manifest()).put("name", "changed");
        assertEquals("sample-pack", i.manifest().path("name").asText());
    }
}
