package com.bloodsugar.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 轻量本地日志：把运行期异常与关键事件写到数据目录下的 {@code logs/sugarpal.log}。
 *
 * <p>为什么需要它：本项目早期大量 catch 块把异常直接丢弃（空 catch / 只返回默认值），
 * 界面不会报错、日志也没有，出问题时无法回溯。AppLog 给这些"必须吞掉"的分支
 * 留一个可查的出口——异常本身不适合打断用户，但必须留下痕迹。
 *
 * <p>设计约束：
 * <ul>
 *   <li>零依赖，仅用 JDK；</li>
 *   <li>写失败绝不影响主流程（内部吞掉自身异常）；</li>
 *   <li>单文件上限 1 MB，超出后轮转一次，避免无限膨胀。</li>
 * </ul>
 */
public final class AppLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long MAX_SIZE = 1024 * 1024; // 1 MB

    private static final Object LOCK = new Object();
    private static Path logFile;

    private AppLog() {
    }

    /**
     * 初始化日志文件（通常在应用启动时、确定数据目录之后调用）。
     *
     * @param dataRoot 数据根目录，日志落在其 {@code logs} 子目录下
     */
    public static void init(Path dataRoot) {
        try {
            Path dir = dataRoot.resolve("logs");
            Files.createDirectories(dir);
            logFile = dir.resolve("sugarpal.log");
        } catch (Exception e) {
            // 日志初始化失败不应阻断应用，退化为只输出到 stderr
            logFile = null;
            System.err.println("[糖伴] 日志初始化失败: " + e.getMessage());
        }
    }

    public static void info(String message) {
        write("INFO ", message, null);
    }

    public static void warn(String message) {
        write("WARN ", message, null);
    }

    public static void warn(String message, Throwable t) {
        write("WARN ", message, t);
    }

    public static void error(String message, Throwable t) {
        write("ERROR", message, t);
    }

    private static void write(String level, String message, Throwable t) {
        String line = TS.format(LocalDateTime.now()) + " [" + level + "] " + message;
        if (t != null) {
            line += " | " + t.getClass().getName() + ": " + t.getMessage();
        }
        Path target = logFile;
        if (target == null) {
            System.err.println(line);
            return;
        }
        synchronized (LOCK) {
            try {
                rotateIfNeeded(target);
                Files.write(target, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                // 日志写失败同样不能影响主流程
                System.err.println(line + "（写日志失败: " + e.getMessage() + "）");
            }
        }
    }

    /** 超过 1 MB 时把旧文件改名归档一次 */
    private static void rotateIfNeeded(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        if (Files.size(target) < MAX_SIZE) {
            return;
        }
        Path backup = target.resolveSibling("sugarpal.log.1");
        Files.deleteIfExists(backup);
        Files.move(target, backup);
    }
}
