package com.hdsl.download;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Downloads published desktop installers. It never executes them or changes installed applications. */
public class DesktopDownloadService {
    public static final Map<String,String> SOURCES = Map.of(
            "deepseek-ai/deepseek-harness", "DeepSeek Harness（官方）",
            "anywhere-labs/dsh-desktop", "DSH Desktop · anywhere-labs（社区）",
            "dataelement/dsh-desktop", "DSH Desktop · dataelement（社区）");
    public record Asset(String id,String source,String version,String name,long size,String url,String sha256,boolean prerelease,String etag) {
        public Asset(String id,String source,String version,String name,long size,String url,String sha256,boolean prerelease){this(id,source,version,name,size,url,sha256,prerelease,"");}
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OFFICIAL = "deepseek-ai/deepseek-harness";
    private static final long MAX_DOWNLOAD = 2L * 1024 * 1024 * 1024;
    private final Path root;
    private final HttpClient http;
    private final URI api;
    private final boolean fixture;

    public DesktopDownloadService(Path root,String proxy) { this(root,proxy,URI.create("https://api.github.com/"),false); }
    DesktopDownloadService(Path root,String proxy,URI api,boolean fixture) {
        this.root=root.toAbsolutePath().normalize();this.api=api;this.fixture=fixture;
        HttpClient.Builder builder=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER);
        if(proxy!=null&&!proxy.isBlank()){URI p=URI.create(proxy);builder.proxy(ProxySelector.of(new InetSocketAddress(p.getHost(),p.getPort())));}
        http=builder.build();
    }
    public List<Asset> releases(String source)throws IOException {
        if(!SOURCES.containsKey(source))throw new IOException("请选择列表中的桌面来源。");
        if(source.equals("deepseek-ai/deepseek-harness"))return officialRelease();
        HttpResponse<byte[]> response=await(http.sendAsync(request(api.resolve("repos/"+source+"/releases?per_page=20"),Duration.ofSeconds(45)),info->new LimitedBody(8*1024*1024)),Duration.ofSeconds(50));
        if(response.statusCode()!=200)throw httpError(response.statusCode());
        JsonNode releases=JSON.readTree(response.body());
        if(releases==null||!releases.isArray())throw new IOException("桌面版发行列表格式无效。");
        List<Asset> result=new ArrayList<>();
        for(JsonNode release:releases){
            if(release.path("draft").asBoolean())continue;
            String version=release.path("tag_name").asText();
            boolean preview=release.path("prerelease").asBoolean()||version.matches("(?i).*(alpha|beta|next|preview|rc).*?");
            for(JsonNode item:release.path("assets")){
                String name=item.path("name").asText(),url=item.path("browser_download_url").asText();
                long size=item.path("size").asLong(),assetId=item.path("id").asLong();
                if(!windowsX64Installer(name)||assetId<1||size<1||size>MAX_DOWNLOAD)continue;
                if(!fixture&&!validAssetUrl(source,url))continue;
                String digest=item.path("digest").asText("");
                String hash=digest.matches("(?i)sha256:[0-9a-f]{64}")?digest.substring(7).toLowerCase(Locale.ROOT):"";
                result.add(new Asset(source+":"+assetId,source,version,name,size,url,hash,preview));
            }
        }
        // Preserve upstream ordering inside each channel; stable releases are offered first.
        result.sort(Comparator.comparing(Asset::prerelease));return List.copyOf(result);
    }
    private List<Asset> officialRelease()throws IOException {
        URI page=fixture?api.resolve("official-download"):URI.create("https://www.deepseek.com/en/download/");
        var response=await(http.sendAsync(HttpRequest.newBuilder(page).timeout(Duration.ofSeconds(30)).header("User-Agent","HDSL/0.2").GET().build(),info->new LimitedBody(2*1024*1024)),Duration.ofSeconds(35));
        if(response.statusCode()!=200)throw new IOException("DeepSeek 官网下载页返回 HTTP "+response.statusCode()+"。");
        String html=new String(response.body(),java.nio.charset.StandardCharsets.UTF_8);
        var links=java.util.regex.Pattern.compile("(?i)href\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html);URI download=null;
        while(links.find()){
            try{URI candidate=page.resolve(links.group(1).replace("&amp;","&"));String path=candidate.getPath();
                if(path==null||!path.startsWith("/desktop/")||candidate.getUserInfo()!=null)continue;
                boolean allowed=fixture&&"127.0.0.1".equals(candidate.getHost())||"https".equals(candidate.getScheme())&&"download.deepseek.com".equals(candidate.getHost());
                if(allowed&&windowsX64Installer(fileName(candidate))){download=candidate;break;}
            }catch(IllegalArgumentException ignored){}
        }
        if(download==null)throw new IOException("官网下载页的 Windows 安装包链接未识别，请查看 https://www.deepseek.com/en/download/ 。");
        for(int redirects=0;redirects<=5;redirects++){
            checkDownloadUri(download);
            var head=await(http.sendAsync(HttpRequest.newBuilder(download).timeout(Duration.ofSeconds(30)).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding()),Duration.ofSeconds(35));
            if(Set.of(301,302,303,307,308).contains(head.statusCode())){download=download.resolve(head.headers().firstValue("location").orElseThrow(()->new IOException("官网安装包重定向缺少地址。")));continue;}
            if(head.statusCode()!=200)throw new IOException("官网安装包信息返回 HTTP "+head.statusCode()+"。");
            long size=head.headers().firstValueAsLong("content-length").orElse(0);if(size<1||size>MAX_DOWNLOAD)throw new IOException("官网下载未提供可识别的安装包大小。");
            String etag=head.headers().firstValue("etag").orElse(""),modified=head.headers().firstValue("last-modified").orElse("");
            if(!strongEtag(etag))throw new IOException("官网下载未提供可核对的文件版本标识（强 ETag），请稍后重试或打开官网。");
            String name=fileName(download);if(!windowsX64Installer(name))throw new IOException("官网安装包重定向后的文件名无法识别。");
            String id="deepseek-ai/deepseek-harness:"+Integer.toUnsignedString(Objects.hash(download.toString(),etag,modified,size));
            return List.of(new Asset(id,"deepseek-ai/deepseek-harness","官网最新版（Windows x64）",name,size,download.toString(),"",false,etag));
        }
        throw new IOException("官网安装包重定向次数过多。");
    }
    private static String fileName(URI uri){String path=uri.getPath();return path==null?"":path.substring(path.lastIndexOf('/')+1);}
    private static boolean strongEtag(String value){return value!=null&&value.matches("\"[\\x21\\x23-\\x7e\\x80-\\xff]*\"");}
    static boolean windowsX64Installer(String name){
        String n=name.toLowerCase(Locale.ROOT);
        return name.matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,180}")&&!n.contains("..")
                &&(n.endsWith(".exe")||n.endsWith(".msi")||n.endsWith(".zip"))
                &&(n.contains("win")||n.endsWith("setup.exe"))
                &&(n.contains("x64")||n.contains("amd64")||n.contains("x86_64"))&&!n.contains("arm64");
    }
    private static boolean validAssetUrl(String source,String url){
        try{URI u=URI.create(url);return "https".equals(u.getScheme())&&"github.com".equals(u.getHost())&&u.getUserInfo()==null&&u.getRawQuery()==null&&u.getFragment()==null&&u.getPath().startsWith("/"+source+"/releases/download/");}catch(IllegalArgumentException e){return false;}
    }
    private void checkDownloadUri(URI uri)throws IOException {
        if(fixture&&"127.0.0.1".equals(uri.getHost()))return;
        String host=Objects.toString(uri.getHost(),"");
        if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||!(host.equals("download.deepseek.com")||host.equals("github.com")||host.equals("release-assets.githubusercontent.com")||host.equals("objects.githubusercontent.com")))
            throw new IOException("桌面下载被重定向到不支持的地址。");
    }
    public Path location(Asset asset)throws IOException {
        if(!SOURCES.containsKey(asset.source())||!asset.id().matches(java.util.regex.Pattern.quote(asset.source())+":[0-9]+")||!windowsX64Installer(asset.name())||asset.size()<1||asset.size()>MAX_DOWNLOAD||asset.sha256()==null||!asset.sha256().isEmpty()&&!asset.sha256().matches("(?i)[0-9a-f]{64}"))throw new IOException("桌面下载条目无效，请刷新列表。");
        Path base=root.resolve("downloads/desktop"),target=base.resolve(asset.source().replace('/','-')).resolve(asset.id().substring(asset.id().lastIndexOf(':')+1)).resolve(asset.name()).normalize();
        if(!target.startsWith(base))throw new IOException("下载路径越界。");return target;
    }
    /** A local checksum detects cache damage; it does not replace a publisher's authenticity check. */
    public boolean isDownloaded(Asset asset)throws IOException {
        Path target=location(asset);
        if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||Files.size(target)!=asset.size())return false;
        String expected=asset.sha256();
        if(expected.isBlank()){
            Path sidecar=checksumPath(target);
            if(!Files.isRegularFile(sidecar,LinkOption.NOFOLLOW_LINKS)||Files.size(sidecar)>66)return false;
            try(InputStream input=Files.newInputStream(sidecar)){expected=new String(input.readNBytes(67),java.nio.charset.StandardCharsets.US_ASCII).strip();}
            if(!expected.matches("(?i)[0-9a-f]{64}"))return false;
        }
        return hash(target).equalsIgnoreCase(expected);
    }
    private static Path checksumPath(Path target){return target.resolveSibling(target.getFileName()+".sha256");}
    private static void moveCompleted(Path source,Path target)throws IOException {
        try{Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    private static void saveLocalChecksum(Path target,String digest)throws IOException {
        Path temporary=Files.createTempFile(target.getParent(),"checksum-",".partial");
        try{Files.writeString(temporary,digest+"\n",java.nio.charset.StandardCharsets.US_ASCII);moveCompleted(temporary,checksumPath(target));}
        finally{Files.deleteIfExists(temporary);}
    }
    public Path download(Asset asset,Consumer<String> log)throws IOException {
        Path target=location(asset);Files.createDirectories(target.getParent());
        if(isDownloaded(asset)){log.accept((asset.sha256().isBlank()?"安装包本地摘要复核通过（发布方未提供 SHA-256）：":"安装包已下载且发布 SHA-256 校验通过：")+target);return target;}
        if(OFFICIAL.equals(asset.source())&&!strongEtag(asset.etag()))throw new IOException("官网下载条目缺少可核对的文件版本标识，请刷新列表。");
        Path partial=Files.createTempFile(target.getParent(),"download-",".partial");
        boolean complete=false;
        DownloadTransfer transfer=new DownloadTransfer(partial,asset,log);
        try{
            URI uri=URI.create(asset.url());
            for(int redirects=0;redirects<=5;redirects++){
                checkDownloadUri(uri);
                HttpRequest.Builder downloadRequest=HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(15)).header("User-Agent","HDSL/0.2").GET();
                if(asset.source().equals("deepseek-ai/deepseek-harness")&&!asset.etag().isBlank())downloadRequest.header("If-Match",asset.etag());
                HttpResponse<Path> response=await(http.sendAsync(downloadRequest.build(),transfer),Duration.ofMinutes(15));
                if(Set.of(301,302,303,307,308).contains(response.statusCode())){
                    String next=response.headers().firstValue("location").orElseThrow(()->new IOException("下载重定向缺少地址。"));uri=uri.resolve(next);continue;
                }
                if(response.statusCode()==412)throw new IOException("官网安装包已更新，请刷新版本列表后重新下载。");
                if(response.statusCode()!=200)throw httpError(response.statusCode());
                if(Files.size(partial)!=asset.size())throw new IOException("安装包大小不符，未保存为可用下载。");
                String digest=hash(partial);
                if(!asset.sha256().isBlank()&&!digest.equalsIgnoreCase(asset.sha256()))throw new IOException("安装包 SHA-256 校验不符，已丢弃本次下载。");
                if(Thread.currentThread().isInterrupted())throw new IOException("桌面下载已取消。");
                moveCompleted(partial,target);
                if(asset.sha256().isBlank())saveLocalChecksum(target,digest);
                complete=true;log.accept("安装包已保存："+target+"。"+(asset.sha256().isBlank()?"发布方未提供 SHA-256；本地摘要 "+digest:"SHA-256 校验通过。"));return target;
            }
            throw new IOException("桌面下载重定向次数过多。");
        }finally{transfer.close();if(!complete)Files.deleteIfExists(partial);}
    }
    private static String hash(Path path)throws IOException {
        try(InputStream input=Files.newInputStream(path)){MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))>=0;){if(Thread.currentThread().isInterrupted())throw new IOException("下载校验已取消。");digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static HttpRequest request(URI uri,Duration timeout){return HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent","HDSL/0.2").header("Accept","application/vnd.github+json").GET().build();}
    private static IOException httpError(int code){return new IOException((code==403||code==429)?"GitHub 访问受限或达到请求额度，请稍后重试。":"下载服务返回 HTTP "+code+"，请检查网络或代理。");}
    private static <T> T await(CompletableFuture<T> pending,Duration timeout)throws IOException {
        try{return pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
        catch(InterruptedException e){pending.cancel(true);Thread.currentThread().interrupt();throw new IOException("下载查询已取消。",e);}
        catch(TimeoutException e){pending.cancel(true);throw new IOException("下载服务超时，请检查网络或代理。",e);}
        catch(ExecutionException e){pending.cancel(true);if(e.getCause() instanceof DownloadValidationException validation)throw validation;throw new IOException("下载未完成，请检查网络、磁盘空间或代理。",e.getCause());}
    }
    private static final class DownloadValidationException extends IOException {DownloadValidationException(String message){super(message);}}
    private static final class RejectedBody implements HttpResponse.BodySubscriber<Path> {
        private final CompletableFuture<Path> result;
        RejectedBody(String message){result=CompletableFuture.failedFuture(new DownloadValidationException(message));}
        public CompletionStage<Path> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){subscription.cancel();}
        public void onNext(List<ByteBuffer> buffers){}
        public void onError(Throwable failure){}
        public void onComplete(){}
    }
    /** Close and handler registration share a lock, including callbacks arriving after HTTP cancellation. */
    static final class DownloadTransfer implements HttpResponse.BodyHandler<Path>,AutoCloseable {
        private final Path partial;private final Asset asset;private final Consumer<String> log;
        private boolean closed;private FileBody body;
        DownloadTransfer(Path partial,Asset asset,Consumer<String> log){this.partial=partial;this.asset=asset;this.log=log;}
        public synchronized HttpResponse.BodySubscriber<Path> apply(HttpResponse.ResponseInfo info){
            if(closed)return new RejectedBody("桌面下载已取消。");
            if(info.statusCode()==200){
                if(OFFICIAL.equals(asset.source())&&!asset.etag().equals(info.headers().firstValue("etag").orElse("")))
                    return new RejectedBody("官网安装包版本标识已变化或缺失，请刷新列表后重新下载。");
                body=new FileBody(partial,asset.size(),log);return body;
            }
            return HttpResponse.BodySubscribers.mapping(new LimitedBody(128*1024),ignored->null);
        }
        public synchronized void close(){closed=true;if(body!=null)body.abort();}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();final long limit;long total;Flow.Subscription subscription;boolean done;
        LimitedBody(long limit){this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> buffers){if(done)return;for(ByteBuffer b:buffers)total+=b.remaining();if(total>limit){subscription.cancel();onError(new IOException("服务响应超出大小限制。"));}else delegate.onNext(buffers);}
        public void onError(Throwable e){if(!done){done=true;delegate.onError(e);}}
        public void onComplete(){if(!done){done=true;delegate.onComplete();}}
    }
    private static final class FileBody implements HttpResponse.BodySubscriber<Path> {
        final CompletableFuture<Path> future=new CompletableFuture<>();final Path path;final long limit;final Consumer<String> log;OutputStream output;Flow.Subscription subscription;long received,lastLog;boolean done;
        FileBody(Path path,long limit,Consumer<String> log){this.path=path;this.limit=Math.min(limit,MAX_DOWNLOAD);this.log=log;}
        public CompletionStage<Path> getBody(){return future;}
        public synchronized void onSubscribe(Flow.Subscription s){subscription=s;if(done){s.cancel();return;}try{output=Files.newOutputStream(path,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING);s.request(1);}catch(IOException e){s.cancel();onError(e);}}
        public synchronized void onNext(List<ByteBuffer> buffers){if(done)return;try{
            byte[] bytes=new byte[65536];for(ByteBuffer b:buffers){received+=b.remaining();if(received>limit)throw new IOException("安装包超过声明大小。");while(b.hasRemaining()){int n=Math.min(bytes.length,b.remaining());b.get(bytes,0,n);output.write(bytes,0,n);}}
            long now=System.nanoTime();if(now-lastLog>2_000_000_000L){lastLog=now;log.accept(String.format(Locale.ROOT,"桌面安装包：%.1f / %.1f MiB",received/1048576d,limit/1048576d));}subscription.request(1);
        }catch(Throwable e){subscription.cancel();onError(e);}}
        public synchronized void onError(Throwable e){if(done)return;done=true;try{if(output!=null)output.close();}catch(IOException ignored){}future.completeExceptionally(e);}
        public synchronized void onComplete(){if(done)return;done=true;try{output.close();future.complete(path);}catch(IOException e){future.completeExceptionally(e);}}
        synchronized void abort(){if(subscription!=null)subscription.cancel();onError(new IOException("桌面下载已取消。"));}
    }
}
