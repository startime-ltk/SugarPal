package com.bloodsugar.service;

import com.bloodsugar.config.AlarmConfig;
import com.bloodsugar.model.AgpReport;
import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.util.PeriodClassifier;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * P0-2 健康摘要服务。
 *
 * 复用 AGP 报告统计口径（均值 / SD / CV / 估算 HbA1c / TIR 等），
 * 在此基础上补充按报警阈值（默认低 3.9、高 13.9）统计的高低血糖报警次数，
 * 并生成面向家人共享的可读趋势说明；取数范围默认近 30 天，数据不联网。
 */
public class HealthSummaryService {

    private final BloodSugarService service = new BloodSugarService();

    /** 健康摘要结果对象 */
    public static class Summary {
        public int rangeDays;
        public LocalDate startDate;
        public LocalDate endDate;
        public int recordCount;
        public int dayCount;
        public double mean;
        public double sd;
        public double cv;
        public double hba1c;
        public double tir;
        public int inRangeCount;
        public int lowCount;
        public int highCount;
        public double minValue;
        public double maxValue;
        public String trendNote = "";

        public boolean hasData() {
            return recordCount > 0;
        }
    }

    /** 计算近 rangeDays 天的健康摘要（默认调用方传 30） */
    public Summary build(int rangeDays) throws SQLException {
        AgpReport report = new AgpReportService().build(rangeDays);

        Summary s = new Summary();
        s.rangeDays = report.rangeDays;
        s.startDate = report.startDate;
        s.endDate = report.endDate;
        s.recordCount = report.recordCount;
        s.dayCount = report.dayCount;
        s.mean = report.mean;
        s.sd = report.sd;
        s.cv = report.cv;
        s.hba1c = report.hba1c;
        s.tir = report.tir;
        s.inRangeCount = report.inRangeCount;
        s.minValue = report.minValue;
        s.maxValue = report.maxValue;

        // 报警阈值口径独立统计：< 低阈值 与 > 高阈值（区别于餐别达标口径）
        double low = AlarmConfig.getLow();
        double high = AlarmConfig.getHigh();
        LocalDateTime from = PeriodClassifier.getBusinessDayStart(report.startDate);
        LocalDateTime to = PeriodClassifier.getBusinessDayEnd(report.endDate);
        List<BloodSugarRecord> records = service.getRecordsByDateRange(from, to);
        int lowCount = 0;
        int highCount = 0;
        for (BloodSugarRecord r : records) {
            double v = r.getBloodSugar();
            if (v > 0 && v < low) {
                lowCount++;
            } else if (v > high) {
                highCount++;
            }
        }
        s.lowCount = lowCount;
        s.highCount = highCount;

        if (s.hasData()) {
            if (highCount > 0 || lowCount > 0) {
                s.trendNote = String.format(
                        "近 %d 天共记录 %d 次血糖，平均 %.1f mmol/L，估算糖化血红蛋白 %.1f%%。期间高血糖报警 %d 次、低血糖报警 %d 次，建议关注对应时段的饮食与运动，并及时咨询医生调整方案。",
                        s.rangeDays, s.recordCount, s.mean, s.hba1c, highCount, lowCount);
            } else {
                s.trendNote = String.format(
                        "近 %d 天共记录 %d 次血糖，平均 %.1f mmol/L，估算糖化血红蛋白 %.1f%%，血糖整体平稳、无超阈值报警，继续保持规律饮食与运动。",
                        s.rangeDays, s.recordCount, s.mean, s.hba1c);
            }
        }
        return s;
    }
}
