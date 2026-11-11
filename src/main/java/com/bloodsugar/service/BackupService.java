/*
 * Copyright (c) 2026 startime-ltk
 * SPDX-License-Identifier: MIT
 */
package com.bloodsugar.service;

import com.bloodsugar.config.DataLocation;
import com.bloodsugar.config.DatabaseConfig;
import com.bloodsugar.config.VersionInfo;
import com.bloodsugar.util.AppLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 数据备份与恢复。
 *
 * <p><b>为什么不能直接复制 data.mv.db：</b>数据库在运行中被 H2 持有，
 * 直接拷贝可能拿到写了一半的文件，恢复时反而把数据搞坏。
 * 这里用 H2 自带的 {@code BACKUP TO} 命令生成一致性快照，再和配置文件、
 * 元信息一起打包成一个 zip。
 *
 * <p>恢复的安全策略：<b>永远先备份当前数据</b>——把现有文件整体挪到
 * {@code backup_before_restore_yyyyMMdd_HHmmss} 目录，再从备份解出数据库。
 * 即使恢复包有问题，也能把这个目录里的文件放回去。
 */
public class BackupService {

    /** 备份文件的元信息 */
    public static class BackupMeta {
        public String app = "sugarpal";
        public String version = "";
        public String createdAt = "";
        public int recordCount = -1;
        /** 是否为糖伴标准备份（带 backup-meta.txt）；手工打包的压缩包为 false */
        public boolean official;
        public long zipSize;
        /** zip 内找到的数据库文件名 */
        public String dbEntryName = "";
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String ENTRY_META = "backup-meta.txt";
    private static final String ENTRY_CONFIG = "config.properties";
    private static final String ENTRY_SNAPSHOT_META = "snapshot-meta.txt";

    /** 自动备份目录名（位于数据根目录下） */
    public static final String AUTO_DIR = "auto-backups";
    /** 自动备份保留份数 */
    public static final int AUTO_KEEP = 7;
    /** 恢复前快照目录名前缀 */
    public static final String SNAPSHOT_PREFIX = "backup_before_restore_";

    /** 备份结果：目标文件 + 元信息 */
    public static class BackupResult {
        public final Path file;
        public final BackupMeta meta;
        public final long size;

        public BackupResult(Path file, BackupMeta meta, long size) {
            this.file = file;
            this.meta = meta;
            this.size = size;
        }
    }

    /** 「可恢复的备份」列表项 */
    public static class BackupEntry {
        /** 自动备份 / 恢复前快照 / 手动备份 */
        public String kind = "";
        public Path path;
        public String timeText = "";
        public int recordCount = -1;
        public long size;

        /** 列表里显示的说明，例如「自动备份 · 11 条 · 8.0 KB」 */
        public String describe() {
            return kind + " · " + (recordCount < 0 ? "条数未知" : recordCount + " 条")
                    + " · " + formatSize(size);
        }
    }

    // ==================== 备份 ====================

    /** 备份到指定 zip（含配置文件） */
    public BackupResult backupTo(Path targetZip) throws BackupException {
        return backupTo(targetZip, true);
    }

    /**
     * 备份到指定 zip。
     *
     * @param includeConfig 是否把 config.properties 一起打包（自动备份不需要，避免多份密钥副本）
     */
    public BackupResult backupTo(Path targetZip, boolean includeConfig) throws BackupException {
        Path dataRoot = DataLocation.getStartupDataRoot();
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("sugarpal-backup-");
            Path snapshot = tempDir.resolve("snapshot.zip");

            int recordCount = countRecordsNow();
            // H2 一致性快照
            try (Connection conn = DatabaseConfig.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("BACKUP TO '" + snapshot.toAbsolutePath().toString().replace("\\", "/")
                        .replace("'", "''") + "'");
            }
            if (!Files.exists(snapshot)) {
                throw new BackupException("数据库快照生成失败（未产生快照文件）");
            }

            if (targetZip.getParent() != null) {
                Files.createDirectories(targetZip.getParent());
            }
            Files.deleteIfExists(targetZip);

            String createdAt = HUMAN.format(LocalDateTime.now());
            try (OutputStream os = Files.newOutputStream(targetZip);
                 ZipOutputStream out = new ZipOutputStream(os)) {

                // 1) 数据库快照（H2 的 BACKUP 本身是个 zip，这里把它里面的文件搬进我们的 zip）
                try (ZipInputStream in = new ZipInputStream(Files.newInputStream(snapshot))) {
                    ZipEntry e;
                    while ((e = in.getNextEntry()) != null) {
                        if (e.isDirectory()) {
                            continue;
                        }
                        out.putNextEntry(new ZipEntry(e.getName()));
                        in.transferTo(out);
                        out.closeEntry();
                    }
                }

                // 2) 配置文件（留档用；恢复时默认不覆盖，避免冲掉用户后来改的 AI 设置）
                Path cfg = dataRoot.resolve(ENTRY_CONFIG);
                if (includeConfig && Files.exists(cfg)) {
                    out.putNextEntry(new ZipEntry(ENTRY_CONFIG));
                    Files.copy(cfg, out);
                    out.closeEntry();
                }

                // 3) 元信息
                String meta = "app=sugarpal\n"
                        + "version=" + VersionInfo.getVersion() + "\n"
                        + "createdAt=" + createdAt + "\n"
                        + "recordCount=" + recordCount + "\n"
                        + "dataRoot=" + dataRoot + "\n";
                out.putNextEntry(new ZipEntry(ENTRY_META));
                out.write(meta.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }

            BackupMeta m = new BackupMeta();
            m.version = VersionInfo.getVersion();
            m.createdAt = createdAt;
            m.recordCount = recordCount;
            m.official = true;
            m.zipSize = Files.size(targetZip);
            m.dbEntryName = "data.mv.db";
            AppLog.info("数据备份成功: " + targetZip + " (" + m.zipSize + " 字节, " + recordCount + " 条记录)");
            return new BackupResult(targetZip, m, m.zipSize);

        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            AppLog.error("数据备份失败", e);
            throw new BackupException("备份失败：" + e.getMessage(), e);
        } finally {
            deleteQuietly(tempDir);
        }
    }

    /**
     * 每天首次启动时自动备份一份，只保留最近 {@value #AUTO_KEEP} 天。
     *
     * <p>这一条是给"从来不会主动点备份"的用户兜底的——它不需要用户记得任何事。
     *
     * @return 今天新生成的备份文件；今天已有则返回 null
     */
    public Path autoBackupIfNeeded() {
        try {
            Path dir = DataLocation.getStartupDataRoot().resolve(AUTO_DIR);
            Files.createDirectories(dir);
            Path target = dir.resolve("auto_" + LocalDate.now().format(DAY) + ".zip");
            if (Files.exists(target)) {
                return null;
            }
            BackupResult r = backupTo(target, false);
            pruneAuto(dir);
            AppLog.info("每日自动备份完成: " + target.getFileName() + " (" + r.size + " 字节)");
            return target;
        } catch (Exception e) {
            // 自动备份失败绝不能影响应用启动
            AppLog.warn("每日自动备份失败（不影响使用）", e);
            return null;
        }
    }

    /** 只保留最近 AUTO_KEEP 份自动备份 */
    private void pruneAuto(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = s.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("auto_") && n.endsWith(".zip");
                    })
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .collect(Collectors.toList());
            for (int i = AUTO_KEEP; i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
                AppLog.info("清理旧自动备份: " + files.get(i).getFileName());
            }
        } catch (Exception e) {
            AppLog.warn("清理旧自动备份失败", e);
        }
    }

    /**
     * 列出应用自己管理的可恢复备份：自动备份 + 恢复前快照，按名称（即时间）倒序。
     */
    public List<BackupEntry> listRestorable() {
        List<BackupEntry> list = new ArrayList<>();
        Path root = DataLocation.getStartupDataRoot();

        Path autoDir = root.resolve(AUTO_DIR);
        if (Files.isDirectory(autoDir)) {
            try (Stream<Path> s = Files.list(autoDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".zip")).forEach(p -> {
                    BackupEntry e = new BackupEntry();
                    e.kind = "自动备份";
                    e.path = p;
                    try {
                        e.recordCount = inspect(p).recordCount;
                    } catch (Exception ignored) {
                        e.recordCount = -1;
                    }
                    fillEntry(e);
                    list.add(e);
                });
            } catch (Exception e) {
                AppLog.warn("列出自动备份失败", e);
            }
        }

        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.list(root)) {
                s.filter(p -> Files.isDirectory(p)
                                && p.getFileName().toString().startsWith(SNAPSHOT_PREFIX))
                        .forEach(p -> {
                            BackupEntry e = new BackupEntry();
                            e.kind = "恢复前快照";
                            e.path = p;
                            Path db = p.resolve("data.mv.db");
                            if (Files.exists(db)) {
                                e.size = db.toFile().length();
                            }
                            Path meta = p.resolve(ENTRY_SNAPSHOT_META);
                            if (Files.exists(meta)) {
                                try (InputStream in = Files.newInputStream(meta)) {
                                    Properties pp = new Properties();
                                    pp.load(in);
                                    e.recordCount = Integer.parseInt(
                                            pp.getProperty("recordCount", "-1").trim());
                                } catch (Exception ignored) {
                                    e.recordCount = -1;
                                }
                            }
                            fillEntry(e);
                            list.add(e);
                        });
            } catch (Exception e) {
                AppLog.warn("列出恢复前快照失败", e);
            }
        }

        list.sort(Comparator.comparing((BackupEntry e) -> e.path.getFileName().toString()).reversed());
        return list;
    }

    private void fillEntry(BackupEntry e) {
        try {
            long t = Files.getLastModifiedTime(e.path).toMillis();
            e.timeText = HUMAN.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault()));
            if (e.size == 0 && Files.isRegularFile(e.path)) {
                e.size = e.path.toFile().length();
            }
        } catch (Exception ignored) {
            e.timeText = "时间未知";
        }
    }

    // ==================== 检查与列表 ====================

    /** 检查一个 zip 是否是可用备份，并读出元信息 */
    public BackupMeta inspect(Path zip) throws BackupException {
        if (zip == null || !Files.exists(zip)) {
            throw new BackupException("备份文件不存在");
        }
        BackupMeta meta = new BackupMeta();
        meta.zipSize = zip.toFile().length();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.toLowerCase().endsWith(".mv.db")) {
                    meta.dbEntryName = name;
                }
            }
            if (meta.dbEntryName.isEmpty()) {
                throw new BackupException("这个文件里没有找到糖伴的数据库（data.mv.db），"
                        + "请选择由「备份数据」生成的 .zip 文件");
            }
            ZipEntry metaEntry = zf.getEntry(ENTRY_META);
            if (metaEntry != null) {
                Properties p = new Properties();
                try (InputStream in = zf.getInputStream(metaEntry)) {
                    p.load(in);
                }
                meta.official = "sugarpal".equalsIgnoreCase(p.getProperty("app", ""));
                meta.version = p.getProperty("version", "");
                meta.createdAt = p.getProperty("createdAt", "");
                String rc = p.getProperty("recordCount", "-1");
                try {
                    meta.recordCount = Integer.parseInt(rc.trim());
                } catch (Exception ignored) {
                    meta.recordCount = -1;
                }
            }
            return meta;
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            throw new BackupException("无法读取这个压缩包：" + e.getMessage(), e);
        }
    }

    /**
     * 从备份恢复。
     *
     * <p>顺序刻意设计成"先把备份解出来、再动现有数据"：
     * 解压失败时当前数据库一根毫毛都没动；交换阶段失败会自动把旧文件搬回来。
     *
     * @return 恢复前自动保存的旧数据目录（出问题可以从这里还原）
     */
    public Path restoreFrom(Path source) throws BackupException {
        if (source == null || !Files.exists(source)) {
            throw new BackupException("备份不存在：" + source);
        }
        // 恢复前快照是目录（里面直接放 data.mv.db），手动备份是 zip
        if (Files.isDirectory(source)) {
            Path db = source.resolve("data.mv.db");
            if (!Files.exists(db) || db.toFile().length() <= 0) {
                throw new BackupException("这个快照目录里没有可用的 data.mv.db，恢复中止（当前数据未受影响）");
            }
            return swapIn(db, source, false);
        }

        BackupMeta meta = inspect(source);
        Path temp;
        try {
            temp = Files.createTempFile("sugarpal-restore-", ".mv.db");
            boolean ok = false;
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(source))) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    if (!e.isDirectory() && e.getName().equals(meta.dbEntryName)) {
                        Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                        ok = true;
                        break;
                    }
                }
            }
            if (!ok || Files.size(temp) <= 0) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
                throw new BackupException("备份包里没找到可用的数据库，恢复中止（当前数据未受影响）");
            }
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            throw new BackupException("读取备份包失败：" + e.getMessage() + "（当前数据未受影响）", e);
        }
        return swapIn(temp, source, true);
    }

    /**
     * 把准备好的数据库文件换入数据目录。
     *
     * @param preparedDb 待换入的数据库文件
     * @param source     来源（仅用于日志与快照说明）
     * @param tempIsOurs preparedDb 是否本方法创建的临时文件（结束时需要删掉）
     * @return 恢复前数据的存放目录
     */
    private Path swapIn(Path preparedDb, Path source, boolean tempIsOurs) throws BackupException {
        Path dataRoot = DataLocation.getStartupDataRoot();
        String stamp = STAMP.format(LocalDateTime.now());
        Path safeDir = dataRoot.resolve(SNAPSHOT_PREFIX + stamp);
        Path tempDb = dataRoot.resolve("restore-pending.mv.db");
        List<String> moved = new ArrayList<>();
        try {
            Files.createDirectories(dataRoot);
            Files.createDirectories(safeDir);
            // 先落到自己的临时文件，避免 preparedDb 来自快照目录时被移动破坏
            Files.copy(preparedDb, tempDb, StandardCopyOption.REPLACE_EXISTING);

            // 关池前统计当前条数，写进快照说明，列表里才能显示「N 条」
            int currentCount = countRecordsNow();
            DatabaseConfig.closeDataSource();

            for (String name : new String[]{"data.mv.db", "data.trace.db", ENTRY_CONFIG}) {
                Path src = dataRoot.resolve(name);
                if (Files.exists(src)) {
                    Files.move(src, safeDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    moved.add(name);
                }
            }
            writeSnapshotMeta(safeDir, currentCount, source);
            Files.move(tempDb, dataRoot.resolve("data.mv.db"), StandardCopyOption.REPLACE_EXISTING);

            AppLog.info("数据恢复完成: 来源=" + source + " 旧数据已存至 " + safeDir + " 挪走文件=" + moved);
            return safeDir;

        } catch (Exception e) {
            rollbackFrom(safeDir, dataRoot, moved);
            AppLog.error("数据恢复失败（已尝试还原旧数据）", e);
            if (e instanceof BackupException) {
                throw (BackupException) e;
            }
            throw new BackupException("恢复失败：" + e.getMessage()
                    + "\n\n恢复前的数据已保存在：" + safeDir, e);
        } finally {
            try {
                Files.deleteIfExists(tempDb);
            } catch (IOException ignored) {
            }
            if (tempIsOurs) {
                try {
                    Files.deleteIfExists(preparedDb);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 把恢复前数据的条数写进快照目录，供「备份清单」显示 */
    private void writeSnapshotMeta(Path dir, int count, Path source) {
        try {
            String meta = "recordCount=" + count + "\n"
                    + "savedAt=" + HUMAN.format(LocalDateTime.now()) + "\n"
                    + "replacedBy=" + source + "\n";
            Files.write(dir.resolve(ENTRY_SNAPSHOT_META), meta.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            AppLog.warn("写入快照说明失败", e);
        }
    }

    /** 把已挪走的旧文件搬回数据目录 */
    private void rollbackFrom(Path safeDir, Path dataRoot, List<String> moved) {
        for (String name : moved) {
            try {
                Path src = safeDir.resolve(name);
                if (Files.exists(src)) {
                    Files.move(src, dataRoot.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                AppLog.error("还原旧数据文件失败: " + name, e);
            }
        }
    }

    /** 当前库里的记录条数；查不到返回 -1 */
    private int countRecordsNow() {
        try (Connection conn = DatabaseConfig.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM blood_sugar_records")) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (Exception e) {
            AppLog.warn("统计记录条数失败", e);
            return -1;
        }
    }

    /** 人类可读的文件大小，例如 8.0 KB */
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " 字节";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    /** 默认备份文件名，例如 糖伴备份_20261106_201530.zip */
    public static String defaultFileName() {
        return "糖伴备份_" + STAMP.format(LocalDateTime.now()) + ".zip";
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            if (!Files.exists(dir)) {
                return;
            }
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    /** 备份/恢复异常，message 可直接展示给用户 */
    public static class BackupException extends Exception {
        public BackupException(String message) {
            super(message);
        }

        public BackupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
