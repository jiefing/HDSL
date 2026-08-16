package com.hdsl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public final class PortableIsolationSmokeTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path portable = Path.of(System.getenv("HDSL_PORTABLE_ROOT"));
        Path runtime = portable.resolve("runtimes/0.1.0-rc.6/node_modules/@deepseek-ai/dsh/package.json");
        String instanceId = System.getenv().getOrDefault("HDSL_TEST_INSTANCE_ID", "probe");
        Path profile = portable.resolve("instances").resolve(instanceId).resolve("dsh-home/profiles/web/package.json");
        if (!Files.isRegularFile(runtime)) throw new AssertionError("Portable runtime missing: " + runtime);
        if (!Files.isRegularFile(profile)) throw new AssertionError("Isolated profile missing: " + profile);
        Constructor<Launcher> constructor = Launcher.class.getDeclaredConstructor(); constructor.setAccessible(true); Launcher launcher = constructor.newInstance();
        String json = Files.readString(profile, StandardCharsets.UTF_8);
        Method arrays = Launcher.class.getDeclaredMethod("jsonArrayValues", String.class, String.class); arrays.setAccessible(true);
        Method objects = Launcher.class.getDeclaredMethod("jsonObjectPairs", String.class, String.class); objects.setAccessible(true);
        Set<String> bundles = (Set<String>) arrays.invoke(launcher, json, "bundles");
        Map<String,String> dependencies = (Map<String,String>) objects.invoke(launcher, json, "dependencies");
        if (!bundles.contains("@deepseek-ai/dsh-base") || !bundles.contains("@deepseek-ai/dsh-web-app")) throw new AssertionError("Active bundles not detected: " + bundles);
        if (!dependencies.isEmpty()) throw new AssertionError("Unexpected external plugins: " + dependencies);
        Class<?> instanceType = Class.forName("com.hdsl.Launcher$Instance");
        Constructor<?> instanceConstructor = instanceType.getDeclaredConstructor(String.class,String.class,String.class,String.class,String.class); instanceConstructor.setAccessible(true);
        Object newInstance = instanceConstructor.newInstance("new-probe","New Probe","0.1.0-rc.6","web","");
        Method ensureFolders = Launcher.class.getDeclaredMethod("ensureInstanceFolders",instanceType); ensureFolders.setAccessible(true); ensureFolders.invoke(launcher,newInstance);
        Method initialize = Launcher.class.getDeclaredMethod("initializeInstance",instanceType); initialize.setAccessible(true); Object initialized=initialize.invoke(launcher,newInstance);
        Path newProfile=portable.resolve("instances/new-probe/dsh-home/profiles/web/package.json");
        if(initialized==null||!Files.isRegularFile(newProfile))throw new AssertionError("New isolated Harness was not initialized");
        Method runDsh=Launcher.class.getDeclaredMethod("runDsh",instanceType,String.class,int.class);runDsh.setAccessible(true);
        String listOutput=(String)runDsh.invoke(launcher,newInstance,"plugin --profile web list --depth 0 --json",120);
        String dumpOutput=(String)runDsh.invoke(launcher,newInstance,"--profile web --dump-config",120);
        if(listOutput==null||dumpOutput==null)throw new AssertionError("Official plugin inspection commands failed");
        Method parseConfig=Launcher.class.getDeclaredMethod("parseConfigPlugins",instanceType,String.class);parseConfig.setAccessible(true);
        java.util.List<?> expanded=(java.util.List<?>)parseConfig.invoke(launcher,newInstance,dumpOutput);
        if(expanded.size()<50)throw new AssertionError("Expanded plugin tree is unexpectedly small: "+expanded.size());
        System.out.println("PORTABLE_ISOLATION_OK runtime=" + runtime);
        System.out.println("PLUGIN_DETECTION_OK bundles=" + bundles);
        System.out.println("NEW_HARNESS_OK profile="+newProfile);
        System.out.println("PLUGIN_COMMANDS_OK expanded="+expanded.size());
    }
}
