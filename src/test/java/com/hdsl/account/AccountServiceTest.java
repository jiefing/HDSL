package com.hdsl.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.hdsl.runtime.Capabilities;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountServiceTest {
    @TempDir Path root;
    static final String FAKE_KEY = "fake-test-account-key-never-valid";
    static final SecretProtector FAKE_PROTECTOR = new SecretProtector() {
        public byte[] protect(byte[] value) { byte[] copy=value.clone(); for(int i=0;i<copy.length;i++)copy[i]^=0x5A; return copy; }
        public byte[] unprotect(byte[] value) { return protect(value); }
    };
    static Capabilities caps() { return new Capabilities("test", Set.of("--patch", "--profile"), Set.of("--port"), true,true,true,true,List.of()); }

    @Test void crudKeepsSecretsOutOfPublicMetadataAndRetainsKeyOnEdit() throws Exception {
        AccountService service=new AccountService(root,FAKE_PROTECTOR);
        AccountEntry created=service.save("", "我的 DeepSeek", "deepseek-official", "", "deepseek-flash", FAKE_KEY);
        assertTrue(created.hasKey()); assertEquals("https://api.deepseek.com",created.baseUrl());
        assertFalse(Files.readString(root.resolve("config/accounts.json")).contains(FAKE_KEY));
        assertFalse(new ObjectMapper().writeValueAsString(service.list()).contains("encryptedKey"));
        assertFalse(service.list().toString().contains(FAKE_KEY));
        String ciphertext=new ObjectMapper().readTree(root.resolve("config/accounts.json").toFile()).path("accounts").get(0).path("encryptedKey").asText();
        service.save(created.id(), "编辑", "deepseek-official", "", "deepseek-pro", "");
        assertEquals(ciphertext,new ObjectMapper().readTree(root.resolve("config/accounts.json").toFile()).path("accounts").get(0).path("encryptedKey").asText());
        assertEquals("deepseek-pro",service.list().getFirst().model());
        service.delete(created.id()); assertTrue(service.list().isEmpty());
    }

    @Test void actualWindowsDpapiRoundTripAndCorruptionFailsClosed() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        WindowsSecretProtector protector=new WindowsSecretProtector(); byte[] plain=FAKE_KEY.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted=protector.protect(plain);
        assertFalse(Arrays.equals(plain,encrypted)); assertArrayEquals(plain,protector.unprotect(encrypted));
        encrypted[encrypted.length-1]^=1;
        IOException error=assertThrows(IOException.class,()->protector.unprotect(encrypted));
        assertFalse(error.getMessage().contains(FAKE_KEY));
    }

    @Test void rejectsCredentialBearingUrlsAndInvalidSecretsWithoutEchoingThem() {
        AccountService service=new AccountService(root,FAKE_PROTECTOR);
        for(String url:List.of("https://user:secret@example.com/v1","https://example.com/v1?key=secret","http://example.com/v1","https://example.com/#secret")) {
            IOException error=assertThrows(IOException.class,()->service.save("","test","openai",url,"model",FAKE_KEY));
            assertFalse(error.getMessage().contains("secret"));
        }
        assertThrows(IOException.class,()->service.save("","test","openai","https://example.com","model","bad\nkey"));
        assertThrows(IOException.class,()->service.save("","test","openai","https://example.com","model",""));
        assertThrows(IOException.class,()->service.delete("../../anything"));
    }

    @Test void launchBindingUsesFinalOverlayAndEnvironmentOnly() throws Exception {
        Path runtime=runtimeFixture(false); AccountService service=new AccountService(root,FAKE_PROTECTOR);
        AccountEntry created=service.save("","test","deepseek-official","http://127.0.0.1:9876","test-model",FAKE_KEY);
        LaunchBinding binding=service.prepare(created.id(),runtime,caps(),root.resolve("binding"));
        String patch=Files.readString(binding.patchPath());
        assertTrue(patch.contains("test-model")); assertTrue(patch.contains(LaunchBinding.KEY_ENV)); assertFalse(patch.contains(FAKE_KEY));
        List<String> command=binding.appendTo(List.of("node","dsh.js","--profile","web","--port","8000"));
        assertEquals("--patch",command.get(2)); assertEquals("--profile",command.get(4)); assertFalse(command.toString().contains(FAKE_KEY));
        Map<String,String> env=new HashMap<>(); binding.applyEnvironment(env); assertEquals(FAKE_KEY,env.get(LaunchBinding.KEY_ENV));
        Files.writeString(binding.patchPath().resolveSibling("account.validation.txt"),"ok");
        binding.awaitValidation(Duration.ofSeconds(1));
        assertFalse(binding.toString().contains(FAKE_KEY)); Path patchPath=binding.patchPath(); binding.close();
        assertFalse(Files.exists(patchPath)); assertThrows(IllegalStateException.class,()->binding.applyEnvironment(new HashMap<>()));
    }

    @Test void legacyOverlayProtectsAccountSettingsWithoutRewritingHarnessFiles() throws Exception {
        Path runtime=runtimeFixture(true); AccountService service=new AccountService(root,FAKE_PROTECTOR);
        AccountEntry account=service.save("","test","deepseek-official","","test-model",FAKE_KEY);
        try(LaunchBinding binding=service.prepare(account.id(),runtime,caps(),root.resolve("binding"))) {
            Path bridge=binding.patchPath().resolveSibling("legacy-account-settings.mjs");
            String source=Files.readString(bridge); assertTrue(source.contains("originalSection.call(this, ns)"));
            assertTrue(source.contains("return originalWrite.call(this, ns, ...args)")); assertFalse(source.contains(FAKE_KEY));
            assertFalse(Files.exists(root.resolve("settings.yaml"))); assertFalse(Files.exists(root.resolve(".credentials.yaml")));
        }
    }

    @Test void unknownRuntimeOrMissingPatchCapabilityFailsBeforeSecretUse() throws Exception {
        AccountService service=new AccountService(root,FAKE_PROTECTOR);
        AccountEntry account=service.save("","test","deepseek-official","","model",FAKE_KEY);
        assertThrows(IOException.class,()->service.prepare(account.id(),root.resolve("unknown"),caps(),root.resolve("binding")));
        Capabilities noPatch=new Capabilities("future",Set.of("--profile"),Set.of(),false,true,false,false,List.of());
        assertThrows(IOException.class,()->service.prepare(account.id(),runtimeFixture(false),noPatch,root.resolve("binding")));
        assertFalse(Files.exists(root.resolve("binding")));
    }

    @Test void malformedStoreDoesNotEchoItsContents() throws Exception {
        Files.createDirectories(root.resolve("config")); Files.writeString(root.resolve("config/accounts.json"),"{\"bad\":\""+FAKE_KEY);
        IOException error=assertThrows(IOException.class,()->new AccountService(root,FAKE_PROTECTOR).list());
        assertFalse(error.toString().contains(FAKE_KEY)); assertNull(error.getCause());
    }

    @Test void startupValidationFailureIsExplicitAndNeverContainsAccountSecret() throws Exception {
        AccountService service=new AccountService(root,FAKE_PROTECTOR);
        AccountEntry account=service.save("","test","deepseek-official","","model",FAKE_KEY);
        try(LaunchBinding binding=service.prepare(account.id(),runtimeFixture(false),caps(),root.resolve("binding"))) {
            Files.writeString(binding.patchPath().resolveSibling("account.validation.txt"),"failed");
            IOException error=assertThrows(IOException.class,()->binding.awaitValidation(Duration.ofSeconds(1)));
            assertTrue(error.getMessage().contains("未生效")); assertFalse(error.toString().contains(FAKE_KEY));
        }
    }

    @Test void officialEndpointsFollowInstalledTransportAndEveryOverlayUsesTheEffectiveAddress() throws Exception {
        AccountService service=new AccountService(root,FAKE_PROTECTOR);
        ObjectMapper json=new ObjectMapper();
        for(boolean legacy:List.of(true,false)) {
            Path runtime=runtimeFixture(legacy);
            String expected=legacy?"https://api.deepseek.com":"https://api.deepseek.com/anthropic";
            for(String input:List.of("https://api.deepseek.com","https://api.deepseek.com/","https://api.deepseek.com/v1",
                    "https://api.deepseek.com/anthropic","https://api.deepseek.com/anthropic/v1","https://api.deepseek.com/anthropic/v1/")) {
                AccountEntry account=service.save("","protocol fixture","deepseek-official",input,"test-model",FAKE_KEY);
                try(LaunchBinding binding=service.prepare(account.id(),runtime,caps(),root.resolve("binding"))) {
                    JsonNode patch=json.readTree(binding.patchPath().toFile());
                    assertEquals(expected,patch.get(1).path("config").path("baseURL").asText(),input);
                    String verifier=Files.readString(binding.patchPath().resolveSibling("account-verify.mjs"));
                    assertTrue(verifier.contains("\"baseURL\":\""+expected+"\""),"verifier must check effective endpoint");
                    if(legacy) {
                        String bridge=Files.readString(binding.patchPath().resolveSibling("legacy-account-settings.mjs"));
                        assertTrue(bridge.contains("\"baseURL\":\""+expected+"\""),"legacy settings must use effective endpoint");
                    }
                    assertEquals(account.baseUrl(),service.list().stream().filter(a->a.id().equals(account.id())).findFirst().orElseThrow().baseUrl(),
                            "normalization must not rewrite the saved endpoint");
                }
            }
        }
    }

    @Test void customEndpointsArePreservedForBothPublishedTransports() throws Exception {
        AccountService service=new AccountService(root,FAKE_PROTECTOR);
        ObjectMapper json=new ObjectMapper();
        for(boolean legacy:List.of(true,false)) {
            Path runtime=runtimeFixture(legacy);
            for(String endpoint:List.of("https://provider.example/v1","http://127.0.0.1:9876","http://localhost:9876/custom/v1",
                    "https://api.deepseek.com/custom/proxy","https://api.deepseek.com:8443/v1","https://api.deepseek.com.example/v1")) {
                AccountEntry account=service.save("","custom fixture","deepseek-official",endpoint,"test-model",FAKE_KEY);
                try(LaunchBinding binding=service.prepare(account.id(),runtime,caps(),root.resolve("binding"))) {
                    JsonNode patch=json.readTree(binding.patchPath().toFile());
                    assertEquals(endpoint,patch.get(1).path("config").path("baseURL").asText());
                    assertTrue(Files.readString(binding.patchPath().resolveSibling("account-verify.mjs")).contains("\"baseURL\":\""+endpoint+"\""));
                }
            }
        }
    }

    @Test void unknownOfficialTransportFailsBeforeDecryptingTheKey() throws Exception {
        Path runtime=runtimeFixture(false);
        write(runtime.resolve("node_modules/@deepseek-ai/dsh-llm-deepseek/lib/index.js"),
                "const PUBLIC_BASE_URL = \"https://api.deepseek.com/future\"; fetch(`${connection.baseURL}/future`);");
        SecretProtector neverDecrypt=new SecretProtector() {
            public byte[] protect(byte[] value) throws IOException { return FAKE_PROTECTOR.protect(value); }
            public byte[] unprotect(byte[] value) { fail("unknown official transport must fail before secret decryption");return null; }
        };
        AccountService service=new AccountService(root,neverDecrypt);
        AccountEntry account=service.save("","unknown transport","deepseek-official","","test-model",FAKE_KEY);
        IOException error=assertThrows(IOException.class,()->service.prepare(account.id(),runtime,caps(),root.resolve("binding")));
        assertFalse(error.toString().contains(FAKE_KEY));assertFalse(Files.exists(root.resolve("binding")));
    }

    private Path runtimeFixture(boolean legacy) throws IOException {
        Path runtime=root.resolve(legacy?"old-runtime":"new-runtime"), scope=runtime.resolve("node_modules/@deepseek-ai");
        write(scope.resolve("dsh-base/cordis.patch.yml"),"id: agent-default-model\nname: '@deepseek-ai/"+(legacy?"dsh-llm-deepseek":"dsh-llm-deepseek-api-key")+"'\n"+(legacy?"name: '@deepseek-ai/dsh-settings-file'":""));
        write(scope.resolve("dsh-agent-default-model/lib/index.js"),legacy?"installSettingsSection( AGENT_DEFAULT_MODEL_SETTINGS_NAMESPACE":"config.provider.get() currentSelection()");
        write(scope.resolve("dsh-llm-deepseek/lib/index.js"),legacy
                ?"apiKeyEnv deepseek-official; const PUBLIC_BASE_URL = \"https://api.deepseek.com\"; fetch(`${connection.baseURL}/chat/completions`, {});"
                :"const PUBLIC_BASE_URL = \"https://api.deepseek.com/anthropic\"; fetch(`${messagesApiRoot(connection.baseURL)}/messages`, {});");
        if(!legacy) write(scope.resolve("dsh-llm-deepseek-api-key/lib/index.js"),"apiKeyEnv deepseek-official; import { registerDeepSeekProvider } from \"@deepseek-ai/dsh-llm-deepseek\";");
        if(legacy) { write(scope.resolve("dsh-settings-file/lib/index.js"),"extends SettingsProvider"); write(scope.resolve("dsh-settings/lib/index.js"),"section(ns) publish(doc, source"); }
        return runtime;
    }
    private static void write(Path file,String value) throws IOException { Files.createDirectories(file.getParent());Files.writeString(file,value); }
}
