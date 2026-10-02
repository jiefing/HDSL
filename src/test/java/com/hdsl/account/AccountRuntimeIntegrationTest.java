package com.hdsl.account;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.hdsl.runtime.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real installed official code, isolated homes, fake key, loopback-only model endpoint. */
@EnabledIfSystemProperty(named="hdsl.account.integration",matches="true")
class AccountRuntimeIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @Test void bothPublishedVersionsRouteSelectedAccountAndPreserveExistingSettings() throws Exception {
        Path compatibility=Path.of(".test-data/compatibility").toAbsolutePath();
        Path test=Files.createTempDirectory(Path.of(".test-data").toAbsolutePath(),"account-binding-");
        List<String> report=new ArrayList<>();
        for(String version:List.of("0.1.0-rc.6","0.2.0-rc.2")) {
            for(String provider:List.of("deepseek-official","custom-openai-completions")) {
                run(compatibility,test,version,provider,report);
            }
        }
        rejectsProfileWithoutAccountAdapter(compatibility,test,report);
        Files.writeString(test.resolve("acceptance.md"),"# Account binding integration\n\n"+String.join("\n",report)+"\n\nOnly generated fake keys and loopback endpoints were used. No paid API call or user credential read.\n");
    }
    private void run(Path compatibility,Path test,String version,String provider,List<String> report) throws Exception {
        Path run=Files.createDirectories(test.resolve(version+"-"+provider)), home=Files.createDirectories(run.resolve("home")), workspace=Files.createDirectories(run.resolve("workspace"));
        RuntimeService runtime=new RuntimeService(compatibility,"https://registry.npmjs.org","");
        AccountService accounts=new AccountService(run);
        String key="fake-local-account-"+UUID.randomUUID(), model="hdsl-local-selected-model";
        CompletableFuture<JsonNode> request=new CompletableFuture<>();
        CompletableFuture<Boolean> authenticated=new CompletableFuture<>();
        String expectedPath=provider.equals("deepseek-official")&&version.equals("0.2.0-rc.2")?"/v1/messages":"/chat/completions";
        CompletableFuture<String> requestPath=new CompletableFuture<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange->{
            try {
                String path=exchange.getRequestURI().getPath();
                if(!exchange.getRequestMethod().equals("POST")||!path.equals(expectedPath)||exchange.getRequestURI().getRawQuery()!=null) {
                    exchange.sendResponseHeaders(404,-1);
                    request.completeExceptionally(new IOException("Model request did not use the published adapter's expected method/path."));
                    return;
                }
                JsonNode body=JSON.readTree(exchange.getRequestBody()); request.complete(body);
                requestPath.complete(path);
                authenticated.complete(key.equals(exchange.getRequestHeaders().getFirst("x-api-key")) || ("Bearer "+key).equals(exchange.getRequestHeaders().getFirst("Authorization")));
                String response=expectedPath.equals("/v1/messages") ? anthropic(model):completions(model);
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } catch(Exception e) { request.completeExceptionally(e); } finally {exchange.close();}
        });
        server.start();
        int port;try(ServerSocket socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        String original="{\"agent-default-model\":{\"provider\":\"wrong-original\",\"model\":\"wrong-original\"},\"llm-deepseek\":{\"apiKeyEnv\":\"ORIGINAL_FAKE_KEY\",\"baseURL\":\"http://127.0.0.1:1\"},\"unrelated-fixture\":{\"preserve\":true}}";
        if(version.equals("0.1.0-rc.6")) Files.writeString(home.resolve("settings.yaml"),original);
        AccountEntry account=accounts.save("","Local fixture",provider,"http://127.0.0.1:"+server.getAddress().getPort(),model,key);
        Path result=run.resolve("result.json"), probe=run.resolve("account-probe.mjs"), probePatch=run.resolve("probe.patch.json");
        String js="""
                import { writeFile } from 'node:fs/promises';
                export const inject=['llm','agentDefaultModel'];
                export function apply(ctx) {
                  setTimeout(async()=>{
                    const selection=ctx.agentDefaultModel.currentSelection();
                    const chunks=[];
                    try {
                      for await(const chunk of ctx.llm.stream({...selection,messages:[{role:'user',content:[{type:'text',text:'local fixture only'}]}],maxTokens:8,signal:AbortSignal.timeout(15000)})) chunks.push(chunk.type);
                      await writeFile(%s,JSON.stringify({selection,chunks}));
                    } catch(error) { await writeFile(%s,JSON.stringify({selection,error:error.code??error.name})); }
                  },2000);
                }
                """.formatted(JSON.writeValueAsString(result.toString()),JSON.writeValueAsString(result.toString()));
        Files.writeString(probe,js);
        JSON.writeValue(probePatch.toFile(),List.of(Map.of("insert",List.of(Map.of("id","hdsl-account-probe","name",probe.toUri().toString())))));
        List<String> output=Collections.synchronizedList(new ArrayList<>());
        Process process=null;
        try(LaunchBinding binding=accounts.prepare(account.id(),runtime.runtimeDirectory(version),runtime.inspect(version,s->{}),run.resolve("binding"))) {
            List<String> command=new ArrayList<>(binding.appendTo(runtime.launchCommand(version,"web",port,s->{})));
            command.add(2,"--patch"); command.add(3,probePatch.toString());
            ProcessBuilder builder=new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
            runtime.configureEnvironment(builder.environment(),home,workspace);
            builder.environment().put("NO_PROXY","localhost,127.0.0.1"); builder.environment().put("ORIGINAL_FAKE_KEY","wrong-original-fake");
            binding.applyEnvironment(builder.environment());
            process=builder.start(); Process started=process;
            started.onExit().thenRun(()->request.completeExceptionally(new IOException("Harness exited before the model request.")));
            Thread reader=Thread.startVirtualThread(()->{try(var lines=started.inputReader(StandardCharsets.UTF_8)){String line;while((line=lines.readLine())!=null){String safe=line.replace(key,"[REDACTED]").replaceAll("(?i)(token=)[^\\s&]+","$1[REDACTED]");if(output.size()<150)output.add(safe);}}catch(IOException ignored){}});
            try { binding.awaitValidation(Duration.ofSeconds(15)); }
            catch(IOException e) { throw new AssertionError("Account runtime validation failed for "+version+" "+provider+"\n"+String.join("\n",output),e); }
            JsonNode body;
            try { body=request.get(45,TimeUnit.SECONDS); }
            catch(Exception e) { throw new AssertionError("Local model request missing for "+version+" "+provider+"\n"+String.join("\n",output),e); }
            assertEquals(model,body.path("model").asText()); assertTrue(authenticated.get(2,TimeUnit.SECONDS));
            assertEquals(expectedPath,requestPath.get(2,TimeUnit.SECONDS));
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();while(!Files.exists(result)&&System.nanoTime()<deadline)Thread.sleep(100);
            assertTrue(Files.exists(result),"probe did not finish");JsonNode read=JSON.readTree(result.toFile());
            assertEquals(provider,read.path("selection").path("provider").asText());assertEquals(model,read.path("selection").path("model").asText());
            assertFalse(read.has("error"),read.toString());
            assertTrue(read.path("chunks").toString().contains("text-delta"),"no successful streamed response");
            if(version.equals("0.1.0-rc.6"))assertEquals(original,Files.readString(home.resolve("settings.yaml")));
            report.add("- "+version+" / "+provider+": POST "+expectedPath+" accepted; selected provider, model, endpoint and fake API key reached loopback server; original settings preserved.");
            process.destroy();if(!process.waitFor(8,TimeUnit.SECONDS))process.destroyForcibly(); reader.join(1000);
        } finally { if(process!=null&&process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}server.stop(0); }
    }
    private void rejectsProfileWithoutAccountAdapter(Path compatibility,Path test,List<String> report) throws Exception {
        Path run=Files.createDirectories(test.resolve("missing-adapter")),home=Files.createDirectories(run.resolve("home")),workspace=Files.createDirectories(run.resolve("workspace"));
        RuntimeService runtime=new RuntimeService(compatibility,"https://registry.npmjs.org","");
        AccountService accounts=new AccountService(run);
        AccountEntry account=accounts.save("","negative fixture","deepseek-official","http://127.0.0.1:1","local-model",AccountServiceTest.FAKE_KEY);
        Path incompatible=run.resolve("incompatible.patch.json");
        JSON.writeValue(incompatible.toFile(),List.of(Map.of("id","llm-deepseek","disabled",true)));
        int port;try(ServerSocket socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        Process process=null;
        try(LaunchBinding binding=accounts.prepare(account.id(),runtime.runtimeDirectory("0.2.0-rc.2"),runtime.inspect("0.2.0-rc.2",s->{}),run.resolve("binding"))) {
            List<String> command=new ArrayList<>(binding.appendTo(runtime.launchCommand("0.2.0-rc.2","web",port,s->{})));
            int profile=command.indexOf("--profile");command.add(profile,"--patch");command.add(profile+1,incompatible.toString());
            ProcessBuilder builder=new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            runtime.configureEnvironment(builder.environment(),home,workspace);binding.applyEnvironment(builder.environment());
            process=builder.start();
            IOException error=assertThrows(IOException.class,()->binding.awaitValidation(Duration.ofSeconds(16)));
            assertTrue(error.getMessage().contains("未生效"),error.getMessage());
            assertTrue(process.waitFor(5,TimeUnit.SECONDS));assertEquals(78,process.exitValue());
            report.add("- Negative profile: disabled official model adapter was rejected explicitly; account validation failed and the owned Harness process exited 78.");
        } finally {if(process!=null&&process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
    }
    private static String completions(String model) {
        return "data: {\"id\":\"local-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\""+model+"\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
                +"data: {\"id\":\"local-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\""+model+"\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}\n\ndata: [DONE]\n\n";
    }
    private static String anthropic(String model) {
        return "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"local-test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\""+model+"\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n"
                +"event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                +"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                +"event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                +"event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}\n\n"
                +"event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
    }
}
