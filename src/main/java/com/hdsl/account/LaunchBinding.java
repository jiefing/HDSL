package com.hdsl.account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** One launch's non-secret overlay plus a short-lived in-memory API key. Never serialize this object. */
public final class LaunchBinding implements AutoCloseable {
    public static final String KEY_ENV = "HDSL_SELECTED_ACCOUNT_API_KEY";
    private final Path directory;
    private final Path patch;
    private byte[] secret;

    LaunchBinding(Path directory, Path patch, byte[] secret) {
        this.directory = directory; this.patch = patch; this.secret = secret;
    }

    /** CLI launcher options must precede app arguments such as --port. */
    public synchronized List<String> appendTo(List<String> command) {
        requireOpen();
        int index = command.indexOf("--profile");
        if (index < 0) index = command.indexOf("web");
        if (index < 0) throw new IllegalArgumentException("无法定位账户 overlay 的 Harness 启动入口。");
        var result = new ArrayList<>(command);
        result.add(index, "--patch"); result.add(index + 1, patch.toString());
        return List.copyOf(result);
    }

    /** Apply immediately before ProcessBuilder.start; never log this environment map. */
    public synchronized void applyEnvironment(Map<String, String> environment) {
        requireOpen();
        environment.put(KEY_ENV, new String(secret, StandardCharsets.UTF_8));
    }

    public Path patchPath() { return patch; }
    /** The account verifier writes only fixed status text, never configuration or credentials. */
    public void awaitValidation(Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Path validation = directory.resolve("account.validation.txt");
        while (System.nanoTime() < deadline) {
            synchronized (this) { requireOpen(); }
            if (Files.isRegularFile(validation)) {
                if (Files.size(validation) > 64) throw new IOException("账户启动校验文件异常。");
                String status = Files.readString(validation, StandardCharsets.UTF_8).strip();
                if (status.equals("ok")) return;
                if (status.equals("failed")) throw new IOException("账户绑定未生效：此 profile 的模型组件或配置与所选账户不兼容。请解除绑定或使用标准 Web profile。");
            }
            try { Thread.sleep(100); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("账户启动校验已取消。"); }
        }
        throw new IOException("账户启动校验超时，未确认此 profile 使用所选账户。");
    }
    private void requireOpen() {
        if (secret == null) throw new IllegalStateException("账户启动绑定已关闭。");
    }

    /** Close after the process exits: loaders may reread the overlay while it runs. */
    @Override public synchronized void close() throws IOException {
        if (secret == null) return;
        Arrays.fill(secret, (byte) 0); secret = null;
        // Only files created by this launch; no traversal or recursive deletion.
        IOException failure = null;
        for (String name : List.of("account.patch.json", "legacy-account-settings.mjs", "account-verify.mjs", "account.validation.txt")) {
            try { Files.deleteIfExists(directory.resolve(name)); }
            catch (IOException e) { failure = e; }
        }
        try { Files.deleteIfExists(directory); } catch (IOException e) { failure = e; }
        if (failure != null) throw new IOException("账户临时启动文件清理未完成。", failure);
    }

    @Override public String toString() { return "LaunchBinding[secret=REDACTED]"; }
}
