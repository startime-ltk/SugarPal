package com.bloodsugar.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 一键更新配置：更新源地址（GitHub Releases API）与国内加速镜像前缀。
 *
 * <p>与 AlarmConfig 同模式，写入数据根目录（默认 用户主目录/.bloodsugar）
 * 下的 config.properties，读取/保存时保留其它键，互不覆盖；
 * 键名：update.source（默认 GitHub Releases latest API）、
 * update.mirror（国内加速镜像前缀，默认 https://ghproxy.net/）。
 */
public class UpdateConfig {

    /** 默认更新源：糖伴SugarPal PC 仓库 Releases 最新版接口 */
    public static final String DEFAULT_SOURCE =
            "https://api.github.com/repos/startime-ltk/SugarPal/releases/latest";

    /** 默认国内加速镜像前缀；默认留空表示不启用镜像，仅直连下载 */
    public static final String DEFAULT_MIRROR = "";

    private static final String KEY_SOURCE = "update.source";
    private static final String KEY_MIRROR = "update.mirror";

    private UpdateConfig() {
    }

    private static Path configFile() {
        return DataLocation.getStartupDataRoot().resolve("config.properties");
    }

    /** 读取更新源地址，缺失时回退默认值 */
    public static String getSource() {
        return read(KEY_SOURCE, DEFAULT_SOURCE);
    }

    /** 读取镜像前缀，缺失时回退默认值 */
    public static String getMirror() {
        return read(KEY_MIRROR, DEFAULT_MIRROR);
    }

    private static String read(String key, String def) {
        Properties props = new Properties();
        try {
            Path file = configFile();
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
        } catch (IOException ignored) {
            // 读取失败使用默认值，不阻塞功能
        }
        String v = props.getProperty(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    /** 保存更新源地址；保存失败抛 RuntimeException 由调用方提示用户 */
    public static void saveSource(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("更新源地址不能为空");
        }
        save(KEY_SOURCE, source.trim());
    }

    /** 保存镜像前缀；传入空串表示禁用镜像，仅使用直连 */
    public static void saveMirror(String mirror) {
        save(KEY_MIRROR, mirror == null ? "" : mirror.trim());
    }

    private static void save(String key, String value) {
        Path file = configFile();
        Properties props = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
            props.setProperty(key, value);
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "SugarPal local config (AI settings & alarm thresholds).");
            }
        } catch (IOException e) {
            throw new RuntimeException("无法保存更新配置: " + e.getMessage(), e);
        }
    }
}
