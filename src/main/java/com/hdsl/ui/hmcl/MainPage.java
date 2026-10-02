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
 * Source: HMCL/src/main/java/org/jackhuang/hmcl/ui/main/MainPage.java
 * Changes: launch/currentGame/update callbacks replaced with Harness instance actions and task status;
 * JFXButton/JFXPopup replaced with Button/ContextMenu. Retains the actual launch-pane structure,
 * bottom-right alignment, two-line button, secondary-click switcher and 200ms arrow rotation.
 */
package com.hdsl.ui.hmcl;

import com.hdsl.ui.UiState;
import javafx.animation.RotateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import java.util.function.Consumer;

public final class MainPage extends StackPane {
    public MainPage(UiState state, BooleanProperty busy, boolean installed, boolean running,
                    Node taskPane, Runnable launch, Runnable stop, Consumer<String> selectInstance) {
        getStyleClass().add("hmcl-main-page");
        setPadding(new Insets(20));
        UiState.InstanceItem currentGame = state.currentInstance();

        HBox launchPane = new HBox();
        launchPane.getStyleClass().add("launch-pane");
        launchPane.setId("hmcl-launch-pane");
        StackPane.setAlignment(launchPane, Pos.BOTTOM_RIGHT);
        {
            Button launchButton = new Button();
            launchButton.setId("primary-launch");
            launchButton.getStyleClass().addAll("jfx-button", "launch-button");
            launchButton.setDefaultButton(true);
            launchButton.disableProperty().bind(busy);
            {
                VBox graphic = new VBox();
                graphic.setAlignment(Pos.CENTER);
                Label launchLabel = new Label();
                launchLabel.setStyle("-fx-font-size: 16px;");
                Label currentLabel = new Label();
                currentLabel.setStyle("-fx-font-size: 12px;");
                currentLabel.setAlignment(Pos.CENTER);

                String action = currentGame == null ? "创建实例" : running ? "打开 Web 界面" : installed ? "启动实例" : "下载并启动";
                launchLabel.setText(action);
                // Keep text available for accessibility and automated semantic activation.
                launchButton.setText(action);
                launchButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                if (currentGame == null) {
                    currentLabel.setText(null);
                    graphic.getChildren().setAll(launchLabel);
                } else {
                    currentLabel.setText(currentGame.name());
                    currentLabel.setMaxWidth(175);
                    graphic.getChildren().setAll(launchLabel, currentLabel);
                }
                launchButton.setOnAction(event -> launch.run());
                launchButton.setGraphic(graphic);
            }

            Button menuButton = new Button();
            menuButton.setId("instance-switcher");
            menuButton.getStyleClass().addAll("jfx-button", "menu-button");
            menuButton.disableProperty().bind(busy);
            SVGPath arrow = new SVGPath();
            arrow.setContent("M 7 14 L 12 9 L 17 14 Z");
            arrow.getStyleClass().add("svg");
            menuButton.setGraphic(arrow);
            menuButton.setTooltip(new Tooltip("切换实例"));
            ContextMenu popup = new ContextMenu();
            popup.getStyleClass().add("hmcl-instance-popup");
            ToggleGroup instanceGroup = new ToggleGroup();
            for (UiState.InstanceItem instance : state.instances()) {
                RadioMenuItem item = new RadioMenuItem(instance.name() + "  ·  " + instance.version());
                item.setToggleGroup(instanceGroup);
                item.setSelected(instance.id().equals(state.currentInstanceId()));
                item.setOnAction(event -> { item.setSelected(true); selectInstance.accept(instance.id()); });
                popup.getItems().add(item);
            }
            if (state.instances().isEmpty()) {
                MenuItem empty = new MenuItem("尚未创建实例");
                empty.setDisable(true);
                popup.getItems().add(empty);
            }
            menuButton.setOnAction(event -> {
                if (popup.isShowing()) { popup.hide(); return; }
                popup.show(menuButton, Side.TOP, -203, -4);
                Node graphic = menuButton.getGraphic();
                if (graphic != null) {
                    RotateTransition rotateOpen = new RotateTransition(Duration.millis(200), graphic);
                    rotateOpen.setToAngle(-180);
                    rotateOpen.play();
                    popup.setOnHidden(windowEvent -> {
                        RotateTransition rotateClose = new RotateTransition(Duration.millis(200), graphic);
                        rotateClose.setToAngle(0);
                        rotateClose.play();
                    });
                }
            });
            EventHandler<MouseEvent> secondaryClickHandle = event -> {
                if (event.getButton() == MouseButton.SECONDARY && event.getClickCount() == 1) {
                    menuButton.fire();
                    event.consume();
                }
            };
            launchButton.addEventHandler(MouseEvent.MOUSE_CLICKED, secondaryClickHandle);
            menuButton.addEventHandler(MouseEvent.MOUSE_CLICKED, secondaryClickHandle);
            launchPane.getChildren().setAll(launchButton, menuButton);
        }

        StackPane.setAlignment(taskPane, Pos.TOP_CENTER);
        ((javafx.scene.layout.Region) taskPane).setMaxHeight(USE_PREF_SIZE);
        getChildren().addAll(taskPane, launchPane);
        if (currentGame != null && !installed && !running) {
            Label firstLaunch = new Label("首次启动将下载 Harness 和依赖。\n下载阶段和已用时会显示在这里，可查看任务或取消。");
            firstLaunch.setId("first-launch-hint");
            firstLaunch.setWrapText(true);
            firstLaunch.getStyleClass().addAll("card", "first-launch-note");
            firstLaunch.maxWidthProperty().bind(widthProperty().subtract(300));
            StackPane.setAlignment(firstLaunch, Pos.BOTTOM_LEFT);
            getChildren().add(firstLaunch);
        }
        if (running) {
            Button stopButton = new Button("停止");
            stopButton.getStyleClass().addAll("jfx-button", "secondary-button");
            stopButton.disableProperty().bind(busy);
            stopButton.setOnAction(event -> stop.run());
            StackPane.setAlignment(stopButton, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(stopButton, new Insets(0, 244, 0, 0));
            getChildren().add(stopButton);
        }
    }
}
