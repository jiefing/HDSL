package com.hdsl;

import com.hdsl.core.Controller;
import com.hdsl.ui.ShellView;
import javafx.application.*;
import javafx.animation.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import javafx.util.Duration;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HdslApplication extends Application {
    private Controller controller;
    private Timeline refresh;
    @Override public void start(Stage stage){
        try{
            Path root=dataRoot();controller=new Controller(root);ShellView shell=new ShellView(controller);
            Scene scene=new Scene(shell.view(),1180,760);stage.setTitle("HDSL · Hello DeepSeek Harness Launcher · 0.2.0-preview.5");stage.setMinWidth(960);stage.setMinHeight(650);stage.setScene(scene);shell.configureStage(stage);
            var icon=HdslApplication.class.getResource("/com/hdsl/ui/icons/hdsl.png");if(icon!=null)stage.getIcons().add(new javafx.scene.image.Image(icon.toExternalForm()));
            shell.render(controller.snapshot());stage.show();
            AtomicBoolean polling=new AtomicBoolean();
            refresh=new Timeline(new KeyFrame(Duration.seconds(1.5),e->{if(polling.compareAndSet(false,true))controller.dispatch("refresh",Map.of()).whenComplete((state,error)->{polling.set(false);if(error==null)Platform.runLater(()->shell.render(state));});}));refresh.setCycleCount(Animation.INDEFINITE);refresh.play();
            stage.setOnCloseRequest(e->{e.consume();stage.hide();if(refresh!=null)refresh.stop();Thread.ofVirtual().start(()->{controller.close();Platform.exit();});});
            String screenshot=System.getProperty("hdsl.screenshot");
            if(screenshot!=null){PauseTransition wait=new PauseTransition(Duration.seconds(3));wait.setOnFinished(e->{try{Path path=Path.of(screenshot);Files.createDirectories(path.toAbsolutePath().getParent());javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(scene.snapshot(null),null),"png",path.toFile());}catch(Exception ex){ex.printStackTrace();}if(Boolean.getBoolean("hdsl.snapshotExit")){refresh.stop();controller.close();Platform.exit();}});wait.play();}
        }catch(Exception e){Alert alert=new Alert(Alert.AlertType.ERROR,"启动失败："+e.getMessage(),ButtonType.OK);alert.setHeaderText("HDSL 无法打开数据目录");alert.showAndWait();Platform.exit();}
    }
    private Path dataRoot(){String configured=System.getProperty("hdsl.data",System.getenv("HDSL_PORTABLE_ROOT"));if(configured!=null&&!configured.isBlank())return Path.of(configured);String app=System.getProperty("jpackage.app-path");if(app!=null)return Path.of(app).toAbsolutePath().getParent();return Path.of(System.getProperty("user.home"),".hdsl");}
}
