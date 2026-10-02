package com.hdsl.ui;

import javafx.application.Platform;
import com.hdsl.ui.hmcl.MainPage;
import com.hdsl.ui.hmcl.MainWindowPane;
import com.hdsl.ui.hmcl.RootPage;
import javafx.stage.Stage;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Harness actions hosted in the GPL HMCL-derived launcher components. */
public final class ShellView {
    private final UiActions actions;
    private final BorderPane root = new BorderPane();
    private final StackPane content = new StackPane();
    private final Label footerStatus = label("正在读取工作空间…", "footer-status");
    private final RootPage rootPage;
    private final MainWindowPane windowPane;
    private final ProgressIndicator progress = new ProgressIndicator();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanProperty taskActive = new SimpleBooleanProperty(false);
    private final StringProperty taskPhase = new SimpleStringProperty("");
    private final StringProperty taskElapsed = new SimpleStringProperty("已用时 00:00");
    private final Timeline taskClock = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateTaskElapsed()));
    private UiState state = UiState.empty();
    private String route = "home";
    private String busyMessage = "";
    private long taskStartedNanos;
    private TextArea visibleLogs;
    private boolean settingsDirty;
    private boolean settingsBuilt;
    private int downloadTab;
    private String pluginQueryDraft;
    private String desktopSourceDraft;

    private record Choice(String id, String title) { @Override public String toString() { return title; } }

    public ShellView(UiActions actions) {
        this.actions = Objects.requireNonNull(actions);
        taskClock.setCycleCount(Timeline.INDEFINITE);
        busy.addListener((observable, previous, current) -> updateTaskIndicator());
        root.setId("hdsl-shell");
        root.getStyleClass().add("launcher-root");
        root.setMinSize(860, 580);
        for (String name : new String[]{"hmcl-blue.css", "hmcl-root.css", "launcher.css"}) {
            var stylesheet = ShellView.class.getResource(name);
            if (stylesheet != null) root.getStylesheets().add(stylesheet.toExternalForm());
        }
        rootPage = new RootPage(content, this::navigate, () -> editInstance(state.currentInstance()), ShellView::icon, busy);
        windowPane = new MainWindowPane(rootPage, landscape(), titleStatus(), () -> navigate("home"), () -> navigate("settings"));
        root.setCenter(windowPane);
        showPage();
    }

    public Parent view() {
        return root;
    }

    public void configureStage(Stage stage) { windowPane.configureStage(stage); }

    public void render(UiState incoming) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> render(incoming));
            return;
        }
        if (incoming == null) return;
        UiState previous = state;
        state = incoming;
        updateTaskIndicator();
        UiState.InstanceItem current = state.currentInstance();
        rootPage.render(state, route);
        windowPane.setBackgroundPath(state.settings().background());
        footerStatus.setText(state.status().startsWith("进行中：") ? shortStatus(state.status()) : busy.get() ? busyMessage : shortStatus(state.status()));
        if (route.equals("logs") && visibleLogs != null) {
            updateLogs();
        } else if (route.equals("settings") && settingsBuilt && settingsDirty) {
            // Keep an unfinished edit intact when a background refresh arrives.
        } else if (!samePageData(previous, incoming) || content.getChildren().isEmpty()) {
            showPage();
        }
    }

    private boolean samePageData(UiState a, UiState b) {
        return switch (route) {
            case "plugins" -> a.plugins().equals(b.plugins()) && Objects.equals(a.currentInstance(), b.currentInstance());
            case "runtimes" -> a.runtimes().equals(b.runtimes()) && a.availableRuntimeVersions().equals(b.availableRuntimeVersions())
                    && a.desktop().equals(b.desktop()) && a.pluginCatalog().equals(b.pluginCatalog()) && a.pluginDetails().equals(b.pluginDetails());
            case "accounts" -> a.accounts().equals(b.accounts()) && a.instances().equals(b.instances());
            case "settings" -> a.settings().equals(b.settings());
            case "packs" -> a.instances().equals(b.instances()) && a.currentInstanceId().equals(b.currentInstanceId());
            default -> a.instances().equals(b.instances()) && a.runtimes().equals(b.runtimes())
                    && a.currentInstanceId().equals(b.currentInstanceId()) && a.settings().background().equals(b.settings().background());
        };
    }

    private Node titleStatus() {
        HBox bar = new HBox(7);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.getStyleClass().add("window-task-status");
        progress.setMaxSize(14, 14);
        progress.setId("task-progress");
        footerStatus.setId("task-status");
        progress.setMinSize(14, 14);
        progress.visibleProperty().bind(taskActive);
        progress.managedProperty().bind(taskActive);
        footerStatus.setMaxWidth(270);
        footerStatus.setMinWidth(0);
        footerStatus.setTooltip(new Tooltip());
        footerStatus.getTooltip().textProperty().bind(footerStatus.textProperty());
        bar.getChildren().addAll(progress, footerStatus);
        return bar;
    }

    private void navigate(String next) {
        route = next;
        settingsBuilt = false;
        settingsDirty = false;
        visibleLogs = null;
        showPage();
    }

    private void showPage() {
        rootPage.render(state, route);
        String[] title = switch (route) {
            case "instances" -> new String[]{"实例", "每个实例独立保存配置、插件、profile 与日志。"};
            case "packs" -> new String[]{"整合包", "导入已有的工作空间，或把你的配置打包带走。"};
            case "runtimes" -> new String[]{"下载", "Web 版、桌面版与插件。"};
            case "accounts" -> new String[]{"账户", "保存提供商配置，为实例绑定账户。"};
            case "plugins" -> new String[]{"插件", "管理当前实例的扩展；支持指定插件版本。"};
            case "logs" -> new String[]{"任务与日志", "查看安装任务、启动过程和运行输出。"};
            case "settings" -> new String[]{"设置", "连接、下载来源与工作空间外观。"};
            default -> new String[]{"启动", "选择实例，启动你的 Harness 工作空间。"};
        };
        windowPane.setPageTitle(title[0]);
        Node page = switch (route) {
            case "instances" -> instancesPage();
            case "packs" -> packsPage();
            case "runtimes" -> downloadsPage();
            case "accounts" -> accountsPage();
            case "plugins" -> pluginsPage();
            case "logs" -> logsPage();
            case "settings" -> settingsPage();
            default -> homePage();
        };
        if (!route.equals("home")) page.getStyleClass().add("gray-background");
        page.setId("page-" + route);
        content.getChildren().setAll(page);
    }

    private Node homePage() {
        UiState.InstanceItem current = state.currentInstance();
        return new MainPage(state, busy, current != null && runtimeInstalled(current), current != null && isRunning(current),
                homeTaskPanel(), () -> {
                    if (current == null) editInstance(null);
                    else if (isRunning(current)) run("openWeb", id(current), "正在打开 Web 界面…");
                    else run("launch", id(current), runtimeInstalled(current) ? "正在启动 " + current.name() + "…" : "首次启动：正在准备下载 Harness 和依赖…");
                }, () -> { if (current != null) run("stop", id(current), "正在停止实例…"); },
                selected -> run("selectInstance", Map.of("id", selected), "正在切换实例…"));
    }

    private Node homeTaskPanel() {
        VBox panel = new VBox(10);
        panel.setId("home-task-panel");
        panel.getStyleClass().addAll("card", "home-task-panel");
        panel.visibleProperty().bind(taskActive);
        panel.managedProperty().bind(taskActive);
        Label elapsed = label("", "task-elapsed");
        elapsed.setId("home-task-elapsed");
        elapsed.textProperty().bind(taskElapsed);
        HBox heading = toolbar(label("任务进行中", "task-heading"), elapsed);
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setMaxSize(21, 21);
        indicator.setMinSize(21, 21);
        Label phase = label("", "task-phase");
        phase.setId("home-task-phase");
        phase.textProperty().bind(taskPhase);
        phase.setWrapText(true);
        phase.setMinWidth(0);
        phase.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(phase, Priority.ALWAYS);
        Button details = new Button("查看任务", icon("terminal"));
        details.setId("home-view-task");
        details.getStyleClass().add("secondary-button");
        details.setGraphicTextGap(8);
        details.setMinWidth(103);
        details.setOnAction(event -> navigate("logs"));
        HBox body = new HBox(12, indicator, phase, details);
        body.setAlignment(Pos.CENTER_LEFT);
        panel.getChildren().addAll(heading, body);
        return panel;
    }

    private boolean runtimeInstalled(UiState.InstanceItem instance) {
        return state.runtimes().stream().anyMatch(runtime -> runtime.installed() && runtime.version().equals(instance.version()));
    }

    private void updateTaskIndicator() {
        boolean active = busy.get() || state.status().startsWith("进行中：");
        if (active) {
            if (!taskActive.get()) {
                taskStartedNanos = System.nanoTime();
                taskClock.playFromStart();
            }
            String phase = state.status().startsWith("进行中：") ? state.status().substring("进行中：".length()) : busyMessage;
            taskPhase.set(phase.isBlank() ? "正在准备任务…" : phase);
            updateTaskElapsed();
        } else {
            taskClock.stop();
            taskStartedNanos = 0;
        }
        taskActive.set(active);
    }

    private void updateTaskElapsed() {
        long seconds = taskStartedNanos == 0 ? 0 : Math.max(0, (System.nanoTime() - taskStartedNanos) / 1_000_000_000);
        String elapsed = seconds >= 3600 ? String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        taskElapsed.set("已用时 " + elapsed);
    }

    private Pane landscape() {
        Pane art = new Pane();
        art.setMouseTransparent(true);
        art.setMinSize(0, 0);
        art.setStyle("-fx-background-color: linear-gradient(to bottom, #c9d4e5, #8daabe);");
        Circle sun = new Circle(48, Color.web("#f9f5e6", 0.85));
        sun.centerXProperty().bind(art.widthProperty().multiply(0.78));
        sun.centerYProperty().bind(art.heightProperty().multiply(0.31));
        art.getChildren().add(sun);
        Polygon far = new Polygon();
        far.setFill(Color.web("#6f91a7", 0.65));
        Polygon middle = new Polygon();
        middle.setFill(Color.web("#365e79", 0.85));
        Polygon near = new Polygon();
        near.setFill(Color.web("#203f5d", 0.86));
        art.getChildren().addAll(far, middle, near);
        Runnable resize = () -> {
            double w = art.getWidth(), h = art.getHeight();
            far.getPoints().setAll(0d, h * .88, w * .22, h * .61, w * .4, h * .77, w * .64, h * .30,
                    w * .80, h * .52, w, h * .41, w, h, 0d, h);
            middle.getPoints().setAll(0d, h * .78, w * .19, h * .64, w * .36, h * .85, w * .64, h * .60,
                    w * .81, h * .69, w, h * .57, w, h, 0d, h);
            near.getPoints().setAll(0d, h * .84, w * .25, h * .97, w * .48, h * .77,
                    w * .75, h * .90, w, h * .73, w, h, 0d, h);
        };
        art.widthProperty().addListener((obs, old, value) -> resize.run());
        art.heightProperty().addListener((obs, old, value) -> resize.run());
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.setArcHeight(0);
        clip.setArcWidth(0);
        clip.widthProperty().bind(art.widthProperty());
        clip.heightProperty().bind(art.heightProperty());
        art.setClip(clip);
        return art;
    }

    private Node instancesPage() {
        VBox page = pageBox();
        page.getChildren().add(toolbar(label(state.instances().size() + " 个实例", "section-title"),
                button("创建实例", "plus", "primary-button", () -> editInstance(null))));
        if (state.instances().isEmpty()) {
            page.getChildren().add(empty("还没有实例", "创建独立的配置空间，为它选择 Harness 版本与 profile。", "layers"));
        }
        for (UiState.InstanceItem instance : state.instances()) {
            VBox card = card();
            boolean selected = instance.id().equals(state.currentInstanceId());
            if (selected) card.getStyleClass().add("selected-card");
            HBox row = new HBox(14);
            row.setAlignment(Pos.CENTER_LEFT);
            VBox description = new VBox(7);
            HBox heading = new HBox(10, label(instance.name(), "card-title"), badge(displayStatus(instance), isRunning(instance) ? "green" : "gray"));
            if (selected) heading.getChildren().add(badge("当前", "blue"));
            description.getChildren().addAll(heading, label("Harness " + instance.version() + "  ·  " + instance.profile() + "  ·  端口 " + instance.port(), "muted-label"));
            Label path = label(instance.path(), "path-label");
            path.setWrapText(true);
            description.getChildren().add(path);
            HBox.setHgrow(description, Priority.ALWAYS);
            row.getChildren().add(description);
            if (!selected) row.getChildren().add(button("选择", "check", "secondary-button", () -> run("selectInstance", id(instance), "正在切换实例…")));
            row.getChildren().add(button(isRunning(instance) ? "停止" : runtimeInstalled(instance) ? "启动" : "下载并启动", isRunning(instance) ? "stop" : "play", "primary-button",
                    () -> run(isRunning(instance) ? "stop" : "launch", id(instance), isRunning(instance) ? "正在停止实例…" : "正在启动实例…")));
            MenuButton more = new MenuButton("更多");
            more.setId("instance-more-" + instance.id());
            more.getStyleClass().add("secondary-button");
            more.disableProperty().bind(busy);
            MenuItem edit = new MenuItem("编辑实例");
            edit.setOnAction(e -> editInstance(instance));
            MenuItem copy = new MenuItem("复制实例");
            copy.setOnAction(e -> copyInstance(instance));
            MenuItem folder = new MenuItem("打开实例文件夹");
            folder.setOnAction(e -> run("openInstanceFolder", id(instance), "正在打开实例文件夹…"));
            MenuItem delete = new MenuItem("删除实例…");
            delete.setOnAction(e -> {
                if (confirm("删除实例", "将「" + instance.name() + "」移入回收目录？", "实例配置、插件和日志会一同移入数据目录下的 trash，可从该目录恢复。\n" + instance.path()))
                    run("deleteInstance", id(instance), "正在删除实例…");
            });
            more.getItems().addAll(edit, copy, folder, new SeparatorMenuItem(), delete);
            row.getChildren().add(more);
            card.getChildren().add(row);
            page.getChildren().add(card);
        }
        return scroll(page);
    }

    private Node accountsPage() {
        VBox page = pageBox();
        Button create = button("添加账户", "plus", "primary-button", () -> editAccount(null));
        create.setId("account-create");
        page.getChildren().add(toolbar(label("账户管理", "section-title"), create));
        page.getChildren().add(note("账户与实例", "保存 API 提供商、地址和模型后，在实例中选择账户。保存配置不会测试 API 连接，也不会发送模型请求。"));
        UiState.InstanceItem current = state.currentInstance();
        if (current != null) {
            ComboBox<Choice> account = accountSelector(current.accountId());
            account.setId("account-binding");
            Button bind = button("应用到当前实例", "check", "primary-button", () -> run("bindAccount",
                    Map.of("id", current.id(), "accountId", account.getValue().id()), "正在保存账户绑定…"));
            bind.setId("account-bind");
            VBox binding = card();
            binding.getChildren().addAll(label("当前实例 · " + current.name(), "card-title"),
                    toolbar(account, bind), paragraph("不绑定时使用实例原有配置。绑定更改在下次启动时生效，用于默认模型；已有会话可能保留单独选择的模型。"));
            page.getChildren().add(binding);
        }
        if (state.accounts().isEmpty()) page.getChildren().add(empty("尚未保存账户", "添加一个 API 账户，即可供多个实例选择。", "user"));
        for (UiState.AccountItem account : state.accounts()) {
            VBox item = card();
            Button edit = button("编辑", "settings", "secondary-button", () -> editAccount(account));
            edit.setId("account-edit-" + account.id());
            Button delete = button("删除", "trash", "subtle-button", () -> {
                if (confirm("删除账户", "删除「" + account.name() + "」？", "删除前请解除实例对该账户的绑定。"))
                    run("deleteAccount", Map.of("id", account.id()), "正在删除账户…");
            });
            item.getChildren().addAll(toolbar(label(account.name(), "card-title"), edit, delete),
                    paragraph(account.provider() + " · " + account.model() + "\n" + account.baseUrl()),
                    paragraph((account.hasKey() ? "已保存 API key" : "未保存 API key") + " · API 连接未验证"));
            page.getChildren().add(item);
        }
        return scroll(page);
    }

    private ComboBox<Choice> accountSelector(String selected) {
        ComboBox<Choice> combo = new ComboBox<>();
        combo.getItems().add(new Choice("", "不绑定 · 使用实例现有配置"));
        state.accounts().forEach(account -> combo.getItems().add(new Choice(account.id(), account.name() + " · " + account.provider())));
        combo.setValue(combo.getItems().stream().filter(choice -> choice.id().equals(selected)).findFirst().orElse(combo.getItems().getFirst()));
        combo.setMaxWidth(Double.MAX_VALUE);
        return combo;
    }

    private void editAccount(UiState.AccountItem existing) {
        Dialog<Map<String, String>> dialog = dialog(existing == null ? "添加账户" : "编辑账户");
        dialog.getDialogPane().setId("account-dialog");
        TextField name = new TextField(existing == null ? "" : existing.name());
        name.setId("account-name");
        ComboBox<String> provider = new ComboBox<>();
        provider.setId("account-provider");
        provider.setEditable(true);
        provider.setMaxWidth(Double.MAX_VALUE);
        var presets = com.hdsl.account.AccountService.providers();
        provider.getItems().setAll(presets.stream().map(com.hdsl.account.ProviderPreset::id).toList());
        TextField baseUrl = new TextField(existing == null ? "https://api.deepseek.com" : existing.baseUrl());
        baseUrl.setId("account-base-url");
        TextField model = new TextField(existing == null ? "deepseek-flash" : existing.model());
        model.setId("account-model");
        provider.setValue(existing == null ? "deepseek-official" : existing.provider());
        provider.getEditor().setText(provider.getValue());
        provider.valueProperty().addListener((o, old, value) -> presets.stream().filter(preset -> preset.id().equals(value)).findFirst().ifPresent(preset -> {
            baseUrl.setText(preset.baseUrl());
            model.setText(preset.model());
        }));
        PasswordField key = new PasswordField();
        key.setId("account-key");
        key.setPromptText(existing != null && existing.hasKey() ? "留空保留已保存的密钥" : "输入此提供商的 API key");
        Label validation = label("", "validation-error");
        validation.setId("account-validation");
        validation.setWrapText(true);
        VBox body = new VBox(12, formField("账户名称", name), formField("提供商（可输入官方支持的标识）", provider),
                formField("API 地址", baseUrl), formField("模型", model), formField("API key", key),
                paragraph("保存后显示为「API 连接未验证」。请填写自己可使用的模型。Codex / Copilot 的订阅登录不能作为 API key。"), validation);
        dialog.getDialogPane().setContent(body);
        ButtonType saveType = new ButtonType("保存账户", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, saveType);
        Button save = (Button) dialog.getDialogPane().lookupButton(saveType);
        save.setId("account-save");
        save.disableProperty().bind(busy);
        save.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String error = name.getText().isBlank() ? "请输入账户名称。" : comboValue(provider).isBlank() ? "请输入提供商。"
                    : baseUrl.getText().isBlank() ? "请输入 API 地址。" : model.getText().isBlank() ? "请输入可使用的模型。"
                    : (existing == null || !existing.hasKey()) && key.getText().isBlank() ? "请输入 API key。" : "";
            if (!error.isEmpty()) { validation.setText(error); event.consume(); }
        });
        dialog.setResultConverter(type -> {
            if (type != saveType) return null;
            Map<String, String> args = new LinkedHashMap<>();
            args.put("id", existing == null ? "" : existing.id());
            args.put("name", name.getText().trim());
            args.put("provider", comboValue(provider));
            args.put("baseUrl", baseUrl.getText().trim());
            args.put("model", model.getText().trim());
            args.put("apiKey", key.getText());
            key.clear();
            return args;
        });
        dialog.setOnHidden(event -> key.clear());
        dialog.showAndWait().ifPresent(args -> run("saveAccount", args, "正在保存账户…"));
    }

    private Node downloadsPage() {
        TabPane tabs = new TabPane(new Tab("Web 版", runtimesPage()), new Tab("桌面版", desktopPage()), new Tab("插件", catalogPage()));
        tabs.setId("download-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("download-tabs");
        tabs.getSelectionModel().select(downloadTab);
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, old, current) -> downloadTab = current.intValue());
        return new BorderPane(tabs);
    }

    private Node desktopPage() {
        VBox page = pageBox();
        ComboBox<Choice> source = new ComboBox<>();
        source.setId("desktop-source");
        source.getItems().setAll(new Choice("deepseek-ai/deepseek-harness", "官方 · DeepSeek Harness（官网）"),
                new Choice("anywhere-labs/dsh-desktop", "社区 · DSH Desktop / anywhere-labs"),
                new Choice("dataelement/dsh-desktop", "社区 · DSH Desktop / dataelement"));
        String selectedSource = desktopSourceDraft == null ? state.desktop().source() : desktopSourceDraft;
        source.setValue(source.getItems().stream().filter(choice -> choice.id().equals(selectedSource)).findFirst().orElse(source.getItems().getFirst()));
        source.valueProperty().addListener((o, old, value) -> desktopSourceDraft = value.id());
        Button refresh = button("获取发行版本", "refresh", "primary-button", () -> run("refreshDesktop", Map.of("source", source.getValue().id()), "正在获取桌面版发行信息…"));
        refresh.setId("desktop-refresh");
        page.getChildren().addAll(toolbar(source, refresh),
                note("目前启动器尚未支持管理桌面版插件", "桌面版的账户和插件需要在其应用内管理。HDSL 的账户绑定仅适用于本启动器管理的 Web 实例。下载后可打开文件夹，自行运行安装程序。"));
        if (!state.desktop().message().isBlank()) page.getChildren().add(paragraph(state.desktop().message()));
        for (UiState.DesktopItem item : state.desktop().items()) {
            VBox card = card();
            Button download = button(item.path().isBlank() ? "下载安装包" : "打开下载文件夹", item.path().isBlank() ? "download" : "folder", "primary-button", () ->
                    run(item.path().isBlank() ? "downloadDesktop" : "openDownloadFolder", Map.of("id", item.id()), item.path().isBlank() ? "正在下载桌面安装包…" : "正在打开下载文件夹…"));
            download.setId("desktop-download-" + item.id());
            Label title = label(item.version() + (item.prerelease() ? " · 预发布" : ""), "card-title");
            card.getChildren().addAll(toolbar(title, download), paragraph(item.name() + " · " + String.format(Locale.ROOT, "%.1f MiB", item.size() / 1048576.0)),
                    paragraph(item.source().equals("deepseek-ai/deepseek-harness") ? "官方 · www.deepseek.com/en/download/" : "社区 · " + item.source()),
                    paragraph(item.sha256().isBlank() ? "发行方未提供 SHA-256。" : "SHA-256：" + item.sha256()));
            page.getChildren().add(card);
        }
        return scroll(page);
    }

    private Node catalogPage() {
        VBox page = pageBox();
        TextField query = new TextField(pluginQueryDraft == null ? state.pluginCatalog().query() : pluginQueryDraft);
        query.setId("catalog-query");
        query.setPromptText("搜索 dsh-plugin 主题项目");
        query.textProperty().addListener((o, old, text) -> pluginQueryDraft = text);
        HBox.setHgrow(query, Priority.ALWAYS);
        Button search = button("搜索", "refresh", "primary-button", () -> searchCatalog(query.getText(), 1, false));
        search.setId("catalog-search");
        query.setOnAction(event -> search.fire());
        Button refresh = button("重新获取", "refresh", "secondary-button", () -> searchCatalog(query.getText(), 1, true));
        page.getChildren().addAll(new HBox(10, query, search, refresh),
                paragraph("来源：github.com/topics/dsh-plugin。先检查项目的安装目标，再选择已发布的 npm 包安装到实例。"));
        UiState.PluginCatalog catalog = state.pluginCatalog();
        if (!catalog.warning().isBlank()) page.getChildren().add(note("目录提示", catalog.warning()));
        if (catalog.items().isEmpty()) page.getChildren().add(empty("暂无目录结果", "点击搜索获取项目。仅通过安装目标检查的包可在此安装。", "puzzle"));
        for (UiState.PluginCatalogItem item : catalog.items()) {
            VBox card = card();
            Button inspect = button("检查安装目标", "check", "secondary-button", () -> run("inspectPluginProject", Map.of("repo", item.repo(), "refresh", "false"), "正在检查插件安装目标…"));
            inspect.setId("catalog-inspect-" + safeId(item.repo()));
            Button project = button("查看项目", "external", "subtle-button", () -> run("openPluginProject", Map.of("repo", item.repo()), "正在打开插件项目…"));
            Label title = label(item.name(), "card-title");
            title.setMinWidth(0);
            card.getChildren().addAll(toolbar(title, inspect, project), paragraph(item.repo() + " · ★ " + item.stars()), paragraph(item.description()));
            UiState.PluginDetails details = state.pluginDetails();
            if (details.repo().equals(item.repo())) {
                card.getChildren().add(paragraph(details.message() + (details.fromCache() ? "（缓存结果）" : "")));
                if (details.status().equals("INSTALLABLE") && !details.targets().isEmpty()) {
                    Button install = button("选择目标并安装", "download", "primary-button", () -> installCatalogPlugin(details));
                    install.setId("catalog-install-" + safeId(item.repo()));
                    card.getChildren().add(install);
                }
            }
            page.getChildren().add(card);
        }
        Button previous = button("上一页", "layers", "secondary-button", () -> searchCatalog(catalog.query(), catalog.page() - 1, false));
        previous.disableProperty().unbind(); previous.disableProperty().bind(busy.or(new SimpleBooleanProperty(catalog.page() <= 1)));
        Button next = button("下一页", "layers", "secondary-button", () -> searchCatalog(catalog.query(), catalog.page() + 1, false));
        next.disableProperty().unbind(); next.disableProperty().bind(busy.or(new SimpleBooleanProperty(!catalog.hasNext())));
        page.getChildren().add(toolbar(label("第 " + catalog.page() + " 页 · " + catalog.total() + " 个项目" + (catalog.fromCache() ? " · 缓存" : ""), "muted-label"), previous, next));
        return scroll(page);
    }

    private static String safeId(String value) { return value.replaceAll("[^A-Za-z0-9_-]", "-"); }

    private void searchCatalog(String query, int page, boolean refresh) {
        run("searchPluginCatalog", Map.of("query", query.trim(), "page", Integer.toString(page), "refresh", Boolean.toString(refresh)), "正在获取插件目录…");
    }

    private void installCatalogPlugin(UiState.PluginDetails details) {
        if (state.instances().isEmpty()) { require("", "请先创建一个实例，再安装插件。"); return; }
        Dialog<Map<String, String>> dialog = dialog("安装插件 · 确认目标");
        dialog.getDialogPane().setId("catalog-install-dialog");
        dialog.getDialogPane().setPrefWidth(580);
        ComboBox<Choice> instance = new ComboBox<>();
        instance.setId("catalog-install-instance");
        state.instances().forEach(item -> instance.getItems().add(new Choice(item.id(), item.name() + " · " + item.profile())));
        instance.setValue(instance.getItems().stream().filter(item -> item.id().equals(state.currentInstanceId())).findFirst().orElse(instance.getItems().getFirst()));
        instance.setMaxWidth(Double.MAX_VALUE);
        ComboBox<Choice> target = new ComboBox<>();
        target.setId("catalog-install-target");
        details.targets().forEach(item -> target.getItems().add(new Choice(item.spec(), item.spec())));
        target.getSelectionModel().selectFirst();
        target.setMaxWidth(Double.MAX_VALUE);
        Label evidence = paragraph("");
        Runnable updateEvidence = () -> details.targets().stream().filter(item -> item.spec().equals(target.getValue().id())).findFirst().ifPresent(item ->
                evidence.setText("仓库路径：" + item.packagePath() + "\n核对来源：" + item.evidenceUrl()));
        target.valueProperty().addListener((o, old, value) -> updateEvidence.run());
        updateEvidence.run();
        dialog.getDialogPane().setContent(new VBox(14, paragraph("项目来源：" + details.repo()), formField("安装到实例", instance),
                formField("已核对的 npm 包与精确版本", target), evidence, paragraph("插件将在所选实例的当前 profile 中运行。请确认你信任此项目。")));
        ButtonType install = new ButtonType("确认安装", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, install);
        dialog.getDialogPane().lookupButton(install).setId("catalog-confirm-install");
        dialog.setResultConverter(type -> type == install ? Map.of("id", instance.getValue().id(), "repo", details.repo(), "spec", target.getValue().id()) : null);
        dialog.showAndWait().ifPresent(args -> run("installCatalogPlugin", args, "正在安装已选择的插件…"));
    }

    private Node runtimesPage() {
        VBox page = pageBox();
        VBox install = card();
        install.getChildren().addAll(label("安装 Harness 运行时", "card-title"),
                paragraph("从发布列表选择版本，或直接输入完整版本号。已安装版本会保留，实例升级可以单独进行。"));
        ComboBox<String> versions = versionSelector("");
        versions.setId("runtime-version");
        versions.setPromptText("输入完整版本号，或刷新版本列表");
        versions.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(versions, Priority.ALWAYS);
        HBox form = new HBox(10, versions,
                button("刷新版本", "refresh", "secondary-button", () -> run("refreshVersions", Map.of(), "正在获取发布版本…")),
                button("安装", "download", "primary-button", () -> {
                    String version = comboValue(versions);
                    if (require(version, "请输入或选择 Harness 版本。")) run("installRuntime", Map.of("version", version), "正在安装 Harness " + version + "…");
                }));
        install.getChildren().add(form);
        page.getChildren().add(install);
        page.getChildren().add(label("本地运行时", "section-title"));
        if (state.runtimes().isEmpty()) page.getChildren().add(empty("还没有运行时", "输入版本并安装后，可在实例设置中选择它。", "cpu"));
        for (UiState.RuntimeItem runtime : state.runtimes()) {
            VBox card = card();
            HBox row = new HBox(12);
            row.setAlignment(Pos.CENTER_LEFT);
            VBox info = new VBox(7, label(runtime.version(), "card-title"), paragraph(runtime.compatibility().isBlank() ? "安装后会检测可用命令与能力。" : runtime.compatibility()));
            HBox.setHgrow(info, Priority.ALWAYS);
            row.getChildren().addAll(info, badge(runtime.installed() ? "已安装" : "未安装", runtime.installed() ? "green" : "gray"));
            if (runtime.installed()) {
                row.getChildren().add(button("检测", "refresh", "secondary-button", () -> run("probeRuntime", Map.of("version", runtime.version()), "正在检测命令兼容性…")));
                row.getChildren().add(button("移除", "trash", "subtle-button", () -> {
                    if (confirm("移除运行时", "移除 Harness " + runtime.version() + "？", "只能移除没有实例引用的版本；如有引用，请先调整对应实例。运行时会移入回收目录。"))
                        run("removeRuntime", Map.of("version", runtime.version()), "正在移除运行时…");
                }));
            } else {
                row.getChildren().add(button("安装", "download", "primary-button", () -> run("installRuntime", Map.of("version", runtime.version()), "正在安装运行时…")));
            }
            card.getChildren().add(row);
            page.getChildren().add(card);
        }
        page.getChildren().add(note("版本兼容", "启动器按实际命令能力适配 Harness。未验证的新版本会展示检测结果；实例固定版本后，不会随启动器更新自动升级。"));
        return scroll(page);
    }

    private Node pluginsPage() {
        VBox page = pageBox();
        UiState.InstanceItem current = state.currentInstance();
        if (current == null) {
            page.getChildren().add(empty("先选择一个实例", "插件安装在实例中。创建实例后即可管理它的插件。", "puzzle"));
            return scroll(page);
        }
        page.getChildren().add(toolbar(instanceSelector(),
                button("刷新", "refresh", "secondary-button", () -> run("refreshPlugins", id(current), "正在读取插件列表…")),
                button("安装插件", "plus", "primary-button", () -> addPlugin(current))));
        page.getChildren().add(note("当前 profile · " + current.profile(), "插件由当前实例的 Harness 管理。可指定完整 npm 包名和版本；安装前请确认来源。"));
        if (state.plugins().isEmpty()) page.getChildren().add(empty("暂无插件记录", "点击刷新读取当前实例，或安装一个插件。", "puzzle"));
        for (UiState.PluginItem plugin : state.plugins()) {
            VBox card = card();
            HBox row = new HBox(12);
            row.setAlignment(Pos.CENTER_LEFT);
            VBox info = new VBox(7);
            info.getChildren().add(label(plugin.name(), "card-title"));
            if (!plugin.description().isBlank()) info.getChildren().add(paragraph(plugin.description()));
            info.getChildren().add(label(plugin.version() + (plugin.source().isBlank() ? "" : "  ·  " + plugin.source()), "path-label"));
            HBox.setHgrow(info, Priority.ALWAYS);
            row.getChildren().addAll(info,
                    button("更新", "refresh", "secondary-button", () -> run("updatePlugin", Map.of("id", current.id(), "name", plugin.name()), "正在更新 " + plugin.name() + "…")),
                    button("移除", "trash", "subtle-button", () -> {
                        if (confirm("移除插件", "从「" + current.name() + "」移除 " + plugin.name() + "？", "这会改变当前 profile 的插件配置。"))
                            run("removePlugin", Map.of("id", current.id(), "name", plugin.name()), "正在移除插件…");
                    }));
            card.getChildren().add(row);
            page.getChildren().add(card);
        }
        return scroll(page);
    }

    private Node packsPage() {
        VBox page = pageBox();
        HBox choices = new HBox(16);
        VBox importCard = card();
        importCard.getChildren().addAll(icon("box"), label("导入整合包", "card-title"),
                paragraph("选择 .dspack 或兼容的快照 ZIP，先检查包信息，再导入为新实例。"),
                button("选择整合包", "download", "primary-button", this::choosePack));
        VBox exportCard = card();
        UiState.InstanceItem current = state.currentInstance();
        exportCard.getChildren().addAll(icon("upload"), label("导出当前实例", "card-title"),
                paragraph(current == null ? "创建或选择实例后，即可导出为整合包。" : "将「" + current.name() + "」的当前 profile 或整个实例导出为 PackForge 整合包。"));
        Button export = button("导出 .dspack", "upload", "secondary-button", () -> exportPack(current));
        export.disableProperty().unbind();
        export.disableProperty().bind(busy.or(new SimpleBooleanProperty(current == null)));
        exportCard.getChildren().add(export);
        HBox.setHgrow(importCard, Priority.ALWAYS);
        HBox.setHgrow(exportCard, Priority.ALWAYS);
        importCard.setMaxWidth(Double.MAX_VALUE);
        exportCard.setMaxWidth(Double.MAX_VALUE);
        importCard.setPrefWidth(380);
        exportCard.setPrefWidth(380);
        choices.getChildren().addAll(importCard, exportCard);
        page.getChildren().add(choices);
        if (current != null) page.getChildren().add(toolbar(label("当前实例", "section-title"), instanceSelector()));
        page.getChildren().add(note("先预览，再导入", "导入会读取清单并检查文件路径。带平台依赖的快照可能只能在对应系统运行；请查看预览中的兼容提示。"));
        page.getChildren().add(note("分享包的导出范围", "分享包不包含凭据、会话和可能含密钥的 patch 配置；导入后可能需要重新配置。"));
        return scroll(page);
    }

    private Node logsPage() {
        VBox page = pageBox();
        visibleLogs = new TextArea();
        visibleLogs.setEditable(false);
        visibleLogs.setWrapText(false);
        visibleLogs.getStyleClass().add("log-area");
        visibleLogs.setMinHeight(250);
        VBox.setVgrow(visibleLogs, Priority.ALWAYS);
        Button cancel = new Button("取消当前任务", icon("stop"));
        cancel.setId("cancel-task");
        cancel.getStyleClass().add("secondary-button");
        cancel.disableProperty().bind(busy.not());
        cancel.setOnAction(event -> {
            footerStatus.setText("正在请求取消任务…");
            try {
                actions.dispatch("cancelTask", Map.of()).whenComplete((result, failure) -> Platform.runLater(() -> {
                    if (failure != null) showFailure(failure);
                    else render(result);
                }));
            } catch (RuntimeException failure) { showFailure(failure); }
        });
        Button copyLogs = button("复制日志", "copy", "secondary-button", () -> {
                    ClipboardContent clipboard = new ClipboardContent();
                    clipboard.putString(state.logs());
                    Clipboard.getSystemClipboard().setContent(clipboard);
                    footerStatus.setText("日志已复制到剪贴板");
                });
        copyLogs.disableProperty().unbind();
        copyLogs.setDisable(false);
        page.getChildren().add(toolbar(label("当前任务输出", "section-title"), cancel, copyLogs,
                button("刷新日志", "refresh", "secondary-button", () -> run("refresh", Map.of(), "正在刷新日志…"))));
        page.getChildren().add(visibleLogs);
        updateLogs();
        return page;
    }

    private void updateLogs() {
        if (visibleLogs == null) return;
        String logs = state.logs().isBlank() ? "这里会显示安装、兼容性检测和实例运行日志。" : state.logs();
        if (logs.equals(visibleLogs.getText())) return;
        boolean atEnd = visibleLogs.getCaretPosition() == visibleLogs.getLength() || visibleLogs.getText().isBlank();
        int selectionStart = visibleLogs.getSelection().getStart();
        int selectionEnd = visibleLogs.getSelection().getEnd();
        visibleLogs.setText(logs);
        if (atEnd) visibleLogs.positionCaret(logs.length());
        else visibleLogs.selectRange(Math.min(selectionStart, logs.length()), Math.min(selectionEnd, logs.length()));
    }

    private Node settingsPage() {
        settingsBuilt = true;
        VBox page = pageBox();
        TextField proxy = new TextField(state.settings().proxy());
        proxy.setId("settings-proxy");
        proxy.setPromptText("例如 http://127.0.0.1:7890；留空使用直连");
        TextField registry = new TextField(state.settings().registry());
        registry.setId("settings-registry");
        registry.setPromptText("https://registry.npmjs.org");
        StringProperty backgroundValue = new SimpleStringProperty(state.settings().background());
        TextField background = new TextField(BackgroundCatalog.isBuiltIn(backgroundValue.get()) ? "" : backgroundValue.get());
        background.setId("settings-background");
        background.setPromptText("选择图片或输入本地图片的完整路径");
        proxy.textProperty().addListener((o, old, value) -> settingsDirty = true);
        registry.textProperty().addListener((o, old, value) -> settingsDirty = true);
        background.textProperty().addListener((o, old, value) -> backgroundValue.set(value));
        backgroundValue.addListener((o, old, value) -> settingsDirty = true);
        VBox network = card();
        network.getChildren().addAll(label("网络与下载", "card-title"),
                paragraph("代理用于下载和版本查询。留空关闭启动器的代理设置。"),
                formField("HTTP / HTTPS 代理", proxy), formField("npm 软件源", registry));
        VBox appearance = card();
        HBox imagePicker = new HBox(10, background,
                button("选择图片", "folder", "secondary-button", () -> {
                    FileChooser picker = new FileChooser();
                    picker.setTitle("选择启动页背景");
                    picker.getExtensionFilters().add(new FileChooser.ExtensionFilter("图片", "*.png", "*.jpg", "*.jpeg"));
                    File chosen = picker.showOpenDialog(window());
                    if (chosen != null) background.setText(chosen.getAbsolutePath());
                }), button("重置", "refresh", "subtle-button", () -> { background.clear(); backgroundValue.set(""); }));
        HBox.setHgrow(background, Priority.ALWAYS);
        TilePane backgrounds = new TilePane(8, 8);
        backgrounds.setId("builtin-backgrounds");
        backgrounds.setPrefColumns(5);
        ToggleGroup backgroundGroup = new ToggleGroup();
        for (BackgroundCatalog.Entry entry : BackgroundCatalog.entries()) {
            ToggleButton choice = new ToggleButton();
            choice.setId("background-" + entry.id());
            choice.setUserData(entry.value());
            choice.setToggleGroup(backgroundGroup);
            choice.getStyleClass().add("background-choice");
            choice.setAccessibleText(entry.title());
            Image thumbnail = BackgroundCatalog.thumbnail(entry);
            ImageView image = new ImageView(thumbnail);
            image.setId("thumbnail-" + entry.id());
            image.setFitWidth(118);
            image.setFitHeight(67);
            image.setPreserveRatio(true);
            StackPane imageBox = new StackPane(image);
            imageBox.getStyleClass().add("background-thumbnail");
            imageBox.setMinSize(118, 67);
            imageBox.setPrefSize(118, 67);
            imageBox.setMaxSize(118, 67);
            choice.setGraphic(new VBox(5, imageBox, label(entry.title(), "background-choice-label")));
            choice.setOnAction(event -> {
                background.clear();
                backgroundValue.set(entry.value());
                choice.setSelected(true);
            });
            backgrounds.getChildren().add(choice);
        }
        Consumer<String> selectBackground = value -> backgroundGroup.selectToggle(backgroundGroup.getToggles().stream()
                .filter(toggle -> toggle.getUserData().equals(BackgroundCatalog.normalized(value))).findFirst().orElse(null));
        selectBackground.accept(backgroundValue.get());
        backgroundValue.addListener((observable, old, value) -> selectBackground.accept(value));
        appearance.getChildren().addAll(label("外观", "card-title"), formField("内置背景", backgrounds),
                formField("自定义背景（本地图片）", imagePicker), paragraph("默认使用「晴空招手」。选择背景后点击保存设置。"));
        Button save = button("保存设置", "check", "primary-button", () -> run("saveSettings",
                Map.of("proxy", proxy.getText().trim(), "registry", registry.getText().trim(), "background", backgroundValue.get().trim()),
                "正在保存设置…", result -> settingsDirty = false));
        VBox about = card();
        about.setId("about-hmcl");
        Label copyright = paragraph("界面改编自 Hello Minecraft! Launcher\n版权所有 © 2013-2026 huangyuhui 及贡献者\nHDSL 0.2.0-preview.5 · Copyright (c) 2026 jiefing · GPLv3 或后续版本");
        Label sourceNotice = paragraph("HDSL 保留上游可见版权，并以独立名称和版本区别于原版。\n随包源码：sources/hdsl-0.2.0-preview.5-source.zip");
        sourceNotice.getStyleClass().add("small-muted");
        Button license = new Button("查看 GPL 许可");
        license.getStyleClass().add("subtle-button");
        license.setOnAction(event -> showLicense());
        about.getChildren().addAll(label("关于 HDSL", "card-title"), copyright, sourceNotice, license);
        page.getChildren().addAll(network, appearance, toolbar(label("修改后点击保存生效", "muted-label"), save), about);
        return scroll(page);
    }

    private void showLicense() {
        Dialog<Void> dialog = dialog("GNU General Public License");
        dialog.getDialogPane().setId("license-dialog");
        String text;
        try (var stream = ShellView.class.getResourceAsStream("GPL-3.0.txt")) {
            text = stream == null ? "本程序采用 GNU GPL 第 3 版或后续版本。完整许可请查看随程序提供的 LICENSE 文件。" : new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            text = "无法读取许可文本，请查看随程序提供的 LICENSE 文件。";
        }
        TextArea license = new TextArea(text);
        license.setEditable(false);
        license.setWrapText(true);
        license.setPrefSize(680, 440);
        TextArea additional = new TextArea();
        additional.setEditable(false);
        additional.setWrapText(true);
        try (var stream = ShellView.class.getResourceAsStream("/META-INF/LICENSES/HMCL-ADDITIONAL-TERMS.md")) {
            additional.setText(stream == null ? "HMCL 附加条款：分发修改版时，应以合理的名称或版本号区别于原版；不得移除软件界面显示的版权声明。" : new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException error) {
            additional.setText("请查看随程序提供的 LICENSES/HMCL-ADDITIONAL-TERMS.md。");
        }
        TabPane tabs = new TabPane(new Tab("GPL 第 3 版", license), new Tab("HMCL 附加条款", additional));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        dialog.getDialogPane().setContent(tabs);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void editInstance(UiState.InstanceItem existing) {
        Dialog<Map<String, String>> dialog = dialog(existing == null ? "创建实例" : "编辑实例");
        dialog.getDialogPane().setId("instance-dialog");
        TextField name = new TextField(existing == null ? "" : existing.name());
        name.setId("instance-name");
        name.setPromptText("例如 日常工作");
        String initialVersion = existing == null ? state.runtimes().stream().filter(UiState.RuntimeItem::installed)
                .map(UiState.RuntimeItem::version).findFirst().orElse(state.availableRuntimeVersions().isEmpty() ? "" : state.availableRuntimeVersions().getFirst()) : existing.version();
        ComboBox<String> version = versionSelector(initialVersion);
        version.setId("instance-version");
        version.setPromptText("完整版本号");
        HBox.setHgrow(version, Priority.ALWAYS);
        Button refreshVersions = new Button("刷新版本", icon("refresh"));
        refreshVersions.setId("instance-refresh-versions");
        refreshVersions.getStyleClass().add("secondary-button");
        Label versionStatus = label("可选择已知版本，或直接输入完整版本号。", "muted-label");
        versionStatus.setId("instance-version-status");
        versionStatus.setWrapText(true);
        BooleanProperty queryingVersions = new SimpleBooleanProperty(false);
        refreshVersions.disableProperty().bind(queryingVersions);
        boolean[] editedVersion = {false}, updatingVersion = {false};
        version.getEditor().textProperty().addListener((observable, previous, current) -> {
            if (!updatingVersion[0]) editedVersion[0] = true;
        });
        Runnable queryVersions = () -> {
            if (queryingVersions.get() || !dialog.isShowing()) return;
            queryingVersions.set(true);
            versionStatus.setText("正在获取版本列表，可继续输入或创建实例…");
            CompletableFuture<UiState> request;
            try { request = Objects.requireNonNull(actions.dispatch("refreshVersions", Map.of())); }
            catch (RuntimeException failure) { request = CompletableFuture.failedFuture(failure); }
            request.whenComplete((snapshot, failure) -> Platform.runLater(() -> {
                // A dismissed form has no pending UI work; an in-flight network response only updates the backend cache.
                if (!dialog.isShowing()) return;
                queryingVersions.set(false);
                if (failure != null || snapshot == null) {
                    versionStatus.setText("获取失败，可重试或手动输入完整版本号；已保留现有内容。");
                    return;
                }
                String entered = version.getEditor().getText();
                int anchor = version.getEditor().getAnchor(), caret = version.getEditor().getCaretPosition();
                java.util.LinkedHashSet<String> available = new java.util.LinkedHashSet<>(state.runtimes().stream()
                        .filter(UiState.RuntimeItem::installed).map(UiState.RuntimeItem::version).toList());
                available.addAll(snapshot.availableRuntimeVersions());
                String selected = entered.isBlank() && !editedVersion[0] && !available.isEmpty() ? available.iterator().next() : entered;
                updatingVersion[0] = true;
                try {
                    version.getItems().setAll(available);
                    version.setValue(selected);
                    version.getEditor().setText(selected);
                    version.getEditor().selectRange(Math.min(anchor, selected.length()), Math.min(caret, selected.length()));
                } finally { updatingVersion[0] = false; }
                state = state.withAvailableRuntimeVersions(snapshot.availableRuntimeVersions());
                versionStatus.setText("已获取 " + snapshot.availableRuntimeVersions().size() + " 个发布版本；也可手动输入完整版本号。");
            }));
        };
        refreshVersions.setOnAction(event -> queryVersions.run());
        // JavaFX emits DIALOG_SHOWN before the underlying window's showing flag settles.
        if (existing == null) dialog.setOnShown(event -> Platform.runLater(queryVersions));
        HBox versionField = new HBox(10, version, refreshVersions);
        ComboBox<String> profile = new ComboBox<>();
        profile.setId("instance-profile");
        profile.setEditable(false);
        profile.setMaxWidth(Double.MAX_VALUE);
        profile.getItems().setAll(existing == null ? java.util.List.of("web") : existing.profiles());
        profile.setValue(existing == null ? "web" : existing.profile());
        TextField port = new TextField(existing == null ? "" : String.valueOf(existing.port()));
        port.setId("instance-port");
        port.setPromptText("留空自动分配未使用的实例端口");
        ComboBox<Choice> account = accountSelector(existing == null ? "" : existing.accountId());
        account.setId("instance-account");
        Label validation = label("", "validation-error");
        validation.setId("instance-validation");
        validation.setWrapText(true);
        validation.setMinHeight(Region.USE_PREF_SIZE);
        VBox body = new VBox(15, formField("实例名称", name), formField("Harness 版本（可直接输入）", new VBox(6, versionField, versionStatus)),
                formField(existing == null ? "初始 Profile" : "已有 Profile", profile), formField("本地 Web 端口（可选）", port), formField("账户", account),
                paragraph(existing == null ? "新实例默认使用 web。自定义 profile 需先通过整合包导入，或在 Harness 中创建。请指定精确的 Harness 版本。"
                        : "请选择此实例中已有的 profile。修改 Harness 版本会保留原实例，并复制所选 profile 到新实例。"), validation);
        dialog.getDialogPane().setContent(body);
        ButtonType saveType = new ButtonType(existing == null ? "创建实例" : "保存", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, saveType);
        Button save = (Button) dialog.getDialogPane().lookupButton(saveType);
        save.setId("instance-save");
        save.getStyleClass().add("primary-button");
        save.disableProperty().bind(busy);
        save.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String error = null;
            if (name.getText().trim().isEmpty()) error = "请输入实例名称。";
            else if (name.getText().trim().length() > 160) error = "实例名称请控制在 160 个字符以内。";
            else if (comboValue(version).isEmpty()) error = "请输入 Harness 版本。";
            else if (!validProfileName(comboValue(profile)))
                error = "Profile 名称须为 1–128 个字符，可使用中文、数字、空格、点、下划线或连字符；不支持路径符号、连续点和系统保留名。";
            else if (!port.getText().isBlank()) {
                try {
                    int parsed = Integer.parseInt(port.getText().trim());
                    if (parsed < 1024 || parsed > 65535) error = "端口必须在 1024–65535 之间。";
                } catch (NumberFormatException ex) { error = "请输入有效端口。"; }
            }
            if (error != null) { validation.setText(error); event.consume(); }
        });
        dialog.setResultConverter(type -> {
            if (type != saveType) return null;
            Map<String, String> args = new LinkedHashMap<>();
            if (existing != null) args.put("id", existing.id());
            args.put("name", name.getText().trim());
            args.put("version", comboValue(version));
            args.put("profile", comboValue(profile));
            args.put("port", port.getText().trim());
            args.put("accountId", account.getValue().id());
            return args;
        });
        dialog.showAndWait().ifPresent(args -> run(existing == null ? "createInstance" : "saveInstance", args,
                existing == null ? "正在创建实例…" : "正在保存实例…"));
    }

    private void copyInstance(UiState.InstanceItem instance) {
        TextInputDialog input = new TextInputDialog(instance.name() + " 副本");
        styleDialog(input, "复制实例");
        input.setHeaderText("复制「" + instance.name() + "」当前 profile（" + instance.profile() + "）的可分享配置与插件\n凭据、会话和可能含密钥的 patch 配置留在原实例。");
        input.setContentText("新实例名称");
        input.showAndWait().filter(name -> !name.isBlank()).ifPresent(name ->
                run("copyInstance", Map.of("id", instance.id(), "name", name.trim()), "正在复制实例…"));
    }

    private void addPlugin(UiState.InstanceItem instance) {
        TextInputDialog input = new TextInputDialog();
        styleDialog(input, "安装插件");
        input.setHeaderText("安装到「" + instance.name() + "」 / " + instance.profile());
        input.setContentText("插件名称或 npm 包规格");
        input.getEditor().setPromptText("例如 @scope/plugin@1.2.3");
        input.showAndWait().filter(spec -> !spec.isBlank()).ifPresent(spec ->
                run("addPlugin", Map.of("id", instance.id(), "spec", spec.trim()), "正在安装插件…"));
    }

    private void choosePack() {
        FileChooser picker = new FileChooser();
        picker.setTitle("选择整合包");
        picker.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Harness 整合包", "*.dspack", "*.zip"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File file = picker.showOpenDialog(window());
        if (file != null) run("previewPack", Map.of("path", file.getAbsolutePath()), "正在检查整合包…", preview -> showPackPreview(file, preview));
    }

    private void showPackPreview(File file, UiState preview) {
        Dialog<Map<String, String>> dialog = dialog("导入整合包 · 确认信息");
        dialog.getDialogPane().setPrefWidth(650);
        TextArea details = new TextArea(preview.packPreview());
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefRowCount(13);
        details.getStyleClass().add("preview-area");
        TextField name = new TextField(file.getName().replaceFirst("(?i)\\.(dspack|zip)$", ""));
        ComboBox<String> version = versionSelector("");
        version.setPromptText("留空使用包中声明的版本");
        Label error = label("", "validation-error");
        VBox body = new VBox(12, label(file.getName(), "card-title"), details,
                formField("新实例名称", name), formField("运行时版本覆盖（可选）", version), error);
        dialog.getDialogPane().setContent(body);
        ButtonType importType = new ButtonType("导入为新实例", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, importType);
        Button importButton = (Button) dialog.getDialogPane().lookupButton(importType);
        importButton.getStyleClass().add("primary-button");
        importButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (name.getText().isBlank()) { error.setText("请输入新实例名称。"); event.consume(); }
        });
        dialog.setResultConverter(type -> type == importType ? Map.of("path", file.getAbsolutePath(), "name", name.getText().trim(), "version", comboValue(version)) : null);
        dialog.showAndWait().ifPresent(args -> run("importPack", args, "正在导入整合包…", result -> navigate("instances")));
    }

    private void exportPack(UiState.InstanceItem current) {
        if (current == null) return;
        Dialog<String> scope = dialog("导出整合包 · 选择范围");
        scope.getDialogPane().setId("export-scope-dialog");
        ToggleGroup group = new ToggleGroup();
        RadioButton single = new RadioButton("当前 profile · " + current.profile());
        single.setId("export-single-profile");
        RadioButton home = new RadioButton("整个实例（所有自定义 profile）");
        home.setId("export-whole-instance");
        single.setToggleGroup(group);
        home.setToggleGroup(group);
        single.setSelected(true);
        scope.getDialogPane().setContent(new VBox(16, single, home,
                paragraph("整个实例按 PackForge 规范跳过 web / headless 基础 profile。没有自定义 profile 时，请选择导出当前 profile。"),
                paragraph("分享包不包含凭据、会话和可能含密钥的 patch 配置；导入后可能需要重新配置。")));
        ButtonType next = new ButtonType("选择保存位置", ButtonBar.ButtonData.OK_DONE);
        scope.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, next);
        scope.setResultConverter(type -> type == next ? (home.isSelected() ? "home" : "profile") : null);
        String mode = scope.showAndWait().orElse(null);
        if (mode == null) return;
        FileChooser picker = new FileChooser();
        picker.setTitle("导出 PackForge 整合包");
        picker.setInitialFileName(current.name().replaceAll("[\\\\/:*?\"<>|]", "_") + ".dspack");
        picker.getExtensionFilters().add(new FileChooser.ExtensionFilter("PackForge 整合包", "*.dspack"));
        File destination = picker.showSaveDialog(window());
        if (destination != null) run("exportPack", Map.of("id", current.id(), "path", destination.getAbsolutePath(), "mode", mode), "正在导出整合包…");
    }

    private ComboBox<UiState.InstanceItem> instanceSelector() {
        ComboBox<UiState.InstanceItem> combo = new ComboBox<>();
        combo.getItems().setAll(state.instances());
        combo.setConverter(new StringConverter<>() {
            @Override public String toString(UiState.InstanceItem item) { return item == null ? "选择实例" : item.name(); }
            @Override public UiState.InstanceItem fromString(String value) { return null; }
        });
        combo.setValue(state.currentInstance());
        combo.setPrefWidth(260);
        combo.disableProperty().bind(busy);
        combo.setOnAction(event -> {
            UiState.InstanceItem chosen = combo.getValue();
            if (chosen != null && !chosen.id().equals(state.currentInstanceId()))
                run("selectInstance", id(chosen), "正在切换实例…");
        });
        return combo;
    }

    private ComboBox<String> versionSelector(String value) {
        ComboBox<String> combo = new ComboBox<>();
        combo.setEditable(true);
        java.util.LinkedHashSet<String> choices = new java.util.LinkedHashSet<>();
        choices.addAll(state.runtimes().stream().filter(UiState.RuntimeItem::installed).map(UiState.RuntimeItem::version).toList());
        choices.addAll(state.availableRuntimeVersions());
        combo.getItems().setAll(choices);
        combo.setValue(value);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.getEditor().setText(value);
        return combo;
    }

    private static String comboValue(ComboBox<String> combo) {
        String text = combo.isEditable() ? combo.getEditor().getText() : combo.getValue();
        return text == null ? "" : text.trim();
    }

    private static boolean validProfileName(String value) {
        try {
            com.hdsl.core.InstanceStore.validateProfile(value);
            return true;
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    private void run(String action, Map<String, String> args, String message) {
        run(action, args, message, ignored -> {});
    }

    private void run(String action, Map<String, String> args, String message, Consumer<UiState> completion) {
        if (busy.get()) return;
        busyMessage = message;
        busy.set(true);
        footerStatus.setText(message);
        CompletableFuture<UiState> request;
        try {
            request = actions.dispatch(action, Map.copyOf(args));
            if (request == null) throw new IllegalStateException("启动器没有返回任务结果。");
        } catch (Throwable failure) {
            busy.set(false);
            showFailure(failure);
            return;
        }
        request.whenComplete((result, failure) -> Platform.runLater(() -> {
            busy.set(false);
            if (failure != null) {
                showFailure(failure);
            } else {
                render(result);
                completion.accept(result == null ? state : result);
            }
        }));
    }

    private void showFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && (cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException)) cause = cause.getCause();
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        footerStatus.setText("操作未完成 · " + shortStatus(message));
        Alert alert = new Alert(Alert.AlertType.ERROR);
        styleDialog(alert, "操作未完成");
        alert.setHeaderText("请检查提示后重试");
        alert.setContentText(message);
        TextArea details = new TextArea(message);
        details.setEditable(false);
        details.setWrapText(true);
        alert.getDialogPane().setExpandableContent(details);
        alert.show();
    }

    private boolean confirm(String title, String question, String detail) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        styleDialog(alert, title);
        alert.setHeaderText(question);
        alert.setContentText(detail);
        ButtonType yes = new ButtonType("确认", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(ButtonType.CANCEL, yes);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == yes;
    }

    private boolean require(String value, String message) {
        if (!value.isBlank()) return true;
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        styleDialog(alert, "补充信息");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.show();
        return false;
    }

    private <T> Dialog<T> dialog(String title) {
        Dialog<T> dialog = new Dialog<>();
        styleDialog(dialog, title);
        dialog.getDialogPane().setPrefWidth(490);
        return dialog;
    }

    private void styleDialog(Dialog<?> dialog, String title) {
        dialog.setTitle(title);
        if (window() != null) dialog.initOwner(window());
        for (String name : new String[]{"hmcl-blue.css", "hmcl-root.css", "launcher.css"}) {
            var stylesheet = ShellView.class.getResource(name);
            if (stylesheet != null) dialog.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());
        }
        dialog.getDialogPane().getStyleClass().add("launcher-dialog");
        dialog.setResizable(true);
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private Button button(String text, String symbol, String style, Runnable action) {
        Button button = new Button(text, icon(symbol));
        button.getStyleClass().add(style);
        button.setGraphicTextGap(8);
        button.disableProperty().bind(busy);
        button.setOnAction(event -> action.run());
        button.setMinHeight(36);
        return button;
    }

    private static Map<String, String> id(UiState.InstanceItem instance) {
        return Map.of("id", instance.id());
    }

    private static boolean isRunning(UiState.InstanceItem instance) {
        String status = instance.status().toLowerCase(Locale.ROOT);
        return status.equals("running") || status.equals("运行中") || status.equals("已启动") || status.equals("starting")
                || status.equals("启动中") || status.equals("进程运行中，服务未就绪") || status.equals("子进程仍在运行");
    }

    private static String displayStatus(UiState.InstanceItem instance) {
        return switch (instance.status().toLowerCase(Locale.ROOT)) {
            case "running" -> "运行中";
            case "starting" -> "启动中";
            case "stopped", "ready", "idle" -> "就绪";
            case "error", "failed" -> "需要检查";
            default -> instance.status().isBlank() ? "就绪" : instance.status();
        };
    }

    private static String shortStatus(String value) {
        if (value == null || value.isBlank()) return "就绪";
        String first = value.replace('\r', ' ').replace('\n', ' ').trim();
        return first.length() > 160 ? first.substring(0, 157) + "…" : first;
    }

    private static VBox pageBox() {
        VBox box = new VBox(10);
        box.getStyleClass().add("card-list");
        box.setPadding(new Insets(10));
        box.setFillWidth(true);
        return box;
    }

    private static ScrollPane scroll(Node node) {
        ScrollPane scroll = new ScrollPane(node);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("page-scroll");
        return scroll;
    }

    private static VBox card() {
        VBox card = new VBox(10);
        card.getStyleClass().addAll("card", "content-card");
        return card;
    }

    private static HBox toolbar(Node leading, Node... actions) {
        HBox toolbar = new HBox(10);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        toolbar.getChildren().addAll(leading, space);
        toolbar.getChildren().addAll(actions);
        return toolbar;
    }

    private static VBox formField(String caption, Node input) {
        return new VBox(8, label(caption, "field-label"), input);
    }

    private static VBox note(String title, String text) {
        VBox note = new VBox(8, label(title, "note-title"), paragraph(text));
        note.getStyleClass().add("note-card");
        return note;
    }

    private static VBox empty(String title, String description, String symbol) {
        VBox empty = new VBox(15, icon(symbol), label(title, "card-title"), paragraph(description));
        empty.setAlignment(Pos.CENTER);
        empty.setMinHeight(215);
        empty.getStyleClass().add("empty-card");
        return empty;
    }

    private static Label paragraph(String text) {
        Label label = label(text, "muted-label");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    private static Label badge(String text, String color) {
        Label badge = label(text, "badge");
        badge.getStyleClass().add("badge-" + color);
        return badge;
    }

    private static Node icon(String name) {
        // Small original line drawings share a 24 x 24 coordinate system.
        String drawing = switch (name) {
            case "home" -> "M3 10 L12 3 L21 10 M5 9 L5 21 L10 21 L10 14 L14 14 L14 21 L19 21 L19 9";
            case "user" -> "M8 7 A4 4 0 1 0 16 7 A4 4 0 1 0 8 7 M3 22 L3 19 C3 11 21 11 21 19 L21 22";
            case "layers" -> "M12 3 L22 8 L12 13 L2 8 Z M3 13 L12 18 L21 13 M3 18 L12 23 L21 18";
            case "box" -> "M3 6 L12 2 L21 6 L21 18 L12 23 L3 18 Z M3 6 L12 11 L21 6 M12 11 L12 23 M7 4 L17 9";
            case "cpu" -> "M6 6 L18 6 L18 18 L6 18 Z M9 9 L15 9 L15 15 L9 15 Z M9 2 L9 6 M15 2 L15 6 M9 18 L9 22 M15 18 L15 22 M2 9 L6 9 M2 15 L6 15 M18 9 L22 9 M18 15 L22 15";
            case "puzzle" -> "M4 3 L10 3 C8 8 16 8 14 3 L20 3 L20 9 C15 7 15 15 20 13 L20 20 L13 20 C15 15 7 15 9 20 L3 20 L3 13 C8 15 8 7 3 9 L3 3 Z";
            case "terminal" -> "M3 4 L21 4 L21 20 L3 20 Z M6 9 L10 12 L6 15 M13 16 L18 16";
            case "settings" -> "M4 6 L20 6 M4 12 L20 12 M4 18 L20 18 M8 3 L8 9 M16 9 L16 15 M10 15 L10 21";
            case "refresh" -> "M20 9 C18 3 8 2 4 8 M4 3 L4 8 L9 8 M4 15 C6 21 16 22 20 16 M20 21 L20 16 L15 16";
            case "plus" -> "M12 4 L12 20 M4 12 L20 12";
            case "play" -> "M7 3 L21 12 L7 21 Z";
            case "stop" -> "M5 5 L19 5 L19 19 L5 19 Z";
            case "download" -> "M12 3 L12 16 M7 11 L12 16 L17 11 M4 16 L4 21 L20 21 L20 16";
            case "upload" -> "M12 17 L12 4 M7 9 L12 4 L17 9 M4 16 L4 21 L20 21 L20 16";
            case "trash" -> "M4 6 L20 6 M9 3 L15 3 M6 6 L7 21 L17 21 L18 6 M10 10 L10 17 M14 10 L14 17";
            case "folder" -> "M2 6 L10 6 L12 9 L22 9 L20 21 L2 21 Z M2 6 L2 3 L10 3 L13 6 L20 6 L20 9";
            case "external" -> "M14 3 L21 3 L21 10 M21 3 L11 13 M10 5 L3 5 L3 21 L19 21 L19 14";
            case "copy" -> "M9 8 L21 8 L21 22 L9 22 Z M15 8 L15 2 L3 2 L3 16 L9 16";
            case "link" -> "M10 8 L14 4 C18 0 24 6 20 10 L16 14 M8 10 L4 14 C0 18 6 24 10 20 L14 16 M8 16 L16 8";
            default -> "M4 12 L9 17 L20 6";
        };
        SVGPath path = new SVGPath();
        path.setContent(drawing);
        path.setFill(Color.TRANSPARENT);
        path.getStyleClass().add("line-icon");
        path.setStrokeWidth(1.7);
        path.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        path.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        path.setScaleX(.73);
        path.setScaleY(.73);
        StackPane holder = new StackPane(path);
        holder.setMinSize(19, 19);
        holder.setPrefSize(19, 19);
        holder.setMaxSize(19, 19);
        holder.setMouseTransparent(true);
        return holder;
    }
}
