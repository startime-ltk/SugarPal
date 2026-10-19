package com.bloodsugar.ui;

import com.bloodsugar.util.PeriodClassifier;

/**
 * 血糖建议文案：按测量值与所属时段正常区间生成一句话建议。
 * 新增记录后的「血糖建议」弹窗与趋势曲线悬停提示共用。
 */
public final class HealthAdvice {

    private HealthAdvice() {
    }

    public static String generate(double bloodSugar, String period) {
        double[] range = PeriodClassifier.getNormalRange(period);
        if (bloodSugar < 3.0) {
            return "⚠ 血糖严重偏低，请立即补充糖分（喝果汁、吃糖果），15分钟后复测。如持续偏低请就医。";
        }
        if (bloodSugar < 3.9) {
            return "血糖偏低，建议立即进食含碳水化合物的食物（饼干、面包等），稍后复测。";
        }
        if (bloodSugar <= range[1]) {
            if (period != null && period.contains("空腹")) {
                return "空腹血糖正常，控制良好。建议继续保持规律饮食和运动。";
            }
            return "餐后血糖在正常范围内，饮食控制得当。建议餐后适度散步有助于血糖平稳。";
        }
        if (bloodSugar <= range[1] + 2.0) {
            return "血糖略偏高，注意本次饮食中的碳水化合物摄入量（米饭/面食/甜食），建议餐后散步30分钟。";
        }
        return String.format("血糖明显偏高（正常上限 %.1f mmol/L），请避免高糖食物，增加运动量。如多次偏高请咨询医生。", range[1]);
    }
}
