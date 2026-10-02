package com.hdsl.pack;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in downloaded public fixtures. Normal tests remain offline and do not read user instances. */
@EnabledIfSystemProperty(named = "hdsl.realPackFixtures", matches = "true")
class PackRealFixtureTest {
    @Test void verifyPublicMarketSamplesWithoutRunningDependencies() throws Exception {
        Path root = Path.of(".test-data/real-packs").toAbsolutePath().normalize();
        ObjectMapper json = new ObjectMapper();
        JsonNode index = json.readTree(Files.readAllBytes(root.resolve("market-index.json")));
        ObjectNode report = json.createObjectNode();
        report.put("verifiedAt", Instant.now().toString());
        report.put("indexUrl", "https://dsh-packforge.github.io/dsh-pack-market/index.json");
        report.put("indexSha256", SafeArchive.sha(root.resolve("market-index.json")));
        report.put("dependenciesInstalled", false).put("thirdPartyCodeExecuted", false);
        ArrayNode samples = report.putArray("samples");
        Set<String> selected = Set.of("hxh230802.pokemon", "1900992335.desktop-pack");
        int success = 0;
        for (JsonNode entry : index.path("modpacks")) {
            if (!selected.contains(entry.path("id").asText())) continue;
            Path archive = root.resolve(entry.path("name").asText() + "-" + entry.path("version").asText() + ".dspack");
            ObjectNode sample = samples.addObject(); sample.set("sourceIndexEntry", entry.deepCopy());
            sample.put("archive", archive.toString()); sample.put("actualSize", Files.size(archive)); sample.put("actualSha256", SafeArchive.sha(archive));
            assertEquals(entry.path("size").asLong(), Files.size(archive));
            assertEquals(entry.path("sha256").asText(), SafeArchive.sha(archive));
            PackService service = new PackService();
            try {
                PackInspection inspection = service.inspect(archive);
                JsonNode manifest = inspection.manifest();
                // This test deliberately never downloads payload pointers or installs dependencies.
                assertTrue(!manifest.has("files") || manifest.path("files").isEmpty(), "Fixture must not require external assets");
                for (JsonNode skill : manifest.path("skills")) assertFalse(skill.has("urls"), "Fixture must not download skills");
                Path destination = root.resolve("extracted-" + entry.path("name").asText() + "-" + UUID.randomUUID());
                service.extract(inspection, destination, ignored -> {});
                sample.put("status", "inspection-and-extraction-passed"); sample.put("manifestVersion", manifest.path("manifestVersion").asInt());
                sample.put("type", manifest.path("type").asText()); sample.put("dshVersion", inspection.dshVersion()); sample.put("destination", destination.toString());
                sample.put("defaultProfile", inspection.defaultProfile()); sample.set("profiles", json.valueToTree(inspection.profiles()));
                sample.set("warnings", json.valueToTree(inspection.warnings())); sample.put("plannedBytes", inspection.unpackedBytes());
                ArrayNode lockDifferences = sample.putArray("lockfileSpecifierDifferences");
                for (String profile : inspection.profiles()) {
                    JsonNode pkg = json.readTree(Files.readAllBytes(destination.resolve("profiles").resolve(profile).resolve("package.json")));
                    JsonNode expected = manifest.path("type").asText().equals("profile") ? manifest : manifest.path("profiles").path(profile);
                    assertEquals(expected.path("bundles"), pkg.at("/dsh/profile/bundles"));
                    assertEquals(expected.path("dependencies").size(), pkg.path("dependencies").size());
                    Path lockPath = destination.resolve("profiles").resolve(profile).resolve("pnpm-lock.yaml");
                    if (Files.isRegularFile(lockPath) && Files.size(lockPath) < SafeArchive.MAX_METADATA) {
                        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(30); options.setCodePointLimit((int)SafeArchive.MAX_METADATA);
                        Object yaml = new Yaml(new SafeConstructor(options)).load(Files.readString(lockPath));
                        JsonNode lock = json.valueToTree(yaml);
                        for (var dependencies = pkg.path("dependencies").fields(); dependencies.hasNext();) {
                            var dependency = dependencies.next();
                            JsonNode specifier = lock.path("importers").path(".").path("dependencies").path(dependency.getKey()).path("specifier");
                            if (!specifier.equals(dependency.getValue())) lockDifferences.add(profile + ":" + dependency.getKey());
                        }
                    }
                }
                try (var files = Files.walk(destination)) { sample.put("restoredRegularFiles", files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).count()); }
                success++;
            } catch (Exception error) {
                sample.put("status", "rejected"); sample.put("reason", error.getMessage());
            }
        }
        report.put("successfulSamples", success);
        report.put("scope", "Source integrity, inspect and file extraction only. No Harness launch, dependency restore, offline operation or future-version compatibility claim.");
        Files.writeString(root.resolve("extraction-report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        assertEquals(2, samples.size()); assertEquals(2, success, "See .test-data/real-packs/extraction-report.json");
    }
}
