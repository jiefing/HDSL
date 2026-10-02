package com.hdsl.ui;

import java.util.List;

/** Immutable, presentation-only snapshot. No credentials are included in this model. */
public record UiState(
        String currentInstanceId,
        Settings settings,
        List<InstanceItem> instances,
        List<RuntimeItem> runtimes,
        List<PluginItem> plugins,
        List<String> availableRuntimeVersions,
        String packPreview,
        String logs,
        String status,
        List<AccountItem> accounts,
        DesktopCatalog desktop,
        PluginCatalog pluginCatalog,
        PluginDetails pluginDetails) {

    public UiState(String currentInstanceId, Settings settings, List<InstanceItem> instances, List<RuntimeItem> runtimes,
                   List<PluginItem> plugins, List<String> availableRuntimeVersions, String packPreview, String logs, String status) {
        this(currentInstanceId, settings, instances, runtimes, plugins, availableRuntimeVersions, packPreview, logs, status,
                List.of(), null, null, null);
    }

    public UiState {
        currentInstanceId = clean(currentInstanceId);
        settings = settings == null ? new Settings("", "https://registry.npmjs.org", "") : settings;
        instances = instances == null ? List.of() : List.copyOf(instances);
        runtimes = runtimes == null ? List.of() : List.copyOf(runtimes);
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        availableRuntimeVersions = availableRuntimeVersions == null ? List.of() : List.copyOf(availableRuntimeVersions);
        packPreview = clean(packPreview);
        logs = clean(logs);
        status = clean(status);
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        desktop = desktop == null ? new DesktopCatalog("deepseek-ai/deepseek-harness", List.of(), "尚未查询发行版本。") : desktop;
        pluginCatalog = pluginCatalog == null ? new PluginCatalog("", 1, 0, false, false, "", List.of()) : pluginCatalog;
        pluginDetails = pluginDetails == null ? new PluginDetails("", "UNRESOLVED", "", false, List.of()) : pluginDetails;
    }

    public static UiState empty() {
        return new UiState("", null, null, null, null, null, "", "", "正在读取工作空间…");
    }

    public InstanceItem currentInstance() {
        return instances.stream().filter(it -> it.id().equals(currentInstanceId)).findFirst()
                .orElse(instances.isEmpty() ? null : instances.getFirst());
    }

    public UiState withAvailableRuntimeVersions(List<String> versions) {
        return new UiState(currentInstanceId, settings, instances, runtimes, plugins, versions, packPreview, logs, status,
                accounts, desktop, pluginCatalog, pluginDetails);
    }

    public record Settings(String proxy, String registry, String background) {
        public Settings {
            proxy = clean(proxy);
            registry = clean(registry);
            background = clean(background);
        }
    }

    public record InstanceItem(String id, String name, String version, String profile, int port, String status, String path,
                               List<String> profiles, String accountId) {
        public InstanceItem(String id, String name, String version, String profile, int port, String status, String path) {
            this(id, name, version, profile, port, status, path, profile == null || profile.isBlank() ? List.of() : List.of(profile), "");
        }
        public InstanceItem(String id, String name, String version, String profile, int port, String status, String path, List<String> profiles) {
            this(id, name, version, profile, port, status, path, profiles, "");
        }

        public InstanceItem {
            id = clean(id);
            name = clean(name);
            version = clean(version);
            profile = clean(profile);
            status = clean(status);
            path = clean(path);
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
            accountId = clean(accountId);
        }
    }

    public record RuntimeItem(String version, boolean installed, String compatibility) {
        public RuntimeItem {
            version = clean(version);
            compatibility = clean(compatibility);
        }
    }

    public record PluginItem(String name, String version, String description, String source) {
        public PluginItem {
            name = clean(name);
            version = clean(version);
            description = clean(description);
            source = clean(source);
        }
    }

    /** Public account metadata only. Keys must never be placed in this record or its status. */
    public record AccountItem(String id, String name, String provider, String baseUrl, String model, boolean hasKey, String status) {
        public AccountItem {
            id = clean(id); name = clean(name); provider = clean(provider); baseUrl = clean(baseUrl); model = clean(model); status = clean(status);
        }
    }

    public record DesktopCatalog(String source, List<DesktopItem> items, String message) {
        public DesktopCatalog { source = clean(source); items = items == null ? List.of() : List.copyOf(items); message = clean(message); }
    }
    public record DesktopItem(String id, String source, String version, String name, long size, String url, String sha256,
                              String path, boolean prerelease) {
        public DesktopItem {
            id = clean(id); source = clean(source); version = clean(version); name = clean(name); url = clean(url);
            sha256 = clean(sha256); path = clean(path);
        }
    }
    public record PluginCatalog(String query, int page, long total, boolean hasNext, boolean fromCache, String warning,
                                List<PluginCatalogItem> items) {
        public PluginCatalog {
            query = clean(query); page = Math.max(1, page); warning = clean(warning); items = items == null ? List.of() : List.copyOf(items);
        }
    }
    public record PluginCatalogItem(String repo, String name, String description, int stars, String url, String spec, String status) {
        public PluginCatalogItem {
            repo = clean(repo); name = clean(name); description = clean(description); url = clean(url); spec = clean(spec); status = clean(status);
        }
    }
    public record PluginDetails(String repo, String status, String message, boolean fromCache, List<InstallTarget> targets) {
        public PluginDetails { repo = clean(repo); status = clean(status); message = clean(message); targets = targets == null ? List.of() : List.copyOf(targets); }
    }
    public record InstallTarget(String name, String version, String spec, String packagePath, String evidenceUrl) {
        public InstallTarget { name = clean(name); version = clean(version); spec = clean(spec); packagePath = clean(packagePath); evidenceUrl = clean(evidenceUrl); }
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
