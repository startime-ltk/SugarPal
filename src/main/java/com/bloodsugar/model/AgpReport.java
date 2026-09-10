package com.bloodsugar.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * AGP（Ambulatory Glucose Profile，标准化葡萄糖图谱）报告数据。
 * 包含：核心指标（平均/SD/CV/TIR/TAR/TBR/估算 HbA1c）
 *      + 24 小时分位曲线（中位数 + 25%/75% 分位）
 *      + 分时段统计（0-6 / 6-12 / 12-18 / 18-24）
 *      + 按餐别统计
 */
public class AgpReport {

    // ===== 统计范围 =====
    public int rangeDays;                 // 分析窗口天数（近 N 天）
    public LocalDate startDate;           // 起始业务日
    public LocalDate endDate;             // 结束业务日（今天所在业务日）
    public int recordCount;               // 记录条数
    public int dayCount;                  // 覆盖的业务日天数

    // ===== 核心指标 =====
    public double mean;                   // 平均血糖 mmol/L
    public double sd;                     // 标准差（样本，n-1）
    public double cv;                     // 变异系数 %
    public double hba1c;                  // 估算 HbA1c %
    public double tir;                    // 目标范围内时间占比 %（按记录条数计）
    public double tar;                    // 高于目标范围占比 %
    public double tbr;                    // 低于目标范围占比 %
    public int inRangeCount;              // 达标条数
    public int aboveCount;                // 偏高条数
    public int belowCount;                // 偏低条数

    // 目标范围下界（mmol/L），上限随餐别不同（空腹 6.1、餐后1h 8.9、餐后2h/3h 7.8）
    public double targetLow = 3.9;
    public double targetHighMax = 8.9;    // 图中参考上限（取各餐别上限的最大值）
    public double minValue;               // 最低血糖
    public double maxValue;               // 最高血糖

    // ===== 曲线与分组统计 =====
    public final List<CurvePoint> curve = new ArrayList<>();
    public final List<GroupStat> timeSegments = new ArrayList<>();
    public final List<GroupStat> mealGroups = new ArrayList<>();

    /** 24 小时曲线上一个整点箱的分位数（箱中心为整点，取样窗口 ±30 分钟） */
    public static class CurvePoint {
        public final int hour;      // 0~23
        public final int count;     // 箱内记录数
        public final double p25;
        public final double p50;
        public final double p75;

        public CurvePoint(int hour, int count, double p25, double p50, double p75) {
            this.hour = hour;
            this.count = count;
            this.p25 = p25;
            this.p50 = p50;
            this.p75 = p75;
        }
    }

    /** 分组统计（分时段 / 按餐别通用） */
    public static class GroupStat {
        public final String name;
        public final int count;
        public final double avg;
        public final double max;
        public final double min;
        public final double tirPercent;   // 达标率 %

        public GroupStat(String name, int count, double avg, double max, double min, double tirPercent) {
            this.name = name;
            this.count = count;
            this.avg = avg;
            this.max = max;
            this.min = min;
            this.tirPercent = tirPercent;
        }
    }
}
