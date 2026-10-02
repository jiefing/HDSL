/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
 * Source: HMCL/src/main/java/org/jackhuang/hmcl/ui/decorator/MainWindowPane.java
 * Changes: preserve clipped background/frame/title bar/navigation/window button composition;
 * replace Theme/Decorator/JFoenix dependencies with JavaFX and HDSL callbacks.
 * The portable drag/resize event handlers below are HDSL additions.
 */
package com.hdsl.ui.hmcl;

import com.hdsl.ui.BackgroundCatalog;
import javafx.event.Event;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;
import java.nio.file.Path;

public final class MainWindowPane extends StackPane {
    private static final double ARC = 8.0;
    private static final double RESIZE_BORDER = 6;
    private final Rectangle clip = new Rectangle();
    private final BorderPane frame;
    private final BorderPane titleBar;
    private final HBox windowButtons;
    private final StackPane navBarPane;
    private final StackPane backgroundNode = new StackPane();
    private final Region customBackground = new Region();
    private final Label title = new Label("HDSL");
    private final ImageView homeIcon = new ImageView();
    private final Button backButton;
    private Stage stage;
    private double pressScreenX, pressScreenY, pressX, pressY, pressWidth, pressHeight;
    private Cursor resizeCursor = Cursor.DEFAULT;
    private boolean dragging;
    private String backgroundPath;
    private Image activeBackgroundImage;

    public MainWindowPane(Node navigator, Node defaultBackground, Node status, Runnable home, Runnable about) {
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(ARC);
        clip.setArcHeight(ARC);
        setClip(clip);
        backgroundNode.setMouseTransparent(true);
        customBackground.setVisible(false);
        customBackground.setId("launcher-background-image");
        customBackground.widthProperty().addListener((observable, previous, current) -> updateImageBackground());
        customBackground.heightProperty().addListener((observable, previous, current) -> updateImageBackground());
        backgroundNode.getChildren().setAll(defaultBackground, customBackground);

        frame = new BorderPane();
        frame.getStyleClass().add("jfx-decorator");
        StackPane center = new StackPane();
        center.getStyleClass().add("jfx-decorator-content-container");
        Rectangle centerClip = new Rectangle();
        centerClip.widthProperty().bind(center.widthProperty());
        centerClip.heightProperty().bind(center.heightProperty());
        center.setClip(centerClip);
        center.getChildren().setAll(navigator);
        frame.setCenter(center);

        windowButtons = createWindowButtons(about);
        titleBar = new BorderPane();
        titleBar.setId("window-title-bar");
        titleBar.setPickOnBounds(false);
        titleBar.getStyleClass().add("jfx-tool-bar");
        titleBar.setRight(windowButtons);
        navBarPane = new StackPane();
        titleBar.setCenter(navBarPane);
        frame.setTop(titleBar);
        // HMCL's default titleBarTransparent=false uses the blue primary-container color.
        titleBar.setBackground(new Background(new BackgroundFill(Color.web("#5C6BC0"), CornerRadii.EMPTY, javafx.geometry.Insets.EMPTY)));

        HBox navBar = new HBox();
        navBar.setAlignment(Pos.CENTER_LEFT);
        backButton = windowButton("‹", "返回启动页", "window-home", home);
        title.getStyleClass().add("jfx-decorator-title");
        var iconResource = MainWindowPane.class.getResource("/com/hdsl/ui/icons/hdsl.png");
        if (iconResource != null) homeIcon.setImage(new Image(iconResource.toExternalForm(), 18, 18, true, true, true));
        homeIcon.setFitWidth(18);
        homeIcon.setFitHeight(18);
        homeIcon.setPreserveRatio(true);
        title.setGraphicTextGap(8);
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        navBar.getChildren().setAll(backButton, title, status);
        navBarPane.getChildren().setAll(navBar);
        getChildren().setAll(backgroundNode, frame);
    }

    private HBox createWindowButtons(Runnable about) {
        HBox buttons = new HBox();
        buttons.setAlignment(Pos.TOP_RIGHT);
        buttons.setMaxSize(Region.USE_PREF_SIZE, 40);
        Button helpButton = windowButton("?", "关于与许可", "window-about", about);
        Button minimizeButton = windowButton("−", "最小化", "window-minimize", () -> { if (stage != null) stage.setIconified(true); });
        Button maximizeButton = windowButton("□", "最大化 / 还原", "window-maximize", this::toggleMaximized);
        Button closeButton = windowButton("×", "关闭", "window-close", () -> {
            if (stage != null) Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
        });
        buttons.getChildren().setAll(helpButton, minimizeButton, maximizeButton, closeButton);
        return buttons;
    }

    private Button windowButton(String text, String tooltip, String id, Runnable action) {
        Button button = new Button(text);
        String path = switch (id) {
            case "window-home" -> "M 12 3 L 5 10 L 12 17 M 5 10 H 18";
            case "window-minimize" -> "M 4 10 H 16";
            case "window-maximize" -> "M 5 5 H 15 V 15 H 5 Z";
            case "window-close" -> "M 5 5 L 15 15 M 15 5 L 5 15";
            default -> "M 7 6 C 7 2 14 2 14 6 C 14 9 10 8 10 12 M 10 15 V 16";
        };
        SVGPath graphic = new SVGPath();
        graphic.setContent(path);
        graphic.getStyleClass().add("window-glyph");
        StackPane graphicBox = new StackPane(graphic);
        graphicBox.setMinSize(20, 20);
        graphicBox.setPrefSize(20, 20);
        graphicBox.setMaxSize(20, 20);
        button.setGraphic(graphicBox);
        button.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        button.setAccessibleText(tooltip);
        button.setId(id);
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        button.getStyleClass().add("jfx-decorator-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    public void setPageTitle(String page) {
        boolean home = page.equals("启动");
        backButton.setVisible(!home);
        backButton.setManaged(!home);
        title.setPadding(new javafx.geometry.Insets(0, 0, 0, home ? 12 : 0));
        title.setGraphic(home ? homeIcon : null);
        title.setText("HDSL 0.2.0-preview.5" + (home ? "" : "  /  " + page));
    }

    public void setBackgroundPath(String path) {
        String normalized = BackgroundCatalog.normalized(path);
        if (normalized.equals(backgroundPath)) return;
        backgroundPath = normalized;
        activeBackgroundImage = null;
        customBackground.setVisible(false);
        customBackground.setBackground(null);
        var location = BackgroundCatalog.imageLocation(normalized);
        if (location.isEmpty()) return;
        try {
            Image image = new Image(location.get(), true);
            activeBackgroundImage = image;
            image.progressProperty().addListener((observable, previous, progress) -> {
                if (activeBackgroundImage == image && progress.doubleValue() == 1) updateImageBackground();
            });
            image.errorProperty().addListener((observable, previous, failed) -> {
                if (failed && activeBackgroundImage == image) customBackground.setVisible(false);
            });
            updateImageBackground();
        } catch (IllegalArgumentException ignored) { customBackground.setVisible(false); }
    }

    private void updateImageBackground() {
        Image image = activeBackgroundImage;
        if (image == null || image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0
                || customBackground.getWidth() <= 0 || customBackground.getHeight() <= 0) return;
        double scale = Math.max(customBackground.getWidth() / image.getWidth(), customBackground.getHeight() / image.getHeight());
        BackgroundPosition position = BackgroundCatalog.isBuiltIn(backgroundPath)
                ? new BackgroundPosition(javafx.geometry.Side.RIGHT, 0, false, javafx.geometry.Side.TOP, .5, true)
                : BackgroundPosition.CENTER;
        // JavaFX 21's cover renderer ignores position. Explicit dimensions preserve right/center alignment.
        BackgroundSize size = new BackgroundSize(image.getWidth() * scale, image.getHeight() * scale, false, false, false, false);
        customBackground.setBackground(new Background(new BackgroundImage(image, BackgroundRepeat.NO_REPEAT,
                BackgroundRepeat.NO_REPEAT, position, size)));
        customBackground.setVisible(true);
    }

    public void configureStage(Stage stage) {
        if (this.stage != null) return;
        if (stage.isShowing()) throw new IllegalStateException("Configure the window before showing it");
        this.stage = stage;
        stage.initStyle(StageStyle.UNDECORATED);
        stage.maximizedProperty().addListener((observable, oldValue, maximized) -> {
            clip.setArcWidth(maximized ? 0 : ARC);
            clip.setArcHeight(maximized ? 0 : ARC);
        });
        addEventFilter(MouseEvent.MOUSE_MOVED, event -> setCursor(edgeCursor(event.getX(), event.getY())));
        addEventFilter(MouseEvent.MOUSE_EXITED, event -> { if (!dragging && resizeCursor == Cursor.DEFAULT) setCursor(Cursor.DEFAULT); });
        addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            resizeCursor = edgeCursor(event.getX(), event.getY());
            boolean onTitle = event.getY() < 40 && !insideButton(event.getTarget());
            if (resizeCursor == Cursor.DEFAULT && !onTitle) return;
            pressScreenX = event.getScreenX(); pressScreenY = event.getScreenY();
            pressX = stage.getX(); pressY = stage.getY(); pressWidth = stage.getWidth(); pressHeight = stage.getHeight();
            dragging = onTitle && resizeCursor == Cursor.DEFAULT && !stage.isMaximized();
            if (resizeCursor != Cursor.DEFAULT) event.consume();
        });
        addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!event.isPrimaryButtonDown()) return;
            double dx = event.getScreenX() - pressScreenX, dy = event.getScreenY() - pressScreenY;
            if (resizeCursor != Cursor.DEFAULT) {
                boolean west = resizeCursor == Cursor.W_RESIZE || resizeCursor == Cursor.NW_RESIZE || resizeCursor == Cursor.SW_RESIZE;
                boolean east = resizeCursor == Cursor.E_RESIZE || resizeCursor == Cursor.NE_RESIZE || resizeCursor == Cursor.SE_RESIZE;
                boolean north = resizeCursor == Cursor.N_RESIZE || resizeCursor == Cursor.NW_RESIZE || resizeCursor == Cursor.NE_RESIZE;
                boolean south = resizeCursor == Cursor.S_RESIZE || resizeCursor == Cursor.SW_RESIZE || resizeCursor == Cursor.SE_RESIZE;
                if (west || east) {
                    double width = Math.max(stage.getMinWidth(), pressWidth + (west ? -dx : dx));
                    stage.setWidth(width);
                    if (west) stage.setX(pressX + pressWidth - width);
                }
                if (north || south) {
                    double height = Math.max(stage.getMinHeight(), pressHeight + (north ? -dy : dy));
                    stage.setHeight(height);
                    if (north) stage.setY(pressY + pressHeight - height);
                }
                event.consume();
            } else if (dragging) {
                stage.setX(pressX + dx); stage.setY(pressY + dy); event.consume();
            }
        });
        addEventFilter(MouseEvent.MOUSE_RELEASED, event -> { dragging = false; resizeCursor = Cursor.DEFAULT; });
        titleBar.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !insideButton(event.getTarget())) {
                toggleMaximized(); event.consume();
            }
        });
    }

    private boolean insideButton(Object target) {
        for (Node node = target instanceof Node n ? n : null; node != null && node != this; node = node.getParent())
            if (node instanceof ButtonBase) return true;
        return false;
    }

    private Cursor edgeCursor(double x, double y) {
        if (stage == null || stage.isMaximized() || !stage.isResizable()) return Cursor.DEFAULT;
        boolean left = x < RESIZE_BORDER, right = x > getWidth() - RESIZE_BORDER;
        boolean top = y < RESIZE_BORDER, bottom = y > getHeight() - RESIZE_BORDER;
        if (left && top) return Cursor.NW_RESIZE;
        if (right && top) return Cursor.NE_RESIZE;
        if (left && bottom) return Cursor.SW_RESIZE;
        if (right && bottom) return Cursor.SE_RESIZE;
        if (left) return Cursor.W_RESIZE;
        if (right) return Cursor.E_RESIZE;
        if (top) return Cursor.N_RESIZE;
        if (bottom) return Cursor.S_RESIZE;
        return Cursor.DEFAULT;
    }

    private void toggleMaximized() { if (stage != null && stage.isResizable()) stage.setMaximized(!stage.isMaximized()); }
}
