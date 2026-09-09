package com.bloodsugar.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * AI 建议模块的配置管理。
 * 配置文件保存在数据根目录（默认 用户主目录/.bloodsugar）下的 config.properties，
 * 与数据库同目录，跟随「数据位置」设置一起迁移；
 * 内置智谱 GLM / DeepSeek / 通义千问 / Kimi / 豆包(火山方舟) 五个 OpenAI 兼容服务商，
 * 并支持「自定义（OpenAI 兼容）」填任意 baseUrl / model / apiKey。
 * 每个 provider 各存一套 baseUrl / model / apiKey，界面上可切换当前使用的模型，
 * Key 由用户在应用内填写，只保存在本地配置文件，不写死在代码里、不进日志；
 * 落盘前做本地混淆编码（非加密，仅防明文误读），兼容旧版本遗留的明文 Key。
 */
public class AiConfig {

    public static final String PROVIDER_GLM = "glm";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_QWEN = "qwen";
    public static final String PROVIDER_KIMI = "kimi";
    public static final String PROVIDER_DOUBAO = "doubao";
    public static final String PROVIDER_CUSTOM = "custom";

    /** provider 元数据：id、界面显示名、默认接口地址、默认模型名 */
    private static final class ProviderMeta {
        final String id;
        final String display;
        final String defaultBaseUrl;
        final String defaultModel;

        ProviderMeta(String id, String display, String defaultBaseUrl, String defaultModel) {
            this.id = id;
            this.display = display;
            this.defaultBaseUrl = defaultBaseUrl;
            this.defaultModel = defaultModel;
        }
    }

    private static final List<ProviderMeta> PROVIDERS = List.of(
            new ProviderMeta(PROVIDER_GLM, "智谱 GLM-4.6-Flash",
                    "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4.6-flash"),
            new ProviderMeta(PROVIDER_DEEPSEEK, "DeepSeek Chat",
                    "https://api.deepseek.com/v1/chat/completions", "deepseek-chat"),
            new ProviderMeta(PROVIDER_QWEN, "通义千问 Qwen",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus"),
            new ProviderMeta(PROVIDER_KIMI, "Kimi (月之暗面)",
                    "https://api.moonshot.cn/v1/chat/completions", "moonshot-v1-8k"),
            new ProviderMeta(PROVIDER_DOUBAO, "豆包 (火山方舟)",
                    "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "doubao-seed-1-6-250615"),
            new ProviderMeta(PROVIDER_CUSTOM, "自定义 OpenAI 兼容", "", ""));

    private static Path configFile() {
        return DataLocation.getStartupDataRoot().resolve("config.properties");
    }

    private final Properties props = new Properties();

    /** 本地混淆盐：仅用于防止 config.properties 明文直读，不承担真正的安全加密 */
    private static final byte[] LOCAL_OBFUSCATION = {0x35, 0x5A, 0x13, 0x77, 0x2E, 0x44, 0x61, 0x1C};

    /** 将 API Key 做本地混淆编码后落盘；空值返回空串，不产生 enc 前缀 */
    private static String encodeKey(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        byte[] src = plain.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = (byte) (src[i] ^ LOCAL_OBFUSCATION[i % LOCAL_OBFUSCATION.length]);
        }
        return "enc:" + java.util.Base64.getEncoder().encodeToString(out);
    }

    /** 读取时还原明文；兼容旧版本直接存储的明文 Key（不以 enc: 开头则原样返回） */
    private static String decodeKey(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        if (!stored.startsWith("enc:")) {
            return stored;
        }
        try {
            byte[] data = java.util.Base64.getDecoder().decode(stored.substring(4));
            byte[] out = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                out[i] = (byte) (data[i] ^ LOCAL_OBFUSCATION[i % LOCAL_OBFUSCATION.length]);
            }
            return new String(out, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 解码失败视为损坏数据，返回空避免崩溃
            return "";
        }
    }

    private AiConfig() {
    }

    /** 读取配置；文件不存在或读失败时用默认模板，首次使用不会强制写盘 */
    public static AiConfig load() {
        AiConfig cfg = new AiConfig();
        Path file = configFile();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    cfg.props.load(in);
                }
            }
        } catch (IOException ignored) {
            // 读失败就用默认值，不阻塞启动
        }
        cfg.props.putIfAbsent("ai.activeProvider", PROVIDER_GLM);
        cfg.props.putIfAbsent("ai.glm.baseUrl", "https://open.bigmodel.cn/api/paas/v4/chat/completions");
        cfg.props.putIfAbsent("ai.glm.model", "glm-4.6-flash");
        cfg.props.putIfAbsent("ai.glm.apiKey", "");
        cfg.props.putIfAbsent("ai.deepseek.baseUrl", "https://api.deepseek.com/v1/chat/completions");
        cfg.props.putIfAbsent("ai.deepseek.model", "deepseek-chat");
        cfg.props.putIfAbsent("ai.deepseek.apiKey", "");
        cfg.props.putIfAbsent("ai.qwen.baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        cfg.props.putIfAbsent("ai.qwen.model", "qwen-plus");
        cfg.props.putIfAbsent("ai.qwen.apiKey", "");
        cfg.props.putIfAbsent("ai.kimi.baseUrl", "https://api.moonshot.cn/v1/chat/completions");
        cfg.props.putIfAbsent("ai.kimi.model", "moonshot-v1-8k");
        cfg.props.putIfAbsent("ai.kimi.apiKey", "");
        cfg.props.putIfAbsent("ai.doubao.baseUrl", "https://ark.cn-beijing.volces.com/api/v3/chat/completions");
        cfg.props.putIfAbsent("ai.doubao.model", "doubao-seed-1-6-250615");
        cfg.props.putIfAbsent("ai.doubao.apiKey", "");
        cfg.props.putIfAbsent("ai.custom.baseUrl", "");
        cfg.props.putIfAbsent("ai.custom.model", "");
        cfg.props.putIfAbsent("ai.custom.apiKey", "");
        return cfg;
    }

    /** 保存到本地配置文件；保存失败抛 RuntimeException 由调用方提示用户 */
    public void save() {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            // 迁移旧版本遗留的明文 Key：统一转为本地混淆编码后再落盘
            for (ProviderMeta m : PROVIDERS) {
                String key = "ai." + m.id + ".apiKey";
                String raw = props.getProperty(key, "");
                if (!raw.isEmpty() && !raw.startsWith("enc:")) {
                    props.setProperty(key, encodeKey(raw));
                }
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Blood Sugar AI config. Keys are stored locally only.");
            }
        } catch (IOException e) {
            throw new RuntimeException("无法保存 AI 配置: " + e.getMessage(), e);
        }
    }

    public static Path getConfigFile() {
        return configFile();
    }

    /** 全部 provider id 列表（内置 + 自定义），顺序即 UI 展示顺序 */
    public static List<String> getProviderIds() {
        List<String> ids = new ArrayList<>();
        for (ProviderMeta m : PROVIDERS) {
            ids.add(m.id);
        }
        return ids;
    }

    /** 模型显示名，用于 UI 下拉框；未知 provider 回退显示 id 本身 */
    public static String displayName(String provider) {
        for (ProviderMeta m : PROVIDERS) {
            if (m.id.equals(provider)) {
                return m.display;
            }
        }
        return provider;
    }

    /** 由显示名反查 provider id；找不到返回 null */
    public static String providerIdByDisplay(String display) {
        for (ProviderMeta m : PROVIDERS) {
            if (m.display.equals(display)) {
                return m.id;
            }
        }
        return null;
    }

    /** 该 provider 是否为注册表内置/自定义中的合法 id */
    public static boolean isValidProvider(String provider) {
        return isRegistered(provider);
    }

    private static boolean isRegistered(String provider) {
        for (ProviderMeta m : PROVIDERS) {
            if (m.id.equals(provider)) {
                return true;
            }
        }
        return false;
    }

    private static ProviderMeta metaOf(String provider) {
        for (ProviderMeta m : PROVIDERS) {
            if (m.id.equals(provider)) {
                return m;
            }
        }
        return null;
    }

    /** 默认接口地址（新用户或字段为空时展示/落盘用） */
    public static String getDefaultBaseUrl(String provider) {
        ProviderMeta m = metaOf(provider);
        return m == null ? "" : m.defaultBaseUrl;
    }

    /** 默认模型名 */
    public static String getDefaultModel(String provider) {
        ProviderMeta m = metaOf(provider);
        return m == null ? "" : m.defaultModel;
    }

    public String getActiveProvider() {
        String active = props.getProperty("ai.activeProvider", PROVIDER_GLM);
        return isRegistered(active) ? active : PROVIDER_GLM;
    }

    public void setActiveProvider(String provider) {
        props.setProperty("ai.activeProvider", provider);
    }

    public String getBaseUrl(String provider) {
        return props.getProperty("ai." + provider + ".baseUrl", "");
    }

    public void setBaseUrl(String provider, String value) {
        props.setProperty("ai." + provider + ".baseUrl", value);
    }

    public String getModel(String provider) {
        return props.getProperty("ai." + provider + ".model", "");
    }

    public void setModel(String provider, String value) {
        props.setProperty("ai." + provider + ".model", value);
    }

    public String getApiKey(String provider) {
        return decodeKey(props.getProperty("ai." + provider + ".apiKey", ""));
    }

    public void setApiKey(String provider, String value) {
        props.setProperty("ai." + provider + ".apiKey", encodeKey(value));
    }
}
