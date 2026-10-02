/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020 huangyuhui <huanghongxun2008@126.com> and contributors
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
 * Source: HMCL/src/main/java/org/jackhuang/hmcl/ui/construct/RipplerContainer.java
 * Changes: retained content container and hover overlay; standard FX animation replaces JFoenix.
 */
package com.hdsl.ui.hmcl;

import javafx.animation.FadeTransition;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

public final class RipplerContainer extends StackPane {
    public RipplerContainer(Node container) {
        getStyleClass().add("rippler-container");
        StackPane overlay = new StackPane();
        overlay.setMouseTransparent(true);
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.05);");
        overlay.setOpacity(0);
        getChildren().setAll(overlay, container);
        setOnMouseEntered(event -> fade(overlay, 1));
        setOnMouseExited(event -> fade(overlay, 0));
    }

    private void fade(Node node, double target) {
        FadeTransition transition = new FadeTransition(Duration.millis(120), node);
        transition.setToValue(target);
        transition.play();
    }
}
