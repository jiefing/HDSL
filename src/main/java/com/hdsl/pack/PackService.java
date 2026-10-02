package com.hdsl.pack;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Format adapters own files only. The caller installs dependencies and publishes the staged instance. */
public final class PackService {
    private static final ObjectMapper JSON = new ObjectMapper().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
    private static final String ORIGIN = ".hdsl-pack-origin.json";
    private static final Set<String> MACHINE = Set.of("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml");
    private static final Pattern GIT_COORD = Pattern.compile("github:([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:#path:/([A-Za-z0-9_./-]+))?");
    private static final Pattern PACKAGE = Pattern.compile("(?:@[a-z0-9._-]+/)?[a-z0-9._-]+");
    private static final Pattern SEMVER = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?(?:\\+[A-Za-z0-9.-]+)?");
    private static final ScheduledExecutorService DOWNLOAD_TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "hdsl-pack-download-timeout"); thread.setDaemon(true); return thread;
    });
    private final HttpClient http;

    public PackService() { this(null); }
    public PackService(URI proxy) {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER);
        if (proxy != null) {
            if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null || proxy.getPort() < 1 || proxy.getUserInfo() != null)
                throw new IllegalArgumentException("代理应为不含凭据的 http://host:port");
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        http = builder.build();
    }

    public PackInspection inspect(Path archive) throws Exception {
        Path source = archive.toAbsolutePath().normalize();
        try (SafeArchive zip = new SafeArchive(source)) {
            int markers = (zip.entries.containsKey("dspack.json") ? 1 : 0) +
                    (zip.entries.containsKey("dsh-snapshot.json") ? 1 : 0) + (zip.entries.containsKey("dsh-pack.yaml") ? 1 : 0);
            if (markers != 1) throw new IOException(markers == 0 ? "无法识别整合包；旧 tgz 和缺少版本标记的 ZIP 尚不支持" : "整合包含多个冲突的格式标记");
            if (zip.entries.containsKey("dsh-pack.yaml")) throw new IOException("Overture YAML 清单格式尚未支持，请导出 format 1 快照 ZIP");
            List<String> warnings = new ArrayList<>();
            long plannedBytes = zip.total;
            JsonNode manifest; String format, name, version, dshVersion, defaultProfile;
            List<String> profiles = new ArrayList<>();
            if (zip.entries.containsKey("dspack.json")) {
                JsonNode marker = readJson(zip, "dspack.json");
                int container = marker.path("version").asInt(-1);
                if (!marker.path("version").isIntegralNumber() || !"dspack".equals(marker.path("format").asText()) || (container != 2 && container != 3))
                    throw new IOException("不支持该 dspack 容器版本: " + marker.path("version"));
                manifest = readJson(zip, "manifest.json");
                int mv = manifest.path("manifestVersion").asInt(-1);
                if (!manifest.path("manifestVersion").isIntegralNumber() || (mv != 4 && mv != 5))
                    throw new IOException("不支持 manifestVersion=" + manifest.path("manifestVersion") + "；当前支持 4/5，未知版本需专门适配");
                name = required(manifest, "name"); version = required(manifest, "version");
                dshVersion = optional(manifest, "dshVersion");
                if (dshVersion != null && !SEMVER.matcher(dshVersion).matches()) throw new IOException("dshVersion 必须为精确版本");
                validateVersions(manifest, dshVersion);
                String type = required(manifest, "type");
                if (type.equals("profile")) {
                    defaultProfile = optional(manifest, "profileName");
                    if (defaultProfile == null) defaultProfile = "pack";
                    profileName(defaultProfile);
                    profiles.add(defaultProfile); validateUnit(manifest);
                } else if (type.equals("dshhome") && mv == 5 && container == 3) {
                    JsonNode units = manifest.path("profiles");
                    if (!units.isObject() || units.isEmpty()) throw new IOException("dshhome.profiles 必须为非空对象");
                    Set<String> seen = new HashSet<>();
                    for (var it = units.fields(); it.hasNext();) {
                        var e = it.next(); profileName(e.getKey());
                        if (Set.of("web", "headless").contains(e.getKey()) || !seen.add(SafeArchive.key(e.getKey())))
                            throw new IOException("dshhome 含基线模板或 profile 名称冲突: " + e.getKey());
                        profiles.add(e.getKey()); validateUnit(e.getValue());
                    }
                    defaultProfile = required(manifest, "defaultProfile");
                    if (!profiles.contains(defaultProfile)) throw new IOException("defaultProfile 未出现在 profiles 中");
                } else throw new IOException("该容器/manifest 不支持 type=" + type);
                if (container == 2 && zip.entries.keySet().stream().anyMatch(p -> p.startsWith("home/")))
                    throw new IOException("容器 v2 不支持 home/，请使用 v3");
                if (type.equals("dshhome") && zip.entries.keySet().stream().anyMatch(p -> p.startsWith("home/")))
                    throw new IOException("dshhome 不应包含独立 home/ 层");
                validateVendors(zip, manifest, profiles);
                validateResources(manifest);
                Map<String, String> mapped = mappings(zip, manifest, defaultProfile, profiles, false, warnings);
                validateResourceMappings(manifest, defaultProfile, mapped);
                plannedBytes = plannedBytes(zip, manifest, profiles, mapped);
                validateLocalDependencies(zip, manifest, profiles, mapped);
                format = "dspack";
                warnings.add("导入后需要 pnpm 恢复依赖；内嵌 tarball 不代表完全离线，安装脚本由启动器的依赖策略处理。");
                if (manifest.has("vendored")) warnings.add("含 PackForge vendored 实现扩展：已校验大小与 SHA-256；仅直接依赖改为本地 tarball，其余仍可能联网。");
                if (manifest.has("launchers")) warnings.add("包声明的启动器兼容信息: " + manifest.path("launchers") + "；注册表 hdsl 指向其他项目，本启动器未视作已兼容。");
                if (manifest.has("dshVersions")) warnings.add("作者声明的 Harness 兼容版本: " + manifest.path("dshVersions") + "；属于作者声明。");
                if (manifest.path("hdslShareExport").path("warnings").isArray())
                    manifest.path("hdslShareExport").path("warnings").forEach(n -> warnings.add(n.asText()));
            } else {
                manifest = readJson(zip, "dsh-snapshot.json");
                if (!manifest.path("format").isIntegralNumber() || manifest.path("format").asInt(-1) != 1)
                    throw new IOException("不支持 Overture snapshot format=" + manifest.path("format"));
                String platform = required(manifest, "platform");
                String local = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "win32" :
                        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "darwin" : "linux";
                if (!platform.equals(local)) throw new IOException("快照平台 " + platform + " 与本机 " + local + " 不符；含已安装依赖，不能跨平台恢复");
                dshVersion = null;
                for (String path : zip.entries.keySet()) {
                    String[] parts = path.split("/");
                    if (parts.length == 3 && parts[0].equals("profiles") && parts[2].equals("profile.yaml") && !parts[1].equals("node_modules")) {
                        profileName(parts[1]); profiles.add(parts[1]);
                        JsonNode metadata = readYaml(zip.bytes(path, 256 * 1024));
                        String pv = optional(metadata, "dshVersion");
                        if (pv != null && !SEMVER.matcher(pv).matches()) throw new IOException("快照 dshVersion 不是精确版本");
                        if (dshVersion != null && pv != null && !dshVersion.equals(pv)) throw new IOException("快照多个 profile 使用不同 Harness 版本，请分别导出");
                        if (pv != null) dshVersion = pv;
                    }
                }
                if (profiles.isEmpty()) throw new IOException("快照缺少 profiles/<id>/profile.yaml");
                defaultProfile = profiles.getFirst(); name = source.getFileName().toString().replaceFirst("(?i)\\.zip$", ""); version = "snapshot-1";
                format = "overture-snapshot";
                Map<String, String> mapped = mappings(zip, manifest, defaultProfile, profiles, true, warnings);
                validateSnapshotDependencies(zip, mapped, profiles);
                validateLinks(manifest, mapped, warnings);
                warnings.add("同平台快照仍受 Node ABI、CPU 架构和原生依赖影响；元数据未声明这些信息，需要启动验证。");
                if (profiles.size() > 1) warnings.add("快照未标准化默认 profile；默认选择包内第一个 profile: " + defaultProfile);
                if (manifest.path("warnings").isArray()) manifest.path("warnings").forEach(n -> warnings.add("源快照提示: " + n.asText()));
                if (manifest.has("private")) warnings.add("源包含隐私类别声明；本启动器仍跳过凭据、设置和会话文件。");
            }
            if (profiles.stream().anyMatch(p -> p.equalsIgnoreCase("desktop")))
                warnings.add("部分新版 Harness CLI 将 desktop profile 保留给 Electron 桌面端。本启动器可导入其文件并恢复依赖，但直接通过 CLI 启动可能被拒绝，需要针对所用 Harness 适配；不会自动更名。");
            return new PackInspection(source, format, name, version, dshVersion, defaultProfile, profiles,
                    warnings, plannedBytes, manifest, SafeArchive.sha(source));
        }
    }

    /** Destination must be new or empty. No existing instance is overwritten. */
    public void extract(PackInspection inspection, Path destinationDshHome, Consumer<String> log) throws Exception {
        Objects.requireNonNull(log);
        if (!SafeArchive.sha(inspection.archive()).equals(inspection.archiveSha256())) throw new IOException("检查后归档已变化，请重新检查");
        PackInspection fresh = inspect(inspection.archive());
        Path dest = destinationDshHome.toAbsolutePath().normalize();
        SafeArchive.noLinks(dest);
        if (Files.exists(dest)) {
            if (!Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) throw new IOException("目标不是目录");
            try (var children = Files.list(dest)) { if (children.findAny().isPresent()) throw new IOException("只能解压到新的空目录，以保护已有实例"); }
        }
        Files.createDirectories(dest);
        boolean snapshot = fresh.format().equals("overture-snapshot");
        JsonNode manifest = fresh.manifest();
        try (SafeArchive zip = new SafeArchive(fresh.archive())) {
            var mapped = mappings(zip, manifest, fresh.defaultProfile(), fresh.profiles(), snapshot, new ArrayList<>());
            int count = 0;
            for (var entry : mapped.entrySet()) {
                Path target = SafeArchive.target(dest, entry.getKey());
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    zip.copy(entry.getValue(), out, SafeArchive.MAX_FILE);
                }
                if (++count % 500 == 0) log.accept("已恢复 " + count + " 个文件");
            }
            if (snapshot) restoreLinks(dest, manifest, mapped, log);
            else {
                for (String profile : fresh.profiles()) materialize(zip, dest, profile, manifest, log);
                downloadResources(dest, manifest, fresh.defaultProfile(), log);
            }
            writeJson(dest.resolve(ORIGIN), manifest);
            log.accept("整合包文件恢复完成，共 " + count + " 个文件；格式 " + fresh.format());
        }
    }

    private static Map<String, String> mappings(SafeArchive zip, JsonNode m, String profile, List<String> profiles,
                                               boolean snapshot, List<String> warnings) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        int excluded = 0;
        for (var entry : zip.entries.entrySet()) {
            if (entry.getValue().isDirectory()) continue;
            String p = entry.getKey(), target = null;
            if (snapshot) { if (!p.equals("dsh-snapshot.json")) target = p; }
            else if (p.startsWith("overrides/")) target = (m.path("type").asText().equals("profile") ? "profiles/" + profile + "/" : "") + p.substring(10);
            else if (p.startsWith("home/")) target = p.substring(5);
            else if (MACHINE.contains(p)) {
                target = "profiles/" + profile + "/" + p;
                // Home packs sometimes carry a default-profile snapshot at both locations.
                // Its per-profile machine file carries the most specific metadata.
                if (m.path("type").asText().equals("dshhome") && zip.entries.containsKey("overrides/" + target)) continue;
            }
            if (target == null) continue;
            SafeArchive.safe(target, false);
            if (excluded(target, false, snapshot)) { excluded++; continue; }
            if (!snapshot && target.startsWith("profiles/")) {
                String[] parts = target.split("/");
                if (parts.length < 3 || !profiles.contains(parts[1])) throw new IOException("包引用未声明 profile: " + target);
            }
            String key = SafeArchive.key(target);
            if (!seen.add(key)) throw new IOException("不同归档入口写入同一目标: " + target);
            out.put(target, p);
        }
        // Mapping can create collisions even when ZIP paths themselves are distinct.
        for (String path : seen) {
            int pos = path.lastIndexOf('/');
            while (pos > 0) {
                if (seen.contains(path.substring(0, pos))) throw new IOException("映射后的文件/目录冲突: " + path);
                pos = path.lastIndexOf('/', pos - 1);
            }
        }
        if (excluded > 0) warnings.add("将跳过 " + excluded + " 个凭据、会话、设置或机器状态文件；未读取这些文件的内容。");
        return out;
    }

    private static void materialize(SafeArchive zip, Path home, String profile, JsonNode manifest, Consumer<String> log) throws Exception {
        JsonNode unit = unit(manifest, profile);
        Path dir = home.resolve("profiles").resolve(profile); Files.createDirectories(dir);
        Path pkgPath = dir.resolve("package.json");
        ObjectNode pkg = Files.exists(pkgPath) ? object(JSON.readTree(Files.readAllBytes(pkgPath)), "package.json") : JSON.createObjectNode();
        if (!pkg.has("name")) pkg.put("name", "dsh-profile-" + profile);
        pkg.put("private", true);
        ObjectNode deps = JSON.createObjectNode();
        for (var iter = unit.path("dependencies").fields(); iter.hasNext();) {
            var e = iter.next(); String coord = e.getKey(), spec = e.getValue().asText();
            String pkgName = packageName(coord);
            JsonNode vendor = manifest.path("vendored").path(coord);
            if (vendor.isObject()) {
                String vendorPath = required(vendor, "path");
                String blob = "vendor-blobs/" + required(vendor, "sha256").toLowerCase(Locale.ROOT) + ".tgz";
                Path file = SafeArchive.target(dir, blob); Files.createDirectories(file.getParent());
                if (!Files.exists(file)) try (var out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) { zip.copy(vendorPath, out, SafeArchive.MAX_FILE); }
                spec = "file:" + blob;
            } else {
                Matcher git = GIT_COORD.matcher(coord);
                if (git.matches()) spec = "github:" + git.group(1) + "/" + git.group(2) + "#" + spec + (git.group(3) == null ? "" : "&path:" + git.group(3));
            }
            deps.put(pkgName, spec);
        }
        pkg.set("dependencies", deps);
        ObjectNode dsh = pkg.path("dsh").isObject() ? (ObjectNode) pkg.path("dsh") : pkg.putObject("dsh");
        ObjectNode p = dsh.path("profile").isObject() ? (ObjectNode) dsh.path("profile") : dsh.putObject("profile");
        p.set("bundles", unit.path("bundles").deepCopy());
        writeJson(pkgPath, pkg);
        if (!Files.exists(dir.resolve("cordis.patch.yml")) && unit.path("patch").isTextual())
            Files.writeString(dir.resolve("cordis.patch.yml"), unit.path("patch").asText(), StandardOpenOption.CREATE_NEW);
        log.accept("已按 manifest 重建 profile: " + profile + "，保留 bundle 顺序与未知 package 字段");
    }

    private static JsonNode unit(JsonNode m, String profile) { return m.path("type").asText().equals("profile") ? m : m.path("profiles").path(profile); }
    private static void validateUnit(JsonNode unit) throws Exception {
        if (!unit.isObject() || !unit.path("bundles").isArray() || !unit.path("dependencies").isObject()) throw new IOException("profile 需要 bundles 数组与 dependencies 对象");
        for (JsonNode n : unit.path("bundles")) {
            if (!n.isTextual() || n.asText().isBlank()) throw new IOException("bundles 必须为非空字符串列表");
            SafeArchive.safe(n.asText().startsWith("./") ? n.asText().substring(2) : n.asText(), false);
        }
        if (unit.has("patch") && !unit.path("patch").isTextual()) throw new IOException("patch 必须为字符串");
        Set<String> names = new HashSet<>();
        for (var i = unit.path("dependencies").fields(); i.hasNext();) {
            var e = i.next(); String name = packageName(e.getKey());
            if (!names.add(SafeArchive.key(name))) throw new IOException("多个依赖坐标映射到同一个 npm 包: " + name);
            if (!e.getValue().isTextual() || e.getValue().asText().isBlank()) throw new IOException("依赖版本必须是非空字符串");
            String spec = e.getValue().asText();
            if (spec.startsWith("vendor:") || spec.startsWith("link:") || spec.startsWith("workspace:")) throw new IOException("尚不支持依赖方言 " + spec.split(":")[0] + ":，请导出标准 vendored tarball");
            if (spec.contains("\n") || spec.contains("\r") || spec.contains("\0") || spec.matches("(?i).*(?:https?|git\\+https?)://[^/]+@.*")) throw new IOException("依赖声明包含非法控制字符或内嵌凭据");
            if (spec.startsWith("file:")) SafeArchive.safe(spec.substring(5), false);
            else if (spec.startsWith("/") || spec.startsWith(".") || spec.contains("\\") || spec.matches("^[A-Za-z]:.*")) throw new IOException("依赖不能引用源机器的路径");
        }
    }
    private static String packageName(String coord) throws Exception {
        Matcher git = GIT_COORD.matcher(coord);
        if (git.matches()) {
            String name = git.group(3) == null ? git.group(2) : git.group(3);
            SafeArchive.safe(name, false);
            if (!PACKAGE.matcher(name).matches()) throw new IOException("不支持该 git 子包名: " + name);
            return name;
        }
        if (!PACKAGE.matcher(coord).matches()) throw new IOException("无法解析依赖坐标: " + coord);
        return coord;
    }
    private static void validateVersions(JsonNode manifest, String dsh) throws IOException {
        if (!manifest.has("dshVersions")) return;
        JsonNode versions = manifest.path("dshVersions");
        if (!versions.isArray() || versions.isEmpty()) throw new IOException("dshVersions 必须为非空版本枚举");
        Set<String> seen = new HashSet<>();
        for (JsonNode v : versions) if (!v.isTextual() || !SEMVER.matcher(v.asText()).matches() || !seen.add(v.asText())) throw new IOException("dshVersions 含非法或重复版本");
        if (dsh != null && !seen.contains(dsh)) throw new IOException("dshVersion 不在 dshVersions 声明中");
    }
    private static void validateLocalDependencies(SafeArchive zip, JsonNode m, List<String> profiles, Map<String, String> mapped) throws Exception {
        for (String profile : profiles) for (var i = unit(m, profile).path("dependencies").fields(); i.hasNext();) {
            var e = i.next(); String spec = e.getValue().asText();
            if (!spec.startsWith("file:") || m.path("vendored").has(e.getKey())) continue;
            String p = "profiles/" + profile + "/" + SafeArchive.safe(spec.substring(5), false);
            if (!mapped.containsKey(p) && mapped.keySet().stream().noneMatch(x -> x.startsWith(p + "/"))) throw new IOException("file: 依赖未包含在包内: " + e.getKey());
            if (mapped.containsKey(p)) {
                if (!p.endsWith(".tgz")) throw new IOException("file: 文件依赖必须为 .tgz npm tarball");
                TarSafety.inspect(zip, mapped.get(p));
            }
        }
    }
    private static void validateSnapshotDependencies(SafeArchive zip, Map<String, String> mapped, List<String> profiles) throws Exception {
        for (String profile : profiles) {
            String pkg = "profiles/" + profile + "/package.json";
            if (!mapped.containsKey(pkg)) throw new IOException("快照 profile 缺少 package.json: " + profile);
            JsonNode metadata = readJson(zip, mapped.get(pkg));
            for (JsonNode specNode : metadata.path("dependencies")) {
                String spec = specNode.asText();
                if (spec.startsWith("file:") || spec.startsWith("link:")) {
                    String local = spec.substring(spec.indexOf(':') + 1);
                    // Source-machine absolute references cannot be made portable by copying node_modules.
                    SafeArchive.safe(local, false);
                    String target = "profiles/" + profile + "/" + local;
                    if (!mapped.containsKey(target) && mapped.keySet().stream().noneMatch(p -> p.startsWith(target + "/")))
                        throw new IOException("快照本地依赖本体缺失: " + profile);
                } else if (spec.startsWith("/") || spec.startsWith(".") || spec.contains("\\") || spec.matches("^[A-Za-z]:.*"))
                    throw new IOException("快照依赖仍引用源机器的路径: " + profile);
            }
        }
    }
    private static long plannedBytes(SafeArchive zip, JsonNode m, List<String> profiles, Map<String, String> mapped) throws Exception {
        long size = 0;
        for (String source : mapped.values()) size = Math.addExact(size, zip.entries.get(source).getSize());
        for (String profile : profiles) for (var deps = unit(m, profile).path("dependencies").fieldNames(); deps.hasNext();) {
            JsonNode vendor = m.path("vendored").path(deps.next());
            if (vendor.isObject()) size = Math.addExact(size, vendor.path("size").asLong());
        }
        for (JsonNode resource : resources(m)) size = Math.addExact(size, resource.path("size").asLong());
        if (size > SafeArchive.MAX_UNPACKED) throw new IOException("恢复文件、内嵌依赖副本和外部资源合计超过 6 GiB");
        return size;
    }

    private static void validateVendors(SafeArchive zip, JsonNode m, List<String> profiles) throws Exception {
        JsonNode vendors = m.path("vendored");
        if (!vendors.isMissingNode() && !vendors.isObject()) throw new IOException("vendored 必须为对象");
        if (zip.entries.containsKey("vendor/vendor.json")) throw new IOException("vendor/vendor.json 方言尚未支持，请使用标准 vendored 对象");
        Set<String> allowed = new HashSet<>();
        for (String profile : profiles) unit(m, profile).path("dependencies").fieldNames().forEachRemaining(allowed::add);
        if (zip.entries.containsKey("pnpm-lock.yaml")) {
            JsonNode lock = readYaml(zip.bytes("pnpm-lock.yaml", SafeArchive.MAX_METADATA));
            for (String section : List.of("packages", "snapshots")) lock.path(section).fieldNames().forEachRemaining(k -> {
                int index = k.indexOf('(', 0); if (index > 0) k = k.substring(0, index);
                int at = k.lastIndexOf('@'); if (at > 0) allowed.add(k.substring(0, at).replaceFirst("^/", ""));
            });
        }
        Set<String> paths = new HashSet<>();
        long expandedVendorBytes = 0;
        for (var i = vendors.fields(); i.hasNext();) {
            var e = i.next(); JsonNode v = e.getValue();
            if (!allowed.contains(e.getKey())) throw new IOException("vendored 坐标不在依赖或锁文件中: " + e.getKey());
            String p = SafeArchive.safe(required(v, "path"), false);
            if (!p.startsWith("vendor/") || !p.endsWith(".tgz")) throw new IOException("vendored.path 必须为 vendor/*.tgz");
            required(v, "version"); validateIntegrity(v);
            if (!paths.add(p)) throw new IOException("vendored tarball 路径重复");
            var entry = zip.entries.get(p);
            if (entry == null || entry.getSize() != v.path("size").asLong()) throw new IOException("vendored 大小不符或文件缺失: " + p);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            zip.copy(p, new java.security.DigestOutputStream(OutputStream.nullOutputStream(), digest), SafeArchive.MAX_FILE);
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(v.path("sha256").asText())) throw new IOException("vendored SHA-256 校验失败: " + p);
            expandedVendorBytes = Math.addExact(expandedVendorBytes, TarSafety.inspect(zip, p));
            if (expandedVendorBytes > SafeArchive.MAX_UNPACKED) throw new IOException("所有内嵌 tarball 展开合计超过 6 GiB");
        }
        for (var e : zip.entries.entrySet()) if (e.getKey().startsWith("vendor/") && !e.getValue().isDirectory() && !paths.contains(e.getKey())) throw new IOException("未登记的 vendor 文件: " + e.getKey());
    }

    private static List<JsonNode> resources(JsonNode m) throws IOException {
        List<JsonNode> result = new ArrayList<>();
        for (String field : List.of("files", "skills")) {
            JsonNode values = m.path(field);
            if (values.isMissingNode()) continue;
            if (!values.isArray()) throw new IOException(field + " 必须为数组");
            for (JsonNode value : values) if (field.equals("files") || value.has("urls") || value.has("sha256")) result.add(value);
        }
        return result;
    }
    private static void validateResources(JsonNode m) throws Exception {
        long bytes = 0; Set<String> seen = new HashSet<>();
        for (JsonNode r : resources(m)) {
            String path = SafeArchive.safe(required(r, "path"), false);
            if (excluded(path, false, false) || !seen.add(SafeArchive.key(path)) || MACHINE.contains(Path.of(path).getFileName().toString())) throw new IOException("资源落点敏感、重复或覆盖机器文件: " + path);
            validateIntegrity(r); bytes += r.path("size").asLong();
            if (bytes > SafeArchive.MAX_UNPACKED) throw new IOException("外部资源总大小超过上限");
            if (!r.path("urls").isArray() || r.path("urls").isEmpty()) throw new IOException("资源 urls 不能为空");
            for (JsonNode url : r.path("urls")) safeUrl(url.asText());
        }
    }
    private static void validateResourceMappings(JsonNode m, String profile, Map<String, String> mapped) throws Exception {
        Set<String> destinations = new HashSet<>(); mapped.keySet().forEach(p -> destinations.add(SafeArchive.key(p)));
        for (JsonNode resource : resources(m)) {
            boolean skill = false; for (JsonNode s : m.path("skills")) if (s == resource) skill = true;
            String prefix = !skill && m.path("type").asText().equals("profile") ? "profiles/" + profile + "/" : "";
            String path = SafeArchive.key(prefix + required(resource, "path"));
            if (!destinations.add(path) || hasParent(destinations, path) || destinations.stream().anyMatch(p -> p.startsWith(path + "/")))
                throw new IOException("下载资源与归档或其他资源的目标冲突: " + resource.path("path").asText());
        }
    }
    private static void validateIntegrity(JsonNode node) throws IOException {
        if (!node.path("sha256").isTextual() || !node.path("sha256").asText().matches("[A-Fa-f0-9]{64}") ||
                !node.path("size").isIntegralNumber() || !node.path("size").canConvertToLong() || node.path("size").asLong() < 0 || node.path("size").asLong() > SafeArchive.MAX_FILE)
            throw new IOException("资源需要合法 sha256 与有界 size");
    }
    private static URI safeUrl(String value) throws Exception {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new IOException("资源下载仅支持不含用户凭据的 HTTPS 地址");
        return uri;
    }
    private void downloadResources(Path home, JsonNode manifest, String profile, Consumer<String> log) throws Exception {
        for (JsonNode resource : resources(manifest)) {
            boolean skill = false; for (JsonNode s : manifest.path("skills")) if (s == resource) skill = true;
            Path root = !skill && manifest.path("type").asText().equals("profile") ? home.resolve("profiles").resolve(profile) : home;
            Path file = SafeArchive.target(root, required(resource, "path")); Files.createDirectories(file.getParent());
            if (Files.exists(file)) throw new IOException("外部资源与归档内容冲突: " + resource.path("path").asText());
            Path temp = Files.createTempFile(file.getParent(), ".hdsl-download-", ".tmp");
            Exception last = null; boolean done = false;
            try {
                for (JsonNode url : resource.path("urls")) {
                    try {
                        URI uri = safeUrl(url.asText());
                        log.accept("下载并校验资源: " + resource.path("path").asText());
                        for (int redirects = 0;; redirects++) {
                            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build();
                            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                            var timeout = DOWNLOAD_TIMER.schedule(() -> {
                                try { response.body().close(); } catch (IOException ignored) { }
                            }, 5, TimeUnit.MINUTES);
                            try (InputStream in = response.body()) {
                                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                                    if (redirects >= 5) throw new IOException("下载重定向过多");
                                    uri = safeUrl(uri.resolve(response.headers().firstValue("Location").orElseThrow()).toString()); continue;
                                }
                                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                                long count = 0; byte[] buffer = new byte[64 * 1024]; int n;
                                try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                                    while ((n = in.read(buffer)) != -1) {
                                        SafeArchive.interrupted(); count += n;
                                        if (count > resource.path("size").asLong()) throw new IOException("下载体积超过声明");
                                        out.write(buffer, 0, n);
                                    }
                                }
                                if (count != resource.path("size").asLong() || !SafeArchive.sha(temp).equalsIgnoreCase(resource.path("sha256").asText())) throw new IOException("资源大小或 SHA-256 校验失败");
                            } finally { timeout.cancel(false); }
                            Files.move(temp, file); done = true; break;
                        }
                        if (done) break;
                    } catch (Exception failure) {
                        if (failure instanceof InterruptedException || failure instanceof InterruptedIOException) throw failure;
                        last = failure;
                    }
                }
                if (!done) throw new IOException("资源下载失败: " + resource.path("path").asText(), last);
            } finally { Files.deleteIfExists(temp); }
        }
    }

    private static Map<String, String> links(JsonNode m) throws Exception {
        JsonNode nodes = m.path("links");
        if (!nodes.isArray()) throw new IOException("快照 links 必须是数组");
        LinkedHashMap<String, String> result = new LinkedHashMap<>(); Set<String> seen = new HashSet<>();
        for (JsonNode node : nodes) {
            String p = SafeArchive.safe(required(node, "path"), false), t = required(node, "target");
            if (excluded(p, false, true)) continue;
            if (!seen.add(SafeArchive.key(p))) throw new IOException("快照链接重复: " + p);
            if (t.startsWith("/") || t.contains("\\") || t.contains(":") || t.contains("\0")) throw new IOException("快照不允许绝对或外部链接: " + p);
            Path parent = Path.of(p).getParent();
            Path normalized = (parent == null ? Path.of(t) : parent.resolve(t)).normalize();
            String target = normalized.toString().replace('\\', '/');
            SafeArchive.safe(target, false);
            if (excluded(target, false, true)) throw new IOException("快照链接指向排除的私人文件: " + p);
            result.put(p, target);
        }
        return result;
    }
    private static void validateLinks(JsonNode m, Map<String, String> mapped, List<String> warnings) throws Exception {
        var links = links(m); NavigableSet<String> files = new TreeSet<>(); mapped.keySet().forEach(p -> files.add(SafeArchive.key(p)));
        NavigableSet<String> linkPaths = new TreeSet<>(); links.keySet().forEach(p -> linkPaths.add(SafeArchive.key(p)));
        for (var link : links.entrySet()) {
            String key = SafeArchive.key(link.getKey());
            if (files.contains(key) || hasChild(files, key) || hasParent(files, key)) throw new IOException("快照链接与文件冲突: " + link.getKey());
            if (hasChild(linkPaths, key) || hasParent(linkPaths, key)) throw new IOException("快照链接嵌套尚不支持: " + link.getKey());
            String target = SafeArchive.key(link.getValue());
            if (linkPaths.contains(target) || hasParent(linkPaths, target) || hasChild(linkPaths, target)) throw new IOException("快照间接或潜在循环链接尚不支持: " + link.getKey());
            if (!files.contains(target) && !hasChild(files, target)) throw new IOException("快照链接目标缺失: " + link.getKey());
            if (target.equals(key) || target.startsWith(key + "/") || key.startsWith(target + "/")) throw new IOException("快照链接形成目录循环: " + link.getKey());
        }
        if (!links.isEmpty()) warnings.add("快照包含 " + links.size() + " 个内部符号链接；Windows 需开发者模式或链接创建权限，失败将终止导入。");
    }
    private static boolean hasChild(NavigableSet<String> paths, String path) {
        String next = paths.ceiling(path + "/"); return next != null && next.startsWith(path + "/");
    }
    private static boolean hasParent(Set<String> paths, String path) {
        for (int p = path.lastIndexOf('/'); p > 0; p = path.lastIndexOf('/', p - 1)) if (paths.contains(path.substring(0, p))) return true;
        return false;
    }
    private static void restoreLinks(Path home, JsonNode m, Map<String, String> mapped, Consumer<String> log) throws Exception {
        validateLinks(m, mapped, new ArrayList<>());
        for (var link : links(m).entrySet()) {
            Path source = SafeArchive.target(home, link.getKey()), target = home.resolve(link.getValue()).normalize();
            SafeArchive.noLinks(target);
            if (!target.toRealPath().startsWith(home.toRealPath())) throw new IOException("链接实际目标越界");
            Files.createDirectories(source.getParent());
            try { Files.createSymbolicLink(source, source.getParent().relativize(target)); }
            catch (IOException | UnsupportedOperationException error) { throw new IOException("无法创建快照内部链接；Windows 请开启开发者模式后重试: " + link.getKey(), error); }
        }
        log.accept("快照内部链接已恢复");
    }

    public void exportProfile(Path dshHome, String profile, String dshVersion, String name, String version, Path output, Consumer<String> log) throws Exception {
        export(dshHome, profile, dshVersion, name, version, output, false, log);
    }
    public void exportHome(Path dshHome, String defaultProfile, String dshVersion, String name, String version, Path output, Consumer<String> log) throws Exception {
        export(dshHome, defaultProfile, dshVersion, name, version, output, true, log);
    }
    private void export(Path dshHome, String profile, String dshVersion, String name, String version, Path output, boolean wholeHome, Consumer<String> log) throws Exception {
        profileName(profile);
        if (name == null || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || version == null || !SEMVER.matcher(version).matches()) throw new IOException("包名需为小写 kebab-case，版本需为 semver");
        if (dshVersion != null && !dshVersion.isBlank() && !SEMVER.matcher(dshVersion).matches()) throw new IOException("Harness 版本需精确版本");
        Path source = dshHome.toAbsolutePath().normalize(), out = output.toAbsolutePath().normalize();
        SafeArchive.noLinks(source); SafeArchive.noLinks(out.getParent());
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("DSH_HOME 不存在");
        if (out.startsWith(source) || Files.exists(out, LinkOption.NOFOLLOW_LINKS)) throw new IOException("导出文件必须位于实例目录之外，且不能覆盖已有文件");
        if (!Files.isDirectory(out.getParent())) throw new IOException("导出目录不存在");
        ObjectNode manifest = JSON.createObjectNode();
        JsonNode previousManifest = JSON.createObjectNode();
        Path origin = source.resolve(ORIGIN);
        if (Files.isRegularFile(origin, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(origin) > SafeArchive.MAX_METADATA) throw new IOException("原始 manifest 太大");
            JsonNode previous = JSON.readTree(Files.readAllBytes(origin));
            if (previous.path("manifestVersion").asInt() == 4 || previous.path("manifestVersion").asInt() == 5) {
                manifest = object(previous.deepCopy(), ORIGIN); previousManifest = previous;
            }
        }
        manifest.put("manifestVersion", 5).put("type", wholeHome ? "dshhome" : "profile").put("name", name).put("version", version);
        for (String remove : List.of("profiles", "defaultProfile", "profileName", "dependencies", "bundles", "patch", "vendored", "files", "skills", "dshVersions")) manifest.remove(remove);
        if (dshVersion != null && !dshVersion.isBlank()) manifest.put("dshVersion", dshVersion); else manifest.remove("dshVersion");
        ObjectNode share = manifest.putObject("hdslShareExport");
        share.putArray("omitted").add("credentials").add("settings").add("sessions").add("cordis.patch.yml").add("cordis.patch.yaml");
        share.putArray("warnings").add("此为分享导出：凭据、全局设置、会话及 cordis.patch 配置未包含；导入后需要重新配置提供商和插件选项。这不是完整备份。");
        List<String> profiles = new ArrayList<>();
        if (wholeHome) {
            try (var children = Files.list(source.resolve("profiles"))) {
                for (Path dir : children.sorted().toList()) if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) && !Set.of("web", "headless", "node_modules").contains(dir.getFileName().toString())) {
                    SafeArchive.noLinks(dir); profileName(dir.getFileName().toString()); profiles.add(dir.getFileName().toString());
                }
            }
            if (profiles.isEmpty() || !profiles.contains(profile)) throw new IOException("默认 profile 必须存在，且不能是 web/headless 基线模板");
            ObjectNode units = manifest.putObject("profiles");
            for (String p : profiles) units.set(p, exportUnit(source.resolve("profiles").resolve(p), unit(previousManifest, p)));
            manifest.put("defaultProfile", profile);
        } else {
            profiles.add(profile); ObjectNode unit = exportUnit(source.resolve("profiles").resolve(profile), unit(previousManifest, profile));
            manifest.setAll(unit); manifest.put("profileName", profile);
        }
        List<Path> files = new ArrayList<>();
        final int[] skipped = {0}; final long[] total = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                String rel = source.relativize(dir).toString().replace('\\', '/');
                if (!rel.isEmpty() && (excluded(rel, true, false) || !selected(rel, profiles, wholeHome))) { skipped[0]++; return FileVisitResult.SKIP_SUBTREE; }
                SafeArchive.noLinks(dir); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                String rel = source.relativize(file).toString().replace('\\', '/');
                if (excluded(rel, true, false) || !selected(rel, profiles, wholeHome)) { skipped[0]++; return FileVisitResult.CONTINUE; }
                if (!a.isRegularFile() || a.isSymbolicLink() || a.isOther()) throw new IOException("导出不跟随文件链接或 junction: " + rel);
                SafeArchive.noLinks(file); SafeArchive.safe(rel, false);
                if (a.size() > SafeArchive.MAX_FILE || (total[0] += a.size()) > SafeArchive.MAX_UNPACKED || files.size() >= SafeArchive.MAX_ENTRIES) throw new IOException("导出体积超过限制");
                files.add(file); return FileVisitResult.CONTINUE;
            }
        });
        // Validate local dependencies against the actual selected files before writing anything.
        for (String p : profiles) for (var i = unit(manifest, p).path("dependencies").fields(); i.hasNext();) {
            var dep = i.next(); String spec = dep.getValue().asText();
            if (spec.startsWith("file:")) {
                Path local = source.resolve("profiles").resolve(p).resolve(SafeArchive.safe(spec.substring(5), false));
                if (files.stream().noneMatch(f -> f.equals(local) || f.startsWith(local))) throw new IOException("本地依赖未包含在可分享文件中: " + dep.getKey());
            }
        }
        Path temp = Files.createTempFile(out.getParent(), ".hdsl-export-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp), StandardCharsets.UTF_8)) {
                put(zip, "dspack.json", JSON.writeValueAsBytes(JSON.createObjectNode().put("format", "dspack").put("version", 3)));
                put(zip, "manifest.json", JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                for (Path file : files) {
                    SafeArchive.interrupted(); SafeArchive.noLinks(file);
                    String rel = source.relativize(file).toString().replace('\\', '/');
                    String zipName;
                    if (wholeHome) zipName = "overrides/" + rel;
                    else if (rel.startsWith("profiles/" + profile + "/")) {
                        String shortName = rel.substring(("profiles/" + profile + "/").length());
                        zipName = MACHINE.contains(shortName) ? shortName : "overrides/" + shortName;
                    } else zipName = "home/" + rel;
                    zip.putNextEntry(new ZipEntry(zipName));
                    try (InputStream in = Files.newInputStream(file)) { in.transferTo(zip); }
                    zip.closeEntry();
                }
            }
            inspect(temp); // Producer must pass the same consumer checks before publishing.
            Files.move(temp, out);
            log.accept("已导出 " + files.size() + " 个文件，跳过 " + skipped[0] + " 项私人设置、凭据、缓存、会话和补丁配置。");
        } finally { Files.deleteIfExists(temp); }
    }
    private static boolean selected(String rel, List<String> profiles, boolean home) {
        if (rel.equals("profiles")) return true;
        if (!rel.startsWith("profiles/")) return true;
        String[] parts = rel.split("/"); return parts.length > 1 && profiles.contains(parts[1]);
    }
    private static ObjectNode exportUnit(Path dir, JsonNode original) throws Exception {
        Path path = dir.resolve("package.json"); SafeArchive.noLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > SafeArchive.MAX_METADATA) throw new IOException("profile 缺少有效 package.json: " + dir.getFileName());
        JsonNode pkg = JSON.readTree(Files.readAllBytes(path));
        ObjectNode unit = original.isObject() ? ((ObjectNode) original).deepCopy() : JSON.createObjectNode();
        // A profile manifest also contains pack-level keys; only preserve unit-level extras for home units.
        if (unit.has("manifestVersion")) unit = JSON.createObjectNode();
        unit.remove("patch");
        unit.set("bundles", pkg.path("dsh").path("profile").path("bundles").deepCopy());
        ObjectNode deps = unit.putObject("dependencies");
        for (var i = pkg.path("dependencies").fields(); i.hasNext();) {
            var e = i.next(); String spec = e.getValue().asText();
            Matcher git = Pattern.compile("github:([^/]+)/([^#]+)#([^&]+)(?:&path:(.+))?").matcher(spec);
            if (git.matches()) deps.put("github:" + git.group(1) + "/" + git.group(2) + (git.group(4) == null ? "" : "#path:/" + git.group(4)), git.group(3));
            else {
                // Resolve ranges only from a package manifest inside the profile; never follow pnpm links outside it.
                if (!SEMVER.matcher(spec).matches() && !spec.contains(":")) {
                    Path installed = dir.resolve("node_modules").resolve(packageName(e.getKey())).resolve("package.json");
                    if (Files.isRegularFile(installed)) {
                        Path real = installed.toRealPath();
                        if (!real.startsWith(dir.toRealPath()) || Files.size(real) > SafeArchive.MAX_METADATA) throw new IOException("已安装依赖位于 profile 外，无法安全锁定版本");
                        String resolved = JSON.readTree(Files.readAllBytes(real)).path("version").asText();
                        if (SEMVER.matcher(resolved).matches()) spec = resolved;
                    }
                    if (!SEMVER.matcher(spec).matches()) throw new IOException("依赖尚未锁定精确版本: " + e.getKey() + "；请先安装依赖");
                }
                deps.put(e.getKey(), spec);
            }
        }
        validateUnit(unit); return unit;
    }

    /** Do not open known credential/configuration files to try to redact them. Exclude by path. */
    private static boolean excluded(String path, boolean exporting, boolean snapshot) {
        String p = path.toLowerCase(Locale.ROOT);
        Set<String> excluded = Set.of(".git", ".ssh", ".aws", ".azure", ".gnupg", ".agents", ".credentials.yaml", ".credentials.yml",
                "credentials", "credentials.json", "auth.json", "auth.yaml", "hosts.yml", ".npmrc", ".netrc", ".pypirc", "sessions", "dsh-session-archive",
                "tokens.json", "token.json", "oauth.json", "oauth_credentials.json", "secrets", "secrets.json", "secrets.yaml", "secrets.yml", ".envrc",
                "attachments", "storages", "dsh-usage", "task-board", ".anonymous-user-id", ".cache", "cache", "logs", ".dsh-module-fallback",
                ".skill-staging", ".preset-staging", ".pack-offline-import", ".dshpkcfg", ".dsh-pack", "settings.yaml", "settings.yml", "settings.json", ORIGIN);
        for (String part : p.split("/")) {
            if (excluded.contains(part) || part.equals(".env") || part.startsWith(".env.") || part.endsWith(".pem") || part.endsWith(".key") || part.endsWith(".p12") || part.endsWith(".pfx") || part.endsWith(".log") || part.startsWith("id_rsa") || part.startsWith("id_ed25519")) return true;
            if (!snapshot && part.equals("node_modules")) return true;
            if (exporting && (part.equals("cordis.patch.yml") || part.equals("cordis.patch.yaml"))) return true;
        }
        return p.startsWith("skills/.system/") || p.equals("skills/.system");
    }
    private static void profileName(String name) throws IOException {
        SafeArchive.safe(name, false);
        if (name.contains("/") || name.equals("node_modules") || name.length() > 100) throw new IOException("非法 profile 名称");
    }
    private static String required(JsonNode node, String name) throws IOException {
        String result = optional(node, name); if (result == null || result.isBlank()) throw new IOException("缺少字符串字段 " + name); return result;
    }
    private static String optional(JsonNode node, String name) throws IOException {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IOException(name + " 必须为字符串");
        return value.asText();
    }
    private static ObjectNode object(JsonNode node, String name) throws IOException {
        if (node == null || !node.isObject()) throw new IOException(name + " 必须为对象"); return (ObjectNode) node;
    }
    private static JsonNode readJson(SafeArchive zip, String name) throws Exception { return object(JSON.readTree(zip.bytes(name, SafeArchive.MAX_METADATA)), name); }
    private static JsonNode readYaml(byte[] bytes) throws IOException {
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(30); options.setNestingDepthLimit(50); options.setCodePointLimit((int) SafeArchive.MAX_METADATA);
        try { Object value = new Yaml(new SafeConstructor(options)).load(new String(bytes, StandardCharsets.UTF_8)); return JSON.valueToTree(value); }
        catch (RuntimeException error) { throw new IOException("无法安全解析 YAML", error); }
    }
    private static void writeJson(Path path, JsonNode value) throws IOException { Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardCharsets.UTF_8); }
    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(content); zip.closeEntry(); }
}
