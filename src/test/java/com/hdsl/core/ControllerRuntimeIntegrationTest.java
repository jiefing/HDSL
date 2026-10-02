package com.hdsl.core;

import com.hdsl.runtime.RuntimeService;
import com.hdsl.ui.UiState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in check of the production controller and process lifecycle with real installed runtimes. */
@EnabledIfSystemProperty(named="hdsl.controllerIntegration",matches="true")
class ControllerRuntimeIntegrationTest {
    @Test void launchStopAndRestartBothVersions()throws Exception{
        Path fixtures=Path.of(".test-data/compatibility").toAbsolutePath();
        Path data=Files.createTempDirectory(Path.of(".test-data").toAbsolutePath(),"controller-integration-");
        RuntimeService runtime=new RuntimeService(fixtures,"https://registry.npmjs.org","http://127.0.0.1:7890");
        StringBuilder report=new StringBuilder("# Controller integration\n\nData: "+data+"\n\n");
        try(Controller controller=new Controller(data,runtime)){
            for(String version:List.of("0.1.0-rc.6","0.2.0-rc.2")){
                assertTrue(runtime.installedVersions().contains(version),"Prepare real runtime fixture first: "+version);
                UiState created=controller.dispatch("createInstance",Map.of("name","真实检查 "+version,"version",version,"profile","web","port","")).get(10,TimeUnit.SECONDS);
                String id=created.currentInstanceId();int port=created.instances().stream().filter(i->i.id().equals(id)).findFirst().orElseThrow().port();
                for(int attempt=0;attempt<2;attempt++){
                    UiState running=controller.dispatch("launch",Map.of("id",id)).get(3,TimeUnit.MINUTES);
                    assertEquals("运行中",running.instances().stream().filter(i->i.id().equals(id)).findFirst().orElseThrow().status(),running.logs());
                    assertFalse(ProcessService.portFree(port));
                    // Exercise the in-memory bootstrap link without printing or persisting its token.
                    try(HttpClient browser=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL)
                            .cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).proxy(new ProxySelector(){
                                public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
                                public void connectFailed(URI uri,SocketAddress address,IOException error){}
                            }).build()){
                        var page=browser.send(HttpRequest.newBuilder(controller.webAddress(id)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.discarding());
                        assertEquals(200,page.statusCode(),"Opening the Web workspace must complete bootstrap authentication");
                    }
                    UiState listed=controller.dispatch("refreshPlugins",Map.of("id",id)).get(15,TimeUnit.SECONDS);
                    assertFalse(listed.plugins().isEmpty());
                    controller.dispatch("stop",Map.of("id",id)).get(15,TimeUnit.SECONDS);
                    assertTrue(ProcessService.portFree(port),"Owned runtime listener must be released");
                }
                report.append("- ").append(version).append(": create, launch, ready, Web bootstrap HTTP 200, plugin list, stop, restart and listener release passed.\n");
                Files.writeString(data.resolve("acceptance.md"),report.toString());
                System.out.println("CONTROLLER verified "+version+"; report="+data.resolve("acceptance.md"));
            }
        }
    }
}
