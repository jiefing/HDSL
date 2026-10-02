package com.hdsl.download;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopDownloadServiceTest {
    @TempDir Path root;
    HttpServer server;DesktopDownloadService service;URI base;
    final byte[] body="fixture desktop payload".getBytes(StandardCharsets.UTF_8);
    @BeforeEach void setup()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());server.start();base=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
        service=new DesktopDownloadService(root,"",base,true);
        server.createContext("/asset",exchange->{exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});
    }
    @AfterEach void stop(){server.stop(0);}
    DesktopDownloadService.Asset asset(String hash){return new DesktopDownloadService.Asset("anywhere-labs/dsh-desktop:7","anywhere-labs/dsh-desktop","v1.0","DSH-1.0-x64-Setup.exe",body.length,base.resolve("asset").toString(),hash,false);}
    String digest()throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));}
    DesktopDownloadService.Asset officialAsset(String responseEtag)throws Exception{
        String page="<a href='/'>Home</a><a href='https://www.deepseek.com/'>Home</a><a href='mailto:info@example.invalid'>Mail</a><a href='javascript:void(0)'>Menu</a><a href='/desktop/'>Directory</a><a href='/desktop/dsh-latest-windows-x64.exe'>Windows</a>";
        server.createContext("/official-download",exchange->{byte[] bytes=page.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});
        server.createContext("/desktop/dsh-latest-windows-x64.exe",exchange->{
            if(exchange.getRequestMethod().equals("HEAD")){exchange.getResponseHeaders().set("Content-Length",Integer.toString(body.length));exchange.getResponseHeaders().set("ETag","\"release-a\"");exchange.sendResponseHeaders(200,-1);exchange.close();}
            else {if(responseEtag!=null)exchange.getResponseHeaders().set("ETag",responseEtag);exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}}
        });
        return service.releases("deepseek-ai/deepseek-harness").getFirst();
    }
    void assertNoPartial()throws Exception{try(var files=Files.walk(root)){assertFalse(files.anyMatch(p->p.toString().endsWith(".partial")));}}
    @Test void downloadChecksPublishedDigestAndReusesVerifiedFile()throws Exception{
        var item=asset(digest());Path saved=service.download(item,s->{});assertArrayEquals(body,Files.readAllBytes(saved));server.stop(0);
        assertEquals(saved,service.download(item,s->{}));try(var files=Files.walk(root)){assertFalse(files.anyMatch(p->p.toString().endsWith(".partial")));}
    }
    @Test void publishedDigestDetectsSameSizeDamageAndOverridesAnyLocalSidecar()throws Exception{
        var item=asset(digest());Path saved=service.download(item,s->{});assertTrue(service.isDownloaded(item));
        byte[] damaged=body.clone();damaged[0]^=1;Files.write(saved,damaged);
        String fakeLocalDigest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(damaged));
        Files.writeString(saved.resolveSibling(saved.getFileName()+".sha256"),fakeLocalDigest);
        assertFalse(service.isDownloaded(item));
        assertEquals(saved,service.download(item,s->{}));assertArrayEquals(body,Files.readAllBytes(saved));assertTrue(service.isDownloaded(item));
    }
    @Test void officialNavigationLinksAreIgnoredAndCacheUsesItsLocalChecksum()throws Exception{
        var item=officialAsset("\"release-a\"");assertEquals("dsh-latest-windows-x64.exe",item.name());
        Path saved=service.download(item,s->{}),sidecar=saved.resolveSibling(saved.getFileName()+".sha256");
        assertEquals(digest(),Files.readString(sidecar).strip());assertTrue(service.isDownloaded(item));server.stop(0);
        List<String> logs=new ArrayList<>();assertEquals(saved,service.download(item,logs::add));
        assertTrue(String.join(" ",logs).contains("本地摘要"));assertNoPartial();
    }
    @Test void localCacheRequiresChecksumAndRejectsDamageMissingOrMalformedSidecar()throws Exception{
        var item=officialAsset("\"release-a\"");Path saved=service.location(item),sidecar=saved.resolveSibling(saved.getFileName()+".sha256");
        Files.createDirectories(saved.getParent());Files.write(saved,body);assertFalse(service.isDownloaded(item));
        service.download(item,s->{});assertTrue(service.isDownloaded(item));
        byte[] damaged=body.clone();damaged[0]^=1;Files.write(saved,damaged);assertFalse(service.isDownloaded(item));
        service.download(item,s->{});assertArrayEquals(body,Files.readAllBytes(saved));
        Files.write(sidecar,new byte[]{(byte)0xff});assertFalse(service.isDownloaded(item));
        Files.writeString(sidecar,"a".repeat(100));assertFalse(service.isDownloaded(item));
        Files.delete(sidecar);assertFalse(service.isDownloaded(item));assertNoPartial();
    }
    @Test void changedGetEtagIsRejectedEvenWhenServerIgnoresIfMatch()throws Exception{
        var item=officialAsset("\"release-b\"");Path target=service.location(item);Files.createDirectories(target.getParent());Files.writeString(target,"preserve old file");
        var error=assertThrows(java.io.IOException.class,()->service.download(item,s->{}));
        assertTrue(error.getMessage().contains("版本标识"));assertEquals("preserve old file",Files.readString(target));assertNoPartial();
    }
    @Test void missingGetEtagIsRejectedBeforePublishingInstaller()throws Exception{
        var item=officialAsset(null);var error=assertThrows(java.io.IOException.class,()->service.download(item,s->{}));
        assertTrue(error.getMessage().contains("版本标识"));assertFalse(Files.exists(service.location(item)));assertNoPartial();
    }
    @Test void weakOrMissingHeadEtagFailsWithAnExplicitMessage()throws Exception{
        server.createContext("/official-download",exchange->{byte[] page="<a href='/desktop/dsh-latest-windows-x64.exe'>Windows</a>".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,page.length);try(var out=exchange.getResponseBody()){out.write(page);}});
        var etag=new java.util.concurrent.atomic.AtomicReference<>("W/\"release-a\"");
        server.createContext("/desktop/dsh-latest-windows-x64.exe",exchange->{exchange.getResponseHeaders().set("Content-Length",Integer.toString(body.length));if(etag.get()!=null)exchange.getResponseHeaders().set("ETag",etag.get());exchange.sendResponseHeaders(200,-1);exchange.close();});
        assertTrue(assertThrows(java.io.IOException.class,()->service.releases("deepseek-ai/deepseek-harness")).getMessage().contains("强 ETag"));
        etag.set(null);assertTrue(assertThrows(java.io.IOException.class,()->service.releases("deepseek-ai/deepseek-harness")).getMessage().contains("强 ETag"));
    }
    @Test void mismatchCannotReplaceExistingDownloadAndCleansPartial()throws Exception{
        var item=asset("0".repeat(64));Path old=service.location(item);Files.createDirectories(old.getParent());Files.writeString(old,"keep old file");
        assertThrows(java.io.IOException.class,()->service.download(item,s->{}));assertEquals("keep old file",Files.readString(old));
        try(var files=Files.walk(root)){assertFalse(files.anyMatch(p->p.toString().endsWith(".partial")));}
    }
    @Test void releaseListFiltersMacAndArmAndOrdersStableFirst()throws Exception{
        String json="""
          [{"tag_name":"v2-next","assets":[{"id":8,"name":"DSH-2-x64-Setup.exe","size":123,"browser_download_url":"https://github.com/anywhere-labs/dsh-desktop/releases/download/v2/a.exe"}]},
           {"tag_name":"v1","assets":[{"id":7,"name":"DSH-1-x64-Setup.exe","size":123,"browser_download_url":"https://github.com/anywhere-labs/dsh-desktop/releases/download/v1/a.exe"},
             {"id":9,"name":"DSH-mac-x64.zip","size":123},{"id":10,"name":"DSH-win-arm64.exe","size":123}]}]
          """;
        server.createContext("/repos/anywhere-labs/dsh-desktop/releases",exchange->{byte[] bytes=json.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});
        var items=service.releases("anywhere-labs/dsh-desktop");assertEquals(2,items.size());assertEquals("v1",items.getFirst().version());assertTrue(items.get(1).prerelease());
    }
    @Test void maliciousAssetNamesAndForeignRedirectsAreRejected()throws Exception{
        var unsafe=new DesktopDownloadService.Asset("anywhere-labs/dsh-desktop:7","anywhere-labs/dsh-desktop","v1","../win-x64.exe",1,base.resolve("asset").toString(),"",false);
        assertThrows(java.io.IOException.class,()->service.location(unsafe));
        server.createContext("/redirect",exchange->{exchange.getResponseHeaders().add("Location","https://example.org/executable");exchange.sendResponseHeaders(302,-1);exchange.close();});
        var item=new DesktopDownloadService.Asset("anywhere-labs/dsh-desktop:7","anywhere-labs/dsh-desktop","v1","DSH-x64-Setup.exe",1,base.resolve("redirect").toString(),"",false);
        assertThrows(java.io.IOException.class,()->service.download(item,s->{}));
    }
    @Test void officialPageUsesItsDesktopLinkAndDetectsReplacedLatestFile()throws Exception{
        String page="<a href='/desktop/dsh-latest-macos-arm64.dmg'>Mac</a><a href='/desktop/dsh-latest-windows-x64.exe'>Windows</a>";
        server.createContext("/official-download",exchange->{byte[] bytes=page.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});
        server.createContext("/desktop/dsh-latest-windows-x64.exe",exchange->{
            if(exchange.getRequestMethod().equals("HEAD")){exchange.getResponseHeaders().set("Content-Length",Integer.toString(body.length));exchange.getResponseHeaders().set("ETag","\"release-a\"");exchange.sendResponseHeaders(200,-1);}
            else {assertEquals("\"release-a\"",exchange.getRequestHeaders().getFirst("If-Match"));exchange.sendResponseHeaders(412,-1);}exchange.close();
        });
        var items=service.releases("deepseek-ai/deepseek-harness");assertEquals(1,items.size());var item=items.getFirst();assertEquals(body.length,item.size());assertEquals("dsh-latest-windows-x64.exe",item.name());assertTrue(item.sha256().isEmpty());
        var error=assertThrows(java.io.IOException.class,()->service.download(item,s->{}));assertTrue(error.getMessage().contains("已更新"));assertFalse(Files.exists(service.location(item)));
    }
    @Test void cancellingMidBodyClosesFileAndPreservesNoUsablePartial()throws Exception{
        CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1);
        server.createContext("/slow",exchange->{exchange.sendResponseHeaders(200,100);try(var out=exchange.getResponseBody()){out.write(new byte[]{1});out.flush();started.countDown();try{finish.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}catch(Exception ignored){}});
        var item=new DesktopDownloadService.Asset("anywhere-labs/dsh-desktop:7","anywhere-labs/dsh-desktop","v1","DSH-x64-Setup.exe",100,base.resolve("slow").toString(),"",false);
        var outcome=new CompletableFuture<Throwable>();Thread task=Thread.ofVirtual().start(()->{try{service.download(item,s->{});outcome.complete(null);}catch(Throwable e){outcome.complete(e);}});
        assertTrue(started.await(5,TimeUnit.SECONDS));task.interrupt();assertInstanceOf(java.io.IOException.class,outcome.get(5,TimeUnit.SECONDS));finish.countDown();
        assertFalse(Files.exists(service.location(item)));try(var files=Files.walk(root)){assertFalse(files.anyMatch(p->p.toString().endsWith(".partial")));}
    }
    @Test void cancellingBeforeHeadersLeavesNoFileWhenResponseEventuallyArrives()throws Exception{
        CountDownLatch started=new CountDownLatch(1),sendHeaders=new CountDownLatch(1),handled=new CountDownLatch(1);
        server.createContext("/late",exchange->{started.countDown();try{sendHeaders.await(5,TimeUnit.SECONDS);exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}}catch(Exception ignored){}finally{exchange.close();handled.countDown();}});
        var item=new DesktopDownloadService.Asset("anywhere-labs/dsh-desktop:9","anywhere-labs/dsh-desktop","v1","DSH-x64-Setup.exe",body.length,base.resolve("late").toString(),"",false);
        var outcome=new CompletableFuture<Throwable>();Thread task=Thread.ofVirtual().start(()->{try{service.download(item,s->{});outcome.complete(null);}catch(Throwable e){outcome.complete(e);}});
        assertTrue(started.await(5,TimeUnit.SECONDS));task.interrupt();assertInstanceOf(java.io.IOException.class,outcome.get(5,TimeUnit.SECONDS));
        sendHeaders.countDown();assertTrue(handled.await(5,TimeUnit.SECONDS));assertFalse(Files.exists(service.location(item)));assertNoPartial();
    }
    @Test void callbackRegisteredOrSubscribedAfterCloseCannotRecreatePartial()throws Exception{
        // Exercise both allowed HTTP callback orders deterministically, beyond server timing.
        HttpResponse.ResponseInfo info=new HttpResponse.ResponseInfo(){public int statusCode(){return 200;}public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}};
        for(boolean registerBeforeClose:List.of(false,true)){
            Path partial=Files.createTempFile(root,"late-",".partial");
            var transfer=new DesktopDownloadService.DownloadTransfer(partial,asset(""),s->{});
            var subscriber=registerBeforeClose?transfer.apply(info):null;
            transfer.close();Files.delete(partial);if(subscriber==null)subscriber=transfer.apply(info);
            var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
            subscriber.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancelled.set(true);}});
            subscriber.onNext(List.of(ByteBuffer.wrap(body)));subscriber.onComplete();
            assertTrue(cancelled.get());CompletableFuture<Path> result=subscriber.getBody().toCompletableFuture();assertThrows(ExecutionException.class,()->result.get(1,TimeUnit.SECONDS));
            assertFalse(Files.exists(partial));
        }
    }
}
