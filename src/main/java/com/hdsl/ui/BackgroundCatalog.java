package com.hdsl.ui;

import javafx.scene.image.Image;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Built-in backgrounds and the legacy custom-file setting share one persisted value. */
public final class BackgroundCatalog {
    public static final String DEFAULT = "builtin:whale-01";
    public record Entry(String id, String title) {
        public String value() { return "builtin:" + id; }
        public Optional<URL> resource() {
            // Packaging may use either lossless PNG or JPEG without changing saved settings.
            URL image = BackgroundCatalog.class.getResource("backgrounds/" + id + ".png");
            if (image == null) image = BackgroundCatalog.class.getResource("backgrounds/" + id + ".jpg");
            return Optional.ofNullable(image);
        }
    }
    private static final List<Entry> ENTRIES = List.of(
            new Entry("whale-01", "01 晴空招手"), new Entry("whale-02", "02 浅海微风"),
            new Entry("whale-03", "03 暖阳小憩"), new Entry("whale-04", "04 月色晚安"),
            new Entry("whale-05", "05 初雪围巾"));

    private BackgroundCatalog() {}
    public static List<Entry> entries() { return ENTRIES; }
    public static boolean isBuiltIn(String value) { return value == null || value.isBlank() || value.startsWith("builtin:"); }
    public static String normalized(String value) {
        if (value == null || value.isBlank()) return DEFAULT;
        if (value.startsWith("builtin:") && ENTRIES.stream().noneMatch(entry -> entry.value().equals(value))) return DEFAULT;
        return value;
    }
    public static Optional<String> imageLocation(String value) {
        String normalized = normalized(value);
        if (isBuiltIn(normalized)) return ENTRIES.stream().filter(entry -> entry.value().equals(normalized))
                .findFirst().flatMap(Entry::resource).map(URL::toExternalForm);
        try { return Optional.of(Path.of(normalized).toUri().toString()); }
        catch (IllegalArgumentException invalidPath) { return Optional.empty(); }
    }
    public static Image thumbnail(Entry entry) {
        return entry.resource().map(resource -> new Image(resource.toExternalForm(), 240, 135, true, true, true)).orElse(null);
    }
}
