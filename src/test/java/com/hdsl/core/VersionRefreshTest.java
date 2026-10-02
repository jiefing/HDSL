package com.hdsl.core;

import com.hdsl.runtime.RuntimeService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class VersionRefreshTest {
    @TempDir Path root;

    @Test void slowQueryIsSharedAndDoesNotBlockCreatingAnInstance() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicInteger queries=new AtomicInteger();
        RuntimeService runtime=new RuntimeService(root,null,null){
            @Override public List<String> availableVersions() throws IOException {
                queries.incrementAndGet();entered.countDown();waitFor(release);return List.of("1.0.0");
            }
            @Override public Path nodeExecutable(){throw new AssertionError("Version refresh must not require Node");}
        };
        try(Controller controller=new Controller(root,runtime)){
            var first=controller.dispatch("refreshVersions",Map.of());
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals("就绪",controller.snapshot().status());
            var closedDialog=controller.dispatch("refreshVersions",Map.of());
            closedDialog.cancel(true);
            var created=controller.dispatch("createInstance",Map.of("name","Created during query","version","9.8.7")).get(5,TimeUnit.SECONDS);
            assertEquals(1,created.instances().size());
            assertFalse(first.isDone());assertEquals(1,queries.get());
            release.countDown();
            var refreshed=first.get(5,TimeUnit.SECONDS);
            assertEquals(List.of("1.0.0"),refreshed.availableRuntimeVersions());
            assertEquals(created.status(),refreshed.status());
        }finally{release.countDown();}
    }

    @Test void failedQueryKeepsPreviousVersionsAndDoesNotReplaceGlobalStatus() throws Exception {
        AtomicInteger queries=new AtomicInteger();
        RuntimeService runtime=new RuntimeService(root,null,null){
            @Override public List<String> availableVersions() throws IOException {
                if(queries.incrementAndGet()>1)throw new IOException("Fixture offline");
                return List.of("1.0.0");
            }
        };
        try(Controller controller=new Controller(root,runtime)){
            controller.dispatch("refreshVersions",Map.of()).get(5,TimeUnit.SECONDS);
            var created=controller.dispatch("createInstance",Map.of("name","Existing","version","1.0.0")).get(5,TimeUnit.SECONDS);
            assertThrows(ExecutionException.class,()->controller.dispatch("refreshVersions",Map.of()).get(5,TimeUnit.SECONDS));
            assertEquals(List.of("1.0.0"),controller.snapshot().availableRuntimeVersions());
            assertEquals(created.status(),controller.snapshot().status());
        }
    }

    @Test void changingSourceDiscardsAnOlderInFlightResponse() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        HttpServer replacement=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        replacement.createContext("/@deepseek-ai/dsh",exchange->{
            byte[] body="{\"versions\":{\"2.0.0\":{}}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(var stream=exchange.getResponseBody()){stream.write(body);}
        });
        replacement.start();
        String registry="http://127.0.0.1:"+replacement.getAddress().getPort();
        RuntimeService runtime=new RuntimeService(root,null,null){
            @Override public List<String> availableVersions() throws IOException {
                entered.countDown();waitFor(release);return List.of("1.0.0");
            }
        };
        try(Controller controller=new Controller(root,runtime)){
            var oldQuery=controller.dispatch("refreshVersions",Map.of());
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var saved=controller.dispatch("saveSettings",Map.of("registry",registry,"proxy","")).get(5,TimeUnit.SECONDS);
            assertEquals(List.of("2.0.0"),controller.dispatch("refreshVersions",Map.of()).get(5,TimeUnit.SECONDS).availableRuntimeVersions());
            release.countDown();
            var late=oldQuery.get(5,TimeUnit.SECONDS);
            assertEquals(List.of("2.0.0"),late.availableRuntimeVersions());
            assertEquals(saved.status(),late.status());
            assertEquals(registry,late.settings().registry());
        }finally{release.countDown();replacement.stop(0);}
    }

    @Test void queryDoesNotChangeOrCancelAnActiveInstallation() throws Exception {
        CountDownLatch installing=new CountDownLatch(1);
        RuntimeService runtime=new RuntimeService(root,null,null){
            @Override public List<String> availableVersions(){return List.of("1.0.0");}
            @Override public void install(String version,Consumer<String> log)throws IOException{
                log.accept("[安装进度] 下载依赖");installing.countDown();waitFor(new CountDownLatch(1));
            }
        };
        try(Controller controller=new Controller(root,runtime)){
            var install=controller.dispatch("installRuntime",Map.of("version","1.0.0"));
            assertTrue(installing.await(5,TimeUnit.SECONDS));
            String progress=controller.snapshot().status();
            assertEquals(progress,controller.dispatch("refreshVersions",Map.of()).get(5,TimeUnit.SECONDS).status());
            assertFalse(install.isDone());
            controller.dispatch("cancelTask",Map.of()).get(5,TimeUnit.SECONDS);
            assertTrue(install.get(5,TimeUnit.SECONDS).status().startsWith("已取消："));
        }
    }

    private static void waitFor(CountDownLatch latch)throws IOException{
        try{if(!latch.await(10,TimeUnit.SECONDS))throw new IOException("Fixture wait timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Fixture cancelled",e);}
    }
}
