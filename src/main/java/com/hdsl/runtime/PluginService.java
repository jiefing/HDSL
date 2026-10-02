package com.hdsl.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Leaves metadata and bundle reconciliation under the installed Harness's control. */
public class PluginService {
    private final RuntimeService runtimes;
    public PluginService(RuntimeService runtimes) { this.runtimes = Objects.requireNonNull(runtimes); }

    /** Reads package metadata only; never boots or initializes a user's profile. */
    public List<PluginEntry> list(String version, String profile, Path dshHome, Path workspace, Consumer<String> log) throws IOException {
        RuntimeService.validateProfile(profile);
        Path profileDir = dshHome.resolve("profiles").resolve(profile);
        Path manifest = profileDir.resolve("package.json");
        if (!Files.isRegularFile(manifest)) { RuntimeService.emit(log, "此 profile 尚未初始化；启动或安装插件后会由 Harness 创建。"); return List.of(); }
        JsonNode data = RuntimeService.readManifest(manifest);
        Map<String, String> packages = new LinkedHashMap<>();
        Set<String> bundles = new LinkedHashSet<>();
        for (String field : List.of("dependencies", "optionalDependencies")) {
            data.path(field).fields().forEachRemaining(e -> packages.put(e.getKey(), e.getValue().asText("")));
        }
        for (JsonNode bundle : data.path("dsh").path("profile").path("bundles")) {
            String id = bundle.isTextual() ? bundle.asText() : bundle.path("name").asText(bundle.path("package").asText(""));
            if (!id.isBlank()) { bundles.add(id); packages.putIfAbsent(id, "内置"); }
        }
        List<PluginEntry> result = new ArrayList<>();
        for (var entry : packages.entrySet()) {
            String id = entry.getKey(), actualVersion = entry.getValue();
            String detail = bundles.contains(id) ? "已列入 profile 组合包" : "profile 依赖";
            Path installed = null;
            if (validPackageName(id)) {
                // In-box packages resolve from the installed Harness before profile dependencies.
                Path runtimePackage = runtimes.runtimeDirectory(version).resolve("node_modules").resolve(id).resolve("package.json");
                Path profilePackage = profileDir.resolve("node_modules").resolve(id).resolve("package.json");
                boolean profileDependency = data.path("dependencies").has(id) || data.path("optionalDependencies").has(id);
                if (!profileDependency && Files.isRegularFile(runtimePackage)) installed = runtimePackage;
                else if (Files.isRegularFile(profilePackage)) installed = profilePackage;
                else if (Files.isRegularFile(runtimePackage)) installed = runtimePackage;
            }
            if (installed != null) {
                try {
                    JsonNode pkg = RuntimeService.readManifest(installed);
                    actualVersion = pkg.path("version").asText(actualVersion);
                    String description = pkg.path("description").asText("");
                    if (!description.isBlank()) detail += " · " + description;
                    if (pkg.path("dsh").has("bundle")) detail += " · 声明 dsh.bundle";
                } catch (IOException e) { detail += " · 包元数据暂时不可读"; }
            } else detail += " · 待恢复依赖";
            result.add(new PluginEntry(id, actualVersion, id.startsWith("@deepseek-ai/"), detail));
        }
        result.sort(Comparator.comparing(PluginEntry::official).thenComparing(PluginEntry::id));
        return List.copyOf(result);
    }

    public synchronized void mutate(String operation, String packageSpec, String version, String profile,
                                    Path dshHome, Path workspace, Consumer<String> log) throws IOException {
        RuntimeService.validateProfile(profile);
        String verb = switch (operation) { case "install", "add" -> "add"; case "update" -> "update"; case "remove", "uninstall" -> "remove";
            default -> throw new IllegalArgumentException("仅支持安装、更新和移除插件。"); };
        String spec = packageSpec == null ? "" : packageSpec.trim();
        if (!verb.equals("update") && spec.isBlank()) throw new IllegalArgumentException("请输入插件包名或地址。");
        if (!spec.isEmpty() && (spec.startsWith("-") || spec.chars().anyMatch(c -> c < 32 || c == 127)))
            throw new IllegalArgumentException("插件坐标不能是命令参数或包含控制字符。");
        if (verb.equals("remove") && !validPackageName(spec)) throw new IllegalArgumentException("移除操作需要完整 npm 包名。");
        if (verb.equals("remove")) {
            Path profileManifest = dshHome.resolve("profiles").resolve(profile).resolve("package.json");
            if (Files.isRegularFile(profileManifest)) {
                JsonNode current = RuntimeService.readManifest(profileManifest);
                if (!current.path("dependencies").has(spec) && !current.path("optionalDependencies").has(spec))
                    throw new IOException("该条目不是此 profile 的独立依赖，不能通过移除插件删除 Harness 内置组合包。");
            }
        }
        if (!runtimes.inspect(version, log).pluginManagement()) throw new IOException("此 Harness 版本未声明原生 plugin 管理命令。");
        Path pnpm = runtimes.pnpmScript();
        runtimes.ensurePnpmShim();
        List<String> command = new ArrayList<>(List.of(runtimes.nodeExecutable().toString(), runtimes.pluginBridge().toString(), pnpm.toString(),
                runtimes.executable(version).toString(), "plugin", "--profile", profile, verb));
        if (!spec.isBlank()) command.add(anchorLocalSpec(spec, workspace));
        command.add("--config.ignore-scripts=true"); command.add("--config.ignore-pnpmfile=true");
        RuntimeService.emit(log, "正在通过原生 Harness plugin " + verb + " 管理插件；pnpm 使用直接参数，安装脚本默认关闭。");
        runtimes.run(command, workspace, dshHome, Duration.ofMinutes(20), log);
        RuntimeService.emit(log, "插件操作完成；重启实例后载入新的组合包。未知元数据由 Harness/pnpm 保留和处理。");
    }

    static String anchorLocalSpec(String spec, Path workspace) {
        String prefix = spec.startsWith("file:") ? "file:" : spec.startsWith("link:") ? "link:" : "";
        String value = spec.substring(prefix.length());
        if (value.equals(".") || value.equals("..") || value.startsWith("./") || value.startsWith("../") || value.startsWith(".\\") || value.startsWith("..\\"))
            return prefix + workspace.toAbsolutePath().resolve(value).normalize();
        if (!prefix.isEmpty() && !Path.of(value).isAbsolute()) return prefix + workspace.toAbsolutePath().resolve(value).normalize();
        return spec;
    }

    static boolean validPackageName(String name) {
        return name != null && name.length() <= 214 && name.matches("(?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9][A-Za-z0-9._-]*");
    }
}
