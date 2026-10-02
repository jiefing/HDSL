package com.hdsl.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseCohortCacheTest {
    private static final String VERSION = "1.0.0-rc.2";
    private static final String REGISTRY = "https://registry.example.test";
    @TempDir Path root;

    @Test void completeCohortIsReusableAcrossSessionsWithoutNetwork() throws Exception {
        FixtureRegistry first = service(REGISTRY);
        List<String> progress = new ArrayList<>();
        ObjectNode expected = first.releaseCohort(VERSION, progress::add);
        assertEquals(2, expected.size()); assertEquals(3, first.requests.get());
        assertTrue(progress.contains("[安装进度] 核对发行依赖：已核对 0 项，当前待核对 1 项"));
        assertTrue(progress.contains("[安装进度] 核对发行依赖：已核对 1 项，当前待核对 1 项"));
        assertTrue(progress.contains("[安装进度] 核对发行依赖：已核对 2 项，当前待核对 0 项"));
        assertTrue(progress.stream().noneMatch(line -> line.contains("%")));

        FixtureRegistry next = service(REGISTRY); next.failNetwork = true;
        progress.clear();
        assertEquals(expected, next.releaseCohort(VERSION, progress::add));
        assertEquals(0, next.requests.get());
        assertTrue(progress.getFirst().contains("复用缓存 2 项"));
    }

    @Test void changedRegistryCannotReuseAnotherRegistrysCache() throws Exception {
        service(REGISTRY).releaseCohort(VERSION, null);
        FixtureRegistry mirror = service("https://mirror.example.test");
        mirror.releaseCohort(VERSION, null);
        assertEquals(3, mirror.requests.get());
        assertEquals(2, cacheFiles().size());
    }

    @Test void expiredOrFutureDatedCacheIsRefreshed() throws Exception {
        FixtureRegistry service = service(REGISTRY);
        service.releaseCohort(VERSION, null);
        for (long timestamp : List.of(System.currentTimeMillis() - Duration.ofDays(31).toMillis(),
                System.currentTimeMillis() + Duration.ofDays(1).toMillis())) {
            Path cache = cacheFiles().getFirst();
            ObjectNode saved = (ObjectNode) RuntimeService.JSON.readTree(cache.toFile());
            saved.put("checkedAtEpochMillis", timestamp);
            RuntimeService.JSON.writeValue(cache.toFile(), saved);
            int previousRequests = service.requests.get();
            service.releaseCohort(VERSION, null);
            assertEquals(previousRequests + 3, service.requests.get());
        }
    }

    @Test void corruptOrIncompleteCacheNeverSkipsMetadataValidation() throws Exception {
        FixtureRegistry service = service(REGISTRY);
        service.releaseCohort(VERSION, null);
        for (String field : List.of("complete", "version", "sha256", "overrides")) {
            Path cache = cacheFiles().getFirst();
            ObjectNode saved = (ObjectNode) RuntimeService.JSON.readTree(cache.toFile());
            switch (field) {
                case "complete" -> saved.put("complete", false);
                case "version" -> saved.put("version", "2.0.0");
                case "sha256" -> saved.put("sha256", "corrupted");
                case "overrides" -> ((ObjectNode) saved.path(field)).put("@deepseek-ai/dsh-a", "2.0.0");
            }
            RuntimeService.JSON.writeValue(cache.toFile(), saved);
            int previousRequests = service.requests.get();
            List<String> progress = new ArrayList<>();
            service.releaseCohort(VERSION, progress::add);
            assertEquals(previousRequests + 3, service.requests.get());
            assertTrue(progress.getFirst().contains("缓存已失效"));
        }
    }

    @Test void failedFetchDoesNotPublishPartialCache() throws Exception {
        FixtureRegistry service = service(REGISTRY); service.failPackageB = true;
        assertThrows(IOException.class, () -> service.releaseCohort(VERSION, null));
        assertTrue(cacheFiles().isEmpty());
        service.failPackageB = false;
        assertEquals(2, service.releaseCohort(VERSION, null).size());
        assertEquals(6, service.requests.get());
        assertEquals(1, cacheFiles().size());
    }

    @Test void mismatchedMetadataDoesNotPublishCache() {
        FixtureRegistry service = service(REGISTRY); service.wrongVersion = true;
        IOException error = assertThrows(IOException.class, () -> service.releaseCohort(VERSION, null));
        assertTrue(error.getMessage().contains("版本不匹配"));
        assertFalse(Files.exists(root.resolve("cache/registry-metadata")));
    }

    @Test void cancellationInterruptsOutstandingRequestsWithoutPublishingPartialCache() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1), requestInterrupted = new CountDownLatch(1);
        RuntimeService service = new FixtureRegistry(root, REGISTRY) {
            @Override JsonNode registryJson(String suffix) throws IOException {
                if (suffix.contains("dsh-a/")) {
                    requestStarted.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException e) {
                        requestInterrupted.countDown(); Thread.currentThread().interrupt();
                        throw new IOException("fixture canceled", e);
                    }
                }
                return super.registryJson(suffix);
            }
        };
        AtomicBoolean interrupted = new AtomicBoolean(), failed = new AtomicBoolean();
        Thread installer = Thread.ofPlatform().start(() -> {
            try { service.releaseCohort(VERSION, null); }
            catch (IOException e) { failed.set(true); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
            installer.interrupt(); installer.join(5000);
            assertFalse(installer.isAlive()); assertTrue(failed.get()); assertTrue(interrupted.get());
            assertTrue(requestInterrupted.await(5, TimeUnit.SECONDS));
            assertTrue(cacheFiles().isEmpty());
        } finally { installer.interrupt(); installer.join(5000); }
    }

    private FixtureRegistry service(String registry) { return new FixtureRegistry(root, registry); }

    private List<Path> cacheFiles() throws IOException {
        Path cache = root.resolve("cache/registry-metadata");
        if (!Files.isDirectory(cache)) return List.of();
        try (var files = Files.walk(cache)) {
            return files.filter(p -> p.getFileName().toString().startsWith("cohort-") && p.toString().endsWith(".json")).toList();
        }
    }

    private static class FixtureRegistry extends RuntimeService {
        final AtomicInteger requests = new AtomicInteger();
        boolean failNetwork, failPackageB, wrongVersion;

        FixtureRegistry(Path root, String registry) { super(root, registry, ""); }

        @Override JsonNode registryJson(String suffix) throws IOException {
            requests.incrementAndGet();
            if (failNetwork || (failPackageB && suffix.contains("dsh-b/"))) throw new IOException("fixture fetch failure");
            String name = suffix.contains("dsh-a/") ? "@deepseek-ai/dsh-a"
                    : suffix.contains("dsh-b/") ? "@deepseek-ai/dsh-b" : PACKAGE;
            ObjectNode data = JSON.createObjectNode().put("name", name).put("version", wrongVersion ? "9.0.0" : VERSION);
            if (name.equals(PACKAGE)) data.putObject("dependencies").put("@deepseek-ai/dsh-a", "^" + VERSION);
            if (name.endsWith("-a")) data.putObject("peerDependencies").put("@deepseek-ai/dsh-b", "~" + VERSION);
            if (name.endsWith("-b")) data.putObject("optionalDependencies").put("@deepseek-ai/dsh-a", VERSION);
            return data;
        }
    }
}
