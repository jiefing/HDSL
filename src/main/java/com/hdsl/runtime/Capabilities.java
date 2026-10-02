package com.hdsl.runtime;

import java.util.*;
import java.util.regex.Pattern;

/** Observed command-line capabilities. Unknown flags are retained, never guessed. */
public record Capabilities(String version, Set<String> launcherOptions, Set<String> appOptions,
                           boolean pluginManagement, boolean profileSelection, boolean webAlias,
                           boolean appHelpVerified, List<String> warnings) {
    private static final Pattern OPTION = Pattern.compile("(?m)^\\s*(?:-[A-Za-z?],?\\s+)?(--[a-zA-Z][a-zA-Z0-9-]*)(?=[\\s=,<\\[]|$)");

    public Capabilities {
        launcherOptions = Collections.unmodifiableSet(new LinkedHashSet<>(launcherOptions));
        appOptions = Collections.unmodifiableSet(new LinkedHashSet<>(appOptions));
        warnings = List.copyOf(warnings);
    }

    public static Capabilities fromHelp(String version, String launcherHelp, String appHelp, boolean appVerified) {
        Set<String> launcher = options(launcherHelp);
        Set<String> app = appVerified ? options(appHelp) : Set.of();
        boolean plugin = Pattern.compile("(?m)(?:^\\s*plugin(?:\\s|\\[)|\\bdsh\\s+plugin\\s)").matcher(launcherHelp).find();
        boolean web = Pattern.compile("(?m)(?:^\\s*web(?:\\s|\\[)|\\bdsh\\s+web(?:\\s|$))").matcher(launcherHelp).find();
        List<String> warnings = new ArrayList<>();
        if (!launcher.contains("--profile") && !web) warnings.add("未识别 profile 或 web 启动入口，请检查此版本的 CLI 帮助。");
        if (!appVerified) warnings.add("Web 应用帮助尚未验证，不会猜测端口等参数。");
        return new Capabilities(version, launcher, app, plugin, launcher.contains("--profile"), web, appVerified, warnings);
    }

    private static Set<String> options(String help) {
        Set<String> result = new LinkedHashSet<>();
        var matcher = OPTION.matcher(help == null ? "" : help);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    public boolean supports(String option) { return launcherOptions.contains(option) || appOptions.contains(option); }

    public String summary() {
        return "Harness " + version + " · " + (profileSelection ? "profile 启动" : webAlias ? "web 启动" : "启动入口待确认")
                + " · " + (pluginManagement ? "原生插件管理" : "未检测到插件命令")
                + " · " + (supports("--port") ? "可设端口" : "端口参数待确认")
                + (warnings.isEmpty() ? "" : "\n" + String.join("\n", warnings));
    }
}
