package com.hdsl.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Version-pinned runtime installs and help-driven CLI adaptation. */
public class RuntimeService {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PACKAGE = "@deepseek-ai/dsh";
    static final int MAX_REGISTRY_BYTES = 32 * 1024 * 1024;
    private static final Duration COHORT_CACHE_TTL = Duration.ofDays(30);
    private static final long PROGRESS_INTERVAL_NANOS = Duration.ofSeconds(2).toNanos();
    private final Path root;
    private final String registry;
    private final String proxy;
    private final HttpClient http;
    private final Map<String, Capabilities> capabilities = new ConcurrentHashMap<>();
    private final Map<String, String> fingerprints = new ConcurrentHashMap<>();

    public RuntimeService(Path root, String registry, String proxy) {
        this.root = root.toAbsolutePath().normalize();
        this.registry = validateHttpUrl(registry == null || registry.isBlank() ? "https://registry.npmjs.org" : registry).replaceAll("/+$", "");
        this.proxy = proxy == null || proxy.isBlank() ? "" : validateHttpUrl(proxy.trim());
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL);
        if (!this.proxy.isBlank()) {
            URI p = URI.create(this.proxy);
            builder.proxy(ProxySelector.of(new InetSocketAddress(p.getHost(), p.getPort() < 0 ? 80 : p.getPort())));
        }
        this.http = builder.build();
    }

    private static String validateHttpUrl(String value) {
        URI uri = URI.create(value);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("需要不含用户名或密码的 HTTP(S) 地址。");
        return value;
    }

    public List<String> availableVersions() throws IOException {
        JsonNode metadata = metadata();
        List<String> versions = new ArrayList<>();
        metadata.path("versions").fieldNames().forEachRemaining(v -> { if (SemVer.isVersion(v)) versions.add(v); });
        versions.sort(SemVer.DESCENDING);
        if (versions.isEmpty()) throw new IOException("npm 源没有返回可用 Harness 版本。");
        return List.copyOf(versions);
    }

    private JsonNode metadata() throws IOException {
        Duration timeout = versionQueryTimeout();
        HttpRequest request = HttpRequest.newBuilder(URI.create(registry + "/@deepseek-ai%2fdsh"))
                .timeout(timeout).header("Accept", "application/json").GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, ignored -> new LimitedRegistryBody());
        try {
            // get() bounds the full body as well as the response headers.
            HttpResponse<byte[]> response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) throw new IOException("版本查询失败，npm HTTP " + response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            if (result == null || !result.isObject()) throw new IOException("npm 返回的版本清单格式无效。");
            return result;
        } catch (TimeoutException e) {
            pending.cancel(true); throw new IOException("版本查询超时，请检查下载源或代理后重试。", e);
        } catch (InterruptedException e) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw new IOException("版本查询已取消。", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) throw new IOException("版本查询超时，请检查下载源或代理后重试。", e.getCause());
            if (e.getCause() instanceof IOException failure) throw failure;
            throw new IOException("版本查询失败，请检查下载源或代理。", e.getCause());
        }
    }

    Duration versionQueryTimeout() { return Duration.ofSeconds(60); }

    /** Stops oversized bodies before the standard byte-array subscriber buffers them. */
    static final class LimitedRegistryBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long received;
        private boolean finished;

        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription; delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (finished) return;
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > MAX_REGISTRY_BYTES) {
                subscription.cancel(); onError(new IOException("npm 返回的版本清单过大。"));
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) {
            if (!finished) { finished = true; delegate.onError(error); }
        }
        @Override public void onComplete() {
            if (!finished) { finished = true; delegate.onComplete(); }
        }
    }

    JsonNode registryJson(String suffix) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(registry + suffix))
                .timeout(Duration.ofSeconds(60)).header("Accept", "application/json").GET().build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() != 200) throw new IOException("版本查询失败，npm HTTP " + response.statusCode());
                byte[] bytes = stream.readNBytes(32 * 1024 * 1024 + 1);
                if (bytes.length > 32 * 1024 * 1024) throw new IOException("npm 返回的版本清单过大。");
                return JSON.readTree(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("版本查询已取消。", e);
        }
    }

    public List<String> installedVersions() throws IOException {
        Path folder = root.resolve("runtimes");
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(SemVer::isVersion)
                    .filter(v -> { try { executable(v); return true; } catch (IOException e) { return false; } })
                    .sorted(SemVer.DESCENDING).toList();
        }
    }

    public Path runtimeDirectory(String version) {
        if (!SemVer.isVersion(version)) throw new IllegalArgumentException("请选择完整的 Harness 版本号（例如 0.2.0-rc.2）。");
        return root.resolve("runtimes").resolve(version);
    }

    public Path nodeExecutable() {
        List<Path> candidates = new ArrayList<>();
        for (Path tools : toolRoots()) candidates.addAll(List.of(tools.resolve("node/node.exe"), tools.resolve("node/bin/node"), tools.resolve("node/node")));
        findOnPath(isWindows() ? "node.exe" : "node").ifPresent(candidates::add);
        return candidates.stream().filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到 Node.js。请安装当前 LTS，或放入 tools/node。"));
    }

    private Path npmScript() throws IOException {
        Path nodeDir = nodeExecutable().getParent();
        List<Path> candidates = new ArrayList<>(List.of(nodeDir.resolve("node_modules/npm/bin/npm-cli.js"),
                nodeDir.resolve("../lib/node_modules/npm/bin/npm-cli.js").normalize(), root.resolve("tools/node/node_modules/npm/bin/npm-cli.js")));
        findOnPath(isWindows() ? "npm.cmd" : "npm").ifPresent(p -> {
            candidates.add(p.getParent().resolve("node_modules/npm/bin/npm-cli.js"));
            try { if (!isWindows()) candidates.add(p.toRealPath()); } catch (IOException ignored) { }
        });
        return candidates.stream().filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IOException("未找到 npm-cli.js；请使用包含 npm 的 Node.js 安装包。"));
    }

    Path pnpmScript() throws IOException {
        List<Path> candidates = new ArrayList<>();
        for (Path tools : toolRoots()) candidates.addAll(List.of(tools.resolve("pnpm/bin/pnpm.cjs"), tools.resolve("pnpm/node_modules/pnpm/bin/pnpm.cjs"),
                tools.resolve("node/node_modules/pnpm/bin/pnpm.cjs")));
        candidates.add(nodeExecutable().getParent().resolve("node_modules/pnpm/bin/pnpm.cjs"));
        findOnPath(isWindows() ? "pnpm.cmd" : "pnpm").ifPresent(p -> {
            candidates.add(p.getParent().resolve("node_modules/pnpm/bin/pnpm.cjs"));
            try { if (!isWindows()) candidates.add(p.toRealPath()); } catch (IOException ignored) { }
        });
        return candidates.stream().filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IOException("缺少 pnpm。请安装 pnpm，或放入 tools/pnpm；profile 恢复需要 pnpm 保留原有锁文件语义。"));
    }

    private List<Path> toolRoots() {
        List<Path> paths = new ArrayList<>(List.of(root.resolve("tools")));
        String packaged = System.getProperty("jpackage.app-path", "");
        if (!packaged.isBlank()) {
            try {
                Path parent = Path.of(packaged).toAbsolutePath().normalize().getParent();
                if (parent != null && !paths.contains(parent.resolve("tools"))) paths.add(parent.resolve("tools"));
            } catch (InvalidPathException ignored) { }
        }
        return paths;
    }

    public synchronized void install(String version, Consumer<String> log) throws IOException {
        Path target = runtimeDirectory(version);
        if (installedVersions().contains(version)) { emit(log, "已安装 Harness " + version + "，保留现有运行时。"); return; }
        Path pnpm = pnpmScript();
        Files.createDirectories(target.getParent());
        Path staging = target.getParent().resolve(".install-" + version + "-" + UUID.randomUUID());
        Files.createDirectories(staging);
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("name", "hdsl-runtime"); manifest.put("private", true);
        manifest.putObject("dependencies").put(PACKAGE, version);
        ObjectNode overrides = releaseCohort(version, log);
        if (!overrides.isEmpty()) manifest.putObject("pnpm").set("overrides", overrides);
        JSON.writerWithDefaultPrettyPrinter().writeValue(staging.resolve("package.json").toFile(), manifest);
        Files.writeString(staging.resolve("pnpm-workspace.yaml"), "autoInstallPeers: true\nnodeLinker: hoisted\nresolutionMode: time-based\nstrictPeerDependencies: false\ndangerouslyAllowAllBuilds: true\n", StandardCharsets.UTF_8);
        emit(log, "使用 pnpm 安装官方 npm 包 " + PACKAGE + "@" + version + "，完整解析 peer 依赖。安装脚本正常执行，已有版本不受影响。");
        try {
            emit(log, "[安装进度] 下载并安装依赖：pnpm 会复用之前已下载的缓存");
            List<String> command = List.of(nodeExecutable().toString(), pnpm.toString(), "install", "--prod", "--no-frozen-lockfile",
                    "--registry=" + registry, "--store-dir=" + root.resolve("cache/pnpm-store"), "--reporter=append-only");
            run(command, staging, probeHome(staging), Duration.ofMinutes(20), log);
            emit(log, "[安装进度] 依赖安装完成，正在检查运行时入口并保存");
            validateExecutable(staging, version);
            if (Files.exists(target)) throw new IOException("目标版本目录已有未完成的安装，保留原目录；请检查 " + target);
            try { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(staging, target); }
            refresh(version);
            emit(log, "Harness " + version + " 安装完成；依赖版本已记录在 pnpm-lock.yaml。");
            emit(log, "[安装进度] Harness " + version + " 安装完成");
        } catch (IOException | RuntimeException e) {
            emit(log, "安装未完成，诊断目录已保留：" + staging);
            throw e;
        }
    }

    /** Pin only packages explicitly declared as members of this same release, including cyclic peers. */
    ObjectNode releaseCohort(String version, Consumer<String> log) throws IOException {
        runtimeDirectory(version); // Validate before using the version as a cache filename.
        ObjectNode cached = cachedCohort(version, log);
        if (cached != null) {
            emit(log, "[安装进度] 发行依赖已核对：复用缓存 " + cached.size() + " 项，版本与下载源校验通过");
            return cached;
        }
        emit(log, "[安装进度] 核对发行依赖：正在读取 " + version + " 的公开清单");
        JsonNode entry = registryJson("/@deepseek-ai%2fdsh/" + version);
        if (!PACKAGE.equals(entry.path("name").asText()) || !version.equals(entry.path("version").asText()))
            throw new IOException("Harness 发行清单的包名或版本不匹配。");
        Set<String> visited = new LinkedHashSet<>();
        Set<String> pending = cohortDependencies(entry, version);
        ObjectNode overrides = JSON.createObjectNode();
        cohortProgress(log, 0, pending.size());
        long lastProgress = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CompletionService<CohortMetadata> completed = new ExecutorCompletionService<>(pool);
        try {
            while (!pending.isEmpty()) {
                int outstanding = 0;
                for (String name : pending) if (visited.add(name)) {
                    if (visited.size() > 2048) throw new IOException("发行批次依赖数量异常，已停止安装。");
                    completed.submit(() -> new CohortMetadata(name, registryJson("/" + name.replace("/", "%2f") + "/" + version)));
                    outstanding++;
                }
                pending = new LinkedHashSet<>();
                while (outstanding > 0) {
                    try {
                        Future<CohortMetadata> future = completed.poll(500, TimeUnit.MILLISECONDS);
                        if (future != null) {
                            CohortMetadata item = future.get();
                            outstanding--;
                            JsonNode pkg = item.manifest();
                            if (!item.name().equals(pkg.path("name").asText()) || !version.equals(pkg.path("version").asText()))
                                throw new IOException("发行批次元数据包名或版本不匹配：" + item.name());
                            overrides.put(item.name(), version);
                            for (String dependency : cohortDependencies(pkg, version)) if (!visited.contains(dependency)) pending.add(dependency);
                        }
                        long now = System.nanoTime();
                        if (outstanding == 0 || now - lastProgress >= PROGRESS_INTERVAL_NANOS) {
                            cohortProgress(log, overrides.size(), outstanding + pending.size());
                            lastProgress = now;
                        }
                    } catch (ExecutionException e) {
                        throw new IOException("无法取得发行批次依赖元数据。已完成的公开清单不会被当作完整缓存使用。", e.getCause());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new IOException("依赖核对已取消。", e);
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        saveCohort(version, overrides, log);
        emit(log, "已固定 " + overrides.size() + " 个同批次组件；其他依赖继续遵循上游声明。");
        return overrides;
    }

    private static void cohortProgress(Consumer<String> log, int checked, int pending) {
        emit(log, "[安装进度] 核对发行依赖：已核对 " + checked + " 项，当前待核对 " + pending + " 项");
    }

    private Path cohortCacheFile(String version) {
        return root.resolve("cache/registry-metadata").resolve(sha256(registry)).resolve("cohort-" + version + ".json");
    }

    private ObjectNode cachedCohort(String version, Consumer<String> log) {
        Path file = cohortCacheFile(version);
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonNode saved = readManifest(file), values = saved.path("overrides");
            long now = System.currentTimeMillis(), checkedAt = saved.path("checkedAtEpochMillis").asLong(0);
            if (saved.path("schemaVersion").asInt() != 1 || !saved.path("complete").asBoolean()
                    || !PACKAGE.equals(saved.path("package").asText()) || !version.equals(saved.path("version").asText())
                    || !registry.equals(saved.path("registry").asText()) || !values.isObject() || values.size() > 2048
                    || saved.path("count").asInt(-1) != values.size() || checkedAt <= 0
                    || checkedAt > now + Duration.ofMinutes(5).toMillis() || now - checkedAt > COHORT_CACHE_TTL.toMillis())
                throw new IOException("过期或格式不匹配");
            Iterator<Map.Entry<String, JsonNode>> entries = values.fields();
            while (entries.hasNext()) {
                var item = entries.next();
                if (!item.getKey().startsWith("@deepseek-ai/dsh-") || !PluginService.validPackageName(item.getKey())
                        || !item.getValue().isTextual() || !version.equals(item.getValue().asText())) throw new IOException("版本清单不匹配");
            }
            if (!cohortDigest(version, (ObjectNode) values).equals(saved.path("sha256").asText())) throw new IOException("校验不匹配");
            return ((ObjectNode) values).deepCopy();
        } catch (IOException | RuntimeException e) {
            emit(log, "[安装进度] 发行依赖缓存已失效，正在重新核对公开元数据");
            return null;
        }
    }

    private void saveCohort(String version, ObjectNode overrides, Consumer<String> log) {
        Path file = cohortCacheFile(version), temporary = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            ObjectNode saved = JSON.createObjectNode();
            saved.put("schemaVersion", 1); saved.put("complete", true); saved.put("package", PACKAGE);
            saved.put("version", version); saved.put("registry", registry); saved.put("checkedAtEpochMillis", System.currentTimeMillis());
            saved.put("count", overrides.size()); saved.set("overrides", overrides.deepCopy()); saved.put("sha256", cohortDigest(version, overrides));
            Files.createDirectories(file.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), saved);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            emit(log, "公开发行依赖已核对，但缓存保存失败；本次安装继续，下次会重新核对。");
        } finally {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private String cohortDigest(String version, ObjectNode overrides) {
        SortedMap<String, String> ordered = new TreeMap<>();
        overrides.fields().forEachRemaining(e -> ordered.put(e.getKey(), e.getValue().asText()));
        StringBuilder canonical = new StringBuilder(registry).append('\n').append(version);
        ordered.forEach((name, value) -> canonical.append('\n').append(name).append('=').append(value));
        return sha256(canonical.toString());
    }

    private static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("Java SHA-256 unavailable", e); }
    }

    private record CohortMetadata(String name, JsonNode manifest) { }

    static Set<String> cohortDependencies(JsonNode pkg, String version) {
        Set<String> result = new LinkedHashSet<>();
        for (String kind : List.of("dependencies", "peerDependencies", "optionalDependencies"))
            pkg.path(kind).fields().forEachRemaining(e -> {
                String spec = e.getValue().asText();
                if (e.getKey().startsWith("@deepseek-ai/dsh-") && PluginService.validPackageName(e.getKey())
                        && Set.of(version, "^" + version, "~" + version).contains(spec)) result.add(e.getKey());
            });
        return result;
    }

    Path executable(String version) throws IOException { return validateExecutable(runtimeDirectory(version), version); }

    private Path validateExecutable(Path runtime, String version) throws IOException {
        Path packageDir = runtime.resolve("node_modules/@deepseek-ai/dsh");
        Path manifest = packageDir.resolve("package.json");
        if (!Files.isRegularFile(manifest)) throw new IOException("Harness " + version + " 尚未安装。");
        JsonNode data = readManifest(manifest);
        if (!PACKAGE.equals(data.path("name").asText()) || !version.equals(data.path("version").asText()))
            throw new IOException("运行时 package.json 的包名或版本与实例要求不符：" + version);
        JsonNode bin = data.path("bin");
        String relative = bin.isTextual() ? bin.asText() : bin.path("dsh").asText("");
        if (relative.isBlank()) throw new IOException("此版本未声明 dsh CLI 入口。");
        Path script = packageDir.resolve(relative).normalize();
        if (!script.startsWith(packageDir) || !Files.isRegularFile(script)
                || !script.toRealPath().startsWith(packageDir.toRealPath())) throw new IOException("dsh CLI 入口无效或超出包目录。");
        return script;
    }

    public void refresh(String version) { capabilities.remove(version); fingerprints.remove(version); }

    public synchronized Capabilities inspect(String version, Consumer<String> log) throws IOException {
        Path script = executable(version);
        Path override = root.resolve("config/compatibility.json");
        String stamp = Files.getLastModifiedTime(script) + ":" + Files.size(script) + ":" +
                (Files.exists(override) ? Files.getLastModifiedTime(override).toString() : "none");
        if (stamp.equals(fingerprints.get(version)) && capabilities.containsKey(version)) return capabilities.get(version);
        Path probe = root.resolve("cache/probes").resolve(version).resolve(UUID.randomUUID().toString());
        Files.createDirectories(probe.resolve("workspace"));
        Path home = probe.resolve("dsh-home");
        List<String> base = List.of(nodeExecutable().toString(), script.toString());
        String launcher = run(append(base, "--help"), probe.resolve("workspace"), home, Duration.ofSeconds(35), null).output();
        Capabilities entry = Capabilities.fromHelp(version, launcher, "", false);
        String app = ""; boolean verified = false;
        if (entry.profileSelection() || entry.webAlias()) {
            try {
                List<String> args = entry.profileSelection() ? append(base, "--profile", "web", "--help") : append(base, "web", "--help");
                app = run(args, probe.resolve("workspace"), home, Duration.ofSeconds(60), null).output();
                verified = app.toLowerCase(Locale.ROOT).contains("usage") || app.toLowerCase(Locale.ROOT).contains("options") || app.contains("--port");
            } catch (IOException e) { emit(log, "Web 帮助探测未完成：" + e.getMessage()); }
        }
        Capabilities result = applyOverrides(Capabilities.fromHelp(version, launcher, app, verified), override);
        capabilities.put(version, result); fingerprints.put(version, stamp);
        emit(log, result.summary());
        return result;
    }

    private Capabilities applyOverrides(Capabilities detected, Path file) throws IOException {
        if (!Files.isRegularFile(file)) return detected;
        JsonNode override = readManifest(file).path("versions").path(detected.version());
        if (!override.isObject()) return detected;
        Set<String> launcher = new LinkedHashSet<>(detected.launcherOptions()), app = new LinkedHashSet<>(detected.appOptions());
        addFlags(launcher, override.path("launcherOptions")); addFlags(app, override.path("appOptions"));
        List<String> warnings = new ArrayList<>(detected.warnings()); warnings.add("已应用本机 config/compatibility.json 的人工能力声明。");
        return new Capabilities(detected.version(), launcher, app, override.path("pluginManagement").asBoolean(detected.pluginManagement()),
                launcher.contains("--profile"), override.path("webAlias").asBoolean(detected.webAlias()), detected.appHelpVerified(), warnings);
    }

    private static void addFlags(Set<String> flags, JsonNode values) throws IOException {
        for (JsonNode item : values) {
            if (!item.isTextual() || !item.asText().matches("--[A-Za-z][A-Za-z0-9-]*")) throw new IOException("compatibility.json 包含无效参数名。");
            flags.add(item.asText());
        }
    }

    public List<String> launchCommand(String version, String profile, int port, Consumer<String> log) throws IOException {
        if (!"web".equals(profile)) throw new IOException("自定义 profile 需要传入实例目录以独立检测应用参数。");
        return assembleLaunch(List.of(nodeExecutable().toString(), executable(version).toString()), inspect(version, log), profile, port);
    }

    /** Probes an imported/custom profile using metadata and dependency links, never its configuration or credentials. */
    public List<String> launchCommand(String version, String profile, int port, Path dshHome, Path workspace, Consumer<String> log) throws IOException {
        validateProfile(profile);
        if ("web".equals(profile)) return launchCommand(version, profile, port, log);
        Capabilities launcher = inspect(version, log);
        Set<String> manual = new LinkedHashSet<>();
        Path overrides = root.resolve("config/compatibility.json");
        if (Files.isRegularFile(overrides)) addFlags(manual, readManifest(overrides).path("versions").path(version).path("profiles").path(profile).path("appOptions"));
        Capabilities custom;
        if (!manual.isEmpty()) {
            custom = new Capabilities(version, launcher.launcherOptions(), manual, launcher.pluginManagement(), launcher.profileSelection(), launcher.webAlias(), false,
                    List.of("已使用本机为 " + profile + " 指定的应用参数。"));
        } else {
            Path source = dshHome.resolve("profiles").resolve(profile);
            if (!Files.isRegularFile(source.resolve("package.json"))) throw new IOException("自定义 profile 尚未初始化：" + profile);
            Path probe = root.resolve("cache/probes").resolve(version).resolve(UUID.randomUUID().toString());
            Path home = probe.resolve("dsh-home"), target = home.resolve("profiles").resolve(profile);
            Files.createDirectories(target); Files.createDirectories(probe.resolve("workspace"));
            JsonNode original = readManifest(source.resolve("package.json"));
            ObjectNode metadata = JSON.createObjectNode(); metadata.put("name", "hdsl-capability-probe"); metadata.put("private", true);
            for (String key : List.of("dependencies", "optionalDependencies")) if (original.has(key)) metadata.set(key, original.get(key).deepCopy());
            metadata.putObject("dsh").putObject("profile").set("bundles", original.path("dsh").path("profile").path("bundles").deepCopy());
            JSON.writeValue(target.resolve("package.json").toFile(), metadata);
            if (Files.isDirectory(source.resolve("node_modules"))) {
                // Node's junction implementation also works on Windows without Developer Mode.
                run(List.of(nodeExecutable().toString(), "-e", "require('node:fs').symlinkSync(process.argv[1], process.argv[2], 'junction')",
                        source.resolve("node_modules").toAbsolutePath().toString(), target.resolve("node_modules").toString()),
                        probe.resolve("workspace"), home, Duration.ofSeconds(15), null);
            }
            String help;
            try { help = run(List.of(nodeExecutable().toString(), executable(version).toString(), "--profile", profile, "--help"),
                    probe.resolve("workspace"), home, Duration.ofSeconds(60), null).output(); }
            catch (IOException e) { throw new IOException("无法确认自定义 profile 的应用参数。可在 config/compatibility.json 的 versions." + version
                    + ".profiles." + profile + ".appOptions 声明已核实的参数。", e); }
            Capabilities observed = Capabilities.fromHelp(version, "", help, true);
            custom = new Capabilities(version, launcher.launcherOptions(), observed.appOptions(), launcher.pluginManagement(), launcher.profileSelection(), launcher.webAlias(), true,
                    List.of("应用帮助在临时目录检测；未复制用户 patch 或凭据。"));
        }
        emit(log, custom.summary());
        return assembleLaunch(List.of(nodeExecutable().toString(), executable(version).toString()), custom, profile, port);
    }

    public static List<String> assembleLaunch(List<String> base, Capabilities caps, String profile, int port) throws IOException {
        validateProfile(profile);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口应为 1–65535。");
        List<String> result = new ArrayList<>(base);
        if (caps.profileSelection()) { result.add("--profile"); result.add(profile); }
        else if (profile.equals("web") && caps.webAlias()) result.add("web");
        else throw new IOException("此运行时未提供可识别的 profile 启动参数；请查看 CLI 帮助或兼容性配置。");
        if (!caps.supports("--port")) throw new IOException("此版本尚未确认 --port 支持，无法保证实例端口隔离。请刷新检测或添加本机兼容性声明。");
        result.add("--port"); result.add(Integer.toString(port));
        if (caps.supports("--host")) { result.add("--host"); result.add("127.0.0.1"); }
        if (caps.supports("--no-open")) result.add("--no-open");
        return List.copyOf(result);
    }

    public void configureEnvironment(Map<String, String> env, Path dshHome, Path workspace) {
        Path home = dshHome.toAbsolutePath().normalize();
        env.put("DSH_HOME", home.toString());
        env.put("DSH_AGENTS_HOME", home.resolveSibling("agents-home").toString());
        env.put("HDSL_NPM_REGISTRY", registry); env.put("NPM_CONFIG_REGISTRY", registry);
        env.put("NPM_CONFIG_CACHE", root.resolve("cache/npm").toString());
        env.put("PNPM_HOME", root.resolve("tools/pnpm").toString());
        env.put("PNPM_STORE_DIR", root.resolve("cache/pnpm-store").toString());
        env.put("NPM_CONFIG_MANAGE_PACKAGE_MANAGER_VERSIONS", "false");
        env.put("NPM_CONFIG_PACKAGE_MANAGER_STRICT", "false");
        env.put("PNPM_CONFIG_AUTO_INSTALL_PEERS", "true");
        env.put("XDG_CACHE_HOME", root.resolve("cache").toString());
        env.put("DSH_TELEMETRY_MODE", "DISABLED");
        String pathKey = env.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        String existing = env.getOrDefault(pathKey, ""); env.remove(pathKey);
        env.put("PATH", nodeExecutable().getParent() + File.pathSeparator + root.resolve("tools/shims") + File.pathSeparator
                + root.resolve("tools/pnpm") + File.pathSeparator + existing);
        if (!proxy.isBlank()) {
            env.put("HTTP_PROXY", proxy); env.put("HTTPS_PROXY", proxy); env.put("NPM_CONFIG_PROXY", proxy); env.put("NPM_CONFIG_HTTPS_PROXY", proxy);
            env.put("NO_PROXY", "localhost,127.0.0.1,::1");
        }
    }

    /** Restores the original manifest/lock; ignores dependency scripts until the user enables them in pnpm. */
    public void restoreProfile(Path dshHome, String profile, Path workspace, Consumer<String> log) throws IOException {
        restoreProfile(dshHome, profile, workspace, false, log);
    }

    /** A staged pack import may explicitly refresh an outdated importer section after retaining the original lock. */
    public void restoreProfile(Path dshHome, String profile, Path workspace, boolean allowLockRefresh, Consumer<String> log) throws IOException {
        validateProfile(profile);
        Path directory = dshHome.resolve("profiles").resolve(profile);
        if (!Files.isRegularFile(directory.resolve("package.json"))) throw new IOException("整合包缺少 profile/package.json。");
        List<String> command = append(List.of(nodeExecutable().toString(), pnpmScript().toString()), "install", "--ignore-scripts", "--config.ignore-pnpmfile=true", "--registry=" + registry,
                "--store-dir=" + root.resolve("cache/pnpm-store"));
        Path lock = directory.resolve("pnpm-lock.yaml");
        boolean frozen = Files.exists(lock);
        if (frozen) command = append(command, "--frozen-lockfile");
        emit(log, "使用 pnpm 恢复 profile 依赖（已有锁文件保持冻结，安装脚本默认关闭）。");
        try { run(command, directory, dshHome, Duration.ofMinutes(20), log); }
        catch (IOException e) {
            if (!allowLockRefresh || !frozen || !e.getMessage().contains("ERR_PNPM_OUTDATED_LOCKFILE")) throw e;
            Path backup = directory.resolve(".hdsl-original-pnpm-lock.yaml");
            if (!Files.exists(backup)) Files.copy(lock, backup);
            emit(log, "整合包清单重建后与原锁文件不一致；已保留 .hdsl-original-pnpm-lock.yaml，正在为导入实例更新锁文件。");
            List<String> rebuild = new ArrayList<>(command); rebuild.remove("--frozen-lockfile"); rebuild.add("--no-frozen-lockfile");
            run(rebuild, directory, dshHome, Duration.ofMinutes(20), log);
        }
        emit(log, "依赖恢复结束；原生扩展或需要构建的插件可能仍需用户单独允许运行相应脚本。");
    }

    void ensurePnpmShim() throws IOException {
        Path script = pnpmScript();
        Path shims = root.resolve("tools/shims"); Files.createDirectories(shims);
        if (isWindows()) {
            // Only launcher-owned absolute file paths occur here; user package arguments are never interpolated.
            String node = nodeExecutable().toString(), cli = script.toString();
            if (node.contains("%") || cli.contains("%") || node.contains("\n") || cli.contains("\n"))
                throw new IOException("Windows pnpm 包装器路径不能包含 % 或换行。");
            Files.writeString(shims.resolve("pnpm.cmd"), "@echo off\r\n\"" + node + "\" \"" + cli + "\" %*\r\n", StandardCharsets.UTF_8);
        } else {
            Path shim = shims.resolve("pnpm");
            String text = "#!/bin/sh\nexec '" + nodeExecutable().toString().replace("'", "'\\''") + "' '" + script.toString().replace("'", "'\\''") + "' \"$@\"\n";
            Files.writeString(shim, text); shim.toFile().setExecutable(true, true);
        }
    }

    /** Preserve native before/after reconciliation while bypassing old Windows shell forwarding. */
    Path pluginBridge() throws IOException {
        Path bridge = root.resolve("tools/shims/hdsl-plugin-runner.mjs");
        Files.createDirectories(bridge.getParent());
        Files.writeString(bridge, """
                import { createRequire, syncBuiltinESMExports } from 'node:module';
                import { pathToFileURL } from 'node:url';
                import { basename } from 'node:path';
                const [pnpm, entry, ...args] = process.argv.slice(2);
                if (!pnpm || !entry) throw new Error('HDSL plugin bridge requires explicit CLI paths');
                const child = createRequire(import.meta.url)('node:child_process');
                for (const name of ['spawnSync', 'spawn']) {
                  const original = child[name];
                  child[name] = function(command, argv, options) {
                    const leaf = typeof command === 'string' ? basename(command).toLowerCase() : '';
                    if ((leaf === 'pnpm' || leaf === 'pnpm.cmd' || leaf === 'pnpm.exe') && Array.isArray(argv))
                      return original.call(child, process.execPath, [pnpm, ...argv], { ...options, shell: false });
                    if (options?.shell || /^(cmd(?:\\.exe)?|powershell(?:\\.exe)?|pwsh(?:\\.exe)?|sh|bash)$/.test(leaf))
                      throw new Error('HDSL cannot safely route this new shell-based plugin command; an adapter update is required');
                    return original.apply(child, arguments);
                  };
                }
                child.exec = child.execSync = () => { throw new Error('HDSL plugin bridge rejects unstructured shell commands'); };
                syncBuiltinESMExports();
                process.argv = [process.execPath, entry, ...args];
                const cli = await import(pathToFileURL(entry).href);
                if (typeof cli.runCli === 'function') await cli.runCli({ packageManager: { command: process.execPath, args: [pnpm] } });
                """, StandardCharsets.UTF_8);
        return bridge;
    }

    ProcessResult run(List<String> command, Path directory, Path home, Duration timeout, Consumer<String> log) throws IOException {
        Files.createDirectories(directory); Files.createDirectories(home); Files.createDirectories(home.resolveSibling("agents-home"));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        configureEnvironment(builder.environment(), home, directory);
        if (command.size() > 1 && command.get(1).endsWith("npm-cli.js")) {
            builder.environment().remove("NPM_CONFIG_MANAGE_PACKAGE_MANAGER_VERSIONS");
            builder.environment().remove("NPM_CONFIG_PACKAGE_MANAGER_STRICT");
        }
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader stream = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = stream.readLine()) != null) {
                    synchronized (output) { if (output.length() < 2 * 1024 * 1024) output.append(line).append('\n'); }
                    emit(log, line);
                }
            } catch (IOException ignored) { }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminateOwned(process); throw new IOException("命令超过 " + timeout.toSeconds() + " 秒，已停止本次创建的进程。");
            }
            reader.join(5000);
            String result; synchronized (output) { result = output.toString(); }
            if (process.exitValue() != 0) throw new IOException("命令退出码 " + process.exitValue() + "：\n" + tail(result, 5000));
            return new ProcessResult(process.exitValue(), result);
        } catch (InterruptedException e) {
            terminateOwned(process); Thread.currentThread().interrupt(); throw new IOException("操作已取消。", e);
        }
    }

    private static void terminateOwned(Process process) {
        List<ProcessHandle> children = process.descendants().toList();
        for (ProcessHandle child : children.reversed()) child.destroyForcibly();
        process.destroyForcibly();
    }

    static JsonNode readManifest(Path path) throws IOException {
        if (Files.size(path) > 8 * 1024 * 1024) throw new IOException("JSON 文件过大：" + path.getFileName());
        return JSON.readTree(path.toFile());
    }
    static void validateProfile(String profile) {
        if (profile == null || profile.isBlank() || profile.length() > 128 || !profile.matches("[\\p{L}\\p{N} ._-]+")
                || profile.equals(".") || profile.equals("..") || profile.endsWith(".") || profile.endsWith(" ")
                || profile.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
            throw new IllegalArgumentException("Profile 名称可使用文字、数字、空格、点、下划线和连字符；不能包含路径、控制字符或 Windows 保留名称。");
    }
    static List<String> append(List<String> base, String... arguments) { List<String> result = new ArrayList<>(base); result.addAll(List.of(arguments)); return result; }
    private static Path probeHome(Path staging) { return staging.resolve(".hdsl-install-home"); }
    static void emit(Consumer<String> log, String text) { if (log != null) log.accept(text); }
    private static String tail(String text, int max) { return text.length() <= max ? text : text.substring(text.length() - max); }
    static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
    private static Optional<Path> findOnPath(String name) {
        String path = System.getenv("PATH"); if (path == null) path = System.getenv("Path");
        if (path != null) for (String part : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (part.isBlank()) continue;
            try { Path candidate = Path.of(part.replace("\"", "")).resolve(name); if (Files.isRegularFile(candidate)) return Optional.of(candidate.toAbsolutePath()); }
            catch (InvalidPathException ignored) { }
        }
        return Optional.empty();
    }
    record ProcessResult(int exitCode, String output) { }
}
