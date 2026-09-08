package com.bloodsugar.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 数据位置管理：决定应用数据（H2 数据库文件、AI 配置等）存放的根目录。
 *
 * <p>默认数据根目录为 {@code 用户主目录/.bloodsugar}（免安装、免配置）。
 * 用户可在界面中通过「数据位置」入口更改存放目录，选择记录保存在固定位置
 * {@code 用户主目录/.bloodsugar/location.properties}——该文件刻意不随数据目录
 * 迁移，保证程序总能找回用户的自定义位置；数据库文件与 AI 配置随后跟随
 * 数据根目录读写，切换后重启应用生效。</p>
 */
public final class DataLocation {

    private static final String KEY_DATA_DIR = "data.dir";

    /** 固定保存“选择”的文件位置（不跟随数据目录迁移） */
    private static final Path LOCATION_FILE = Path.of(
            System.getProperty("user.home"), ".bloodsugar", "location.properties");

    /** 默认数据根目录：用户主目录/.bloodsugar */
    private static final Path DEFAULT_ROOT = Path.of(
            System.getProperty("user.home"), ".bloodsugar");

    /**
     * 本进程启动时实际绑定的数据根目录。
     * 在类首次加载时读取 location.properties 并缓存——数据库连接、AI 配置等
     * 所有运行期数据读写都应基于该目录；运行期内修改 location.properties
     * （例如通过「数据位置」对话框保存了新位置）只影响下一次启动。
     */
    private static final Path STARTUP_ROOT = resolveStartupRoot();

    /** 解析启动绑定数据根目录：读取 location.properties 中的记录，缺失/损坏回退默认目录 */
    private static Path resolveStartupRoot() {
        Path root = readRootFromLocationFile();
        return root != null ? root : DEFAULT_ROOT;
    }

    private DataLocation() {
    }

    /**
     * 当前生效的数据根目录。
     * 每次调用都重新读取 location.properties，用于展示“保存的选择”；
     * 运行期真实读写请使用 {@link #getStartupDataRoot()}。
     */
    public static Path getDataRoot() {
        Path root = readRootFromLocationFile();
        return root != null ? root : DEFAULT_ROOT;
    }

    /**
     * 本进程启动时实际绑定的数据根目录（缓存值）。
     * 数据库、AI 配置及数据迁移的源目录都必须以它为准，避免运行期内
     * 修改 location.properties 后读取到尚未生效的“未来位置”。
     */
    public static Path getStartupDataRoot() {
        return STARTUP_ROOT;
    }

    /** 读取 location.properties 中记录的数据目录；无记录或读失败返回 null */
    private static Path readRootFromLocationFile() {
        try {
            if (Files.exists(LOCATION_FILE)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(LOCATION_FILE)) {
                    props.load(in);
                }
                String dir = props.getProperty(KEY_DATA_DIR);
                if (dir != null && !dir.isBlank()) {
                    return Path.of(dir).toAbsolutePath().normalize();
                }
            }
        } catch (IOException ignored) {
            // 读不到选择记录或损坏时回退默认目录，不阻塞启动
        }
        return null;
    }

    /** 保存新的数据根目录选择 */
    public static void saveDataRoot(Path root) throws IOException {
        Path abs = root.toAbsolutePath().normalize();
        Properties props = new Properties();
        if (Files.exists(LOCATION_FILE)) {
            try (InputStream in = Files.newInputStream(LOCATION_FILE)) {
                props.load(in);
            } catch (IOException ignored) {
                // 原文件读失败就整体重写
            }
        }
        props.setProperty(KEY_DATA_DIR, abs.toString());
        Files.createDirectories(LOCATION_FILE.getParent());
        try (OutputStream out = Files.newOutputStream(LOCATION_FILE)) {
            props.store(out, "SugarPal data location. This file is kept at the fixed default path on purpose.");
        }
    }

    public static Path getLocationFile() {
        return LOCATION_FILE;
    }

    public static Path getDefaultRoot() {
        return DEFAULT_ROOT;
    }
}
