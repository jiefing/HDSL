package com.hdsl.core;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Only processes created by this launcher are ever terminated. */
public final class ProcessService implements AutoCloseable {
    private final Map<String,Process> processes=new ConcurrentHashMap<>();
    private final Map<String,String> states=new ConcurrentHashMap<>();
    private final Map<String,Integer> ports=new ConcurrentHashMap<>();
    // New Harness versions issue a one-time local bootstrap URL. Never write it to disk.
    private final Map<String,URI> webAddresses=new ConcurrentHashMap<>();
    private record Owned(ProcessHandle handle,Instant started){boolean alive(){return handle.isAlive()&&started!=null&&handle.info().startInstant().map(started::equals).orElse(false);}}
    private final Map<String,Map<Long,Owned>> children=new ConcurrentHashMap<>();
    private final Object lifecycle=new Object();
    private boolean closed;
    private final ExecutorService readers;
    public ProcessService(){this(Executors.newVirtualThreadPerTaskExecutor());}
    ProcessService(ExecutorService readers){this.readers=Objects.requireNonNull(readers);}
    public String status(Instance i){Process p=processes.get(i.id());if(p!=null&&p.isAlive())return states.getOrDefault(i.id(),"启动中");if(running(i.id()))return "子进程仍在运行";if(p!=null)return "已退出 ("+p.exitValue()+")";return portFree(i.port())?"未启动":"端口被占用";}
    public boolean running(String id){Process p=processes.get(id);return (p!=null&&p.isAlive())||children.getOrDefault(id,Map.of()).values().stream().anyMatch(Owned::alive);}
    private void captureChildren(String id,Process p){Map<Long,Owned> tracked=children.computeIfAbsent(id,k->new ConcurrentHashMap<>());if(p.isAlive())p.descendants().forEach(h->tracked.putIfAbsent(h.pid(),new Owned(h,h.info().startInstant().orElse(null))));}
    public void start(Instance i,List<String> command,Path workspace,Map<String,String> environment,Consumer<String> log)throws IOException{
      synchronized(lifecycle){
        if(closed)throw new IOException("进程管理器已关闭，不能再启动实例。");
        if(running(i.id()))throw new IOException("实例已经运行。");
        if(!portFree(i.port()))throw new IOException("端口 "+i.port()+" 已被占用。HDSL 不会停止其他程序。");
        Files.createDirectories(workspace);
        ProcessBuilder pb=new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);pb.environment().clear();pb.environment().putAll(environment);
        List<String> secretValues=environment.entrySet().stream().filter(e->e.getKey().matches("(?i).*(api_?key|token|secret|password).*"))
                .map(Map.Entry::getValue).filter(v->v!=null&&v.length()>=4).distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        Process p=pb.start();webAddresses.remove(i.id());processes.put(i.id(),p);ports.put(i.id(),i.port());states.put(i.id(),"启动中");
        children.put(i.id(),new ConcurrentHashMap<>());captureChildren(i.id(),p);
        List<Future<?>> tasks=new ArrayList<>();
        try{
            tasks.add(readers.submit(()->{while(p.isAlive()&&processes.get(i.id())==p){captureChildren(i.id(),p);try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}}));
            log.accept("启动 "+i.name()+" · PID "+p.pid()+" · 端口 "+i.port());
            tasks.add(readers.submit(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null){if(processes.get(i.id())==p)bootstrapAddress(line,i.port()).ifPresent(uri->webAddresses.put(i.id(),uri));log.accept("["+i.name()+"] "+redactValues(line,secretValues));}}catch(IOException e){log.accept("输出读取结束："+redactValues(Objects.toString(e.getMessage(),""),secretValues));}}));
            tasks.add(readers.submit(()->{try{p.waitFor();if(processes.get(i.id())==p)states.put(i.id(),"已退出 ("+p.exitValue()+")");log.accept(i.name()+" 进程退出，代码 "+p.exitValue());}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
        }catch(RuntimeException|Error failure){
            // Executor shutdown/rejection or a failed callback must never orphan pb.start().
            boolean interrupted=Thread.interrupted();
            try{stop(i.id(),ignored->{});}catch(IOException cleanup){failure.addSuppressed(cleanup);forceStopOwned(i.id(),p);}
            finally{tasks.forEach(task->task.cancel(true));if(interrupted)Thread.currentThread().interrupt();}
            if(failure instanceof Error error)throw error;
            throw new IOException("实例启动未完成，已停止本次启动的进程。",failure);
        }
      }
    }
    public boolean awaitReady(Instance i,Duration timeout)throws IOException,InterruptedException{
        HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).proxy(new ProxySelector(){public List<Proxy> select(URI u){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,IOException e){}}).build();
        long deadline=System.nanoTime()+timeout.toNanos();
        while(System.nanoTime()<deadline){
            Process p=processes.get(i.id());if(p==null||!p.isAlive())throw new IOException("Harness 在就绪前退出，请查看任务日志。");
            try{
                var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+i.port()+"/")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding());
                int code=response.statusCode();
                // The new server binds before routes mount and briefly returns 404. Protected
                // surfaces additionally need their printed bootstrap address before Web can open.
                if((code>=200&&code<400)||((code==401||code==403)&&webAddresses.containsKey(i.id()))){states.put(i.id(),"运行中");return true;}
            }catch(IOException ignored){}
            Thread.sleep(400);
        }
        states.put(i.id(),"进程运行中，服务未就绪");return false;
    }
    public void stop(String id,Consumer<String> log)throws IOException{
      synchronized(lifecycle){
        Process p=processes.get(id);if(p==null)return;
        captureChildren(id,p);List<Owned> owned=new ArrayList<>(children.getOrDefault(id,Map.of()).values());Collections.reverse(owned);
        for(Owned child:owned)if(child.alive())child.handle().destroy();if(p.isAlive())p.destroy();
        try{if(!p.waitFor(3,TimeUnit.SECONDS)){for(Owned child:owned)if(child.alive())child.handle().destroyForcibly();p.destroyForcibly();p.waitFor(4,TimeUnit.SECONDS);}for(Owned child:owned)if(child.alive()){child.handle().destroyForcibly();try{child.handle().onExit().get(2,TimeUnit.SECONDS);}catch(ExecutionException|TimeoutException ignored){}}}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("停止被中断",e);}
        if(p.isAlive()||owned.stream().anyMatch(Owned::alive))throw new IOException("进程尚未退出，暂不能修改该实例。");
        children.remove(id);
        webAddresses.remove(id);
        processes.remove(id);states.put(id,"未启动");
        Integer port=ports.remove(id);long releaseDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        if(port!=null)while(!portFree(port)&&System.nanoTime()<releaseDeadline)try{Thread.sleep(80);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        log.accept("已停止实例进程树。");
      }
    }
    private void forceStopOwned(String id,Process p){
        captureChildren(id,p);
        children.getOrDefault(id,Map.of()).values().stream().filter(Owned::alive).forEach(child->child.handle().destroyForcibly());
        if(p.isAlive())p.destroyForcibly();
    }
    public static boolean portFree(int port){
        // Check for a listener instead of binding: a closed Windows listener may leave
        // TIME_WAIT sockets that reject Java's exclusive bind but accept a Node restart.
        // A later bind failure is still reported by the owned Harness process.
        try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),120);return false;}catch(IOException e){return true;}
    }
    public URI webAddress(Instance instance){return webAddresses.getOrDefault(instance.id(),URI.create("http://127.0.0.1:"+instance.port()+"/"));}
    static Optional<URI> bootstrapAddress(String line,int port){
        String plain=line.replaceAll("\u001b\\[[0-9;]*m","");int marker=plain.indexOf("dsh web:");
        if(marker<0)return Optional.empty();
        try{URI uri=URI.create(plain.substring(marker+8).trim().split("\\s+",2)[0]);
            if("http".equals(uri.getScheme())&&Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost())&&uri.getPort()==port&&uri.getUserInfo()==null)return Optional.of(uri);
        }catch(IllegalArgumentException ignored){}
        return Optional.empty();
    }
    static String redactValues(String text,Collection<String> values){for(String value:values)text=text.replace(value,"[已隐藏]");return redact(text);}
    static String redact(String text){return text
            .replaceAll("(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+|basic\\s+)?[^\\s,]+","$1[已隐藏]")
            .replaceAll("(?i)([\\\"']?(?:api[_-]?key|access_token|refresh_token|token|password|secret)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,\\\"'&}]+","$1[已隐藏]")
            .replaceAll("sk-[A-Za-z0-9_-]{12,}","[已隐藏]");}
    @Override public void close(){
      synchronized(lifecycle){
        if(closed)return;closed=true;
        boolean interrupted=Thread.interrupted();
        try{
            for(String id:List.copyOf(processes.keySet()))try{stop(id,s->{});}catch(IOException ignored){Process p=processes.get(id);if(p!=null)forceStopOwned(id,p);}
            webAddresses.clear();readers.shutdownNow();
        }finally{if(interrupted)Thread.currentThread().interrupt();}
      }
    }
}
