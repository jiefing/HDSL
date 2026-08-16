package com.hdsl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RuntimeLifecycleIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(System.getenv("HDSL_PORTABLE_ROOT"));
        int port=Integer.parseInt(System.getenv().getOrDefault("HDSL_TEST_PORT","3180"));
        if(Launcher.isLoopbackPortOpen(port))throw new AssertionError("Test port is already occupied: "+port);
        Constructor<Launcher> constructor=Launcher.class.getDeclaredConstructor();constructor.setAccessible(true);
        Launcher launcher=constructor.newInstance();
        Method selected=Launcher.class.getDeclaredMethod("selectedInstance");selected.setAccessible(true);
        Launcher.Instance item=(Launcher.Instance)selected.invoke(launcher);item.port=port;item.customCommand="";
        Method initialize=Launcher.class.getDeclaredMethod("initializeInstance",Launcher.Instance.class);initialize.setAccessible(true);
        Method launch=Launcher.class.getDeclaredMethod("launchProcess");launch.setAccessible(true);
        Method stop=Launcher.class.getDeclaredMethod("stopProcess",Launcher.Instance.class);stop.setAccessible(true);
        Method stopAll=Launcher.class.getDeclaredMethod("stopAllProcesses");stopAll.setAccessible(true);
        Path pidFile=root.resolve("instances").resolve(item.id).resolve("launcher.pid");
        try{
            if(initialize.invoke(launcher,item)==null)throw new AssertionError("Profile initialization failed");
            launch.invoke(launcher);
            waitForPort(port,true,30000);
            if(!Files.isRegularFile(pidFile))throw new AssertionError("PID file was not written");
            long pid=Long.parseLong(Files.readString(pidFile,StandardCharsets.UTF_8).trim());
            if(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)==false)throw new AssertionError("Recorded launcher PID is not alive");
            stop.invoke(launcher,item);
            waitForPort(port,false,10000);
            if(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))throw new AssertionError("Launcher process survived Stop");
            if(Files.exists(pidFile))throw new AssertionError("PID file survived successful Stop");
            System.out.println("RUNTIME_LIFECYCLE_OK port="+port+" pid="+pid);
        }finally{
            stopAll.invoke(launcher);
        }
    }

    private static void waitForPort(int port,boolean expected,long timeoutMillis)throws Exception{
        long deadline=System.currentTimeMillis()+timeoutMillis;
        while(System.currentTimeMillis()<deadline){if(Launcher.isLoopbackPortOpen(port)==expected)return;Thread.sleep(200);}
        throw new AssertionError("Port "+port+" did not become "+(expected?"open":"closed"));
    }
}
