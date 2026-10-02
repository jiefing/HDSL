package com.hdsl.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class VersionQueryHttpTest {
    @TempDir Path root;

    @Test void readsVersionsWithoutAnyInstalledRuntimeOrNode() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/@deepseek-ai/dsh",exchange->{
            byte[] body="{\"versions\":{\"1.0.0-rc.2\":{},\"1.0.0\":{},\"1.0.0-rc.10\":{},\"not-a-version\":{}}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(var stream=exchange.getResponseBody()){stream.write(body);}
        });
        server.start();
        try{
            RuntimeService service=new RuntimeService(root,registry(server),""){
                @Override public Path nodeExecutable(){throw new AssertionError("Unexpected Node lookup");}
            };
            assertEquals(List.of("1.0.0","1.0.0-rc.10","1.0.0-rc.2"),service.availableVersions());
            assertTrue(service.installedVersions().isEmpty());
        }finally{server.stop(0);}
    }

    @Test void timeoutCoversAResponseBodyThatStallsAfterHeaders() throws Exception {
        CountDownLatch bodyStarted=new CountDownLatch(1), release=new CountDownLatch(1);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService handlers=Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        server.createContext("/@deepseek-ai/dsh",exchange->{
            exchange.sendResponseHeaders(200,0);
            try(var stream=exchange.getResponseBody()){
                stream.write('{');stream.flush();bodyStarted.countDown();
                try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            }
        });
        server.start();
        try{
            RuntimeService service=new RuntimeService(root,registry(server),""){
                @Override Duration versionQueryTimeout(){return Duration.ofMillis(800);}
            };
            long start=System.nanoTime();
            IOException error=assertThrows(IOException.class,service::availableVersions);
            assertTrue(bodyStarted.await(1,TimeUnit.SECONDS),"Fixture never delivered response headers/body");
            assertTrue(error.getMessage().contains("超时"));
            assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()<4);
        }finally{release.countDown();server.stop(0);handlers.shutdownNow();}
    }

    @Test void oversizedResponseIsCancelledBeforeBufferingBeyondLimit() {
        RuntimeService.LimitedRegistryBody body=new RuntimeService.LimitedRegistryBody();
        AtomicBoolean cancelled=new AtomicBoolean();
        body.onSubscribe(new Flow.Subscription(){
            @Override public void request(long count){}
            @Override public void cancel(){cancelled.set(true);}
        });
        byte[] block=new byte[1024*1024];
        for(int i=0;i<33;i++)body.onNext(List.of(ByteBuffer.wrap(block)));
        assertTrue(cancelled.get());
        var failure=assertThrows(CompletionException.class,()->body.getBody().toCompletableFuture().join());
        assertInstanceOf(IOException.class,failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("过大"));
    }

    private static String registry(HttpServer server){return "http://127.0.0.1:"+server.getAddress().getPort();}
}
