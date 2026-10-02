package com.hdsl.pack;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;

/** An inspection is bound to the exact archive bytes, not only its file name. */
public record PackInspection(Path archive, String format, String name, String version,
                             String dshVersion, String defaultProfile, List<String> profiles,
                             List<String> warnings, long unpackedBytes, JsonNode manifest,
                             String archiveSha256) {
    public PackInspection {
        profiles = List.copyOf(profiles);
        warnings = List.copyOf(warnings);
        manifest = manifest.deepCopy();
    }
    @Override public JsonNode manifest() { return manifest.deepCopy(); }
}
