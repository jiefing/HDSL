package com.hdsl.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcessServiceTest {
    @TempDir Path root;
    public static class ServerFixture {
        public static void main(String[] args)throws Exception{
            if(args.length>1&&args[1].equals("parent")){String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),ServerFixture.class.getName(),args[0]).inheritIO().start();Thread.sleep(120000);return;}
            boolean protectedFixture=args.length>1&&args[1].equals("startup");int requests=0;
            try(ServerSocket server=new ServerSocket(Integer.parseInt(args[0]),50,InetAddress.getLoopbackAddress())){
                if(!protectedFixture)System.out.println("dsh web: http://127.0.0.1:"+args[0]+"/?token=synthetic-bootstrap-fixture");
                while(true)try(Socket s=server.accept()){
                    requests++;String response="200 OK";
                    if(protectedFixture){response=requests<3?"404 Not Found":"401 Unauthorized";if(requests==3)System.out.println("dsh web: http://127.0.0.1:"+args[0]+"/?token=synthetic-bootstrap-fixture");}
                    s.getOutputStream().write(("HTTP/1.1 "+response+"\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK").getBytes());
                }
            }
        }
    }
    @Test void launchReadinessAndOwnedStop()throws Exception{
        int port;try(ServerSocket s=new ServerSocket(0)){port=s.getLocalPort();}
        Instance i=new Instance("test","Test","1.0.0","web",port,"");
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String cp=Path.of(ServerFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        List<String> logs=new java.util.concurrent.CopyOnWriteArrayList<>();
        try(ProcessService service=new ProcessService()){
            service.start(i,List.of(java,"-cp",cp,ServerFixture.class.getName(),Integer.toString(port)),root,Map.of(),logs::add);
            assertTrue(service.awaitReady(i,Duration.ofSeconds(10)));assertEquals("运行中",service.status(i));
            assertEquals("token=synthetic-bootstrap-fixture",service.webAddress(i).getQuery());
            assertFalse(String.join("\n",logs).contains("synthetic-bootstrap-fixture"));
            service.stop(i.id(),s->{});assertFalse(service.running(i.id()));assertTrue(ProcessService.portFree(port));
            assertNull(service.webAddress(i).getQuery());
        }
    }
    @Test void occupiedPortDoesNotTouchOwner()throws Exception{
        try(ServerSocket socket=new ServerSocket(0);ProcessService service=new ProcessService()){
            Instance i=new Instance("test","Test","1.0.0","web",socket.getLocalPort(),"");
            assertThrows(java.io.IOException.class,()->service.start(i,List.of("no-such-command"),root,Map.of(),s->{}));assertFalse(socket.isClosed());
        }
    }
    @Test void logRedactionCoversHeadersJsonAndUrls(){
        String log="Authorization: Bearer abcd1234 {\"api_key\":\"privatevalue\"} https://host/?token=hiddenvalue&x=1";
        String safe=ProcessService.redact(log);assertFalse(safe.contains("abcd1234"));assertFalse(safe.contains("privatevalue"));assertFalse(safe.contains("hiddenvalue"));assertTrue(safe.contains("&x=1"));
    }
    @Test void bootstrapLinkMustComeFromExpectedLocalListener(){
        assertTrue(ProcessService.bootstrapAddress("dsh web: http://example.invalid:3080/?token=fixture",3080).isEmpty());
        assertTrue(ProcessService.bootstrapAddress("dsh web: http://127.0.0.1:3090/?token=fixture",3080).isEmpty());
        assertTrue(ProcessService.bootstrapAddress("dsh web: http://user@127.0.0.1:3080/?token=fixture",3080).isEmpty());
        assertTrue(ProcessService.bootstrapAddress("unrelated log http://127.0.0.1:3080/?token=fixture",3080).isEmpty());
        assertTrue(ProcessService.bootstrapAddress("\u001b[32mdsh web:\u001b[0m http://127.0.0.1:3080/?token=fixture",3080).isPresent());
    }
    @Test void initial404IsNotReadyAndProtectedAppNeedsBootstrapLink()throws Exception{
        int port;try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String cp=Path.of(ServerFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Instance instance=new Instance("protected","Protected fixture","1.0.0","web",port,"");
        try(ProcessService service=new ProcessService()){
            service.start(instance,List.of(java,"-cp",cp,ServerFixture.class.getName(),Integer.toString(port),"startup"),root,Map.of(),ignored->{});
            assertTrue(service.awaitReady(instance,Duration.ofSeconds(10)));
            assertEquals("token=synthetic-bootstrap-fixture",service.webAddress(instance).getQuery());
            service.stop(instance.id(),ignored->{});
        }
    }
    @Test void stopIncludesOwnedServerChild()throws Exception{
        int port;try(ServerSocket s=new ServerSocket(0)){port=s.getLocalPort();}
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String cp=Path.of(ServerFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Instance i=new Instance("tree","Tree","1.0.0","web",port,"");
        try(ProcessService service=new ProcessService()){
            service.start(i,List.of(java,"-cp",cp,ServerFixture.class.getName(),Integer.toString(port),"parent"),root,Map.of(),s->{});
            assertTrue(service.awaitReady(i,Duration.ofSeconds(10)));service.stop(i.id(),s->{});assertFalse(service.running(i.id()));assertTrue(ProcessService.portFree(port));
        }
    }
    @Test void closedServiceRejectsStartBeforeCreatingWorkspaceOrProcess()throws Exception{
        ProcessService service=new ProcessService();service.close();service.close();
        Instance instance=instance("closed");Path workspace=root.resolve("must-not-be-created");
        java.io.IOException error=assertThrows(java.io.IOException.class,
                ()->service.start(instance,command(instance),workspace,Map.of(),ignored->fail("closed service logged a process start")));
        assertTrue(error.getMessage().contains("已关闭"));assertFalse(Files.exists(workspace));
        assertFalse(service.running(instance.id()));assertTrue(ProcessService.portFree(instance.port()));
    }
    @Test void closeRacingWithStartWaitsForRegistrationThenStopsOwnedProcess()throws Exception{
        Instance instance=instance("race");ProcessService service=new ProcessService();
        CountDownLatch startRegistered=new CountDownLatch(1),allowStartToFinish=new CountDownLatch(1),closeRequested=new CountDownLatch(1);
        AtomicLong pid=new AtomicLong();
        try(ExecutorService threads=Executors.newVirtualThreadPerTaskExecutor()){
            Future<?> starter=threads.submit(()->{
                service.start(instance,command(instance),root,Map.of(),line->{
                    if(line.startsWith("启动 ")){
                        pid.set(pidFrom(line));startRegistered.countDown();
                        try{if(!allowStartToFinish.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture timed out");}
                        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    }
                });return null;
            });
            assertTrue(startRegistered.await(5,TimeUnit.SECONDS));
            Future<?> closer=threads.submit(()->{closeRequested.countDown();service.close();});
            try{
                assertTrue(closeRequested.await(5,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->closer.get(200,TimeUnit.MILLISECONDS));
                assertTrue(ProcessHandle.of(pid.get()).map(ProcessHandle::isAlive).orElse(false));
            }finally{allowStartToFinish.countDown();}
            starter.get(10,TimeUnit.SECONDS);closer.get(10,TimeUnit.SECONDS);
            assertFalse(service.running(instance.id()));
            assertFalse(ProcessHandle.of(pid.get()).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(ProcessService.portFree(instance.port()));
            assertThrows(java.io.IOException.class,()->service.start(instance,command(instance),root,Map.of(),ignored->{}));
        }finally{allowStartToFinish.countDown();service.close();}
    }
    @Test void rejectedReaderSubmissionCleansUpAlreadyStartedProcess()throws Exception{
        Instance instance=instance("rejected");AtomicLong pid=new AtomicLong();
        try(ProcessService service=new ProcessService(new RejectSecondTaskExecutor())){
            java.io.IOException error=assertThrows(java.io.IOException.class,
                    ()->service.start(instance,command(instance),root,Map.of(),line->{if(line.startsWith("启动 "))pid.set(pidFrom(line));}));
            assertInstanceOf(RejectedExecutionException.class,error.getCause());assertTrue(pid.get()>0);
            assertFalse(service.running(instance.id()));
            assertFalse(ProcessHandle.of(pid.get()).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(ProcessService.portFree(instance.port()));
        }
    }
    private static Instance instance(String id)throws Exception{
        try(ServerSocket socket=new ServerSocket(0)){return new Instance(id,id,"1.0.0","web",socket.getLocalPort(),"");}
    }
    private static List<String> command(Instance instance)throws Exception{
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String cp=Path.of(ServerFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return List.of(java,"-cp",cp,ServerFixture.class.getName(),Integer.toString(instance.port()));
    }
    private static long pidFrom(String line){return Long.parseLong(line.split("PID ",2)[1].split(" ",2)[0]);}
    private static final class RejectSecondTaskExecutor extends AbstractExecutorService{
        private final ExecutorService delegate=Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicInteger tasks=new AtomicInteger();
        @Override public void execute(Runnable task){if(tasks.incrementAndGet()==2)throw new RejectedExecutionException("fixture rejection");delegate.execute(task);}
        @Override public void shutdown(){delegate.shutdown();}
        @Override public List<Runnable> shutdownNow(){return delegate.shutdownNow();}
        @Override public boolean isShutdown(){return delegate.isShutdown();}
        @Override public boolean isTerminated(){return delegate.isTerminated();}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit)throws InterruptedException{return delegate.awaitTermination(timeout,unit);}
    }
}
