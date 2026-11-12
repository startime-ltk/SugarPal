/*
 * Copyright (c) 2026 李图康
 * SPDX-License-Identifier: MIT
 */
package com.bloodsugar.model;

import java.time.LocalDateTime;

/** 一条血糖记录 */
public class BloodSugarRecord {

    private int id;
    private LocalDateTime recordTime; // 测量时间
    private double bloodSugar; // 血糖值 mmol/L
    private LocalDateTime mealTime; // 用餐时间，可能没有
    private String mealPeriod; // 空腹/餐后1h/餐后2h/餐后3h
    private String mealType; // 早餐/午餐/晚餐
    private String note; // 备注
    private double insulin; // 胰岛素剂量 U，0 表示未填
    private double carbs; // 碳水化合物 克，0 表示未填
    private double activity; // 运动时长 分钟，0 表示未填
    private double weight; // 体重 kg，0 表示未填
    private double pulse; // 脉搏 次/分，0 表示未填
    private String bloodPressure; // 血压 如 "120/80"，空表示未填

    public BloodSugarRecord() {}

    public BloodSugarRecord(LocalDateTime recordTime, double bloodSugar,
            LocalDateTime mealTime, String mealPeriod, String mealType, String note) {
        this(recordTime, bloodSugar, mealTime, mealPeriod, mealType, note,
                0, 0, 0, 0, 0, null);
    }

    public BloodSugarRecord(LocalDateTime recordTime, double bloodSugar,
            LocalDateTime mealTime, String mealPeriod, String mealType, String note,
            double insulin, double carbs, double activity, double weight, double pulse,
            String bloodPressure) {
        this.recordTime = recordTime;
        this.bloodSugar = bloodSugar;
        this.mealTime = mealTime;
        this.mealPeriod = mealPeriod;
        this.mealType = mealType;
        this.note = note;
        this.insulin = insulin;
        this.carbs = carbs;
        this.activity = activity;
        this.weight = weight;
        this.pulse = pulse;
        this.bloodPressure = bloodPressure;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public LocalDateTime getRecordTime() { return recordTime; }
    public void setRecordTime(LocalDateTime recordTime) { this.recordTime = recordTime; }

    public double getBloodSugar() { return bloodSugar; }
    public void setBloodSugar(double bloodSugar) { this.bloodSugar = bloodSugar; }

    public LocalDateTime getMealTime() { return mealTime; }
    public void setMealTime(LocalDateTime mealTime) { this.mealTime = mealTime; }

    public String getMealPeriod() { return mealPeriod; }
    public void setMealPeriod(String mealPeriod) { this.mealPeriod = mealPeriod; }

    public String getMealType() { return mealType; }
    public void setMealType(String mealType) { this.mealType = mealType; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public double getInsulin() { return insulin; }
    public void setInsulin(double insulin) { this.insulin = insulin; }

    public double getCarbs() { return carbs; }
    public void setCarbs(double carbs) { this.carbs = carbs; }

    public double getActivity() { return activity; }
    public void setActivity(double activity) { this.activity = activity; }

    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }

    public double getPulse() { return pulse; }
    public void setPulse(double pulse) { this.pulse = pulse; }

    public String getBloodPressure() { return bloodPressure; }
    public void setBloodPressure(String bloodPressure) { this.bloodPressure = bloodPressure; }

    /** 是否填了任意健康维度数据（供 UI 判断展示） */
    public boolean hasHealthData() {
        return insulin > 0 || carbs > 0 || activity > 0 || weight > 0 || pulse > 0
                || (bloodPressure != null && !bloodPressure.isBlank());
    }

    @Override
    public String toString() {
        return String.format("%s | %.1f mmol/L | %s | %s",
                recordTime != null ? recordTime.toLocalDate() : "",
                bloodSugar, mealPeriod, mealType);
    }
}
