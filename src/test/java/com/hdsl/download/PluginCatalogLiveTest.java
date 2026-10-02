package com.hdsl.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit public metadata probe; no packages are downloaded or executed. */
@EnabledIfSystemProperty(named = "hdsl.catalogLive", matches = "true")
class PluginCatalogLiveTest {
    @Test void searchPublicTopicAndResolvePublishedPackForgePlugin() throws Exception {
        String proxy = System.getProperty("hdsl.catalogProxy", "http://127.0.0.1:7890");
        PluginCatalogService service = new PluginCatalogService(proxy.isBlank() ? null : URI.create(proxy));
        var page = service.search("", 1, true);
        var details = service.resolve("DSH-PackForge/dsh-pack-plugin", true);
        Path directory = Path.of(".test-data", "plugin-catalog-live"); Files.createDirectories(directory);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checkedAt", Instant.now().toString()); report.put("source", "https://github.com/topics/dsh-plugin");
        report.put("page", page); report.put("details", details);
        report.put("scope", "Read-only GitHub/npm metadata; no credential access, package download, or plugin execution.");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(directory.resolve("report.json").toFile(), report);
        assertFalse(page.entries().isEmpty());
        assertEquals(PluginCatalogService.Status.INSTALLABLE, details.status(), details.message());
        assertTrue(details.targets().stream().anyMatch(target -> target.name().equals("@dsh-packforge/dsh-pack-plugin") && target.spec().startsWith("@dsh-packforge/dsh-pack-plugin@")));
    }
}
