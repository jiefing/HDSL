package com.hdsl.core;

import com.hdsl.runtime.*;
import com.hdsl.pack.*;
import com.hdsl.ui.*;
import com.hdsl.account.AccountService;
import com.hdsl.account.LaunchBinding;
import com.hdsl.download.*;
import java.awt.Desktop;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Application orchestration; UI never performs package or filesystem operations. */
public final class Controller implements UiActions, AutoCloseable {
    private final InstanceStore store;
    private final AccountService accounts;
    private final ProcessService processes=new ProcessService();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService controls=Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicBoolean closed=new AtomicBoolean(),storeClosed=new AtomicBoolean();
    private final Set<Thread> discoveryThreads=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicLong discoveryEpoch=new java.util.concurrent.atomic.AtomicLong();
    private final Deque<String> logs=new ArrayDeque<>();
    private volatile Future<?> active;
    private volatile Thread activeThread;
    private volatile boolean cancelRequested;
    private volatile String status="就绪";
    private volatile String preparingInstance="",preparingStage="";
    private volatile List<String> available=List.of();
    private volatile List<UiState.PluginItem> plugins=List.of();
    private volatile String pluginInstance="", pluginProfile="", packPreview="";
    private volatile RuntimeService runtimeService;
    private RuntimeService versionQuerySource;
    private CompletableFuture<UiState> versionQuery;
    private final Map<String,String> compatibility=new ConcurrentHashMap<>();
    private final Map<String,LaunchBinding> accountBindings=new ConcurrentHashMap<>();
    private volatile DesktopDownloadService desktopService;
    private volatile PluginCatalogService catalogService;
    private volatile UiState.DesktopCatalog desktopCatalog=new UiState.DesktopCatalog("deepseek-ai/deepseek-harness",List.of(),"");
    private volatile UiState.PluginCatalog pluginCatalog=new UiState.PluginCatalog("",1,0,false,false,"",List.of());
    private volatile UiState.PluginDetails pluginDetails=new UiState.PluginDetails("","UNRESOLVED","",false,List.of());
    private final Map<String,DesktopDownloadService.Asset> desktopAssets=new ConcurrentHashMap<>();
    private final Map<String,String> desktopFiles=new ConcurrentHashMap<>();
    private final Map<String,List<PluginCatalogService.InstallTarget>> catalogTargets=new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong desktopQueryId=new java.util.concurrent.atomic.AtomicLong(),catalogQueryId=new java.util.concurrent.atomic.AtomicLong(),detailsQueryId=new java.util.concurrent.atomic.AtomicLong();

    public Controller(Path root)throws IOException{this(root,null);}
    Controller(Path root,RuntimeService runtime)throws IOException{store=new InstanceStore(root);accounts=new AccountService(root);runtimeService=runtime;Files.createDirectories(store.root().resolve("cache"));log("HDSL 0.2.0 预览版 · 数据目录 "+store.root());}
    Controller(Path root,RuntimeService runtime,DesktopDownloadService desktops)throws IOException{this(root,runtime);desktopService=desktops;}
    public Path root(){return store.root();}
    private synchronized RuntimeService runtimes(){if(runtimeService==null)runtimeService=new RuntimeService(store.root(),store.setting("registry","https://registry.npmjs.org"),store.setting("proxy",""));return runtimeService;}
    private synchronized DesktopDownloadService desktops(){if(desktopService==null)desktopService=new DesktopDownloadService(store.root(),store.setting("proxy",""));return desktopService;}
    private synchronized PluginCatalogService catalog(){if(catalogService==null){String proxy=store.setting("proxy","");catalogService=new PluginCatalogService(proxy.isBlank()?null:URI.create(proxy));}return catalogService;}
    private PackService packs(){String proxy=store.setting("proxy","");return proxy.isBlank()?new PackService():new PackService(URI.create(proxy));}
    public synchronized void log(String message){
        String safe=ProcessService.redact(message);
        String progress=TaskProgress.describe(safe);
        if(busy.get()&&!cancelRequested&&progress!=null)status="进行中："+progress;
        String line=LocalTime.now().withNano(0)+"  "+safe;
        logs.addLast(line);while(logs.size()>800)logs.removeFirst();
        try{Path directory=store.root().resolve("logs");Files.createDirectories(directory);Files.writeString(directory.resolve("launcher-"+LocalDate.now()+".log"),line+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(IOException ignored){/* A read-only log directory must not stop an otherwise usable instance. */}
    }
    private synchronized String logText(){return String.join("\n",logs);}

    public UiState snapshot(){
        accountBindings.forEach((id,binding)->{if(!processes.running(id)&&accountBindings.remove(id,binding))closeBinding(binding);});
        List<UiState.InstanceItem> items=store.list().stream().map(i->new UiState.InstanceItem(i.id(),i.name(),i.runtimeVersion(),i.profile(),i.port(),preparingInstance.equals(i.id())&&!preparingStage.isBlank()?preparingStage:processes.status(i),store.directory(i).toString(),profiles(i),i.accountId())).toList();
        List<UiState.RuntimeItem> runtimeItems=new ArrayList<>();try{for(String v:runtimes().installedVersions())runtimeItems.add(new UiState.RuntimeItem(v,true,compatibility.getOrDefault(v,"待检测")));}catch(IOException ignored){}
        boolean pluginCacheMatches=items.stream().anyMatch(i->i.id().equals(store.selected())&&i.id().equals(pluginInstance)&&i.profile().equals(pluginProfile));
        List<UiState.AccountItem> accountItems;
        try{accountItems=accounts.list().stream().map(a->new UiState.AccountItem(a.id(),a.name(),a.provider(),a.baseUrl(),a.model(),a.hasKey(),a.status())).toList();}
        catch(IOException e){accountItems=List.of();status="账户列表无法读取："+e.getMessage();}
        UiState.DesktopCatalog desktop=desktopCatalog;
        desktop=new UiState.DesktopCatalog(desktop.source(),desktop.items().stream().map(a->new UiState.DesktopItem(a.id(),a.source(),a.version(),a.name(),a.size(),a.url(),a.sha256(),desktopFiles.getOrDefault(a.id(),""),a.prerelease())).toList(),desktop.message());
        return new UiState(store.selected(),new UiState.Settings(store.setting("proxy",""),store.setting("registry","https://registry.npmjs.org"),store.setting("background","")),items,runtimeItems,pluginCacheMatches?plugins:List.of(),available,packPreview,logText(),status,accountItems,desktop,pluginCatalog,pluginDetails);
    }

    @Override public synchronized CompletableFuture<UiState> dispatch(String action,Map<String,String> args){
        if(closed.get())return CompletableFuture.failedFuture(new IOException("启动器已关闭。"));
        if("refreshVersions".equals(action))return refreshVersions();
        if(Set.of("refreshDesktop","searchPluginCatalog","inspectPluginProject").contains(action))return discover(action,args);
        if(Set.of("refresh","selectInstance","openWeb","openInstanceFolder","cancelTask","stop","openPluginProject","openDownloadFolder").contains(action)){
            return CompletableFuture.supplyAsync(()->{try{switch(action){
                case "selectInstance"->store.select(required(args,"id"));
                case "openWeb"->Desktop.getDesktop().browse(webAddress(required(args,"id")));
                case "openInstanceFolder"->{Path dir=store.directory(store.find(required(args,"id")));Files.createDirectories(dir);Desktop.getDesktop().open(dir.toFile());}
                case "cancelTask"->{cancelRequested=true;cancelDiscovery();status=busy.get()?"进行中：取消任务，正在收尾":"查询已取消";Thread current=activeThread;if(current!=null)current.interrupt();log("已请求取消当前任务，正在收尾。");}
                case "stop"->{String id=required(args,"id");processes.stop(id,this::log);closeBinding(id);status="已停止";}
                case "openPluginProject"->{String repo=required(args,"repo");if(!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))throw new IOException("项目地址无效。");Desktop.getDesktop().browse(URI.create("https://github.com/"+repo));}
                case "openDownloadFolder"->{String id=required(args,"id");var asset=desktopAssets.get(id);if(asset==null||!desktops().isDownloaded(asset)){desktopFiles.remove(id);throw new IOException("安装包缺失或校验未通过，请重新下载。");}Desktop.getDesktop().open(desktops().location(asset).getParent().toFile());}
            }return snapshot();}catch(Exception e){throw new CompletionException(e);}},controls);
        }
        if(!busy.compareAndSet(false,true))return CompletableFuture.failedFuture(new IOException("当前有任务正在执行，请等待完成或在任务页取消。"));
        CompletableFuture<UiState> result=new CompletableFuture<>();
        cancelRequested=false;
        status="进行中："+actionLabel(action);
        active=worker.submit(()->{
            activeThread=Thread.currentThread();Throwable failure=null;
            try{checkActive();execute(action,args);status="完成："+actionLabel(action);}
            catch(Throwable e){if(cancelRequested){status="已取消："+actionLabel(action);log("任务已取消，已完成的下载缓存会供下次安装使用。");}else{failure=e;status="未完成："+actionLabel(action);log("任务失败："+Objects.toString(e.getMessage(),e.getClass().getSimpleName()));}}
            finally{activeThread=null;active=null;busy.set(false);Thread.interrupted();if(closed.get())finishClose();}
            if(failure==null)result.complete(snapshot());else result.completeExceptionally(failure);
        });
        return result;
    }

    /** Public version discovery does not reserve the instance mutation worker. */
    private synchronized CompletableFuture<UiState> refreshVersions(){
        RuntimeService source=runtimes();
        if(source==versionQuerySource&&versionQuery!=null&&!versionQuery.isDone())return versionQuery.copy();
        versionQuerySource=source;
        long epoch=discoveryEpoch.get();
        versionQuery=CompletableFuture.supplyAsync(()->{
            Thread thread=Thread.currentThread();discoveryThreads.add(thread);
            try{
                if(discoveryCancelled(epoch))return discoverySnapshot();
                List<String> latest=source.availableVersions();
                synchronized(this){
                    // A response from an old registry/proxy must not replace the new source's list.
                    if(runtimeService==source&&!discoveryCancelled(epoch)){available=List.copyOf(latest);log("已读取 "+available.size()+" 个 Harness 版本。列表不会自动升级已安装实例。");}
                }
                return discoverySnapshot();
            }catch(IOException failure){if(discoveryCancelled(epoch)){Thread.interrupted();return snapshot();}throw new CompletionException(failure);}
            finally{discoveryThreads.remove(thread);Thread.interrupted();}
        },controls);
        // A closed dialog may cancel its own future without cancelling another caller's query.
        return versionQuery.copy();
    }

    private CompletableFuture<UiState> discover(String action,Map<String,String> args){
        long ticket=switch(action){case "refreshDesktop"->desktopQueryId.incrementAndGet();case "searchPluginCatalog"->catalogQueryId.incrementAndGet();default->detailsQueryId.incrementAndGet();};
        long epoch=discoveryEpoch.get();
        return CompletableFuture.supplyAsync(()->{Thread thread=Thread.currentThread();discoveryThreads.add(thread);try{
            if(discoveryCancelled(epoch))return discoverySnapshot();
            switch(action){
                case "refreshDesktop"->{
                    DesktopDownloadService service=desktops();String source=args.getOrDefault("source","deepseek-ai/deepseek-harness");
                    List<DesktopDownloadService.Asset> assets=service.releases(source);List<UiState.DesktopItem> rows=new ArrayList<>();
                    for(var asset:assets){Path path=service.location(asset);String saved=service.isDownloaded(asset)?path.toString():"";
                        rows.add(new UiState.DesktopItem(asset.id(),asset.source(),asset.version(),asset.name(),asset.size(),asset.url(),asset.sha256(),saved,asset.prerelease()));}
                    synchronized(this){if(service==desktopService&&ticket==desktopQueryId.get()&&!discoveryCancelled(epoch)){
                        assets.forEach(a->desktopAssets.put(a.id(),a));rows.forEach(r->{if(r.path().isBlank())desktopFiles.remove(r.id());else desktopFiles.put(r.id(),r.path());});
                        desktopCatalog=new UiState.DesktopCatalog(source,rows,rows.isEmpty()?"此来源暂未发布可下载的 Windows x64 桌面安装包。":"安装包下载后可打开所在文件夹，按该桌面应用的提示安装。");
                    }}
                }
                case "searchPluginCatalog"->{
                    PluginCatalogService service=catalog();String query=args.getOrDefault("query","");int page=Integer.parseInt(args.getOrDefault("page","1"));
                    var result=service.search(query,page,Boolean.parseBoolean(args.getOrDefault("refresh","false")),()->discoveryCancelled(epoch));
                    synchronized(this){if(service==catalogService&&ticket==catalogQueryId.get()&&!discoveryCancelled(epoch))pluginCatalog=new UiState.PluginCatalog(query,result.page(),result.total(),result.hasNext(),result.fromCache(),result.warning(),result.entries().stream().map(e->new UiState.PluginCatalogItem(e.repo(),e.name(),e.description(),e.stars(),e.url(),e.spec(),e.status().name())).toList());}
                }
                case "inspectPluginProject"->{
                    PluginCatalogService service=catalog();String repo=required(args,"repo");
                    var result=service.resolve(repo,Boolean.parseBoolean(args.getOrDefault("refresh","false")),()->discoveryCancelled(epoch));
                    synchronized(this){if(service==catalogService&&ticket==detailsQueryId.get()&&!discoveryCancelled(epoch)){
                        catalogTargets.put(repo,result.targets());pluginDetails=new UiState.PluginDetails(repo,result.status().name(),result.message(),result.fromCache(),result.targets().stream().map(t->new UiState.InstallTarget(t.name(),t.version(),t.spec(),t.packagePath(),t.evidenceUrl())).toList());
                    }}
                }
            }
            return discoverySnapshot();
        }catch(Exception e){if(discoveryCancelled(epoch)){Thread.interrupted();return snapshot();}throw new CompletionException(e);}
        finally{discoveryThreads.remove(thread);Thread.interrupted();}},controls);
    }
    private boolean discoveryCancelled(long epoch){return closed.get()||discoveryEpoch.get()!=epoch||Thread.currentThread().isInterrupted();}
    private UiState discoverySnapshot(){Thread.interrupted();return snapshot();}
    private synchronized void cancelDiscovery(){discoveryEpoch.incrementAndGet();versionQuery=null;discoveryThreads.forEach(Thread::interrupt);}
    private void checkActive()throws InterruptedException{if(closed.get()||cancelRequested||Thread.currentThread().isInterrupted())throw new InterruptedException("任务已取消");}
    private void validateAccount(String id)throws IOException{if(!id.isBlank()&&accounts.list().stream().noneMatch(a->a.id().equals(id)))throw new IOException("所选账户不存在，请重新选择。");}
    private void closeBinding(String id){LaunchBinding binding=accountBindings.remove(id);if(binding!=null)closeBinding(binding);}
    private void closeBinding(LaunchBinding binding){try{binding.close();}catch(IOException e){log(e.getMessage());}}

    private void execute(String action,Map<String,String> a)throws Exception{
        switch(action){
            case "createInstance"->{String profile=a.getOrDefault("profile","web");if(!"web".equals(profile))throw new IOException("空白实例使用 web 模板；自定义 profile 可通过整合包导入。");String accountId=a.getOrDefault("accountId","");validateAccount(accountId);Instance i=new Instance(InstanceStore.newId(),required(a,"name"),required(a,"version"),profile,port(a),"",accountId);Files.createDirectories(store.workspace(i));Files.createDirectories(store.home(i));try{store.add(i);}catch(IOException e){moveToTrash(store.directory(i),"unregistered-"+i.id());throw e;}log("已创建实例 "+i.name());}
            case "saveAccount"->{accounts.save(a.getOrDefault("id",""),required(a,"name"),required(a,"provider"),a.getOrDefault("baseUrl",""),required(a,"model"),a.getOrDefault("apiKey",""));log("账户已保存；启动实例时应用所选账户，尚未验证 API 连接。");}
            case "deleteAccount"->{String id=required(a,"id");if(store.list().stream().anyMatch(i->i.accountId().equals(id)))throw new IOException("此账户仍被实例使用，请先在实例设置中解除绑定。");accounts.delete(id);log("账户已删除。");}
            case "bindAccount"->{Instance i=store.find(required(a,"id"));ensureStopped(i);String account=a.getOrDefault("accountId","");validateAccount(account);store.replace(new Instance(i.id(),i.name(),i.runtimeVersion(),i.profile(),i.port(),i.customCommand(),account));log("实例账户已更新，将在下次启动时生效。");}
            case "downloadDesktop"->{String id=required(a,"id");DesktopDownloadService.Asset asset=desktopAssets.get(id);if(asset==null)throw new IOException("请先刷新桌面版列表并选择安装包。");desktopFiles.put(id,desktops().download(asset,this::log).toString());}
            case "installCatalogPlugin"->{Instance i=store.find(required(a,"id"));ensureStopped(i);String repo=required(a,"repo"),spec=required(a,"spec");if(catalogTargets.getOrDefault(repo,List.of()).stream().noneMatch(t->t.spec().equals(spec)))throw new IOException("请先检查项目并选择已确认的安装包。");new PluginService(runtimes()).mutate("add",spec,i.runtimeVersion(),i.profile(),store.home(i),store.workspace(i),this::log);refreshPlugins(i);}
            case "launch"->launch(store.find(required(a,"id")));
            case "saveSettings"->saveSettings(a);
            case "installRuntime"->{runtimes().install(required(a,"version"),this::log);probe(required(a,"version"));}
            case "probeRuntime"->probe(required(a,"version"));
            case "removeRuntime"->{String v=required(a,"version");InstanceStore.validateVersion(v);if(store.list().stream().anyMatch(i->i.runtimeVersion().equals(v)))throw new IOException("有实例引用此运行时，请先调整或删除相关实例。");moveToTrash(runtimes().runtimeDirectory(v),"runtime-"+v);compatibility.remove(v);}
            case "refreshPlugins"->{Instance i=store.find(required(a,"id"));refreshPlugins(i);}
            case "addPlugin","updatePlugin","removePlugin"->{Instance i=store.find(required(a,"id"));ensureStopped(i);String operation=switch(action){case "addPlugin"->"add";case "updatePlugin"->"update";default->"remove";};new PluginService(runtimes()).mutate(operation,required(a,action.equals("addPlugin")?"spec":"name"),i.runtimeVersion(),i.profile(),store.home(i),store.workspace(i),this::log);refreshPlugins(i);}
            case "previewPack"->{PackInspection p=packs().inspect(Path.of(required(a,"path")));packPreview=describe(p);log("整合包检查完成。尚未安装。");}
            case "importPack"->importPack(Path.of(required(a,"path")),a.getOrDefault("name",""),a.getOrDefault("version",""));
            case "exportPack"->{Instance i=store.find(required(a,"id"));ensureStopped(i);if("home".equals(a.get("mode")))packs().exportHome(store.home(i),i.profile(),i.runtimeVersion(),packName(i),"1.0.0",Path.of(required(a,"path")),this::log);else packs().exportProfile(store.home(i),i.profile(),i.runtimeVersion(),packName(i),"1.0.0",Path.of(required(a,"path")),this::log);log("导出完成。凭据与私有运行数据不进入分享包；补丁配置需在新实例重新配置。");}
            case "copyInstance"->{Instance i=store.find(required(a,"id"));duplicate(i,required(a,"name"),i.runtimeVersion());}
            case "saveInstance"->saveInstance(a);
            case "deleteInstance"->{Instance i=store.find(required(a,"id"));ensureStopped(i);Path old=store.directory(i);Path trash=moveToTrash(old,i.id());try{store.remove(i.id());}catch(IOException e){if(trash!=null)Files.move(trash,old);throw e;}log("实例已移入回收目录，可从 "+store.root().resolve("trash")+" 恢复。");}
            default->throw new IOException("未知操作："+action);
        }
    }

    private void launch(Instance i)throws Exception{
        RuntimeService runtime=runtimes();
        preparingInstance=i.id();
        try{
        if(runtime.installedVersions().contains(i.runtimeVersion())){preparingStage="准备中";status="进行中：准备已安装的 Harness "+i.runtimeVersion();}
        else{preparingStage="下载中";status="进行中：首次使用，下载 Harness "+i.runtimeVersion()+" 和依赖";log("尚未安装 Harness "+i.runtimeVersion()+"，将自动下载。完成后继续启动；下次可复用已安装版本。");}
        runtime.install(i.runtimeVersion(),this::log);
        checkActive();
        preparingStage="检测兼容性";status="进行中：检查 Harness "+i.runtimeVersion()+" 启动参数";
        List<String> command;
        if(!i.customCommand().isBlank()){
            // A legacy command is locally authored and kept intact; never interpolate pack fields into it.
            log("使用旧实例保存的自定义命令。端口请与实例设置保持一致。");
            command=System.getProperty("os.name").toLowerCase().contains("win")?List.of("cmd.exe","/d","/s","/c",i.customCommand()):List.of("/bin/sh","-c",i.customCommand());
        }else command=runtime.launchCommand(i.runtimeVersion(),i.profile(),i.port(),store.home(i),store.workspace(i),this::log);
        Map<String,String> environment=new HashMap<>(System.getenv());runtime.configureEnvironment(environment,store.home(i),store.workspace(i));
        LaunchBinding binding=null;
        try{
            if(!i.accountId().isBlank()){
                if(!i.customCommand().isBlank())throw new IOException("旧自定义启动命令不能自动注入账户，请先使用标准启动方式。");
                binding=accounts.prepare(i.accountId(),runtime.runtimeDirectory(i.runtimeVersion()),runtime.inspect(i.runtimeVersion(),this::log),store.directory(i).resolve(".hdsl-account"));
                command=binding.appendTo(command);binding.applyEnvironment(environment);
            }
            checkActive();
            processes.start(i,command,store.workspace(i),environment,this::log);
            if(binding!=null)binding.awaitValidation(Duration.ofSeconds(20));
            checkActive();
            if(binding!=null){LaunchBinding old=accountBindings.put(i.id(),binding);binding=null;if(old!=null)closeBinding(old);}
        }catch(Exception failure){
            // Clear interruption while terminating the owned process; overlays must outlive it.
            boolean interrupted=Thread.interrupted();
            try{processes.stop(i.id(),this::log);}catch(IOException stopFailure){failure.addSuppressed(stopFailure);}
            finally{if(interrupted)Thread.currentThread().interrupt();}
            if(binding!=null&&processes.running(i.id())){accountBindings.put(i.id(),binding);binding=null;}
            throw failure;
        }finally{if(binding!=null)closeBinding(binding);environment.clear();}
        preparingStage="";status="进行中：等待 Harness 网页服务就绪";
        try{if(processes.awaitReady(i,Duration.ofSeconds(90)))log("服务已就绪：http://127.0.0.1:"+i.port());else log("进程仍在运行，但 90 秒内未确认网页服务就绪。可查看日志或停止。");}
        catch(InterruptedException e){Thread.interrupted();try{processes.stop(i.id(),this::log);closeBinding(i.id());}finally{Thread.currentThread().interrupt();}throw new IOException("启动任务已取消。",e);}
        }finally{preparingStage="";preparingInstance="";}
    }
    private void probe(String version)throws IOException{var c=runtimes().inspect(version,this::log);compatibility.put(version,c.summary());log(c.summary());}
    URI webAddress(String id)throws IOException{Instance i=store.find(id);if(!processes.running(i.id()))throw new IOException("请先启动当前实例。");if(!"运行中".equals(processes.status(i)))throw new IOException("网页服务尚未确认就绪，请等待启动完成或检查任务日志。");return processes.webAddress(i);}
    private void refreshPlugins(Instance i)throws IOException{var entries=new PluginService(runtimes()).list(i.runtimeVersion(),i.profile(),store.home(i),store.workspace(i),this::log);plugins=entries.stream().map(p->new UiState.PluginItem(p.id(),p.version(),p.detail(),p.official()?"官方":"社区 / 本地")).toList();pluginProfile=i.profile();pluginInstance=i.id();}
    private int port(Map<String,String> a)throws IOException{String text=a.getOrDefault("port","");try{int value=text.isBlank()?availablePort():Integer.parseInt(text);if(value<1024||value>65535)throw new IOException("端口须为 1024–65535。");return value;}catch(NumberFormatException e){throw new IOException("端口必须为整数。");}}
    private int availablePort()throws IOException{Set<Integer> used=new HashSet<>();store.list().forEach(i->used.add(i.port()));for(int p=3080;p<65536;p++){if(Thread.currentThread().isInterrupted())throw new IOException("分配端口已取消");if(!used.contains(p)&&ProcessService.portFree(p))return p;}throw new IOException("没有可用端口。");}
    private List<String> profiles(Instance i){Set<String> names=new TreeSet<>();names.add(i.profile());Path directory=store.home(i).resolve("profiles");if(Files.isDirectory(directory))try(var stream=Files.list(directory)){stream.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)&&!p.getFileName().toString().equals("node_modules")).forEach(p->names.add(p.getFileName().toString()));}catch(IOException ignored){}return List.copyOf(names);}
    private void ensureStopped(Instance i)throws IOException{if(processes.running(i.id()))throw new IOException("请先停止实例，再修改其文件。");if(!ProcessService.portFree(i.port()))throw new IOException("实例端口正在被占用，无法确认可安全修改。");}
    private void saveSettings(Map<String,String> a)throws IOException{
        String proxy=a.getOrDefault("proxy","").trim(),registry=a.getOrDefault("registry","https://registry.npmjs.org").trim();
        try{URI r=URI.create(registry);if(!Set.of("https","http").contains(r.getScheme())||r.getHost()==null||r.getUserInfo()!=null)throw new IllegalArgumentException();if(!proxy.isBlank()){URI p=URI.create(proxy);if(!"http".equals(p.getScheme())||p.getHost()==null||p.getPort()<1||p.getPort()>65535||p.getUserInfo()!=null)throw new IllegalArgumentException();}}catch(IllegalArgumentException e){throw new IOException("Registry 须为 HTTP(S) 地址；代理请填写如 http://127.0.0.1:7890，且不包含凭据。");}
        synchronized(this){
            boolean sourceChanged=!registry.equals(store.setting("registry","https://registry.npmjs.org"))||!proxy.equals(store.setting("proxy",""));
            store.settings(Map.of("proxy",proxy,"registry",registry,"background",a.getOrDefault("background","")));
            if(sourceChanged){runtimeService=null;available=List.of();desktopService=null;catalogService=null;desktopQueryId.incrementAndGet();catalogQueryId.incrementAndGet();detailsQueryId.incrementAndGet();catalogTargets.clear();}
        }
        log("设置已保存。将用于后续任务。");
    }
    private void saveInstance(Map<String,String> a)throws Exception{
        Instance old=store.find(required(a,"id"));ensureStopped(old);
        String version=required(a,"version"),profile=required(a,"profile"),name=required(a,"name"),accountId=a.getOrDefault("accountId",old.accountId());validateAccount(accountId);
        InstanceStore.validateVersion(version);InstanceStore.validateProfile(profile);
        if(!profile.equals("web")&&!Files.isRegularFile(store.home(old).resolve("profiles").resolve(profile).resolve("package.json")))throw new IOException("这个 profile 尚未初始化，请选择已有 profile 或导入整合包。");
        int requestedPort=port(a);
        if(requestedPort!=old.port()&&store.list().stream().anyMatch(i->i.port()==requestedPort))throw new IOException("端口已被其他实例使用。");
        if(!version.equals(old.runtimeVersion())){
            Instance source=new Instance(old.id(),old.name(),old.runtimeVersion(),profile,old.port(),old.customCommand(),old.accountId());
            Instance created=duplicate(source,name,version);
            store.replace(new Instance(created.id(),created.name(),created.runtimeVersion(),created.profile(),requestedPort==old.port()?created.port():requestedPort,created.customCommand(),accountId));
            log("已复制所选 profile 到新版本实例；原实例保留，并为副本分配独立端口。");
        }else store.replace(new Instance(old.id(),name,version,profile,requestedPort,old.customCommand(),accountId));
    }
    private Instance duplicate(Instance source,String name,String version)throws Exception{
        if(source.profile().equals("web")&&!Files.isRegularFile(store.home(source).resolve("profiles/web/package.json"))){
            ensureStopped(source);Instance target=new Instance(InstanceStore.newId(),name,version,"web",availablePort(),"",source.accountId());
            Files.createDirectories(store.home(target));Files.createDirectories(store.workspace(target));
            try{store.add(target);}catch(IOException failure){moveToTrash(store.directory(target),"unregistered-"+target.id());throw failure;}
            log("已复制尚未初始化的空白实例。");return target;
        }
        ensureStopped(source);Path folder=Files.createTempDirectory(store.root().resolve("cache"),"copy-");
        try{Path archive=folder.resolve("copy.dspack");packs().exportProfile(store.home(source),source.profile(),source.runtimeVersion(),packName(source),"1.0.0",archive,this::log);Instance imported=importPack(archive,name,version);Instance target=new Instance(imported.id(),imported.name(),imported.runtimeVersion(),imported.profile(),imported.port(),imported.customCommand(),source.accountId());store.replace(target);log("复制的是可分享配置和插件；原实例的凭据、会话保持在原处。");return target;}finally{deleteStaging(folder);}
    }
    private static String packName(Instance i){return i.name().matches("[a-z0-9]+(?:-[a-z0-9]+)*")?i.name():"hdsl-"+i.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$","");}
    private Instance importPack(Path archive,String name,String selectedVersion)throws Exception{
        PackService pack=packs();PackInspection inspected=pack.inspect(archive);packPreview=describe(inspected);
        String version=resolvePackVersion(inspected,selectedVersion);
        if(version.isBlank())throw new IOException("包未声明 DSH 版本，请在导入框填写一个精确版本号。");
        InstanceStore.validateVersion(version);
        Path stagingBase=store.root().resolve("cache/pack-staging");Files.createDirectories(stagingBase);Path stage=Files.createTempDirectory(stagingBase,"job-");
        String id=InstanceStore.newId();Path destination=store.root().resolve("instances").resolve(id);boolean registered=false;
        try{
            Path home=stage.resolve("dsh-home"),workspace=stage.resolve("workspace");Files.createDirectories(workspace);
            pack.extract(inspected,home,this::log);
            RuntimeService runtime=runtimes();runtime.install(version,this::log);
            Instance target=new Instance(id,name.isBlank()?inspected.name():name,version,inspected.defaultProfile(),availablePort(),"");
            InstanceStore.validateProfile(target.profile());
            // pnpm can create absolute Windows junctions: install dependencies at their final location.
            // The instance stays absent from the launcher index until every restoration step succeeds.
            Files.move(stage,destination);
            if(!inspected.format().toLowerCase(Locale.ROOT).contains("snapshot"))for(String profile:inspected.profiles())runtime.restoreProfile(store.home(target),profile,store.workspace(target),true,this::log);
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("安装已取消");
            store.add(target);registered=true;
            plugins=List.of();log("整合包已安装到新实例："+target.name()+"。可以从启动页启动。");
            return target;
        }catch(Exception failure){
            if(!registered&&Files.exists(destination,LinkOption.NOFOLLOW_LINKS))try{Path kept=moveToTrash(destination,"failed-import-"+id);log("导入未完成，本次文件保留在："+kept);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }finally{if(!registered)deleteStaging(stage);}
    }
    private String describe(PackInspection p){return "名称："+p.name()+"\n格式："+p.format()+"\n包版本："+p.version()+"\nHarness："+Objects.toString(p.dshVersion(),"未声明，请指定")+"\nProfile："+String.join(", ",p.profiles())+"\n解压内容约："+String.format(Locale.ROOT,"%.1f MiB",p.unpackedBytes()/1048576d)+"\n安装位置：新的独立实例\n"+String.join("\n",p.warnings());}
    private String resolvePackVersion(PackInspection p,String requested)throws IOException{if(!requested.isBlank())return requested;List<String> installed=runtimes().installedVersions();String preferred=Objects.toString(p.dshVersion(),"");if(installed.contains(preferred))return preferred;Set<String> compatible=new LinkedHashSet<>();p.manifest().path("dshVersions").forEach(n->{if(n.isTextual())compatible.add(n.asText());});for(String candidate:installed)if(compatible.contains(candidate))return candidate;if(!preferred.isBlank())return preferred;if(!compatible.isEmpty())return compatible.iterator().next();return installed.isEmpty()?"":installed.getFirst();}
    private Path moveToTrash(Path source,String name)throws IOException{Path normalized=source.toAbsolutePath().normalize();if(!normalized.startsWith(store.root())||normalized.equals(store.root()))throw new IOException("目标越出数据目录。");if(!Files.exists(normalized,LinkOption.NOFOLLOW_LINKS))return null;Path trash=store.root().resolve("trash");Files.createDirectories(trash);Path dest=trash.resolve(name+"-"+System.currentTimeMillis());Files.move(normalized,dest);return dest;}
    private void deleteStaging(Path folder)throws IOException{Path normalized=folder.toAbsolutePath().normalize();Path cache=store.root().resolve("cache").toAbsolutePath().normalize();if(!normalized.startsWith(cache)||normalized.equals(cache))throw new IOException("暂存清理路径不合法。");if(!Files.exists(normalized,LinkOption.NOFOLLOW_LINKS))return;Files.walkFileTree(normalized,new SimpleFileVisitor<>(){public FileVisitResult visitFile(Path p,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException{Files.delete(p);return FileVisitResult.CONTINUE;}public FileVisitResult postVisitDirectory(Path p,IOException e)throws IOException{if(e!=null)throw e;Files.delete(p);return FileVisitResult.CONTINUE;}});}
    private static String required(Map<String,String> a,String key)throws IOException{String v=a.get(key);if(v==null||v.isBlank())throw new IOException("缺少参数："+key);return v.trim();}
    private static String actionLabel(String a){return switch(a){case "launch"->"启动 Harness";case "importPack"->"安装整合包";case "exportPack"->"导出整合包";case "installRuntime"->"安装运行时";case "refreshVersions"->"获取版本列表";case "previewPack"->"检查整合包";case "refreshPlugins"->"读取插件";case "probeRuntime"->"检测运行时能力";case "saveAccount"->"保存账户";case "deleteAccount"->"删除账户";case "bindAccount"->"设置实例账户";case "downloadDesktop"->"下载桌面安装包";case "installCatalogPlugin"->"安装目录插件";default->a;};}
    public void close(){
        synchronized(this){if(!closed.compareAndSet(false,true))return;cancelRequested=true;cancelDiscovery();worker.shutdown();controls.shutdownNow();}
        Thread t=activeThread;if(t!=null)t.interrupt();
        processes.close();
        try{if(worker.awaitTermination(15,TimeUnit.SECONDS))finishClose();}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
    private void finishClose(){
        processes.close();
        accountBindings.forEach((id,binding)->{if(!processes.running(id)&&accountBindings.remove(id,binding))closeBinding(binding);});
        if(storeClosed.compareAndSet(false,true))try{store.close();}catch(IOException ignored){}
    }
}
