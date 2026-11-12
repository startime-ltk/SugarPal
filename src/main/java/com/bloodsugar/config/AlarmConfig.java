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
import java.util.Properties;

/**
 * P0-1 异常报警阈值配置。
 *
 * 阈值写入数据根目录（默认 用户主目录/.bloodsugar）下的 config.properties，
 * 与 AI 配置共用同一文件：读取/保存时保留其它键，互不覆盖；
 * 键名与安卓端 AlarmConfig 保持一致（alarm.low / alarm.high），
 * 默认低 3.9、高 13.9 mmol/L，与 AGP 指标口径一致。
 */
public class AlarmConfig {

    public static final double DEFAULT_LOW = 3.9;
    public static final double DEFAULT_HIGH = 13.9;

    private static final String KEY_LOW = "alarm.low";
    private static final String KEY_HIGH = "alarm.high";

    private AlarmConfig() {
    }

    private static Path configFile() {
        return DataLocation.getStartupDataRoot().resolve("config.properties");
    }

    /** 读取低阈值，非法或缺失时回退默认值 */
    public static double getLow() {
        return readDouble(KEY_LOW, DEFAULT_LOW);
    }

    /** 读取高阈值，非法或缺失时回退默认值 */
    public static double getHigh() {
        return readDouble(KEY_HIGH, DEFAULT_HIGH);
    }

    private static double readDouble(String key, double def) {
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
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            double d = Double.parseDouble(v.trim());
            return d > 0 ? d : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 保存阈值；保存失败抛 RuntimeException 由调用方提示用户 */
    public static void save(double low, double high) {
        if (!(low > 0) || !(high > low)) {
            throw new IllegalArgumentException("阈值不合法：需满足 0 < 低阈值 < 高阈值");
        }
        Path file = configFile();
        Properties props = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
            props.setProperty(KEY_LOW, String.valueOf(low));
            props.setProperty(KEY_HIGH, String.valueOf(high));
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "SugarPal local config (AI settings & alarm thresholds).");
            }
        } catch (IOException e) {
            throw new RuntimeException("无法保存报警阈值配置: " + e.getMessage(), e);
        }
    }
}
