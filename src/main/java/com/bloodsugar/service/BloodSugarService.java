package com.bloodsugar.service;

import com.bloodsugar.dao.BloodSugarDAO;
import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.util.PeriodClassifier;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 业务逻辑层，UI 只跟它打交道 */
public class BloodSugarService {

    private final BloodSugarDAO dao = new BloodSugarDAO();

    // 新增记录，时段自动归类
    public void addRecord(LocalDateTime recordTime, double bloodSugar,
            LocalDateTime mealTime, String mealType, String note) throws SQLException {
        addRecord(recordTime, bloodSugar, mealTime, mealType, note, 0, 0, 0, 0, 0, null);
    }

    // 新增记录（含多维健康数据），时段自动归类
    public void addRecord(LocalDateTime recordTime, double bloodSugar,
            LocalDateTime mealTime, String mealType, String note,
            double insulin, double carbs, double activity, double weight, double pulse,
            String bloodPressure) throws SQLException {
        String mealPeriod = PeriodClassifier.classify(recordTime, mealTime);
        BloodSugarRecord record = new BloodSugarRecord(recordTime, bloodSugar, mealTime, mealPeriod, mealType, note,
                insulin, carbs, activity, weight, pulse, bloodPressure);
        dao.insert(record);
    }

    /** 批量导入专用：直接写入已解析好的记录，保留其餐别/时段，不再重新归类 */
    public void addImportedRecord(BloodSugarRecord record) throws SQLException {
        dao.insert(record);
    }

    // 查全部
    public List<BloodSugarRecord> getAllRecords() throws SQLException {
        return dao.findAll();
    }

    // 最近 limit 条记录（主界面默认只展示近 8 条）
    public List<BloodSugarRecord> getLatestRecords(int limit) throws SQLException {
        return dao.findLatest(limit);
    }

    // 按日期范围查
    public List<BloodSugarRecord> getRecordsByDateRange(LocalDateTime from, LocalDateTime to) throws SQLException {
        return dao.findByDateRange(from, to);
    }

    // 按业务日查当天记录（凌晨4点边界）
    public List<BloodSugarRecord> getRecordsByBusinessDate(LocalDate businessDate) throws SQLException {
        return dao.findByDateRange(PeriodClassifier.getBusinessDayStart(businessDate),
                PeriodClassifier.getBusinessDayEnd(businessDate));
    }

    // 有记录的业务日集合（日历黑体标记用）
    public Set<LocalDate> getRecordBusinessDates() throws SQLException {
        Set<LocalDate> set = new HashSet<>();
        for (String s : dao.findDistinctDates()) {
            set.add(LocalDate.parse(s));
        }
        return set;
    }

    // 删除一条
    public void deleteRecord(int id) throws SQLException {
        dao.delete(id);
    }

    // 更新一条
    public void updateRecord(BloodSugarRecord record) throws SQLException {
        // 重新计算时段
        String mealPeriod = PeriodClassifier.classify(record.getRecordTime(), record.getMealTime());
        record.setMealPeriod(mealPeriod);
        dao.update(record);
    }

    /**
     * 找 before 之前最近的一次用餐时间，用来自动识别餐别。
     * 只查当天（凌晨4点起）内的用餐，当天没吃过就返回 null，算空腹。
     */
    public LocalDateTime getLatestMealTimeBefore(LocalDateTime before) throws SQLException {
        if (before == null) return null;
        java.time.LocalDate businessDate = PeriodClassifier.getBusinessDate(before);
        LocalDateTime dayStart = PeriodClassifier.getBusinessDayStart(businessDate);
        return dao.findLatestMealTimeBefore(dayStart, before);
    }

    /**
     * 保存/覆盖用餐时间：同一业务日同一餐别只保留最新一条，
     * 再次保存时覆盖旧值。业务日按凌晨4点边界计算。
     */
    public void saveMealTime(String mealName, LocalDateTime mealTime) throws SQLException {
        if (mealName == null || mealTime == null) return;
        LocalDate businessDate = PeriodClassifier.getBusinessDate(mealTime);
        dao.upsertMealTime(businessDate, mealName, mealTime);
    }

    /**
     * 按指定业务日保存用餐时间（日历补填用）：
     * 业务日固定为选中日期，凌晨 0~4 点的时间也归到该业务日。
     */
    public void saveMealTimeForDate(LocalDate businessDate, String mealName, LocalDateTime mealTime) throws SQLException {
        if (businessDate == null || mealName == null || mealTime == null) return;
        dao.upsertMealTime(businessDate, mealName, mealTime);
    }

    // 查某业务日已保存的全部用餐时间（餐名 -> 时间）
    public Map<String, LocalDateTime> getMealTimesByBusinessDate(LocalDate businessDate) throws SQLException {
        return dao.findMealTimesByBusinessDate(businessDate);
    }

    /**
     * 查 before 之前最近一条已保存的用餐时间（来自 meal_times 表），
     * 用于餐别自动识别时兜底（面板未填但已保存过的情况）。
     */
    public LocalDateTime getLatestSavedMealTime(LocalDateTime before) throws SQLException {
        if (before == null) return null;
        LocalDate businessDate = PeriodClassifier.getBusinessDate(before);
        return dao.findLatestSavedMealTime(businessDate, before);
    }

    // 有哪些日期有记录
    public List<String> getRecordDates() throws SQLException {
        return dao.findDistinctDates();
    }

    /**
     * 估算 HbA1c（糖化血红蛋白）：取近 90 天平均血糖，
     * 先 mmol/L ×18 转 mg/dL，再用公式 HbA1c(%) ≈ (平均血糖 mg/dL + 46.7) / 28.7。
     * 返回 null 表示近 90 天没有数据。
     */
    public Double getHbA1cEstimate() throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.minusDays(90);
        List<BloodSugarRecord> recent = dao.findByDateRange(from, now);
        if (recent.isEmpty()) return null;
        double avgMmol = recent.stream()
                .mapToDouble(BloodSugarRecord::getBloodSugar).average().orElse(0);
        if (avgMmol <= 0) return null;
        double avgMgDl = avgMmol * 18.0;
        return (avgMgDl + 46.7) / 28.7;
    }

    /** 近 90 天平均血糖（mmol/L），无数据返回 null */
    public Double getRecentAvgBloodSugar() throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        List<BloodSugarRecord> recent = dao.findByDateRange(now.minusDays(90), now);
        if (recent.isEmpty()) return null;
        return recent.stream().mapToDouble(BloodSugarRecord::getBloodSugar).average().orElse(0);
    }

    /** 近 90 天记录条数 */
    public long getRecentRecordCount() throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        return dao.findByDateRange(now.minusDays(90), now).size();
    }
}
