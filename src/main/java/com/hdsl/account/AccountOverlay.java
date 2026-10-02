package com.hdsl.account;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Non-secret configuration adapters for the published Harness contracts. */
final class AccountOverlay {
    private static final ObjectMapper JSON = new ObjectMapper();

    static Plan plan(Path modules, AccountEntry account) throws IOException {
        Path scope = modules.resolve("@deepseek-ai");
        String base = source(scope.resolve("dsh-base/cordis.patch.yml"));
        String defaultModel = source(scope.resolve("dsh-agent-default-model/lib/index.js"));
        boolean legacy = defaultModel.contains("installSettingsSection(") && defaultModel.contains("AGENT_DEFAULT_MODEL_SETTINGS_NAMESPACE");
        boolean liveConfig = defaultModel.contains("config.provider.get()") && defaultModel.contains("currentSelection()");
        if ((!legacy && !liveConfig) || !base.contains("id: agent-default-model")) unsupported();
        Path legacyProvider = null;
        if (legacy) {
            legacyProvider = scope.resolve("dsh-settings-file/lib/index.js");
            String settingsSource = source(scope.resolve("dsh-settings/lib/index.js"));
            if (!base.contains("@deepseek-ai/dsh-settings-file") || !source(legacyProvider).contains("extends SettingsProvider")
                    || !settingsSource.contains("section(ns)") || !settingsSource.contains("publish(doc, source")) unsupported();
        }
        String route = account.provider();
        ObjectNode selection = JSON.createObjectNode().put("provider", route).put("model", account.model());
        ObjectNode adapter = JSON.createObjectNode();
        String effectiveBaseUrl = account.baseUrl();
        String adapterId;
        if (route.equals("deepseek-official")) {
            adapterId = "llm-deepseek";
            String plugin = base.contains("name: '@deepseek-ai/dsh-llm-deepseek-api-key'") ? "dsh-llm-deepseek-api-key" : "dsh-llm-deepseek";
            String code = source(scope.resolve(plugin + "/lib/index.js"));
            if (!code.contains("apiKeyEnv") || !code.contains("deepseek-official")) unsupported();
            effectiveBaseUrl = deepSeekEndpoint(scope, account.baseUrl());
            adapter.put("apiKeyEnv", LaunchBinding.KEY_ENV).put("baseURL", effectiveBaseUrl);
            adapter.putArray("models").addObject().put("id", account.model());
        } else {
            adapterId = "llm-pi-ai";
            String code = source(scope.resolve("dsh-llm-pi-ai/lib/index.js"));
            if (!base.contains("id: llm-pi-ai") || !code.contains("apiKeyEnv") || !code.contains("providers: z.dict(profile)")) unsupported();
            Path providers = modules.resolve("@earendil-works/pi-ai/dist/providers");
            String api;
            if (route.startsWith("custom-")) {
                api = route.substring("custom-".length());
                if (!Set.of("openai-completions", "openai-responses", "anthropic-messages").contains(api))
                    throw new IOException("未识别自定义提供商协议。请选择界面中提供的自定义 API 类型。");
                if (!code.contains(api)) unsupported();
            } else {
                Path providerFile = providers.resolve(route + ".js");
                if (!Files.isRegularFile(providerFile)) throw new IOException("当前 Harness 未包含提供商 " + route + "；请选择支持它的运行时或自定义协议。");
                String providerSource = source(providerFile);
                if (!providerSource.contains("apiKey") || providerSource.contains("id: \"openai-codex\"") || route.equals("github-copilot"))
                    throw new IOException("此提供商需要专用登录流程，不能用统一 API Key 账户替代。");
                api = switch (route) {
                    case "openai", "xai" -> "openai-responses";
                    case "anthropic", "minimax", "minimax-cn", "kimi-coding" -> "anthropic-messages";
                    case "google" -> "google-generative-ai";
                    default -> catalogApi(providers.resolve("data/" + route + ".json"), account.model());
                };
                // These public routes explicitly offer an OpenAI-compatible endpoint.
                if (Set.of("openrouter", "mistral").contains(route)) api = "openai-completions";
            }
            ObjectNode profile = adapter.putObject("providers").putObject(route);
            profile.put("apiKeyEnv", LaunchBinding.KEY_ENV).put("baseURL", account.baseUrl()).put("api", api);
            profile.putArray("models").addObject().put("id", account.model());
        }
        ObjectNode overrides = JSON.createObjectNode();
        overrides.set("agent-default-model", selection); overrides.set(adapterId, adapter);
        ArrayNode patches = JSON.createArrayNode();
        patches.addObject().put("id", "agent-default-model").set("config", selection);
        patches.addObject().put("id", adapterId).put("disabled", false).set("config", adapter);
        return new Plan(patches, overrides, legacyProvider, account, adapterId, effectiveBaseUrl);
    }

    /** Official aliases select the protocol used by installed code; private endpoints stay literal. */
    private static String deepSeekEndpoint(Path scope, String configured) throws IOException {
        URI endpoint = URI.create(configured);
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || !"api.deepseek.com".equalsIgnoreCase(endpoint.getHost())
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)) return configured;
        String path = endpoint.getPath().replaceAll("/+$", "");
        if (!Set.of("", "/v1", "/anthropic", "/anthropic/v1").contains(path)) return configured;
        String adapter = source(scope.resolve("dsh-llm-deepseek/lib/index.js"));
        boolean messages = adapter.contains("https://api.deepseek.com/anthropic")
                && (adapter.contains("messagesApiRoot(") || adapter.contains("/v1/messages"));
        boolean completions = adapter.contains("https://api.deepseek.com") && adapter.contains("${connection.baseURL}/chat/completions");
        if (messages == completions) throw new IOException("无法识别此 Harness 的 DeepSeek 官方 API 协议，请更新启动器或使用明确的自定义提供商配置。");
        return messages ? "https://api.deepseek.com/anthropic" : "https://api.deepseek.com";
    }

    private static String catalogApi(Path file, String model) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("当前运行时未提供可读取的提供商模型目录，请使用明确的自定义协议。");
        if (Files.size(file) > 16 * 1024 * 1024) unsupported();
        JsonNode root = JSON.readTree(Files.readAllBytes(file));
        String first = null;
        var groups = root.fields();
        while (groups.hasNext()) {
            var group = groups.next();
            if (!group.getValue().isObject()) continue;
            for (JsonNode item : group.getValue()) {
                if (item.path("api").isTextual()) {
                    if (first == null) first = item.path("api").asText();
                    if (item.path("id").asText().equals(model)) return item.path("api").asText();
                }
            }
        }
        if (first == null) unsupported();
        return first;
    }
    private static String source(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > 8 * 1024 * 1024) unsupported();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
    private static void unsupported() throws IOException {
        throw new IOException("当前 Harness 的模型配置结构尚未识别，无法保证账户绑定生效。请更换已支持的运行时，或先解除账户绑定。");
    }

    record Plan(ArrayNode patches, ObjectNode overrides, Path legacyProvider, AccountEntry account, String adapterId, String effectiveBaseUrl) {
        String render(Path directory) throws IOException {
            ArrayNode rendered = patches.deepCopy();
            if (legacyProvider != null) {
                Path bridge = directory.resolve("legacy-account-settings.mjs");
                String code = """
                        // HDSL account overlay: public configuration only, no API key is written here.
                        const overrides = %s;
                        export const inject = ['settings'];
                        export function apply(ctx) {
                          const settings = ctx.settings;
                          if (typeof settings.section !== 'function' || typeof settings.publish !== 'function' || typeof settings.write !== 'function')
                            throw new Error('HDSL does not recognize this legacy settings provider.');
                          const originalSection = settings.section;
                          const originalWrite = settings.write;
                          ctx.effect(() => {
                            settings.section = function(ns) {
                              const original = originalSection.call(this, ns);
                              const selected = overrides[ns];
                              if (!selected) return original;
                              const merged = { ...(original ?? {}), ...selected };
                              if (selected.providers) merged.providers = { ...(original?.providers ?? {}), ...selected.providers };
                              return merged;
                            };
                            settings.write = function(ns, ...args) {
                              if (Object.hasOwn(overrides, ns)) throw new Error('This model setting is controlled by the selected HDSL account. Change or unbind the account in HDSL.');
                              return originalWrite.call(this, ns, ...args);
                            };
                            settings.publish(settings.document);
                            return () => {
                              settings.section = originalSection;
                              settings.write = originalWrite;
                            };
                          });
                        }
                        """.formatted(JSON.writeValueAsString(overrides));
                Files.writeString(bridge, code, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                rendered.addObject().putArray("insert").addObject().put("id", "hdsl-account-settings")
                        .put("name", bridge.toUri().toString());
            }
            Path verifier = directory.resolve("account-verify.mjs");
            ObjectNode expected = JSON.createObjectNode().put("provider", account.provider()).put("model", account.model())
                    .put("baseURL", effectiveBaseUrl).put("adapterId", adapterId).put("keyRef", LaunchBinding.KEY_ENV)
                    .put("legacy", legacyProvider != null);
            String verifyCode = """
                    // Checks public runtime configuration only. It never resolves or reads a key.
                    import { writeFileSync } from 'node:fs';
                    const expected = %s;
                    const statusPath = %s;
                    function matches(config) {
                      const route = expected.adapterId === 'llm-pi-ai' ? config?.providers?.[expected.provider] : config;
                      return route?.apiKeyEnv === expected.keyRef && route?.baseURL === expected.baseURL;
                    }
                    export function apply(ctx) {
                      const deadline = Date.now() + 10000;
                      let stableSince = 0;
                      ctx.effect(() => {
                        const timer = setInterval(() => {
                          let valid = false;
                          try {
                            const selection = ctx.get('agentDefaultModel')?.currentSelection();
                            const llm = ctx.get('llm');
                            const loader = ctx.get('loader');
                            const entries = loader ? [...loader.entries()].filter(entry => entry.options.id === expected.adapterId) : [];
                            const entry = entries.length === 1 ? entries[0] : undefined;
                            const names = expected.adapterId === 'llm-pi-ai'
                              ? ['@deepseek-ai/dsh-llm-pi-ai']
                              : ['@deepseek-ai/dsh-llm-deepseek', '@deepseek-ai/dsh-llm-deepseek-api-key'];
                            const effective = expected.legacy ? ctx.get('settings')?.get(expected.adapterId) : entry?.options.config;
                            valid = selection?.provider === expected.provider && selection?.model === expected.model
                              && entry && !entry.disabled && names.includes(entry.options.name)
                              && matches(entry.options.config) && matches(effective)
                              && typeof llm?.listProviders === 'function' && llm.listProviders().some(item => item.id === expected.provider);
                          } catch { valid = false; }
                          if (valid) {
                            stableSince ||= Date.now();
                            if (Date.now() - stableSince >= 300) {
                              clearInterval(timer); writeFileSync(statusPath, 'ok', { encoding: 'utf8', mode: 0o600 });
                              return;
                            }
                          } else stableSince = 0;
                          if (Date.now() >= deadline) {
                            clearInterval(timer); writeFileSync(statusPath, 'failed', { encoding: 'utf8', mode: 0o600 });
                            process.stderr.write('[HDSL] Account binding validation failed: this profile did not activate the selected account configuration.\\n');
                            process.exit(78);
                          }
                        }, 100);
                        return () => clearInterval(timer);
                      });
                    }
                    """.formatted(JSON.writeValueAsString(expected), JSON.writeValueAsString(directory.resolve("account.validation.txt").toString()));
            Files.writeString(verifier, verifyCode, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            rendered.addObject().putArray("insert").addObject().put("id", "hdsl-account-verifier").put("name", verifier.toUri().toString());
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
        }
    }
}
