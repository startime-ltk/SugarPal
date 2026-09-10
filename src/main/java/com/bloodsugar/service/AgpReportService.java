package com.bloodsugar.service;

import com.bloodsugar.model.AgpReport;
import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.util.PeriodClassifier;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * AGP（标准化葡萄糖图谱）报告计算服务。
 *
 * 取数口径（与项目现有指标保持一致）：
 * - 时间范围：按业务日边界（凌晨 4 点）取近 N 个业务日；
 * - 达标判定：逐条按所属时段正常区间判定（空腹 3.9~6.1、餐后1h 3.9~8.9、餐后2h/3h 3.9~7.8）；
 *   TIR = 达标条数占比，TAR = 高于各自上限占比，TBR = 低于 3.9 占比；
 * - 标准差：样本标准差（n-1）；CV = SD / 平均血糖 × 100%；
 * - 估算 HbA1c：范围内平均血糖 mmol/L ×18 → mg/dL，HbA1c% ≈ (mg/dL + 46.7) / 28.7；
 * - 24 小时曲线：以整点为箱中心、±30 分钟为取样窗口取分位数（环形窗口，跨越 0 点正确归箱）。
 */
public class AgpReportService {

    private final BloodSugarService service = new BloodSugarService();

    /** 计算近 rangeDays 天的 AGP 报告 */
    public AgpReport build(int rangeDays) throws SQLException {
        if (rangeDays <= 0) rangeDays = 30;
        LocalDate today = PeriodClassifier.getBusinessDate(LocalDateTime.now());
        LocalDate startDate = today.minusDays(rangeDays - 1L);
        LocalDateTime from = PeriodClassifier.getBusinessDayStart(startDate);
        LocalDateTime to = PeriodClassifier.getBusinessDayEnd(today);

        List<BloodSugarRecord> records = new ArrayList<>();
        for (BloodSugarRecord r : service.getRecordsByDateRange(from, to)) {
            if (r.getRecordTime() != null && r.getBloodSugar() > 0) records.add(r);
        }

        AgpReport report = new AgpReport();
        report.rangeDays = rangeDays;
        report.startDate = startDate;
        report.endDate = today;
        report.recordCount = records.size();

        Set<LocalDate> days = new HashSet<>();
        for (BloodSugarRecord r : records) {
            LocalDate d = PeriodClassifier.getBusinessDate(r.getRecordTime());
            if (d != null) days.add(d);
        }
        report.dayCount = days.size();

        if (records.isEmpty()) return report;

        // ===== 核心指标 =====
        int n = records.size();
        double sum = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (BloodSugarRecord r : records) {
            double v = r.getBloodSugar();
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / n;
        double sq = 0;
        for (BloodSugarRecord r : records) sq += Math.pow(r.getBloodSugar() - mean, 2);
        double sd = n > 1 ? Math.sqrt(sq / (n - 1)) : 0;

        int inRange = 0, above = 0, below = 0;
        for (BloodSugarRecord r : records) {
            double[] range = PeriodClassifier.getNormalRange(r.getMealPeriod());
            double v = r.getBloodSugar();
            if (v > range[1]) above++;
            else if (v < range[0]) below++;
            else inRange++;
        }

        report.mean = mean;
        report.sd = sd;
        report.cv = mean > 0 ? sd / mean * 100.0 : 0;
        report.hba1c = (mean * 18.0 + 46.7) / 28.7;
        report.inRangeCount = inRange;
        report.aboveCount = above;
        report.belowCount = below;
        report.tir = 100.0 * inRange / n;
        report.tar = 100.0 * above / n;
        report.tbr = 100.0 * below / n;
        report.minValue = min;
        report.maxValue = max;
        report.targetLow = 3.9;
        report.targetHighMax = 8.9;

        // ===== 24 小时分位曲线 =====
        // 环形取样窗口：记录时刻分钟数与整点中心相差 ≤30 分钟（跨 0 点按 1440 取模）
        Map<Integer, List<Double>> bins = new TreeMap<>();
        for (BloodSugarRecord r : records) {
            int minuteOfDay = r.getRecordTime().getHour() * 60 + r.getRecordTime().getMinute();
            for (int h = 0; h < 24; h++) {
                int diff = Math.abs(minuteOfDay - h * 60);
                diff = Math.min(diff, 1440 - diff);
                if (diff <= 30) {
                    bins.computeIfAbsent(h, k -> new ArrayList<>()).add(r.getBloodSugar());
                    break;
                }
            }
        }
        // 先算出有数据整点的分位数，再线性插值补齐无数据整点，使 AGP 曲线连续（标准图谱形态）
        int[] counts = new int[24];
        double[] p25 = new double[24], p50 = new double[24], p75 = new double[24];
        Arrays.fill(p25, Double.NaN);
        Arrays.fill(p50, Double.NaN);
        Arrays.fill(p75, Double.NaN);
        for (Map.Entry<Integer, List<Double>> e : bins.entrySet()) {
            List<Double> vals = new ArrayList<>(e.getValue());
            Collections.sort(vals);
            int h = e.getKey();
            counts[h] = vals.size();
            p25[h] = percentile(vals, 0.25);
            p50[h] = percentile(vals, 0.50);
            p75[h] = percentile(vals, 0.75);
        }
        boolean anyData = false;
        for (int h = 0; h < 24 && !anyData; h++) {
            if (!Double.isNaN(p50[h])) anyData = true;
        }
        if (anyData) {
            interpolate(p25);
            interpolate(p50);
            interpolate(p75);
            for (int h = 0; h < 24; h++) {
                report.curve.add(new AgpReport.CurvePoint(h, counts[h], p25[h], p50[h], p75[h]));
            }
        }

        // ===== 分时段统计（0-6 / 6-12 / 12-18 / 18-24）=====
        String[] segNames = {"00:00-06:00", "06:00-12:00", "12:00-18:00", "18:00-24:00"};
        List<List<BloodSugarRecord>> segs = new ArrayList<>();
        for (int i = 0; i < 4; i++) segs.add(new ArrayList<>());
        for (BloodSugarRecord r : records) {
            int h = r.getRecordTime().getHour();
            segs.get(Math.min(3, h / 6)).add(r);
        }
        for (int i = 0; i < 4; i++) {
            report.timeSegments.add(groupStat(segNames[i], segs.get(i)));
        }

        // ===== 按餐别统计 =====
        Map<String, List<BloodSugarRecord>> byMeal = new LinkedHashMap<>();
        for (String p : new String[]{"空腹", "餐后1h", "餐后2h", "餐后3h"}) {
            byMeal.put(p, new ArrayList<>());
        }
        for (BloodSugarRecord r : records) {
            String p = r.getMealPeriod() == null || r.getMealPeriod().isBlank() ? "空腹" : r.getMealPeriod();
            byMeal.computeIfAbsent(p, k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<BloodSugarRecord>> e : byMeal.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            report.mealGroups.add(groupStat(e.getKey(), e.getValue()));
        }

        return report;
    }

    private AgpReport.GroupStat groupStat(String name, List<BloodSugarRecord> list) {
        int n = list.size();
        if (n == 0) return new AgpReport.GroupStat(name, 0, 0, 0, 0, 0);
        double sum = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        int inRange = 0;
        for (BloodSugarRecord r : list) {
            double v = r.getBloodSugar();
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
            double[] range = PeriodClassifier.getNormalRange(r.getMealPeriod());
            if (v >= range[0] && v <= range[1]) inRange++;
        }
        return new AgpReport.GroupStat(name, n, sum / n, max, min, 100.0 * inRange / n);
    }

    /** 线性插值补齐 24 个整点中的空缺（首尾用最近有效值平推），入参为长度 24 的数组 */
    private static void interpolate(double[] arr) {
        int n = arr.length, first = -1, last = -1;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(arr[i])) {
                first = i;
                break;
            }
        }
        if (first < 0) return;
        for (int i = n - 1; i >= 0; i--) {
            if (!Double.isNaN(arr[i])) {
                last = i;
                break;
            }
        }
        for (int i = 0; i < first; i++) arr[i] = arr[first];
        for (int i = last + 1; i < n; i++) arr[i] = arr[last];
        int prev = first;
        for (int i = first + 1; i <= last; i++) {
            if (Double.isNaN(arr[i])) continue;
            if (i - prev > 1) {
                double step = (arr[i] - arr[prev]) / (i - prev);
                for (int k = prev + 1; k < i; k++) arr[k] = arr[prev] + step * (k - prev);
            }
            prev = i;
        }
    }

    /** 线性插值分位数，入参需已升序排序 */
    static double percentile(List<Double> sorted, double p) {
        int n = sorted.size();
        if (n == 0) return 0;
        if (n == 1) return sorted.get(0);
        double idx = p * (n - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) return sorted.get(lo);
        double frac = idx - lo;
        return sorted.get(lo) * (1 - frac) + sorted.get(hi) * frac;
    }
}
