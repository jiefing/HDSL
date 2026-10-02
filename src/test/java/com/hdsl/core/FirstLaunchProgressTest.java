package com.hdsl.core;

import com.hdsl.runtime.RuntimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class FirstLaunchProgressTest {
    @TempDir Path root;
    @Test void firstDownloadReportsProgressAndCancellationReleasesControls()throws Exception{
        CountDownLatch downloading=new CountDownLatch(1);
        RuntimeService runtime=new RuntimeService(root,"https://example.invalid",""){
            @Override public List<String> installedVersions(){return List.of();}
            @Override public void install(String version,Consumer<String> log)throws IOException{
                log.accept("[安装进度] 核对发行依赖：已核对 277 项");
                log.accept("Progress: resolved 600, reused 0, downloaded 528, added 528");
                downloading.countDown();
                try{new CountDownLatch(1).await(15,TimeUnit.SECONDS);throw new IOException("Fixture was not cancelled");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Cancelled fixture",interrupted);}
            }
        };
        try(Controller controller=new Controller(root,runtime)){
            String id=controller.dispatch("createInstance",Map.of("name","First launch","version","0.2.0-rc.2","profile","web")).get(5,TimeUnit.SECONDS).currentInstanceId();
            var launch=controller.dispatch("launch",Map.of("id",id));
            assertTrue(downloading.await(5,TimeUnit.SECONDS));
            var progress=controller.snapshot();
            assertEquals("下载中",progress.currentInstance().status());
            assertTrue(progress.status().contains("已下载 528"));
            assertEquals(0,progress.runtimes().size());
            controller.dispatch("cancelTask",Map.of()).get(5,TimeUnit.SECONDS);
            var cancelled=launch.get(5,TimeUnit.SECONDS);
            assertTrue(cancelled.status().startsWith("已取消："));
            assertEquals("未启动",cancelled.currentInstance().status());
            assertEquals(2,controller.dispatch("createInstance",Map.of("name","After cancellation","version","0.2.0-rc.2","profile","web")).get(5,TimeUnit.SECONDS).instances().size());
        }
    }
}
