package com.hdsl.core;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InstanceStoreTest {
    @TempDir Path root;
    @Test void migrationPreservesOriginalAndUnknownFields()throws Exception{
        Files.createDirectories(root.resolve("config"));
        String legacy="count=1\nid.0=stable\nname.0=稳定环境\nversion.0=0.1.0-rc.6\nprofile.0=web\ncommand.0=custom --port 4100\nport.0=4100\n";
        Path old=root.resolve("config/instances.properties");Files.writeString(old,legacy,StandardCharsets.UTF_8);
        try(InstanceStore store=new InstanceStore(root)){assertEquals("stable",store.selected());assertEquals("custom --port 4100",store.find("stable").customCommand());}
        Path file=root.resolve("config/launcher-v2.json");ObjectMapper json=new ObjectMapper();ObjectNode metadata=(ObjectNode)json.readTree(file.toFile());metadata.put("futureRoot",42);((ObjectNode)metadata.path("instances").get(0)).put("futureInstance","keep");json.writeValue(file.toFile(),metadata);
        try(InstanceStore store=new InstanceStore(root)){Instance i=store.find("stable");store.replace(new Instance(i.id(),"新名称",i.runtimeVersion(),i.profile(),i.port(),i.customCommand()));}
        JsonNode updated=json.readTree(file.toFile());assertEquals(42,updated.path("futureRoot").asInt());assertEquals("keep",updated.path("instances").get(0).path("futureInstance").asText());assertEquals(legacy,Files.readString(old));
    }
    @Test void unknownSchemaIsNeverRewritten()throws Exception{
        Files.createDirectories(root.resolve("config"));Path file=root.resolve("config/launcher-v2.json");String data="{\"schemaVersion\":999,\"instances\":[],\"extra\":true}";Files.writeString(file,data);
        assertThrows(IOException.class,()->new InstanceStore(root));assertEquals(data,Files.readString(file));
    }
    @Test void preventsDoubleOpenAndRejectsTraversal()throws Exception{
        try(InstanceStore first=new InstanceStore(root)){assertThrows(IOException.class,()->new InstanceStore(root));assertThrows(IOException.class,()->first.add(new Instance("../bad","Bad","1.0.0","web",3080,"")));assertTrue(first.list().isEmpty());}
        try(InstanceStore reopened=new InstanceStore(root)){assertTrue(reopened.list().isEmpty());}
    }
    @Test void duplicatePortsCannotChangeStoredInstances()throws Exception{
        try(InstanceStore store=new InstanceStore(root)){store.add(new Instance("one","One","1.0.0","web",3080,""));store.add(new Instance("two","Two","2.0.0","web",3081,""));assertThrows(IOException.class,()->store.replace(new Instance("two","Two","2.0.0","web",3080,"")));assertEquals(3081,store.find("two").port());}
    }
}
