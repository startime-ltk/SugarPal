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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
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

    private static final String ENTRY_META = "backup-meta.txt";
    private static final String ENTRY_CONFIG = "config.properties";

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

    /**
     * 把当前数据备份到指定 zip 文件（覆盖同名的已存在文件）。
     */
    public BackupResult backupTo(Path targetZip) throws BackupException {
        Path dataRoot = DataLocation.getStartupDataRoot();
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("sugarpal-backup-");
            Path snapshot = tempDir.resolve("snapshot.zip");

            int recordCount = countRecords();
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

                // 2) 配置文件（含 AI 模型设置；恢复时默认不覆盖，只作为留档）
                Path cfg = dataRoot.resolve(ENTRY_CONFIG);
                if (Files.exists(cfg)) {
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
     * 检查一个 zip 是否是可用备份，并读出元信息。
     */
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
    public Path restoreFrom(Path zip) throws BackupException {
        BackupMeta meta = inspect(zip);
        Path dataRoot = DataLocation.getStartupDataRoot();
        String stamp = STAMP.format(LocalDateTime.now());
        Path safeDir = dataRoot.resolve("backup_before_restore_" + stamp);
        Path tempDb = dataRoot.resolve("restore-pending.mv.db");
        List<String> moved = new ArrayList<>();
        try {
            Files.createDirectories(dataRoot);
            Files.createDirectories(safeDir);

            // 1) 先把备份里的数据库解到临时文件——这一步失败，现有数据完全没动
            boolean extracted = false;
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    if (!e.isDirectory() && e.getName().equals(meta.dbEntryName)) {
                        Files.copy(in, tempDb, StandardCopyOption.REPLACE_EXISTING);
                        extracted = true;
                        break;
                    }
                }
            }
            if (!extracted) {
                throw new BackupException("备份包里没找到数据库文件，恢复中止（当前数据未受影响）");
            }
            if (Files.size(tempDb) <= 0) {
                throw new BackupException("备份包里的数据库是空的，恢复中止（当前数据未受影响）");
            }

            // 2) 关连接池，释放 data.mv.db 的文件句柄
            DatabaseConfig.closeDataSource();

            // 3) 现有数据整体挪走（不是删除，随时可还原）
            for (String name : new String[]{"data.mv.db", "data.trace.db", ENTRY_CONFIG}) {
                Path src = dataRoot.resolve(name);
                if (Files.exists(src)) {
                    Files.move(src, safeDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    moved.add(name);
                }
            }

            // 4) 临时文件转正；失败则把旧文件搬回来
            try {
                Files.move(tempDb, dataRoot.resolve("data.mv.db"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception swapError) {
                rollbackFrom(safeDir, dataRoot, moved);
                throw new BackupException("写入数据库失败，已自动还原为恢复前的数据：" + swapError.getMessage(), swapError);
            }

            AppLog.info("数据恢复完成: 来源=" + zip + " 旧数据已存至 " + safeDir + " 挪走文件=" + moved);
            return safeDir;

        } catch (BackupException e) {
            AppLog.error("数据恢复失败", e);
            throw e;
        } catch (Exception e) {
            rollbackFrom(safeDir, dataRoot, moved);
            AppLog.error("数据恢复失败（已尝试还原旧数据）", e);
            throw new BackupException("恢复失败：" + e.getMessage()
                    + "\n\n恢复前的数据已保存在：" + safeDir, e);
        } finally {
            try {
                Files.deleteIfExists(tempDb);
            } catch (IOException ignored) {
            }
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
    private int countRecords() {
        try (Connection conn = DatabaseConfig.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM blood_sugar_records")) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (Exception e) {
            AppLog.warn("备份时统计记录条数失败", e);
            return -1;
        }
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
