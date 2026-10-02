package com.hdsl.core;

import com.hdsl.download.DesktopDownloadService;
import com.hdsl.runtime.RuntimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryCancellationTest {
    @TempDir Path root;
    private static void block(CountDownLatch entered)throws IOException{
        entered.countDown();
        try{new CountDownLatch(1).await(20,TimeUnit.SECONDS);throw new IOException("Not cancelled");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Cancelled fixture",e);}
    }
    @Test void cancelInterruptsDesktopDiscoveryAndAllowsRetry()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        var desktop=new DesktopDownloadService(root,""){
            @Override public List<Asset> releases(String source)throws IOException{if(calls.incrementAndGet()==1)block(entered);return List.of();}
        };
        try(var controller=new Controller(root,new RuntimeService(root,null,null),desktop)){
            var query=controller.dispatch("refreshDesktop",Map.of());assertTrue(entered.await(5,TimeUnit.SECONDS));
            controller.dispatch("cancelTask",Map.of()).get(5,TimeUnit.SECONDS);
            assertTrue(query.get(5,TimeUnit.SECONDS).desktop().items().isEmpty());
            controller.dispatch("refreshDesktop",Map.of()).get(5,TimeUnit.SECONDS);assertEquals(2,calls.get());
            assertEquals(1,controller.dispatch("createInstance",Map.of("name","After cancel","version","1.0.0")).get(5,TimeUnit.SECONDS).instances().size());
        }
    }
    @Test void versionQueryCanBeCancelledAndRetried()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        var runtime=new RuntimeService(root,null,null){
            @Override public List<String> availableVersions()throws IOException{if(calls.incrementAndGet()==1)block(entered);return List.of("1.0.0");}
        };
        try(var controller=new Controller(root,runtime)){
            var query=controller.dispatch("refreshVersions",Map.of());assertTrue(entered.await(5,TimeUnit.SECONDS));
            controller.dispatch("cancelTask",Map.of()).get(5,TimeUnit.SECONDS);
            assertTrue(query.get(5,TimeUnit.SECONDS).availableRuntimeVersions().isEmpty());
            assertEquals(List.of("1.0.0"),controller.dispatch("refreshVersions",Map.of()).get(5,TimeUnit.SECONDS).availableRuntimeVersions());
        }
    }
    @Test void sameSizeCorruptionIsNotShownAsDownloadedOrOpened()throws Exception{
        byte[] content="fixture installer".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var asset=new DesktopDownloadService.Asset("anywhere-labs/dsh-desktop:7","anywhere-labs/dsh-desktop","1.0.0","dsh-windows-x64.exe",content.length,"https://example.invalid/unused",hash,false);
        var desktop=new DesktopDownloadService(root,""){@Override public List<Asset> releases(String source){return List.of(asset);}};
        Path file=desktop.location(asset);Files.createDirectories(file.getParent());Files.write(file,content);
        try(var controller=new Controller(root,new RuntimeService(root,null,null),desktop)){
            assertFalse(controller.dispatch("refreshDesktop",Map.of()).get(5,TimeUnit.SECONDS).desktop().items().getFirst().path().isBlank());
            Files.write(file,new byte[content.length]);
            // Open checks integrity again, even before a list refresh; no OS folder opens.
            assertThrows(ExecutionException.class,()->controller.dispatch("openDownloadFolder",Map.of("id",asset.id())).get(5,TimeUnit.SECONDS));
            assertTrue(controller.snapshot().desktop().items().getFirst().path().isBlank());
            assertTrue(controller.dispatch("refreshDesktop",Map.of()).get(5,TimeUnit.SECONDS).desktop().items().getFirst().path().isBlank());
        }
    }
    @Test void closingDuringPreparationPreventsLateProcessLaunch()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);AtomicInteger launchCalls=new AtomicInteger();
        var runtime=new RuntimeService(root,null,null){
            @Override public void install(String version,Consumer<String> log){entered.countDown();try{new CountDownLatch(1).await(20,TimeUnit.SECONDS);}catch(InterruptedException ignored){/* Simulates a dependency that swallows interruption. */}}
            @Override public List<String> launchCommand(String v,String p,int port,Path h,Path w,Consumer<String> log){launchCalls.incrementAndGet();throw new AssertionError("Closed controller must not prepare a new process");}
        };
        var controller=new Controller(root,runtime);
        try{
            String id=controller.dispatch("createInstance",Map.of("name","Close race","version","1.0.0")).get(5,TimeUnit.SECONDS).currentInstanceId();
            var launch=controller.dispatch("launch",Map.of("id",id));assertTrue(entered.await(5,TimeUnit.SECONDS));
            controller.close();launch.get(5,TimeUnit.SECONDS);assertEquals(0,launchCalls.get());
            assertThrows(ExecutionException.class,()->controller.dispatch("refresh",Map.of()).get(5,TimeUnit.SECONDS));
            try(var reopened=new InstanceStore(root)){assertEquals(1,reopened.list().size());}
        }finally{controller.close();}
    }
}
