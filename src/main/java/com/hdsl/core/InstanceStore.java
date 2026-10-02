package com.hdsl.core;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Launcher-owned metadata only. Harness configuration belongs to Harness. */
public final class InstanceStore implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root, file;
    private final FileChannel channel;
    private final FileLock lock;
    private ObjectNode state;

    public InstanceStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root.resolve("config"));
        file = this.root.resolve("config/launcher-v2.json");
        channel = FileChannel.open(this.root.resolve("config/launcher.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = channel.tryLock(); } catch (OverlappingFileLockException e) { channel.close(); throw new IOException("这个数据目录已有 HDSL 正在运行。", e); }
        if (acquired == null) { channel.close(); throw new IOException("这个数据目录已有 HDSL 正在运行。"); }
        lock = acquired;
        try {
            if (Files.exists(file)) {
                JsonNode parsed = JSON.readTree(file.toFile());
                if (!(parsed instanceof ObjectNode obj) || obj.path("schemaVersion").asInt() != 1 || !obj.path("instances").isArray())
                    throw new IOException("无法识别启动器数据版本；原文件已保留，请使用兼容版本打开。");
                state = obj;
                validateState();
            } else {
                state = JSON.createObjectNode().put("schemaVersion", 1).put("selected", "");
                state.putObject("settings").put("proxy", "").put("registry", "https://registry.npmjs.org").put("background", "");
                state.putArray("instances");
                migrateLegacy();
                persist(state);
            }
            Files.createDirectories(root.resolve("instances"));
        } catch (Exception e) { lock.release(); channel.close(); throw e; }
    }

    private void migrateLegacy() throws IOException {
        Path old = root.resolve("config/instances.properties");
        if (!Files.isRegularFile(old)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(old, StandardCharsets.UTF_8)) { p.load(r); }
        int count;
        try { count = Integer.parseInt(p.getProperty("count", "0")); } catch (NumberFormatException e) { throw new IOException("旧实例清单 count 非法，迁移已停止。", e); }
        if (count < 0 || count > 10000) throw new IOException("旧实例数量不合理，迁移已停止。");
        Set<Integer> ports = new HashSet<>(); Set<String> ids = new HashSet<>();
        for (int i=0;i<count;i++) {
            String id=p.getProperty("id."+i,"legacy-"+i); validateId(id);
            if (!ids.add(id)) throw new IOException("旧实例 ID 重复："+id);
            int port; try { port=Integer.parseInt(p.getProperty("port."+i,"3080")); } catch(NumberFormatException e){port=3080;}
            if(port<1024||port>65535) port=3080;
            while(ports.contains(port)&&port<65535) port++;
            if(!ports.add(port)) throw new IOException("没有空闲实例端口。");
            String profile=p.getProperty("profile."+i,"web"); validateProfile(profile);
            String version=p.getProperty("version."+i,"0.1.0-rc.6"); validateVersion(version);
            ObjectNode item=((ArrayNode)state.path("instances")).addObject();
            item.put("id",id).put("name",p.getProperty("name."+i,"Instance "+(i+1))).put("runtimeVersion",version).put("profile",profile).put("port",port).put("customCommand",p.getProperty("command."+i,""));
        }
        if (count>0) state.put("selected",state.path("instances").get(0).path("id").asText());
        state.put("migratedFrom", "instances.properties");
        Path oldSettings=root.resolve("config/settings.properties");
        if(Files.isRegularFile(oldSettings)) {
            Properties legacy=new Properties(); try(Reader r=Files.newBufferedReader(oldSettings,StandardCharsets.UTF_8)){legacy.load(r);}
            if("china".equals(legacy.getProperty("source"))) ((ObjectNode)state.path("settings")).put("registry","https://registry.npmmirror.com");
        }
    }

    private void validateState() throws IOException {
        Set<String> ids=new HashSet<>(); Set<Integer> ports=new HashSet<>();
        for(JsonNode n:state.path("instances")) {
            validateId(n.path("id").asText()); validateVersion(n.path("runtimeVersion").asText()); validateProfile(n.path("profile").asText());
            if(!ids.add(n.path("id").asText())||!ports.add(n.path("port").asInt())) throw new IOException("实例清单存在重复 ID 或端口。");
            if(n.path("port").asInt()<1024||n.path("port").asInt()>65535) throw new IOException("实例端口非法。");
        }
    }

    public Path root(){return root;}
    public Path directory(Instance i){return root.resolve("instances").resolve(i.id());}
    public Path home(Instance i){return directory(i).resolve("dsh-home");}
    public Path workspace(Instance i){return directory(i).resolve("workspace");}
    public synchronized String setting(String key,String fallback){return state.path("settings").path(key).asText(fallback);}
    public synchronized void settings(Map<String,String> values)throws IOException{
        update(copy->{ObjectNode settings=copy.withObject("settings");values.forEach(settings::put);});
    }
    public synchronized List<Instance> list(){
        List<Instance> out=new ArrayList<>(); for(JsonNode n:state.path("instances")) out.add(decode(n)); return List.copyOf(out);
    }
    public synchronized Instance find(String id)throws IOException{return list().stream().filter(i->i.id().equals(id)).findFirst().orElseThrow(()->new IOException("实例不存在："+id));}
    public synchronized String selected(){return state.path("selected").asText("");}
    public synchronized void select(String id)throws IOException{find(id);update(n->n.put("selected",id));}
    public synchronized int nextPort(){Set<Integer> used=new HashSet<>();for(Instance i:list())used.add(i.port());for(int p=3080;p<=65535;p++)if(!used.contains(p))return p;throw new IllegalStateException("没有可分配端口");}
    public static String newId(){return "i-"+UUID.randomUUID().toString().substring(0,12);}
    public synchronized void add(Instance instance)throws IOException{
        validate(instance); if(list().stream().anyMatch(i->i.id().equals(instance.id())||i.port()==instance.port()))throw new IOException("实例 ID 或端口重复。");
        update(n->{((ArrayNode)n.path("instances")).add(encode(instance));n.put("selected",instance.id());});
    }
    public synchronized void replace(Instance instance)throws IOException{
        validate(instance);find(instance.id());if(list().stream().anyMatch(i->!i.id().equals(instance.id())&&i.port()==instance.port()))throw new IOException("端口已分配给其他实例。");
        update(n->{for(JsonNode node:n.path("instances"))if(node.path("id").asText().equals(instance.id()))((ObjectNode)node).setAll(encode(instance));});
    }
    public synchronized void remove(String id)throws IOException{
        find(id); update(n->{ArrayNode all=(ArrayNode)n.path("instances");for(int k=0;k<all.size();k++)if(all.get(k).path("id").asText().equals(id)){all.remove(k);break;}if(id.equals(n.path("selected").asText()))n.put("selected",all.isEmpty()?"":all.get(0).path("id").asText());});
    }
    private void update(Consumer<ObjectNode> edit)throws IOException{ObjectNode copy=state.deepCopy();edit.accept(copy);persist(copy);state=copy;}
    private void persist(ObjectNode data)throws IOException{
        Path tmp=Files.createTempFile(file.getParent(),"launcher-v2-",".tmp");
        try {JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(),data);atomicMove(tmp,file);}finally{Files.deleteIfExists(tmp);}
    }
    public static void atomicMove(Path from,Path to)throws IOException{try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}}
    private static Instance decode(JsonNode n){return new Instance(n.path("id").asText(),n.path("name").asText(),n.path("runtimeVersion").asText(),n.path("profile").asText(),n.path("port").asInt(),n.path("customCommand").asText(""),n.path("accountId").asText(""));}
    private static ObjectNode encode(Instance i){return JSON.createObjectNode().put("id",i.id()).put("name",i.name()).put("runtimeVersion",i.runtimeVersion()).put("profile",i.profile()).put("port",i.port()).put("customCommand",i.customCommand()).put("accountId",i.accountId());}
    private static void validate(Instance i)throws IOException{validateId(i.id());validateVersion(i.runtimeVersion());validateProfile(i.profile());if(i.name()==null||i.name().isBlank()||i.name().length()>160)throw new IOException("实例名称须为 1–160 个字符。");if(i.port()<1024||i.port()>65535)throw new IOException("端口须在 1024–65535 之间。");}
    public static void validateId(String id)throws IOException{if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,95}"))throw new IOException("实例 ID 含不支持的路径字符。");}
    public static void validateProfile(String profile)throws IOException{if(profile==null||!profile.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}._ -]{0,127}")||profile.contains("..")||profile.endsWith(".")||profile.endsWith(" ")||profile.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))throw new IOException("Profile 名称含不支持的字符。");}
    public static void validateVersion(String version)throws IOException{if(version==null||!version.matches("[0-9][0-9A-Za-z.+-]{0,79}"))throw new IOException("请输入精确版本号，例如 0.1.0-rc.6。");}
    public void close()throws IOException{lock.release();channel.close();}
}
