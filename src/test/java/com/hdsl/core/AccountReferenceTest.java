package com.hdsl.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class AccountReferenceTest {
    @TempDir Path root;
    @Test void accountBindingSurvivesEditsButCannotDeleteReferencedAccount()throws Exception{
        String sentinel="test-only-controller-account-key-1234";
        try(Controller c=new Controller(root)){
            var state=c.dispatch("saveAccount",Map.of("name","测试账户","provider","deepseek-official","model","deepseek-flash","apiKey",sentinel)).get(10,TimeUnit.SECONDS);
            assertEquals(1,state.accounts().size());String account=state.accounts().getFirst().id();
            assertFalse(new ObjectMapper().writeValueAsString(state).contains(sentinel));
            assertFalse(Files.readString(root.resolve("config/accounts.json")).contains(sentinel));
            state=c.dispatch("createInstance",Map.of("name","测试实例","version","0.2.0-rc.2","accountId",account)).get(10,TimeUnit.SECONDS);
            var instance=state.currentInstance();assertEquals(account,instance.accountId());
            state=c.dispatch("saveInstance",Map.of("id",instance.id(),"name","改名实例","version",instance.version(),"profile","web","port",Integer.toString(instance.port()))).get(10,TimeUnit.SECONDS);
            assertEquals(account,state.currentInstance().accountId());
            assertThrows(ExecutionException.class,()->c.dispatch("deleteAccount",Map.of("id",account)).get(10,TimeUnit.SECONDS));
            state=c.dispatch("bindAccount",Map.of("id",instance.id(),"accountId","")).get(10,TimeUnit.SECONDS);assertEquals("",state.currentInstance().accountId());
            state=c.dispatch("deleteAccount",Map.of("id",account)).get(10,TimeUnit.SECONDS);assertTrue(state.accounts().isEmpty());
            assertFalse(state.logs().contains(sentinel));
        }
        try(Controller reopened=new Controller(root)){assertEquals("",reopened.snapshot().currentInstance().accountId());assertTrue(reopened.snapshot().accounts().isEmpty());}
    }
    @Test void inventedAccountIdCannotBeAttachedAndRawKeyOutputIsRedacted()throws Exception{
        try(Controller c=new Controller(root)){
            assertThrows(ExecutionException.class,()->c.dispatch("createInstance",Map.of("name","invalid","version","0.2.0-rc.2","accountId","missing")).get(5,TimeUnit.SECONDS));
            assertTrue(c.snapshot().instances().isEmpty());
        }
        assertEquals("provider echoed [已隐藏]",ProcessService.redactValues("provider echoed fake-plain-value",List.of("fake-plain-value")));
    }
}
