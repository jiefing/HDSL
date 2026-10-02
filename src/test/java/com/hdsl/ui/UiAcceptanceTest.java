package com.hdsl.ui;

import javafx.application.Platform;
import com.hdsl.ui.hmcl.AdvancedListItem;
import com.hdsl.ui.hmcl.AdvancedListBox;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.PickResult;
import javafx.stage.WindowEvent;
import javafx.stage.StageStyle;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in desktop acceptance test. All actions use an in-memory fixture: no Harness,
 * network, user workspace, browser, global input, or clipboard interaction occurs.
 * Run with -Dhdsl.uiTest=true -Dhdsl.buildDirectory=target-ui -Dtest=UiAcceptanceTest.
 */
@EnabledIfSystemProperty(named = "hdsl.uiTest", matches = "true")
final class UiAcceptanceTest {
    private static Stage stage;
    private static Scene scene;
    private static ShellView shell;
    private static FixtureActions fixture;
    private static Path screenshots;

    @BeforeAll
    static void startDesktopFixture() throws Exception {
        screenshots = Path.of(System.getProperty("hdsl.buildDirectory", "target-ui"), "ui-screens").toAbsolutePath();
        Files.createDirectories(screenshots);
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(15, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        onFx(() -> {
            fixture = new FixtureActions();
            shell = new ShellView(fixture);
            scene = new Scene(shell.view(), 1180, 760);
            stage = new Stage();
            stage.setTitle("HDSL UI 验收 · 隔离测试数据");
            stage.setScene(scene);
            stage.setMinWidth(960);
            stage.setMinHeight(650);
            shell.configureStage(stage);
            stage.show();
            shell.render(fixture.state);
            layout();
            return null;
        });
        if (BackgroundCatalog.imageLocation("").isPresent()) awaitBackgroundLoaded();
    }

    @AfterAll
    static void closeFixture() throws Exception {
        if (stage != null) onFx(() -> {
            // Close test-owned windows only, including any dialog left by a failed assertion.
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window == stage || window instanceof Stage s && s.getOwner() == stage) window.hide();
            }
            return null;
        });
        Platform.exit();
    }

    @Test
    @Timeout(90)
    void navigationDialogsPollingAndCancellationRemainUsable() throws Exception {
        String[] pages = {"home", "accounts", "instances", "packs", "runtimes", "plugins", "logs", "settings"};
        for (int index = 0; index < pages.length; index++) {
            String route = pages[index];
            onFx(() -> {
                navigate(route);
                layout();
                assertNotNull(scene.lookup("#page-" + route), "Navigation failed: " + route);
                assertEquals(7, scene.getRoot().lookupAll(".nav-button").size());
                assertInsideScene(scene.lookup("#nav-settings"));
                assertInsideScene(scene.lookup("#task-status"));
                if (route.equals("home")) assertInsideScene(scene.getRoot().lookup(".launch-button"));
                return null;
            });
            screenshot(String.format("%02d-%s.png", index + 1, route));
        }

        // A 1.5-second backend refresh must not discard text that has not been saved.
        onFx(() -> {
            TextField proxy = (TextField) scene.lookup("#settings-proxy");
            proxy.setText("http://127.0.0.1:8888");
            shell.render(fixture.withStatus("后台状态刷新（验收数据）"));
            assertSame(proxy, scene.lookup("#settings-proxy"));
            assertEquals("http://127.0.0.1:8888", proxy.getText());
            return null;
        });
        screenshot("08-settings-unsaved-polling.png");

        // showAndWait starts a nested event loop, so opening is deliberately queued separately.
        onFx(() -> { ((AdvancedListItem) scene.lookup("#nav-instances")).fire(); layout(); return null; });
        Platform.runLater(() -> findButton(scene.getRoot(), "创建实例").fire());
        await(() -> onFxUnchecked(() -> instanceDialog() != null), "Instance dialog did not open");
        onFx(() -> {
            DialogPane dialog = instanceDialog();
            assertNotNull(dialog);
            ((Button) dialog.lookup("#instance-save")).fire();
            assertEquals("请输入实例名称。", ((Label) dialog.lookup("#instance-validation")).getText());
            assertTrue(fixture.calls.stream().noneMatch(call -> call.action().equals("createInstance")), "Invalid form dispatched an action");
            ((TextField) dialog.lookup("#instance-name")).setText("新版本兼容验收实例");
            @SuppressWarnings("unchecked") ComboBox<String> versions = (ComboBox<String>) dialog.lookup("#instance-version");
            assertTrue(versions.isEditable(), "Future exact versions must remain editable");
            versions.getEditor().setText("9.88.77-future.1");
            @SuppressWarnings("unchecked") ComboBox<String> initialProfiles = (ComboBox<String>) dialog.lookup("#instance-profile");
            assertFalse(initialProfiles.isEditable(), "New instances must not offer uninitialized custom profiles");
            assertEquals(List.of("web"), List.copyOf(initialProfiles.getItems()));
            TextField port = (TextField) dialog.lookup("#instance-port");
            port.setText("22");
            ((Button) dialog.lookup("#instance-save")).fire();
            assertEquals("端口必须在 1024–65535 之间。", ((Label) dialog.lookup("#instance-validation")).getText());
            dialog.applyCss();
            dialog.layout();
            ImageIO.write(SwingFXUtils.fromFXImage(dialog.snapshot(null, null), null), "png", screenshots.resolve("09-create-dialog.png").toFile());
            port.clear();
            ((Button) dialog.lookup("#instance-save")).fire();
            return null;
        });
        await(() -> fixture.calls.stream().anyMatch(call -> call.action().equals("createInstance")), "Valid instance was not dispatched");
        RecordedCall created = fixture.calls.stream().filter(call -> call.action().equals("createInstance")).findFirst().orElseThrow();
        assertEquals("", created.arguments().get("port"), "Blank port should delegate allocation to the backend");
        assertEquals("web", created.arguments().get("profile"));
        assertEquals("9.88.77-future.1", created.arguments().get("version"));
        await(() -> onFxUnchecked(() -> instanceDialog() == null), "Valid dialog did not close");

        // Imported profile names may contain Unicode and spaces; editing must share backend validation.
        onFx(() -> { layout(); return null; });
        Platform.runLater(() -> {
            MenuButton more = (MenuButton) scene.lookup("#instance-more-ui-main");
            more.getItems().stream().filter(item -> "编辑实例".equals(item.getText())).findFirst().orElseThrow().fire();
        });
        await(() -> onFxUnchecked(() -> instanceDialog() != null), "Edit instance dialog did not open");
        onFx(() -> {
            DialogPane dialog = instanceDialog();
            @SuppressWarnings("unchecked") ComboBox<String> profiles = (ComboBox<String>) dialog.lookup("#instance-profile");
            assertFalse(profiles.isEditable(), "Profile editing must select an existing profile");
            assertTrue(profiles.getItems().contains("研究 工作区"), "Existing Unicode profile was not offered");
            profiles.setValue("研究 工作区");
            ((Button) dialog.lookup("#instance-save")).fire();
            return null;
        });
        await(() -> fixture.calls.stream().anyMatch(call -> call.action().equals("saveInstance")), "Unicode profile edit was not dispatched");
        RecordedCall saved = fixture.calls.stream().filter(call -> call.action().equals("saveInstance")).findFirst().orElseThrow();
        assertEquals("ui-main", saved.arguments().get("id"));
        assertEquals("研究 工作区", saved.arguments().get("profile"));
        await(() -> onFxUnchecked(() -> instanceDialog() == null), "Unicode profile edit did not close");
        screenshot("16-unicode-profile-edited.png");

        // First launch is visibly a download operation, with live stages and a usable task link.
        double regularWidth = onFx(stage::getWidth);
        double regularHeight = onFx(stage::getHeight);
        onFx(() -> {
            navigate("home");
            layout();
            Button downloadAndLaunch = findButton(scene.getRoot(), "下载并启动");
            assertTrue(((Label) scene.lookup("#first-launch-hint")).getText().contains("下载 Harness 和依赖"));
            downloadAndLaunch.fire();
            assertNotNull(fixture.pendingLaunch);
            assertTrue(scene.lookup("#home-task-panel").isVisible());
            assertTrue(((Label) scene.lookup("#home-task-phase")).getText().contains("首次启动"));
            assertFalse(scene.lookup("#home-view-task").isDisabled());
            fixture.state = fixture.withInstanceStatus("下载中");
            fixture.state = fixture.withStatus("进行中：下载与安装依赖 · 已解析600，复用0，已下载528，已写入528");
            shell.render(fixture.state);
            layout();
            String phase = ((Label) scene.lookup("#home-task-phase")).getText();
            assertTrue(phase.contains("已解析600") && phase.contains("已下载528"), "Polling must update the live stage without rebuilding the page");
            assertFalse(phase.contains("%"), "Dependency counters are not a download percentage");
            return null;
        });
        await(() -> onFxUnchecked(() -> !((Label) scene.lookup("#home-task-elapsed")).getText().endsWith("00:00")), "Elapsed time did not advance");
        screenshot("17-first-launch-progress.png");
        onFx(() -> { stage.setWidth(960); stage.setHeight(650); layout(); return null; });
        await(() -> onFxUnchecked(() -> scene.getWidth() <= 960 && scene.getHeight() <= 650), "Task test window did not resize to its minimum size");
        onFx(() -> {
            layout();
            assertInsideScene(scene.lookup("#home-task-panel"));
            assertInsideScene(scene.lookup("#home-view-task"));
            assertInsideScene(scene.getRoot().lookup(".launch-button"));
            return null;
        });
        screenshot("18-first-launch-progress-minimum.png");
        onFx(() -> {
            ((Button) scene.lookup("#home-view-task")).fire();
            layout();
            assertNotNull(scene.lookup("#page-logs"));
            assertFalse(scene.lookup("#cancel-task").isDisabled());
            ((Button) scene.lookup("#cancel-task")).fire();
            return null;
        });
        await(() -> onFxUnchecked(() -> !scene.lookup("#task-progress").isVisible()), "First-launch cancellation did not finish");
        onFx(() -> { stage.setWidth(regularWidth); stage.setHeight(regularHeight); layout(); return null; });
        await(() -> onFxUnchecked(() -> scene.getWidth() >= 1100), "Task test window did not restore its normal size");

        // Hold a fake installation open to verify navigation, progress, and task cancellation.
        onFx(() -> {
            ((AdvancedListItem) scene.lookup("#nav-runtimes")).fire();
            layout();
            @SuppressWarnings("unchecked") ComboBox<String> versions = (ComboBox<String>) scene.lookup("#runtime-version");
            versions.getEditor().setText("9.88.77-future.1");
            findButton(scene.getRoot(), "安装").fire();
            assertNotNull(fixture.pendingInstall);
            assertTrue(scene.lookup("#task-progress").isVisible());
            AdvancedListItem logs = (AdvancedListItem) scene.lookup("#nav-logs");
            assertFalse(logs.isDisabled(), "Navigation was disabled during installation");
            logs.fire();
            layout();
            assertFalse(scene.lookup("#cancel-task").isDisabled());
            assertFalse(findButton(scene.getRoot(), "复制日志").isDisabled(), "Copy should remain available without invoking the clipboard in this test");
            layout();
            return null;
        });
        screenshot("10-task-in-progress.png");
        onFx(() -> { ((Button) scene.lookup("#cancel-task")).fire(); return null; });
        await(() -> onFxUnchecked(() -> !scene.lookup("#task-progress").isVisible()), "Cancel did not clear the pending progress state");
        assertTrue(fixture.calls.stream().anyMatch(call -> call.action().equals("cancelTask")));
        onFx(() -> {
            assertTrue(scene.lookup("#cancel-task").isDisabled());
            assertTrue(((TextArea) scene.lookup(".log-area")).getText().contains("取消"));
            return null;
        });
        screenshot("11-task-cancelled.png");

        // The export scope step is reachable without opening a native picker or writing a package.
        onFx(() -> { ((AdvancedListItem) scene.lookup("#nav-packs")).fire(); layout(); return null; });
        Platform.runLater(() -> findButton(scene.getRoot(), "导出 .dspack").fire());
        await(() -> onFxUnchecked(() -> findDialog("export-scope-dialog") != null), "Export scope chooser did not open");
        onFx(() -> {
            DialogPane scope = findDialog("export-scope-dialog");
            RadioButton single = (RadioButton) scope.lookup("#export-single-profile");
            RadioButton home = (RadioButton) scope.lookup("#export-whole-instance");
            assertTrue(single.isSelected());
            home.fire();
            assertTrue(home.isSelected());
            assertFalse(single.isSelected());
            scope.applyCss();
            scope.layout();
            ImageIO.write(SwingFXUtils.fromFXImage(scope.snapshot(null, null), null), "png", screenshots.resolve("14-export-scope-dialog.png").toFile());
            ((Button) scope.lookupButton(ButtonType.CANCEL)).fire();
            assertTrue(fixture.calls.stream().noneMatch(call -> call.action().equals("exportPack")));
            return null;
        });

        // Every backend status with an owned live process must expose Stop instead of a second Launch.
        onFx(() -> { navigate("home"); return null; });
        for (String status : List.of("运行中", "启动中", "进程运行中，服务未就绪", "子进程仍在运行")) {
            onFx(() -> {
                fixture.state = fixture.withInstanceStatus(status);
                shell.render(fixture.state);
                layout();
                assertNotNull(findButton(scene.getRoot(), "停止"));
                assertNotNull(findButton(scene.getRoot(), "打开 Web 界面"));
                assertTrue(scene.getRoot().lookupAll(".button").stream().filter(Button.class::isInstance)
                        .map(Button.class::cast).noneMatch(button -> "启动实例".equals(button.getText())), "Live process received Launch button: " + status);
                return null;
            });
        }
        screenshot("15-owned-child-running.png");
        onFx(() -> { findButton(scene.getRoot(), "停止").fire(); return null; });
        await(() -> fixture.calls.stream().anyMatch(call -> call.action().equals("stop")), "Stop action was not dispatched");
        assertEquals(fixture.state.currentInstanceId(), fixture.calls.stream().filter(call -> call.action().equals("stop")).findFirst().orElseThrow().arguments().get("id"));

        // A smaller supported window must keep navigation and home actions reachable.
        onFx(() -> {
            navigate("home");
            stage.setWidth(960);
            stage.setHeight(650);
            layout();
            return null;
        });
        await(() -> onFxUnchecked(() -> scene.getWidth() <= 960 && scene.getHeight() <= 650), "Home test window did not resize to its minimum size");
        onFx(() -> {
            layout();
            assertInsideScene(scene.lookup("#nav-settings"));
            Node launch = scene.getRoot().lookup(".launch-button");
            assertInsideScene(launch);
            assertTrue(launch.localToScene(launch.getBoundsInLocal()).getMaxY()
                    < scene.getHeight(),
                    "Launch action must stay inside the window without scrolling");
            return null;
        });
        screenshot("12-home-minimum-window.png");
        onFx(() -> {
            shell.render(new UiState("", fixture.state.settings(), List.of(), fixture.state.runtimes(), List.of(),
                    fixture.state.availableRuntimeVersions(), "", fixture.state.logs(), "空工作空间 UI 验收"));
            layout();
            Node create = scene.getRoot().lookup(".launch-button");
            assertEquals("创建实例", ((Button) create).getText());
            assertInsideScene(create);
            assertTrue(create.localToScene(create.getBoundsInLocal()).getMaxY()
                    < scene.getHeight());
            return null;
        });
        screenshot("13-empty-minimum-window.png");
        verifyWindowDecoration();

        // Copyright and both license texts are available without network or filesystem writes.
        onFx(() -> {
            ((AdvancedListItem) scene.lookup("#nav-settings")).fire();
            layout();
            assertNotNull(scene.lookup("#about-hmcl"));
            assertTrue(scene.getRoot().lookupAll(".label").stream().filter(Label.class::isInstance)
                    .map(Label.class::cast).anyMatch(label -> label.getText().contains("版权所有 © 2013-2026 huangyuhui 及贡献者")));
            return null;
        });
        Platform.runLater(() -> findButton(scene.getRoot(), "查看 GPL 许可").fire());
        await(() -> onFxUnchecked(() -> findDialog("license-dialog") != null), "License viewer did not open");
        onFx(() -> {
            DialogPane dialog = findDialog("license-dialog");
            TabPane tabs = (TabPane) dialog.getContent();
            assertEquals(2, tabs.getTabs().size());
            assertTrue(((TextArea) tabs.getTabs().getFirst().getContent()).getText().contains("GNU GENERAL PUBLIC LICENSE"));
            assertTrue(((TextArea) tabs.getTabs().get(1).getContent()).getText().contains("HMCL"));
            ((Button) dialog.lookupButton(ButtonType.CLOSE)).fire();
            return null;
        });
        verifyAutomaticVersionRefresh();
        verifyBuiltInBackgrounds();
        verifyAccountsAndDownloads();
        Files.writeString(screenshots.resolve("README.txt"), "HDSL JavaFX UI acceptance screenshots\n"
                + "All data is an in-memory fixture; no Harness process, network, browser, clipboard, or user workspace was used.\n"
                + "Validated: seven navigation pages, create-dialog required name and port range, blank automatic port, editable future version,\n"
                + "unsaved settings across polling, navigation and copy availability during work, progress and cancellation, minimum window,\n"
                + "HMCL dimensions and disabled navigation, drag, edge resize, double-click maximize/restore, close request, copyright and license viewer,\n"
                + "automatic version lookup with delayed/failed replies, dismissed form, five built-in backgrounds and legacy custom path.\n");
    }

    private static void navigate(String route) {
        if (route.equals("home")) ((Button) scene.lookup("#window-home")).fire();
        else ((AdvancedListItem) scene.lookup("#nav-" + route)).fire();
    }

    private static void verifyAccountsAndDownloads() throws Exception {
        onFx(() -> { navigate("accounts"); layout(); return null; });
        Platform.runLater(() -> ((Button) scene.lookup("#account-create")).fire());
        await(() -> onFxUnchecked(() -> findDialog("account-dialog") != null), "Account dialog did not open");
        PasswordField[] keyField = new PasswordField[1];
        onFx(() -> {
            DialogPane dialog = findDialog("account-dialog");
            ((Button) dialog.lookup("#account-save")).fire();
            assertEquals("请输入账户名称。", ((Label) dialog.lookup("#account-validation")).getText());
            ((TextField) dialog.lookup("#account-name")).setText("研究账户（隔离验收）");
            @SuppressWarnings("unchecked") ComboBox<String> providers = (ComboBox<String>) dialog.lookup("#account-provider");
            assertTrue(providers.isEditable());
            providers.setValue("openai");
            assertEquals("", ((TextField) dialog.lookup("#account-model")).getText(), "Must not invent a model the user may not have access to");
            providers.setValue("deepseek-official");
            assertEquals("deepseek-flash", ((TextField) dialog.lookup("#account-model")).getText());
            keyField[0] = (PasswordField) dialog.lookup("#account-key");
            keyField[0].setText("ui-fixture-not-a-real-key");
            dialog.applyCss(); dialog.layout();
            ImageIO.write(SwingFXUtils.fromFXImage(dialog.snapshot(null, null), null), "png", screenshots.resolve("23-account-dialog.png").toFile());
            ((Button) dialog.lookup("#account-save")).fire();
            return null;
        });
        await(() -> fixture.calls.stream().anyMatch(call -> call.action().equals("saveAccount")), "Account save action was not dispatched");
        onFx(() -> {
            assertTrue(keyField[0].getText().isEmpty(), "PasswordField must be cleared on submit");
            assertFalse(fixture.state.toString().contains("ui-fixture-not-a-real-key"));
            assertEquals("ui-fixture-not-a-real-key", fixture.calls.stream().filter(call -> call.action().equals("saveAccount")).findFirst().orElseThrow().arguments().get("apiKey"));
            @SuppressWarnings("unchecked") ComboBox<Object> binding = (ComboBox<Object>) scene.lookup("#account-binding");
            binding.getSelectionModel().select(1);
            ((Button) scene.lookup("#account-bind")).fire();
            return null;
        });
        await(() -> fixture.calls.stream().anyMatch(call -> call.action().equals("bindAccount")), "Binding was not saved");
        assertEquals("fixture-account", fixture.calls.stream().filter(call -> call.action().equals("bindAccount")).findFirst().orElseThrow().arguments().get("accountId"));
        screenshot("24-accounts.png");
        Platform.runLater(() -> ((Button) scene.lookup("#account-edit-fixture-account")).fire());
        await(() -> onFxUnchecked(() -> findDialog("account-dialog") != null), "Account edit did not open");
        onFx(() -> {
            DialogPane dialog = findDialog("account-dialog");
            assertEquals("", ((PasswordField) dialog.lookup("#account-key")).getText());
            ((Button) dialog.lookup("#account-save")).fire();
            return null;
        });
        await(() -> fixture.calls.stream().filter(call -> call.action().equals("saveAccount")).count() == 2, "Account edit did not dispatch");
        assertEquals("", fixture.calls.stream().filter(call -> call.action().equals("saveAccount")).toList().get(1).arguments().get("apiKey"));
        Platform.runLater(() -> ((Button) scene.lookup("#account-edit-fixture-account")).fire());
        await(() -> onFxUnchecked(() -> findDialog("account-dialog") != null), "Account cancellation dialog missing");
        onFx(() -> {
            DialogPane dialog = findDialog("account-dialog");
            PasswordField cancelled = (PasswordField) dialog.lookup("#account-key");
            cancelled.setText("discarded-fixture-key");
            ((Button) dialog.lookupButton(ButtonType.CANCEL)).fire();
            assertEquals("", cancelled.getText(), "Cancel must clear secret input");
            assertEquals(2, fixture.calls.stream().filter(call -> call.action().equals("saveAccount")).count());
            return null;
        });

        onFx(() -> { navigate("instances"); layout(); return null; });
        Platform.runLater(() -> findButton(scene.getRoot(), "创建实例").fire());
        await(() -> onFxUnchecked(() -> instanceDialog() != null), "Create dialog missing account selector");
        onFx(() -> {
            DialogPane dialog = instanceDialog();
            @SuppressWarnings("unchecked") ComboBox<Object> accounts = (ComboBox<Object>) dialog.lookup("#instance-account");
            assertEquals(2, accounts.getItems().size());
            ((Button) dialog.lookupButton(ButtonType.CANCEL)).fire();
            navigate("runtimes");
            ((TabPane) scene.lookup("#download-tabs")).getSelectionModel().select(1); layout();
            ((Button) scene.lookup("#desktop-refresh")).fire();
            return null;
        });
        await(() -> fixture.calls.stream().anyMatch(call -> call.action().equals("refreshDesktop")), "Desktop refresh missing");
        onFx(() -> {
            layout();
            assertEquals(1, ((TabPane) scene.lookup("#download-tabs")).getSelectionModel().getSelectedIndex());
            assertEquals("deepseek-ai/deepseek-harness", fixture.calls.stream().filter(call -> call.action().equals("refreshDesktop")).findFirst().orElseThrow().arguments().get("source"));
            layout(); assertInsideScene(scene.lookup("#desktop-refresh"));
            ((Button) scene.lookup("#desktop-download-fixture-asset")).fire();
            return null;
        });
        await(() -> fixture.calls.stream().anyMatch(call -> call.action().equals("downloadDesktop")), "Desktop package download missing");
        screenshot("25-download-desktop.png");
        onFx(() -> {
            assertEquals("打开下载文件夹", ((Button) scene.lookup("#desktop-download-fixture-asset")).getText());
            ((TabPane) scene.lookup("#download-tabs")).getSelectionModel().select(2); layout();
            ((TextField) scene.lookup("#catalog-query")).setText("fixture");
            ((Button) scene.lookup("#catalog-search")).fire();
            return null;
        });
        await(() -> fixture.calls.stream().anyMatch(call -> call.action().equals("searchPluginCatalog")), "Catalog search missing");
        onFx(() -> {
            layout();
            assertEquals(2, ((TabPane) scene.lookup("#download-tabs")).getSelectionModel().getSelectedIndex());
            assertNull(scene.lookup("#catalog-install-fixture-project"), "Uninspected topic repo must not be installable");
            ((Button) scene.lookup("#catalog-inspect-fixture-project")).fire();
            return null;
        });
        await(() -> fixture.calls.stream().anyMatch(call -> call.action().equals("inspectPluginProject")), "Catalog inspect missing");
        screenshot("26-download-plugins.png");
        Platform.runLater(() -> ((Button) scene.lookup("#catalog-install-fixture-project")).fire());
        await(() -> onFxUnchecked(() -> findDialog("catalog-install-dialog") != null), "Catalog installation confirmation missing");
        onFx(() -> {
            DialogPane dialog = findDialog("catalog-install-dialog");
            @SuppressWarnings("unchecked") ComboBox<Object> targets = (ComboBox<Object>) dialog.lookup("#catalog-install-target");
            assertEquals(2, targets.getItems().size()); targets.getSelectionModel().select(1);
            @SuppressWarnings("unchecked") ComboBox<Object> instances = (ComboBox<Object>) dialog.lookup("#catalog-install-instance");
            instances.getSelectionModel().select(0);
            dialog.applyCss(); dialog.layout();
            ImageIO.write(SwingFXUtils.fromFXImage(dialog.snapshot(null, null), null), "png", screenshots.resolve("27-plugin-confirm.png").toFile());
            ((Button) dialog.lookup("#catalog-confirm-install")).fire();
            return null;
        });
        await(() -> fixture.calls.stream().anyMatch(call -> call.action().equals("installCatalogPlugin")), "Verified target install action missing");
        RecordedCall installed = fixture.calls.stream().filter(call -> call.action().equals("installCatalogPlugin")).findFirst().orElseThrow();
        assertEquals(Map.of("id", "ui-main", "repo", "fixture/project", "spec", "@fixture/second@2.0.0"), installed.arguments());
        onFx(() -> {
            ((Button) scene.lookup("#catalog-inspect-fixture-docs-only")).fire();
            return null;
        });
        await(() -> fixture.state.pluginDetails().status().equals("VIEW_PROJECT"), "Docs-only result missing");
        onFx(() -> {
            assertNull(scene.lookup("#catalog-install-fixture-docs-only"));
            stage.setWidth(960); stage.setHeight(650); layout();
            return null;
        });
        screenshot("28-download-minimum.png");
        onFx(() -> { navigate("accounts"); layout(); return null; });
        screenshot("29-accounts-minimum.png");
    }

    private static void verifyAutomaticVersionRefresh() throws Exception {
        onFx(() -> {
            shell.render(fixture.state);
            ((AdvancedListItem) scene.lookup("#nav-instances")).fire();
            fixture.holdVersionRequests = true;
            layout();
            return null;
        });
        int start = fixture.versionRequests.size();
        Platform.runLater(() -> findButton(scene.getRoot(), "创建实例").fire());
        await(() -> fixture.versionRequests.size() == start + 1, "Opening create form did not automatically query versions exactly once");
        onFx(() -> {
            DialogPane dialog = instanceDialog();
            assertTrue(dialog.lookup("#instance-refresh-versions").isDisabled());
            assertFalse(dialog.lookup("#instance-save").isDisabled(), "Version lookup must not block instance creation");
            assertFalse(scene.lookup("#task-progress").isVisible(), "Version lookup must not start a global UI task");
            ((TextField) dialog.lookup("#instance-name")).setText("等待网络时输入的名称");
            @SuppressWarnings("unchecked") ComboBox<String> version = (ComboBox<String>) dialog.lookup("#instance-version");
            version.getEditor().setText("8.7.6-manual.1");
            shell.render(fixture.withStatus("后台轮询仍正常"));
            return null;
        });
        snapshotDialog("20-create-version-pending.png");
        fixture.state = fixture.withVersions(List.of("9.9.9-online.1", "0.2.0-rc.2"));
        fixture.versionRequests.get(start).complete(fixture.state);
        await(() -> onFxUnchecked(() -> !instanceDialog().lookup("#instance-refresh-versions").isDisabled()), "Successful lookup did not finish locally");
        onFx(() -> {
            DialogPane dialog = instanceDialog();
            @SuppressWarnings("unchecked") ComboBox<String> version = (ComboBox<String>) dialog.lookup("#instance-version");
            assertTrue(version.getItems().contains("9.9.9-online.1"));
            assertEquals("8.7.6-manual.1", version.getEditor().getText(), "Async refresh replaced a manually entered version");
            assertEquals("等待网络时输入的名称", ((TextField) dialog.lookup("#instance-name")).getText());
            assertEquals(start + 1, fixture.versionRequests.size());
            ((Button) dialog.lookup("#instance-refresh-versions")).fire();
            return null;
        });
        await(() -> fixture.versionRequests.size() == start + 2, "Manual retry did not start");
        fixture.versionRequests.get(start + 1).completeExceptionally(new java.io.IOException("模拟网络失败"));
        await(() -> onFxUnchecked(() -> ((Label) instanceDialog().lookup("#instance-version-status")).getText().contains("获取失败")), "Network failure was not shown in the form");
        snapshotDialog("21-create-version-retry-failed.png");
        int previousCreates = (int) fixture.calls.stream().filter(call -> call.action().equals("createInstance")).count();
        onFx(() -> {
            DialogPane dialog = instanceDialog();
            @SuppressWarnings("unchecked") ComboBox<String> version = (ComboBox<String>) dialog.lookup("#instance-version");
            assertTrue(version.getItems().contains("9.9.9-online.1"), "Failed lookup cleared cached versions");
            assertEquals("8.7.6-manual.1", version.getEditor().getText());
            assertFalse(dialog.lookup("#instance-save").isDisabled());
            ((Button) dialog.lookup("#instance-save")).fire();
            return null;
        });
        await(() -> fixture.calls.stream().filter(call -> call.action().equals("createInstance")).count() == previousCreates + 1,
                "Manual version could not be submitted after a failed lookup");
        await(() -> onFxUnchecked(() -> instanceDialog() == null && !scene.lookup("#task-progress").isVisible()), "Created form did not close");

        // Late responses cannot mutate a dismissed dialog, and each new form requests once again.
        Platform.runLater(() -> findButton(scene.getRoot(), "创建实例").fire());
        await(() -> fixture.versionRequests.size() == start + 3, "Second create form did not query once");
        Label closedStatus = onFx(() -> {
            DialogPane dialog = instanceDialog();
            Label label = (Label) dialog.lookup("#instance-version-status");
            ((Button) dialog.lookupButton(ButtonType.CANCEL)).fire();
            return label;
        });
        String before = onFx(closedStatus::getText);
        fixture.versionRequests.get(start + 2).complete(fixture.state);
        onFx(() -> { assertEquals(before, closedStatus.getText()); return null; });

        // Saving a hand-entered version does not wait for the network request to finish.
        Platform.runLater(() -> findButton(scene.getRoot(), "创建实例").fire());
        await(() -> fixture.versionRequests.size() == start + 4, "Pending-create lookup did not start");
        int createsBeforePending = (int) fixture.calls.stream().filter(call -> call.action().equals("createInstance")).count();
        onFx(() -> {
            DialogPane dialog = instanceDialog();
            ((TextField) dialog.lookup("#instance-name")).setText("无需等待网络的实例");
            @SuppressWarnings("unchecked") ComboBox<String> version = (ComboBox<String>) dialog.lookup("#instance-version");
            version.getEditor().setText("7.7.7-manual.2");
            ((Button) dialog.lookup("#instance-save")).fire();
            return null;
        });
        await(() -> fixture.calls.stream().filter(call -> call.action().equals("createInstance")).count() == createsBeforePending + 1,
                "Create waited for the in-flight version query");
        assertFalse(fixture.versionRequests.get(start + 3).isDone());
        await(() -> onFxUnchecked(() -> instanceDialog() == null && !scene.lookup("#task-progress").isVisible()), "Pending-create form did not close");
        fixture.versionRequests.get(start + 3).complete(fixture.state);

        // Existing-instance editing offers only explicit refresh.
        int queriesBeforeEdit = fixture.versionRequests.size();
        Platform.runLater(() -> {
            MenuButton more = (MenuButton) scene.lookup("#instance-more-ui-main");
            more.getItems().stream().filter(item -> "编辑实例".equals(item.getText())).findFirst().orElseThrow().fire();
        });
        await(() -> onFxUnchecked(() -> instanceDialog() != null), "Existing form did not open");
        onFx(() -> {
            assertEquals(queriesBeforeEdit, fixture.versionRequests.size(), "Editing an existing instance unexpectedly queried versions");
            ((Button) instanceDialog().lookupButton(ButtonType.CANCEL)).fire();
            return null;
        });

        // With no local runtime or cached release, the first response supplies a usable default.
        UiState previous = fixture.state;
        int coldQuery = fixture.versionRequests.size();
        onFx(() -> {
            fixture.state = new UiState(previous.currentInstanceId(), previous.settings(), previous.instances(), List.of(), previous.plugins(),
                    List.of(), previous.packPreview(), previous.logs(), previous.status());
            shell.render(fixture.state);
            layout();
            return null;
        });
        Platform.runLater(() -> findButton(scene.getRoot(), "创建实例").fire());
        await(() -> fixture.versionRequests.size() == coldQuery + 1, "Cold-start form did not query releases");
        fixture.versionRequests.get(coldQuery).complete(fixture.withVersions(List.of("10.0.0-new.1", "9.9.9-online.1")));
        await(() -> onFxUnchecked(() -> !instanceDialog().lookup("#instance-refresh-versions").isDisabled()), "Cold-start lookup did not finish");
        onFx(() -> {
            @SuppressWarnings("unchecked") ComboBox<String> version = (ComboBox<String>) instanceDialog().lookup("#instance-version");
            assertEquals("10.0.0-new.1", version.getEditor().getText());
            ((Button) instanceDialog().lookupButton(ButtonType.CANCEL)).fire();
            fixture.state = previous;
            shell.render(previous);
            fixture.holdVersionRequests = false;
            return null;
        });
    }

    private static void verifyBuiltInBackgrounds() throws Exception {
        boolean requireImages = Boolean.parseBoolean(System.getProperty("hdsl.requireBuiltinBackgrounds", "true"));
        assertEquals(5, BackgroundCatalog.entries().size());
        assertEquals("builtin:whale-01", BackgroundCatalog.normalized(""));
        for (BackgroundCatalog.Entry entry : BackgroundCatalog.entries()) {
            if (requireImages) assertTrue(entry.resource().isPresent(), "Missing packaged background: " + entry.id());
            onFx(() -> {
                ((AdvancedListItem) scene.lookup("#nav-settings")).fire();
                layout();
                ToggleButton choice = (ToggleButton) scene.lookup("#background-" + entry.id());
                choice.fire();
                shell.render(fixture.withStatus("背景选择期间轮询"));
                assertTrue(choice.isSelected(), "Polling discarded the selected background");
                assertEquals("", ((TextField) scene.lookup("#settings-background")).getText());
                findButton(scene.getRoot(), "保存设置").fire();
                return null;
            });
            await(() -> fixture.state.settings().background().equals(entry.value()), "Built-in selection was not saved");
            await(() -> onFxUnchecked(() -> !scene.lookup("#task-progress").isVisible()), "Background save did not finish");
            onFx(() -> {
                navigate("home");
                assertEquals(entry.value(), fixture.calls.stream().filter(call -> call.action().equals("saveSettings")).reduce((a, b) -> b).orElseThrow().arguments().get("background"));
                return null;
            });
            if (requireImages) {
                awaitBackgroundLoaded();
                onFx(() -> {
                    Region image = (Region) scene.lookup("#launcher-background-image");
                    var backgroundImage = image.getBackground().getImages().getFirst();
                    var full = backgroundImage.getImage();
                    assertTrue(full.getUrl().contains(entry.id()));
                    assertTrue(full.getWidth() >= 1000 && full.getHeight() >= 600, "Background is not a full-size image");
                    assertFalse(backgroundImage.getSize().isCover(), "JavaFX cover=true would ignore the requested image position");
                    assertEquals(javafx.geometry.Side.RIGHT, backgroundImage.getPosition().getHorizontalSide());
                    assertTrue(backgroundImage.getSize().getWidth() + .01 >= image.getWidth());
                    assertTrue(backgroundImage.getSize().getHeight() + .01 >= image.getHeight());
                    return null;
                });
            }
            screenshot("background-" + entry.id() + ".png");
        }
        onFx(() -> { ((AdvancedListItem) scene.lookup("#nav-settings")).fire(); layout(); return null; });
        if (requireImages) await(() -> onFxUnchecked(() -> BackgroundCatalog.entries().stream().allMatch(entry -> {
            ImageView image = (ImageView) scene.lookup("#thumbnail-" + entry.id());
            return image.getImage() != null && image.getImage().getProgress() == 1 && !image.getImage().isError();
        })), "All five thumbnails must decode successfully");
        screenshot("19-backgrounds-settings.png");

        String custom = requireImages ? Path.of(BackgroundCatalog.entries().get(2).resource().orElseThrow().toURI()).toString()
                : "C:\\UI-fixture\\custom-whale.png";
        onFx(() -> {
            TextField path = (TextField) scene.lookup("#settings-background");
            path.setText(custom);
            shell.render(fixture.withStatus("自定义背景编辑期间轮询"));
            assertEquals(custom, path.getText());
            for (BackgroundCatalog.Entry entry : BackgroundCatalog.entries()) assertFalse(((ToggleButton) scene.lookup("#background-" + entry.id())).isSelected());
            findButton(scene.getRoot(), "保存设置").fire();
            return null;
        });
        await(() -> fixture.state.settings().background().equals(custom), "Legacy custom background path was changed during save");
        await(() -> onFxUnchecked(() -> !scene.lookup("#task-progress").isVisible()), "Custom background save did not finish");
        if (requireImages) awaitBackgroundLoaded();
        onFx(() -> {
            ((AdvancedListItem) scene.lookup("#nav-settings")).fire();
            layout();
            assertEquals(custom, ((TextField) scene.lookup("#settings-background")).getText());
            findButton(scene.getRoot(), "重置").fire();
            assertTrue(((ToggleButton) scene.lookup("#background-whale-01")).isSelected());
            findButton(scene.getRoot(), "保存设置").fire();
            return null;
        });
        await(() -> fixture.state.settings().background().isEmpty(), "Reset did not preserve the legacy blank-default setting");
        await(() -> onFxUnchecked(() -> !scene.lookup("#task-progress").isVisible()), "Default background save did not finish");
        if (requireImages) {
            awaitBackgroundLoaded();
            onFx(() -> {
                Region image = (Region) scene.lookup("#launcher-background-image");
                assertTrue(image.getBackground().getImages().getFirst().getImage().getUrl().contains("whale-01"));
                return null;
            });
        }
    }

    private static void awaitBackgroundLoaded() throws Exception {
        await(() -> onFxUnchecked(() -> {
            Region image = (Region) scene.lookup("#launcher-background-image");
            return image.isVisible() && image.getBackground() != null && !image.getBackground().getImages().isEmpty()
                    && image.getBackground().getImages().getFirst().getImage().getProgress() == 1
                    && !image.getBackground().getImages().getFirst().getImage().isError();
        }), "Launcher background did not decode");
    }

    private static void verifyWindowDecoration() throws Exception {
        onFx(() -> {
            assertEquals(StageStyle.UNDECORATED, stage.getStyle());
            assertEquals(40, scene.lookup("#window-title-bar").getLayoutBounds().getHeight(), .1);
            assertEquals(40, scene.lookup("#window-close").getLayoutBounds().getWidth(), .1);
            assertEquals(200, scene.lookup("#hmcl-sidebar").getLayoutBounds().getWidth(), .1);
            assertEquals(200, scene.lookup("#primary-launch").getLayoutBounds().getWidth(), .1);
            assertEquals(55, scene.lookup("#primary-launch").getLayoutBounds().getHeight(), .1);
            AdvancedListItem item = new AdvancedListItem();
            int[] activated = {0};
            item.setOnAction(event -> activated[0]++);
            item.setDisable(true);
            item.fire();
            assertEquals(0, activated[0]);
            AdvancedListBox list = new AdvancedListBox().add(item);
            assertEquals(0, list.indexOf(item));
            list.remove(item);
            assertEquals(-1, list.indexOf(item));

            Node titleBar = scene.lookup("#window-title-bar");
            double oldX = stage.getX(), oldY = stage.getY();
            mouse(titleBar, MouseEvent.MOUSE_PRESSED, 400, 20, oldX + 400, oldY + 20, 1, true);
            mouse(titleBar, MouseEvent.MOUSE_DRAGGED, 430, 45, oldX + 430, oldY + 45, 1, true);
            mouse(titleBar, MouseEvent.MOUSE_RELEASED, 430, 45, oldX + 430, oldY + 45, 1, false);
            assertEquals(oldX + 30, stage.getX(), 1);
            assertEquals(oldY + 25, stage.getY(), 1);

            // A top-right edge drag resizes the window even with custom chrome.
            double width = stage.getWidth(), height = stage.getHeight();
            Node pane = titleBar.getParent().getParent();
            mouse(pane, MouseEvent.MOUSE_PRESSED, width - 1, height - 1, stage.getX() + width - 1, stage.getY() + height - 1, 1, true);
            mouse(pane, MouseEvent.MOUSE_DRAGGED, width + 79, height + 39, stage.getX() + width + 79, stage.getY() + height + 39, 1, true);
            mouse(pane, MouseEvent.MOUSE_RELEASED, width + 79, height + 39, stage.getX() + width + 79, stage.getY() + height + 39, 1, false);
            assertEquals(width + 80, stage.getWidth(), 1);
            assertEquals(height + 40, stage.getHeight(), 1);
            int[] closes = {0};
            javafx.event.EventHandler<WindowEvent> intercept = event -> { closes[0]++; event.consume(); };
            stage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, intercept);
            ((Button) scene.lookup("#window-close")).fire();
            stage.removeEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, intercept);
            assertEquals(1, closes[0], "Close button must follow the controller's cleanup event path");
            assertTrue(stage.isShowing());
            mouse(titleBar, MouseEvent.MOUSE_CLICKED, 400, 20, stage.getX() + 400, stage.getY() + 20, 2, false);
            return null;
        });
        await(() -> onFxUnchecked(stage::isMaximized), "Title double-click did not maximize");
        onFx(() -> {
            mouse(scene.lookup("#window-title-bar"), MouseEvent.MOUSE_CLICKED, 400, 20, stage.getX() + 400, stage.getY() + 20, 2, false);
            return null;
        });
        await(() -> !onFxUnchecked(stage::isMaximized), "Title double-click did not restore");
    }

    private static void mouse(Node target, EventType<MouseEvent> type, double x, double y, double screenX, double screenY, int clicks, boolean down) {
        Event.fireEvent(target, new MouseEvent(type, x, y, screenX, screenY, MouseButton.PRIMARY, clicks,
                false, false, false, false, down, false, false, false, false, true, new PickResult(target, x, y)));
    }

    private static void assertInsideScene(Node node) {
        assertNotNull(node);
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        assertTrue(bounds.getMinX() >= -1 && bounds.getMinY() >= -1
                        && bounds.getMaxX() <= scene.getWidth() + 1 && bounds.getMaxY() <= scene.getHeight() + 1,
                () -> "Control falls outside the visible scene: " + node.getId() + " " + bounds);
    }

    private static Button findButton(Parent parent, String text) {
        return parent.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow(() -> new AssertionError("Button not found: " + text));
    }

    private static DialogPane instanceDialog() {
        return findDialog("instance-dialog");
    }

    private static DialogPane findDialog(String id) {
        for (Window window : Window.getWindows()) {
            if (window.getScene() == null) continue;
            Node found = window.getScene().lookup("#" + id);
            if (found instanceof DialogPane pane) return pane;
        }
        return null;
    }

    private static void layout() {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    private static void screenshot(String filename) throws Exception {
        onFx(() -> {
            layout();
            assertTrue(ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null), null), "png", screenshots.resolve(filename).toFile()));
            return null;
        });
    }

    private static void snapshotDialog(String filename) throws Exception {
        onFx(() -> {
            DialogPane dialog = instanceDialog();
            dialog.applyCss();
            dialog.layout();
            assertTrue(ImageIO.write(SwingFXUtils.fromFXImage(dialog.snapshot(null, null), null), "png", screenshots.resolve(filename).toFile()));
            return null;
        });
    }

    private static <T> T onFx(Callable<T> callable) throws Exception {
        if (Platform.isFxApplicationThread()) return callable.call();
        FutureTask<T> work = new FutureTask<>(callable);
        Platform.runLater(work);
        try { return work.get(15, TimeUnit.SECONDS); }
        catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) throw cause;
            if (exception.getCause() instanceof Error cause) throw cause;
            throw exception;
        }
    }

    private static <T> T onFxUnchecked(Callable<T> callable) {
        try { return onFx(callable); } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void await(BooleanSupplier condition, String failure) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail(failure);
    }

    private record RecordedCall(String action, Map<String, String> arguments) {}

    private static final class FixtureActions implements UiActions {
        private final List<RecordedCall> calls = new CopyOnWriteArrayList<>();
        private volatile CompletableFuture<UiState> pendingInstall;
        private volatile CompletableFuture<UiState> pendingLaunch;
        private volatile boolean holdVersionRequests;
        private final List<CompletableFuture<UiState>> versionRequests = new CopyOnWriteArrayList<>();
        private volatile UiState state = new UiState("ui-main", new UiState.Settings("http://127.0.0.1:7890", "https://registry.npmjs.org", ""),
                List.of(new UiState.InstanceItem("ui-main", "UI 验收工作空间", "0.2.0-rc.2", "web", 3080, "未启动", "C:\\UI-fixture\\instances\\ui-main", List.of("web", "research", "研究 工作区")),
                        new UiState.InstanceItem("ui-old", "旧版兼容验收", "0.1.0-rc.6", "web", 3081, "未启动", "C:\\UI-fixture\\instances\\ui-old", List.of("web"))),
                List.of(new UiState.RuntimeItem("0.2.0-rc.2", true, "验收数据 · 支持 profile、Web 端口与插件命令"),
                        new UiState.RuntimeItem("0.1.0-rc.6", true, "验收数据 · 支持旧版启动参数")),
                List.of(new UiState.PluginItem("@test/workspace-plugin", "1.2.3", "仅用于界面验收的插件条目", "隔离测试数据")),
                List.of("0.2.0-rc.2", "0.1.0-rc.6"), "", "[UI 验收] 使用内存 fixture；没有启动 Harness 或访问网络。", "就绪 · UI 验收数据");

        private UiState withStatus(String status) {
            return new UiState(state.currentInstanceId(), state.settings(), state.instances(), state.runtimes(), state.plugins(),
                    state.availableRuntimeVersions(), state.packPreview(), state.logs(), status, state.accounts(), state.desktop(), state.pluginCatalog(), state.pluginDetails());
        }

        private UiState withVersions(List<String> versions) {
            return new UiState(state.currentInstanceId(), state.settings(), state.instances(), state.runtimes(), state.plugins(),
                    versions, state.packPreview(), state.logs(), state.status(), state.accounts(), state.desktop(), state.pluginCatalog(), state.pluginDetails());
        }

        private UiState withInstanceStatus(String status) {
            List<UiState.InstanceItem> instances = state.instances().stream().map(instance -> instance.id().equals(state.currentInstanceId())
                    ? new UiState.InstanceItem(instance.id(), instance.name(), instance.version(), instance.profile(), instance.port(), status, instance.path(), instance.profiles()) : instance).toList();
            return new UiState(state.currentInstanceId(), state.settings(), instances, state.runtimes(), state.plugins(),
                    state.availableRuntimeVersions(), state.packPreview(), state.logs(), state.status(), state.accounts(), state.desktop(), state.pluginCatalog(), state.pluginDetails());
        }

        private void features(List<UiState.AccountItem> accounts, UiState.DesktopCatalog desktop, UiState.PluginCatalog catalog, UiState.PluginDetails details) {
            state = new UiState(state.currentInstanceId(), state.settings(), state.instances(), state.runtimes(), state.plugins(),
                    state.availableRuntimeVersions(), state.packPreview(), state.logs(), state.status(), accounts, desktop, catalog, details);
        }

        @Override public CompletableFuture<UiState> dispatch(String action, Map<String, String> arguments) {
            calls.add(new RecordedCall(action, Map.copyOf(arguments)));
            UiState before = state;
            switch (action) {
                case "saveAccount" -> features(List.of(new UiState.AccountItem("fixture-account", arguments.get("name"), arguments.get("provider"),
                        arguments.get("baseUrl"), arguments.get("model"), true, "已保存 · API 连接未验证")), state.desktop(), state.pluginCatalog(), state.pluginDetails());
                case "bindAccount" -> {
                    List<UiState.InstanceItem> instances = state.instances().stream().map(instance -> instance.id().equals(arguments.get("id"))
                            ? new UiState.InstanceItem(instance.id(), instance.name(), instance.version(), instance.profile(), instance.port(), instance.status(), instance.path(), instance.profiles(), arguments.get("accountId")) : instance).toList();
                    state = new UiState(state.currentInstanceId(), state.settings(), instances, state.runtimes(), state.plugins(), state.availableRuntimeVersions(), state.packPreview(), state.logs(), state.status(),
                            state.accounts(), state.desktop(), state.pluginCatalog(), state.pluginDetails());
                }
                case "refreshDesktop" -> features(state.accounts(), new UiState.DesktopCatalog(arguments.get("source"),
                        List.of(new UiState.DesktopItem("fixture-asset", arguments.get("source"), "0.4.0-fixture", "dsh-desktop-windows-x64.exe", 188743680,
                                "https://example.invalid/fixture.exe", "1234567890abcdef".repeat(4), "", false)), "来自 DeepSeek 官网 · 隔离验收数据"), state.pluginCatalog(), state.pluginDetails());
                case "downloadDesktop" -> {
                    var old = state.desktop().items().getFirst();
                    features(state.accounts(), new UiState.DesktopCatalog(state.desktop().source(), List.of(new UiState.DesktopItem(old.id(), old.source(), old.version(), old.name(), old.size(), old.url(), old.sha256(), "C:\\UI-fixture\\downloads\\fixture.exe", old.prerelease())), "安装包已下载；未执行。"), state.pluginCatalog(), state.pluginDetails());
                }
                case "searchPluginCatalog" -> features(state.accounts(), state.desktop(), new UiState.PluginCatalog(arguments.get("query"), Integer.parseInt(arguments.get("page")), 2, false, false, "",
                        List.of(new UiState.PluginCatalogItem("fixture/project", "验收插件项目", "包含两个已发布 npm 包的 workspace 项目。", 42, "https://example.invalid/project", "", "UNRESOLVED"),
                                new UiState.PluginCatalogItem("fixture/docs-only", "说明文档项目", "主题项目不一定有可安装的插件。", 3, "https://example.invalid/docs", "", "UNRESOLVED"))), null);
                case "inspectPluginProject" -> features(state.accounts(), state.desktop(), state.pluginCatalog(), arguments.get("repo").endsWith("docs-only")
                        ? new UiState.PluginDetails("fixture/docs-only", "VIEW_PROJECT", "没有找到已核对的安装目标，可查看项目文档。", false, List.of())
                        : new UiState.PluginDetails("fixture/project", "INSTALLABLE", "已核对 npm 发布信息与仓库中的插件声明。", false,
                        List.of(new UiState.InstallTarget("@fixture/first", "1.0.0", "@fixture/first@1.0.0", "packages/first", "https://example.invalid/first"),
                                new UiState.InstallTarget("@fixture/second", "2.0.0", "@fixture/second@2.0.0", "packages/second", "https://example.invalid/second"))));
                case "installCatalogPlugin" -> { }
                case "createInstance" -> {
                    List<UiState.InstanceItem> instances = new ArrayList<>(state.instances());
                    String id = "ui-new-" + instances.size();
                    instances.add(new UiState.InstanceItem(id, arguments.get("name"), arguments.get("version"), arguments.get("profile"),
                            arguments.get("port").isBlank() ? 3082 + instances.size() : Integer.parseInt(arguments.get("port")), "未启动", "C:\\UI-fixture\\instances\\" + id));
                    state = new UiState(id, state.settings(), instances, state.runtimes(), state.plugins(), state.availableRuntimeVersions(), "", state.logs(), "验收：创建已完成");
                }
                case "installRuntime" -> {
                    pendingInstall = new CompletableFuture<>();
                    return pendingInstall;
                }
                case "launch" -> {
                    pendingLaunch = new CompletableFuture<>();
                    return pendingLaunch;
                }
                case "saveInstance" -> {
                    List<UiState.InstanceItem> instances = state.instances().stream().map(instance -> instance.id().equals(arguments.get("id"))
                            ? new UiState.InstanceItem(instance.id(), arguments.get("name"), arguments.get("version"), arguments.get("profile"),
                            Integer.parseInt(arguments.get("port")), instance.status(), instance.path(), instance.profiles()) : instance).toList();
                    state = new UiState(state.currentInstanceId(), state.settings(), instances, state.runtimes(), state.plugins(), state.availableRuntimeVersions(), "", state.logs(), "验收：编辑已完成");
                }
                case "cancelTask" -> {
                    state = withInstanceStatus("未启动");
                    state = new UiState(state.currentInstanceId(), state.settings(), state.instances(), state.runtimes(), state.plugins(), state.availableRuntimeVersions(), "",
                            state.logs() + "\n[UI 验收] 已取消模拟安装。", "验收：任务已取消");
                    if (pendingInstall != null && !pendingInstall.isDone()) pendingInstall.complete(state);
                    if (pendingLaunch != null && !pendingLaunch.isDone()) pendingLaunch.complete(state);
                }
                case "refreshVersions" -> {
                    if (holdVersionRequests) {
                        CompletableFuture<UiState> future = new CompletableFuture<>();
                        versionRequests.add(future);
                        return future;
                    }
                }
                case "saveSettings" -> state = new UiState(state.currentInstanceId(), new UiState.Settings(arguments.get("proxy"), arguments.get("registry"), arguments.get("background")),
                        state.instances(), state.runtimes(), state.plugins(), state.availableRuntimeVersions(), state.packPreview(), state.logs(), "验收：设置已保存");
                case "refresh" -> { }
                case "stop" -> state = withInstanceStatus("未启动");
                default -> throw new AssertionError("Unexpected action in isolated UI test: " + action);
            }
            if (!List.of("saveAccount", "bindAccount", "refreshDesktop", "downloadDesktop", "searchPluginCatalog", "inspectPluginProject").contains(action))
                state = new UiState(state.currentInstanceId(), state.settings(), state.instances(), state.runtimes(), state.plugins(),
                        state.availableRuntimeVersions(), state.packPreview(), state.logs(), state.status(), before.accounts(), before.desktop(), before.pluginCatalog(), before.pluginDetails());
            return CompletableFuture.completedFuture(state);
        }
    }
}
