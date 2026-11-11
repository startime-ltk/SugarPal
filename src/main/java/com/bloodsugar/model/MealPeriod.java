/*
 * Copyright (c) 2026 startime-ltk
 * SPDX-License-Identifier: MIT
 */
package com.bloodsugar.model;

/** 一个时间段定义，用于自动归类 */
public class MealPeriod {

    private String name; // 名字，比如"空腹"、"餐后2h"
    private int minMinutes; // 距离用餐最小分钟数（含）
    private int maxMinutes; // 距离用餐最大分钟数（不含），MAX_VALUE 就是没上限
    private double normalLow; // 正常血糖下限 mmol/L
    private double normalHigh; // 正常血糖上限 mmol/L

    public MealPeriod(String name, int minMinutes, int maxMinutes, double normalLow, double normalHigh) {
        this.name = name;
        this.minMinutes = minMinutes;
        this.maxMinutes = maxMinutes;
        this.normalLow = normalLow;
        this.normalHigh = normalHigh;
    }

    public String getName() { return name; }
    public int getMinMinutes() { return minMinutes; }
    public int getMaxMinutes() { return maxMinutes; }
    public double getNormalLow() { return normalLow; }
    public double getNormalHigh() { return normalHigh; }

    // 判断分钟数是否落在这个时段里
    public boolean matches(long minutesAfterMeal) {
        return minutesAfterMeal >= minMinutes && minutesAfterMeal < maxMinutes;
    }
}
