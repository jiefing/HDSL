/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
/* HDSL adaptation, 2026-10-02. HMCL 77eee17d361996259a48cc7896006a57d2e34a2a
 * Source: HMCL/src/main/java/org/jackhuang/hmcl/ui/main/RootPage.java
 * Changes: account/game repositories replaced by Harness instances and UiActions navigation;
 * retains the categorized AdvancedListBox and DecoratorAnimatedPage 200px sidebar layout.
 */
package com.hdsl.ui.hmcl;

import com.hdsl.ui.UiState;
import javafx.scene.Node;
import javafx.beans.property.BooleanProperty;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public final class RootPage extends DecoratorAnimatedPage {
    private final Map<String, AdvancedListItem> navigation = new LinkedHashMap<>();
    private final AdvancedListItem currentInstanceItem = new AdvancedListItem();
    private final AdvancedListItem accountItem;

    public RootPage(Node navigator, Consumer<String> navigate, Runnable editCurrent, Function<String, Node> icons, BooleanProperty busy) {
        getStyleClass().remove("gray-background");
        getLeft().getStyleClass().add("gray-background");

        accountItem = item("accounts", "账户管理", "user", navigate, icons);
        accountItem.setSubtitle("API 提供商与密钥");

        // HMCL's current game row keeps its position and opens the instance settings.
        currentInstanceItem.setId("current-instance-item");
        currentInstanceItem.setLeftIcon(icons.apply("box"));
        currentInstanceItem.setOnAction(event -> editCurrent.run());
        currentInstanceItem.disableProperty().bind(busy);

        AdvancedListItem gameItem = item("instances", "实例列表", "layers", navigate, icons);
        AdvancedListItem downloadItem = item("runtimes", "下载", "download", navigate, icons);
        AdvancedListItem packItem = item("packs", "整合包", "box", navigate, icons);
        AdvancedListItem pluginItem = item("plugins", "已装插件", "puzzle", navigate, icons);
        AdvancedListItem launcherSettingsItem = item("settings", "设置", "settings", navigate, icons);
        AdvancedListItem tasksItem = item("logs", "任务与日志", "terminal", navigate, icons);

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory("账户")
                .add(accountItem)
                .startCategory("实例")
                .add(currentInstanceItem)
                .add(gameItem)
                .add(downloadItem)
                .add(packItem)
                .add(pluginItem)
                .startCategory("通用")
                .add(launcherSettingsItem)
                .add(tasksItem);
        sideBar.setId("hmcl-sidebar");
        VBox.setVgrow(sideBar, Priority.ALWAYS);
        setLeft(sideBar);
        setCenter(navigator);
    }

    private AdvancedListItem item(String id, String title, String icon, Consumer<String> navigate, Function<String, Node> icons) {
        AdvancedListItem item = new AdvancedListItem();
        item.getStyleClass().add("nav-button");
        item.setId("nav-" + id);
        item.setTitle(title);
        item.setLeftIcon(icons.apply(icon));
        item.setOnAction(event -> navigate.accept(id));
        navigation.put(id, item);
        return item;
    }

    public void render(UiState state, String route) {
        UiState.InstanceItem instance = state.currentInstance();
        UiState.AccountItem account = instance == null ? null : state.accounts().stream()
                .filter(item -> item.id().equals(instance.accountId())).findFirst().orElse(null);
        accountItem.setTitle(account == null ? "账户管理" : account.name());
        accountItem.setSubtitle(account == null ? "已保存 " + state.accounts().size() + " 个账户" : account.provider());
        currentInstanceItem.setTitle(instance == null ? "未选择实例" : instance.name());
        currentInstanceItem.setSubtitle(instance == null ? "点击创建第一个实例" : instance.version() + " · " + instance.profile() + " · " + instance.status());
        navigation.forEach((id, item) -> item.setActive(id.equals(route)));
    }
}
