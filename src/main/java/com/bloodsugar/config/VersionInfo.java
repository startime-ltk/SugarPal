/*
 * Copyright (c) 2026 startime-ltk
 * SPDX-License-Identifier: MIT
 */
package com.bloodsugar.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 应用版本信息。
 *
 * <p>版本号在打包时由 Maven 过滤注入到 {@code version.properties}
 * （pom.xml 中 {@code <resources>} 过滤规则 + {@code ${project.version}}），
 * 运行时从 classpath 读取，保证 UI 显示、更新比较与打包版本始终一致，避免硬编码。
 */
public final class VersionInfo {

    private static final String VERSION = loadVersion();

    private VersionInfo() {
    }

    /** 当前应用版本号，例如 1.3.1；资源缺失时回退未知版本标记 */
    public static String getVersion() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream in = VersionInfo.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("app.version");
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        } catch (IOException ignored) {
            // 资源缺失时使用兜底值，不阻塞启动
        }
        return "unknown";
    }
}
