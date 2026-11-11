/*
 * Copyright (c) 2026 startime-ltk
 * SPDX-License-Identifier: MIT
 */
package com.bloodsugar.util;

import com.bloodsugar.model.MealPeriod;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 时间段自动归类：拿测量时间和用餐时间算差值，匹配出时段和对应的正常血糖区间
 */
public class PeriodClassifier {

    /** 一天从凌晨 4 点开始算，4 点之前的记录归前一天 */
    public static final LocalTime DAY_BOUNDARY = LocalTime.of(4, 0);

    private static final List<MealPeriod> PERIODS = new ArrayList<>();

    static {
        // 没吃饭或者超过3小时没吃都算空腹
        PERIODS.add(new MealPeriod("空腹", 180, Integer.MAX_VALUE, 3.9, 6.1));
        // 餐后 0~1h、1~2h、2~3h
        PERIODS.add(new MealPeriod("餐后1h", 0, 60, 3.9, 8.9));
        PERIODS.add(new MealPeriod("餐后2h", 60, 120, 3.9, 7.8));
        PERIODS.add(new MealPeriod("餐后3h", 120, 180, 3.9, 7.8));
    }

    // 凌晨 0~4 点之间记的，归到前一天
    public static LocalDate getBusinessDate(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        LocalDate date = dateTime.toLocalDate();
        if (dateTime.toLocalTime().isBefore(DAY_BOUNDARY)) {
            return date.minusDays(1);
        }
        return date;
    }

    // 业务日开始时间，也就是当天凌晨 4 点
    public static LocalDateTime getBusinessDayStart(LocalDate date) {
        return LocalDateTime.of(date, DAY_BOUNDARY);
    }

    // 业务日结束时间，次日凌晨 4 点（不含）
    public static LocalDateTime getBusinessDayEnd(LocalDate date) {
        return LocalDateTime.of(date.plusDays(1), DAY_BOUNDARY);
    }

    // 根据测量时间和用餐时间算时段，返回时段名
    public static String classify(LocalDateTime recordTime, LocalDateTime mealTime) {
        if (mealTime == null || recordTime == null) return "空腹";

        long minutes = Duration.between(mealTime, recordTime).toMinutes();
        // 比吃饭时间还早，算空腹
        if (minutes < 0) return "空腹";

        for (MealPeriod period : PERIODS) {
            if (period.matches(minutes)) {
                return period.getName();
            }
        }
        return "空腹";
    }

    /**
     * 根据测量时间和最近用餐时间判断餐别（空腹/餐前/餐后/睡前）。
     * 当天（凌晨4点起）没吃过饭就是空腹；22点以后算睡前；
     * 餐后 2 小时内算餐后，2~5 小时算餐前，超过 5 小时又回到空腹。
     */
    public static String classifyMealType(LocalDateTime recordTime, LocalDateTime mealTime) {
        if (recordTime == null) return "空腹";
        // 无用餐记录，或用餐不在同一天（凌晨4点边界）内 → 空腹
        if (mealTime == null || !getBusinessDate(mealTime).equals(getBusinessDate(recordTime))) {
            return "空腹";
        }
        // 晚上 22:00 以后 → 睡前
        if (recordTime.toLocalTime().getHour() >= 22) return "睡前";
        long minutes = Duration.between(mealTime, recordTime).toMinutes();
        // 比吃饭时间还早，算空腹
        if (minutes < 0) return "空腹";
        // 餐后 2 小时内 → 餐后
        if (minutes <= 120) return "餐后";
        // 餐后 2-5 小时 → 餐前（距下一餐）
        if (minutes <= 300) return "餐前";
        // 距上次用餐超过 5 小时（含超过 6 小时）→ 空腹
        return "空腹";
    }

    // 按时段名取正常血糖区间 [下限, 上限]，查不到就按空腹的给
    public static double[] getNormalRange(String periodName) {
        for (MealPeriod period : PERIODS) {
            if (period.getName().equals(periodName)) {
                return new double[]{period.getNormalLow(), period.getNormalHigh()};
            }
        }
        return new double[]{3.9, 6.1}; // 默认空腹
    }

    // 血糖是否正常
    public static boolean isNormal(String periodName, double bloodSugar) {
        double[] range = getNormalRange(periodName);
        return bloodSugar >= range[0] && bloodSugar <= range[1];
    }

    // 所有时段定义
    public static List<MealPeriod> getAllPeriods() {
        return PERIODS;
    }
}
