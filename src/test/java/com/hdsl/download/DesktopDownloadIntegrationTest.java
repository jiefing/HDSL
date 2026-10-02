package com.hdsl.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="hdsl.desktopIntegration",matches="true")
class DesktopDownloadIntegrationTest {
    @Test void officialWindowsInstallerDownloadsCompletely()throws Exception{
        Path root=Path.of(".test-data/desktop-download-smoke").toAbsolutePath();
        DesktopDownloadService service=new DesktopDownloadService(root,System.getProperty("hdsl.testProxy","http://127.0.0.1:7890"));
        var releases=service.releases("deepseek-ai/deepseek-harness");
        var selected=releases.stream().filter(a->!a.prerelease()&&a.name().endsWith(".exe")).findFirst().orElseThrow();
        var downloaded=service.download(selected,System.out::println);
        assertEquals(selected.size(),Files.size(downloaded));
        Files.writeString(root.resolve("verification.md"),"# Desktop download verification\n\nOfficial page: https://www.deepseek.com/en/download/\n\nSource: "+selected.source()+"\n\nVersion: "+selected.version()+"\n\nAsset: "+selected.name()+"\n\nBytes: "+selected.size()+"\n\nPublished SHA-256: "+(selected.sha256().isBlank()?"not provided; local checksum only":selected.sha256())+"\n\nInstaller was downloaded, not executed.\n");
    }
}
