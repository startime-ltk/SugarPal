/*
 * Copyright (c) 2026 李图康
 * SPDX-License-Identifier: MIT
 */
package com.bloodsugar.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 一键更新配置：更新源地址（GitHub Releases API）与国内加速镜像前缀。
 *
 * <p>与 AlarmConfig 同模式，写入数据根目录（默认 用户主目录/.bloodsugar）
 * 下的 config.properties，读取/保存时保留其它键，互不覆盖；
 * 键名：update.source（默认 GitHub Releases latest API）、
 * update.mirror（国内加速镜像前缀，默认见 {@link #DEFAULT_MIRRORS}）。
 */
public class UpdateConfig {

    /** 默认更新源：糖伴SugarPal PC 仓库 Releases 最新版接口 */
    public static final String DEFAULT_SOURCE =
            "https://api.github.com/repos/startime-ltk/SugarPal/releases/latest";

    /**
     * 内置加速镜像候选（按实测可达性排序，靠前的先试）。
     *
     * <p>为什么要默认启用：{@code github.com} 与文件真实所在的
     * {@code release-assets.githubusercontent.com} 是不同线路，前者在国内常整段不可达，
     * 镜像为空时只剩直连一条路，必然超时。这里预置两个实测可用的前缀，
     * 主用 {@code ghproxy.net}，失败自动落到 {@code gh-proxy.com}。
     *
     * <p>实测数据（2026-11，Range 请求取前 64KB）：
     * ghproxy.net → HTTP 206，1.9s；gh-proxy.com → HTTP 206，3.6s；
     * ghfast.top / hub.gitmirror.com / ghproxy.cc / gh.llkk.cc / github.moeyy.xyz → 不可达。
     *
     * <p>用户可在「关于 → 更新源」里填自定义前缀覆盖；填 {@code none} 表示禁用镜像仅直连。
     */
    public static final List<String> DEFAULT_MIRRORS = List.of(
            "https://ghproxy.net/",
            "https://gh-proxy.com/"
    );

    /** 兼容旧引用：默认取第一个镜像 */
    public static final String DEFAULT_MIRROR = DEFAULT_MIRRORS.get(0);

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

    /**
     * 取本次下载要依次尝试的镜像前缀列表。
     *
     * <p>规则：配置文件显式写了 {@code update.mirror} 就用它（写 {@code none}/空则禁用镜像）；
     * 没写过则用内置的 {@link #DEFAULT_MIRRORS}，按顺序全部尝试。
     *
     * @return 镜像前缀列表；返回空列表表示禁用镜像、只走直连
     */
    public static List<String> getMirrors() {
        String raw = readRaw(KEY_MIRROR);
        if (raw == null) {
            // 用户从未配置过 → 启用内置默认镜像
            return DEFAULT_MIRRORS;
        }
        String v = raw.trim();
        if (v.isEmpty() || "none".equalsIgnoreCase(v) || "-".equals(v)) {
            return List.of();
        }
        // 支持用户用逗号/空格填多个
        List<String> list = new ArrayList<>();
        for (String s : v.split("[,;\\s]+")) {
            if (!s.isBlank()) {
                list.add(s.trim());
            }
        }
        return list;
    }

    /** 读取原始值（区分「没写过」与「写了空串」）；未配置返回 null */
    private static String readRaw(String key) {
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
        return props.getProperty(key);
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
