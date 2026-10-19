package com.bloodsugar.dao;

import com.bloodsugar.config.DatabaseConfig;
import com.bloodsugar.model.BloodSugarRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据访问层，读写 blood_sugar_records 表 */
public class BloodSugarDAO {

    // 插入一条记录
    public void insert(BloodSugarRecord record) throws SQLException {
        String sql = "INSERT INTO blood_sugar_records (record_time, blood_sugar, meal_time, meal_period, meal_type, note, "
                + "insulin, carbs, activity, weight, pulse, blood_pressure) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, record.getRecordTime());
            ps.setDouble(2, record.getBloodSugar());
            ps.setObject(3, record.getMealTime());
            ps.setString(4, record.getMealPeriod());
            ps.setString(5, record.getMealType());
            ps.setString(6, record.getNote());
            ps.setDouble(7, record.getInsulin());
            ps.setDouble(8, record.getCarbs());
            ps.setDouble(9, record.getActivity());
            ps.setDouble(10, record.getWeight());
            ps.setDouble(11, record.getPulse());
            ps.setString(12, record.getBloodPressure());
            ps.executeUpdate();
        }
    }

    // 查全部，按测量时间倒序
    public List<BloodSugarRecord> findAll() throws SQLException {
        String sql = "SELECT * FROM blood_sugar_records ORDER BY record_time DESC";
        List<BloodSugarRecord> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    // 查最近 limit 条，按测量时间倒序（主界面默认展示近 8 条）
    public List<BloodSugarRecord> findLatest(int limit) throws SQLException {
        String sql = "SELECT * FROM blood_sugar_records ORDER BY record_time DESC LIMIT ?";
        List<BloodSugarRecord> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    // 按日期范围查，区间是 [from, to)
    public List<BloodSugarRecord> findByDateRange(LocalDateTime from, LocalDateTime to) throws SQLException {
        String sql = "SELECT * FROM blood_sugar_records WHERE record_time >= ? AND record_time < ? ORDER BY record_time ASC";
        List<BloodSugarRecord> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, from);
            ps.setObject(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        }
        return list;
    }

    // 按 id 删除
    public void delete(int id) throws SQLException {
        String sql = "DELETE FROM blood_sugar_records WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    // 按 id 更新
    public void update(BloodSugarRecord record) throws SQLException {
        String sql = "UPDATE blood_sugar_records SET record_time=?, blood_sugar=?, meal_time=?, meal_period=?, meal_type=?, note=?, "
                + "insulin=?, carbs=?, activity=?, weight=?, pulse=?, blood_pressure=? WHERE id=?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, record.getRecordTime());
            ps.setDouble(2, record.getBloodSugar());
            ps.setObject(3, record.getMealTime());
            ps.setString(4, record.getMealPeriod());
            ps.setString(5, record.getMealType());
            ps.setString(6, record.getNote());
            ps.setDouble(7, record.getInsulin());
            ps.setDouble(8, record.getCarbs());
            ps.setDouble(9, record.getActivity());
            ps.setDouble(10, record.getWeight());
            ps.setDouble(11, record.getPulse());
            ps.setString(12, record.getBloodPressure());
            ps.setInt(13, record.getId());
            ps.executeUpdate();
        }
    }

    // 查某业务日当天、before 之前（含）最近的一顿，没有就返回 null
    public LocalDateTime findLatestMealTimeBefore(LocalDateTime dayStart, LocalDateTime before) throws SQLException {
        String sql = "SELECT MAX(meal_time) FROM blood_sugar_records "
                + "WHERE meal_time IS NOT NULL AND meal_time >= ? AND meal_time <= ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, dayStart);
            ps.setObject(2, before);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    return ts != null ? ts.toLocalDateTime() : null;
                }
            }
        }
        return null;
    }

    // 有记录的日期列表；凌晨 4 点前记的算前一天
    public List<String> findDistinctDates() throws SQLException {
        String sql = "SELECT DISTINCT "
                + "CASE WHEN EXTRACT(HOUR FROM record_time) < 4 "
                + "THEN DATEADD(DAY, -1, CAST(record_time AS DATE)) "
                + "ELSE CAST(record_time AS DATE) END AS d "
                + "FROM blood_sugar_records ORDER BY d DESC";
        List<String> dates = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                java.sql.Date d = rs.getDate("d");
                if (d != null) dates.add(d.toLocalDate().toString());
            }
        }
        return dates;
    }

    // 保存/覆盖用餐时间：同一业务日同一餐别只保留最新一条，重复保存时覆盖旧值
    public void upsertMealTime(LocalDate businessDate, String mealName, LocalDateTime mealTime) throws SQLException {
        String sql = "MERGE INTO meal_times (business_date, meal_name, meal_time) "
                + "KEY (business_date, meal_name) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, businessDate);
            ps.setString(2, mealName);
            ps.setObject(3, mealTime);
            ps.executeUpdate();
        }
    }

    // 查某业务日已保存的全部用餐时间（meal_times 表），返回 餐名 -> 用餐时间
    public Map<String, LocalDateTime> findMealTimesByBusinessDate(LocalDate businessDate) throws SQLException {
        String sql = "SELECT meal_name, meal_time FROM meal_times WHERE business_date = ? ORDER BY meal_time ASC";
        Map<String, LocalDateTime> map = new LinkedHashMap<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, businessDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("meal_time");
                    if (ts != null) {
                        map.put(rs.getString("meal_name"), ts.toLocalDateTime());
                    }
                }
            }
        }
        return map;
    }

    // 查某业务日当天、before 之前（含）最近一条保存的用餐时间，没有就返回 null
    public LocalDateTime findLatestSavedMealTime(LocalDate businessDate, LocalDateTime before) throws SQLException {
        String sql = "SELECT meal_time FROM meal_times "
                + "WHERE business_date = ? AND meal_time <= ? "
                + "ORDER BY meal_time DESC LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, businessDate);
            ps.setObject(2, before);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    return ts != null ? ts.toLocalDateTime() : null;
                }
            }
        }
        return null;
    }

    private BloodSugarRecord mapRow(ResultSet rs) throws SQLException {
        BloodSugarRecord r = new BloodSugarRecord();
        r.setId(rs.getInt("id"));
        Timestamp rt = rs.getTimestamp("record_time");
        if (rt != null) r.setRecordTime(rt.toLocalDateTime());
        r.setBloodSugar(rs.getDouble("blood_sugar"));
        Timestamp mt = rs.getTimestamp("meal_time");
        if (mt != null) r.setMealTime(mt.toLocalDateTime());
        r.setMealPeriod(rs.getString("meal_period"));
        r.setMealType(rs.getString("meal_type"));
        r.setNote(rs.getString("note"));
        r.setInsulin(rs.getDouble("insulin"));
        r.setCarbs(rs.getDouble("carbs"));
        r.setActivity(rs.getDouble("activity"));
        r.setWeight(rs.getDouble("weight"));
        r.setPulse(rs.getDouble("pulse"));
        r.setBloodPressure(rs.getString("blood_pressure"));
        return r;
    }
}
