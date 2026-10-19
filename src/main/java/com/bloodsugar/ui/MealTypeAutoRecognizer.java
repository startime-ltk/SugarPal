package com.bloodsugar.ui;

import com.bloodsugar.service.BloodSugarService;
import com.bloodsugar.util.PeriodClassifier;

import javafx.scene.control.TextField;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Duration;

/**
 * 餐别自动识别：根据测量时间推断「早餐后 2小时35分钟」「空腹」「睡前」等餐别描述。
 * <p>数据来源按优先级依次为：</p>
 * <ol>
 *   <li>左侧「今日用餐时间」面板填写的时间（面板带餐名，优先级最高）；</li>
 *   <li>meal_times 表中已保存的用餐时间（同一业务日同餐别只保留最新一条）；</li>
 *   <li>当天（凌晨 4 点起）历史血糖记录自带的历史用餐时间。</li>
 * </ol>
 * 当天没吃过饭则判定为空腹；22 点以后的测量固定记为睡前。
 */
public class MealTypeAutoRecognizer {

    /** 一顿饭的信息：时间 + 餐名（早餐 / 午餐 / 晚餐 / 加餐 / 用餐） */
    public static class MealTimeInfo {
        public final LocalDateTime time;
        public final String mealName;

        public MealTimeInfo(LocalDateTime time, String mealName) {
            this.time = time;
            this.mealName = mealName;
        }
    }

    private static final String[] PANEL_MEAL_NAMES = {"早餐", "午餐", "晚餐", "加餐"};

    private final BloodSugarService service;
    private final TextField[] panelFields;

    public MealTypeAutoRecognizer(BloodSugarService service,
                                  TextField breakfastField, TextField lunchField,
                                  TextField dinnerField, TextField extraMealField) {
        this.service = service;
        this.panelFields = new TextField[]{breakfastField, lunchField, dinnerField, extraMealField};
    }

    /**
     * 找 recordTime 之前最近的一顿：优先看左侧面板填的时间，
     * 面板没有再看数据库里的历史记录，返回时间 + 餐名；当天没吃过返回 null。
     */
    public MealTimeInfo resolve(LocalDateTime recordTime) {
        MealTimeInfo best = null;

        // 先看面板里填的四个餐别时间（面板带餐名，优先级高）
        for (int i = 0; i < panelFields.length; i++) {
            best = updateBest(best, parsePanelField(panelFields[i]), PANEL_MEAL_NAMES[i], recordTime);
        }

        // 再看已保存的用餐时间（meal_times 表），没有餐名就统一叫"用餐"
        try {
            LocalDateTime savedMealTime = service.getLatestSavedMealTime(recordTime);
            if (savedMealTime != null) {
                best = updateBest(best, savedMealTime, "用餐", recordTime);
            }
        } catch (SQLException ignored) {
            // 单来源查询失败不影响其它来源，继续尝试
        }

        // 再看数据库当天的历史用餐记录；当天没吃过就返回 null，最终显示"空腹"
        try {
            LocalDateTime dbMealTime = service.getLatestMealTimeBefore(recordTime);
            if (dbMealTime != null) {
                best = updateBest(best, dbMealTime, "用餐", recordTime);
            }
        } catch (SQLException ignored) {
            // 同上
        }

        return best;
    }

    /**
     * 生成餐别描述，比如"早餐后 2小时35分钟"、"空腹"、"睡前"。
     * 当天（凌晨4点起）没吃过饭就直接算空腹。
     */
    public String describe(LocalDateTime recordTime, MealTimeInfo mealInfo) {
        if (recordTime == null || mealInfo == null) {
            return "空腹";
        }
        // 22 点以后算睡前
        if (recordTime.toLocalTime().getHour() >= 22) {
            return "睡前";
        }

        long minutes = Duration.between(mealInfo.time, recordTime).toMinutes();
        if (minutes < 0) {
            return "空腹"; // 比吃饭时间还早，算空腹
        }

        long hours = minutes / 60;
        long mins = minutes % 60;
        if (hours == 0) {
            return mealInfo.mealName + "后 " + mins + "分钟";
        }
        if (mins == 0) {
            return mealInfo.mealName + "后 " + hours + "小时";
        }
        return mealInfo.mealName + "后 " + hours + "小时" + mins + "分钟";
    }

    /** 面板时间按当前业务日解析，凌晨 0~4 点算前一天；解析失败返回 null */
    private LocalDateTime parsePanelField(TextField field) {
        String s = field.getText().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            LocalDate businessToday = PeriodClassifier.getBusinessDate(LocalDateTime.now());
            return LocalDateTime.of(businessToday, LocalTime.parse(s.replace('.', ':')));
        } catch (Exception e) {
            return null;
        }
    }

    /** 候选比较：只取不晚于测量时间、且最接近测量时间的那一餐 */
    private MealTimeInfo updateBest(MealTimeInfo best, LocalDateTime mealTime, String name, LocalDateTime recordTime) {
        if (mealTime == null || mealTime.isAfter(recordTime)) {
            return best;
        }
        if (best == null || mealTime.isAfter(best.time)) {
            return new MealTimeInfo(mealTime, name);
        }
        return best;
    }
}
