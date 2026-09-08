package com.bloodsugar.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * H2 嵌入式数据库连接配置（HikariCP 连接池）。
 * 数据根目录默认为用户主目录下 .bloodsugar，可在应用内「数据位置」设置中更改；
 * 数据库文件为 {@code <数据根目录>/data.mv.db}，切换位置后重启应用生效。
 */
public class DatabaseConfig {

    /** H2 数据库文件名（不含后缀），实际生成 data.mv.db */
    private static final String DB_FILE_NAME = "data";

    /** 当前数据根目录，跟随 DataLocation 中保存的用户设置 */
    private static Path databaseDir() {
        return DataLocation.getDataRoot();
    }

    private static String buildDbUrl() {
        return "jdbc:h2:file:" + databaseDir().resolve(DB_FILE_NAME).toString().replace('\\', '/')
                + ";AUTO_SERVER=TRUE;MODE=MySQL";
    }

    private static final HikariDataSource dataSource;

    static {
        try {
            databaseDir().toFile().mkdirs();
        } catch (Exception e) {
            throw new RuntimeException("无法创建数据目录: " + databaseDir(), e);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(buildDbUrl());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(300000);
        dataSource = new HikariDataSource(config);

        // 启动时顺手把表建好
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS blood_sugar_records ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "record_time TIMESTAMP, "
                    + "blood_sugar DOUBLE, "
                    + "meal_time TIMESTAMP, "
                    + "meal_period VARCHAR(20), "
                    + "meal_type VARCHAR(20), "
                    + "note VARCHAR(200), "
                    + "insulin DOUBLE DEFAULT 0, "
                    + "carbs DOUBLE DEFAULT 0, "
                    + "activity DOUBLE DEFAULT 0, "
                    + "weight DOUBLE DEFAULT 0, "
                    + "pulse DOUBLE DEFAULT 0, "
                    + "blood_pressure VARCHAR(20), "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                    + ")");
            // 兼容旧库：老版本没有健康维度字段，用 ALTER TABLE ADD COLUMN 补齐，不丢已有数据
            migrateAddColumn(conn, "BLOOD_SUGAR_RECORDS", "INSULIN", "ALTER TABLE blood_sugar_records ADD COLUMN insulin DOUBLE DEFAULT 0");
            migrateAddColumn(conn, "BLOOD_SUGAR_RECORDS", "CARBS", "ALTER TABLE blood_sugar_records ADD COLUMN carbs DOUBLE DEFAULT 0");
            migrateAddColumn(conn, "BLOOD_SUGAR_RECORDS", "ACTIVITY", "ALTER TABLE blood_sugar_records ADD COLUMN activity DOUBLE DEFAULT 0");
            migrateAddColumn(conn, "BLOOD_SUGAR_RECORDS", "WEIGHT", "ALTER TABLE blood_sugar_records ADD COLUMN weight DOUBLE DEFAULT 0");
            migrateAddColumn(conn, "BLOOD_SUGAR_RECORDS", "PULSE", "ALTER TABLE blood_sugar_records ADD COLUMN pulse DOUBLE DEFAULT 0");
            migrateAddColumn(conn, "BLOOD_SUGAR_RECORDS", "BLOOD_PRESSURE", "ALTER TABLE blood_sugar_records ADD COLUMN blood_pressure VARCHAR(20)");
            // 用餐时间表：同一业务日（凌晨4点边界）同一餐别只保留一条，重复保存覆盖旧值
            stmt.execute("CREATE TABLE IF NOT EXISTS meal_times ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "business_date DATE NOT NULL, "
                    + "meal_name VARCHAR(20) NOT NULL, "
                    + "meal_time TIMESTAMP NOT NULL, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "CONSTRAINT uk_meal_business UNIQUE (business_date, meal_name)"
                    + ")");
        } catch (SQLException e) {
            throw new RuntimeException("数据库初始化失败", e);
        }
    }

    /** 检查表是否缺列，缺则执行迁移 SQL（兼容旧库，不丢数据） */
    private static void migrateAddColumn(Connection conn, String tableName, String columnName, String alterSql) throws SQLException {
        String checkSql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, tableName);
            ps.setString(2, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(alterSql);
                    }
                }
            }
        }
    }

    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** 关闭连接池并释放数据库文件句柄；切换数据位置前调用，保证迁移文件一致 */
    public static void closeDataSource() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
