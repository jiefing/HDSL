package com.hdsl.account;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.hdsl.runtime.Capabilities;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Launcher-owned account catalogue. Secrets are DPAPI ciphertext at rest. */
public final class AccountService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_STORE_BYTES = 4 * 1024 * 1024;
    private static final String STATUS = "已保存 · API 连接未验证";
    private final Path file;
    private final SecretProtector protector;

    public AccountService(Path root) { this(root, new WindowsSecretProtector()); }
    AccountService(Path root, SecretProtector protector) {
        this.file = root.toAbsolutePath().normalize().resolve("config/accounts.json");
        this.protector = Objects.requireNonNull(protector);
    }

    public synchronized List<AccountEntry> list() throws IOException {
        List<AccountEntry> result = new ArrayList<>();
        for (JsonNode row : read().withArray("accounts")) result.add(entry(row));
        return List.copyOf(result);
    }

    /** An empty key on edit retains the previous encrypted key. New accounts require a key. */
    public synchronized AccountEntry save(String id, String name, String provider, String baseUrl,
                                          String model, String apiKey) throws IOException {
        name = text(name, "账户名称", 128); provider = provider(provider); model = text(model, "模型", 256);
        String providerId = provider;
        ProviderPreset preset = providers().stream().filter(p -> p.id().equals(providerId)).findFirst().orElse(null);
        baseUrl = endpoint(baseUrl == null || baseUrl.isBlank() ? preset == null ? "" : preset.baseUrl() : baseUrl);
        ObjectNode store = read(); ArrayNode rows = store.withArray("accounts");
        ObjectNode row = null;
        if (id != null && !id.isBlank()) {
            validateId(id);
            for (JsonNode value : rows) if (value.path("id").asText().equals(id)) row = (ObjectNode) value;
            if (row == null) throw new IOException("账户不存在或已被删除。");
        } else { id = UUID.randomUUID().toString(); row = JSON.createObjectNode(); rows.add(row); }
        if (apiKey != null && !apiKey.isEmpty()) {
            if (apiKey.length() > 16384 || apiKey.chars().anyMatch(Character::isISOControl) || !apiKey.equals(apiKey.strip()))
                throw new IOException("API Key 含有空白、换行或超出长度限制，请重新粘贴。");
            byte[] plain = apiKey.getBytes(StandardCharsets.UTF_8);
            try { row.put("encryptedKey", Base64.getEncoder().encodeToString(protector.protect(plain))); }
            finally { Arrays.fill(plain, (byte) 0); }
        } else if (!row.hasNonNull("encryptedKey")) throw new IOException("新账户需要 API Key。");
        row.put("id", id).put("name", name).put("provider", provider).put("baseUrl", baseUrl).put("model", model);
        write(store); return entry(row);
    }

    public synchronized void delete(String id) throws IOException {
        validateId(id); ObjectNode store = read(); ArrayNode rows = store.withArray("accounts");
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).path("id").asText().equals(id)) {
            rows.remove(i); write(store); return;
        }
        throw new IOException("账户不存在或已被删除。");
    }

    /** Prepare a non-secret final patch. Existing Harness configuration and credential files are never read here. */
    public synchronized LaunchBinding prepare(String id, Path runtimeDirectory, Capabilities capabilities,
                                               Path bindingDirectory) throws IOException {
        validateId(id); ObjectNode selected = null;
        for (JsonNode row : read().withArray("accounts")) if (row.path("id").asText().equals(id)) selected = (ObjectNode) row;
        if (selected == null) throw new IOException("实例绑定的 API 账户已被删除，请重新选择。");
        AccountEntry account = entry(selected);
        if (!capabilities.launcherOptions().contains("--patch")) throw new IOException("此 Harness 版本尚未确认 --patch 支持，无法安全绑定账户。");
        Path modules = runtimeDirectory.toAbsolutePath().normalize().resolve("node_modules");
        AccountOverlay.Plan plan = AccountOverlay.plan(modules, account);
        byte[] secret;
        try { secret = protector.unprotect(Base64.getDecoder().decode(selected.path("encryptedKey").asText())); }
        catch (IllegalArgumentException e) { throw new IOException("账户加密数据损坏，请重新保存 API Key。"); }
        Path directory = bindingDirectory.toAbsolutePath().normalize().resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(directory);
            Path patch = directory.resolve("account.patch.json");
            Files.writeString(patch, plan.render(directory), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return new LaunchBinding(directory, patch, secret);
        } catch (IOException | RuntimeException e) { Arrays.fill(secret, (byte) 0); throw e; }
    }

    private ObjectNode read() throws IOException {
        if (!Files.exists(file)) { var root = JSON.createObjectNode(); root.put("schemaVersion", 1); root.putArray("accounts"); return root; }
        if (Files.size(file) > MAX_STORE_BYTES) throw new IOException("账户资料文件超过大小限制。");
        JsonNode root;
        try { root = JSON.readTree(Files.readAllBytes(file)); }
        catch (IOException e) { throw new IOException("账户资料文件无法解析。请保留原文件后重新配置。"); }
        if (root == null || !root.isObject() || root.path("schemaVersion").asInt() != 1 || !root.path("accounts").isArray())
            throw new IOException("账户资料格式不受支持。");
        Set<String> ids = new HashSet<>();
        for (JsonNode row : root.path("accounts")) {
            if (!row.isObject()) throw new IOException("账户资料格式不受支持。");
            AccountEntry entry = entry(row); validateId(entry.id());
            if (!ids.add(entry.id()) || !entry.hasKey()) throw new IOException("账户资料包含重复项目或缺失的加密数据。");
        }
        return (ObjectNode) root;
    }

    private void write(ObjectNode root) throws IOException {
        Files.createDirectories(file.getParent()); Path temp = Files.createTempFile(file.getParent(), "accounts-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), root);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private static AccountEntry entry(JsonNode row) throws IOException {
        return new AccountEntry(row.path("id").asText(), text(row.path("name").asText(), "账户名称", 128),
                provider(row.path("provider").asText()), endpoint(row.path("baseUrl").asText()),
                text(row.path("model").asText(), "模型", 256), !row.path("encryptedKey").asText().isBlank(), STATUS);
    }
    private static void validateId(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IOException("无效账户编号。");
    }
    private static String provider(String value) throws IOException {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,80}")) throw new IOException("无效提供商编号。");
        return value;
    }
    private static String text(String value, String label, int max) throws IOException {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new IOException(label + "不能为空、含控制字符或超出长度限制。");
        return value.strip();
    }
    private static String endpoint(String value) throws IOException {
        String url = text(value, "API 地址", 2048);
        try {
            URI uri = URI.create(url);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean local = "http".equalsIgnoreCase(uri.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost());
            if ((!https && !local) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new IOException("API 地址需为 HTTPS（本机可用 HTTP），且不能包含用户信息、查询参数或片段。"); }
        return url.replaceAll("/+$", "");
    }

    public static List<ProviderPreset> providers() { return PRESETS; }
    private static ProviderPreset p(String id, String name, String url) { return new ProviderPreset(id, name, url, ""); }
    private static final List<ProviderPreset> PRESETS = List.of(
            new ProviderPreset("deepseek-official", "DeepSeek", "https://api.deepseek.com", "deepseek-flash"),
            p("openai", "OpenAI", "https://api.openai.com/v1"), p("anthropic", "Anthropic", "https://api.anthropic.com"),
            p("google", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta"),
            p("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"), p("groq", "Groq", "https://api.groq.com/openai/v1"),
            p("mistral", "Mistral", "https://api.mistral.ai/v1"), p("moonshotai", "Moonshot 国际", "https://api.moonshot.ai/v1"),
            p("moonshotai-cn", "Moonshot 国内", "https://api.moonshot.cn/v1"),
            p("minimax", "MiniMax 国际", "https://api.minimax.io/anthropic"), p("minimax-cn", "MiniMax 国内", "https://api.minimaxi.com/anthropic"),
            p("zai", "Z.ai", "https://api.z.ai/api/coding/paas/v4"), p("zai-coding-cn", "智谱 Coding", "https://open.bigmodel.cn/api/coding/paas/v4"),
            p("xai", "xAI", "https://api.x.ai/v1"), p("cerebras", "Cerebras", "https://api.cerebras.ai/v1"),
            p("together", "Together", "https://api.together.ai/v1"), p("huggingface", "Hugging Face", "https://router.huggingface.co/v1"),
            p("nvidia", "NVIDIA", "https://integrate.api.nvidia.com/v1"), p("kimi-coding", "Kimi Coding", "https://api.kimi.com/coding"),
            p("qwen-token-plan-cn", "阿里云 Coding", "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"),
            p("custom-openai-completions", "自定义 OpenAI Chat Completions", ""),
            p("custom-openai-responses", "自定义 OpenAI Responses", ""), p("custom-anthropic-messages", "自定义 Anthropic Messages", ""));
}
