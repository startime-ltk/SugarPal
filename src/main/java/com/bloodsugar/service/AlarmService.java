package com.bloodsugar.service;

import com.bloodsugar.config.AlarmConfig;
import com.bloodsugar.model.BloodSugarRecord;

import java.util.List;

/**
 * P0-1 异常报警与提醒服务（PC 端）。
 *
 * 判定规则：血糖值 < 低阈值（默认 3.9）判低血糖报警；> 高阈值（默认 13.9）判高血糖报警；
 * 阈值来自 AlarmConfig（可配置），判定为纯本地计算，不联网、不做后台常驻扫描。
 */
public class AlarmService {

    public enum Level { LOW, HIGH, NORMAL }

    private AlarmService() {
    }

    /** 判定单条血糖值属于哪个报警级别 */
    public static Level evaluate(double value) {
        if (value < AlarmConfig.getLow()) {
            return Level.LOW;
        }
        if (value > AlarmConfig.getHigh()) {
            return Level.HIGH;
        }
        return Level.NORMAL;
    }

    public static boolean isAlarm(double value) {
        return evaluate(value) != Level.NORMAL;
    }

    /** 生成报警提示文案；正常值返回 null */
    public static String describe(double value) {
        Level lv = evaluate(value);
        if (lv == Level.LOW) {
            return String.format("本次血糖 %.1f mmol/L，低于报警阈值 %.1f，请注意及时补充糖分（如果汁、糖果），15 分钟后复测，持续偏低请就医。",
                    value, AlarmConfig.getLow());
        }
        if (lv == Level.HIGH) {
            return String.format("本次血糖 %.1f mmol/L，高于报警阈值 %.1f，请注意控制饮食与运动，多次偏高请及时就医咨询。",
                    value, AlarmConfig.getHigh());
        }
        return null;
    }

    /** 统计列表中超出报警阈值的记录条数（导入后汇总用） */
    public static int countAlarm(List<BloodSugarRecord> records) {
        int count = 0;
        for (BloodSugarRecord r : records) {
            if (r != null && r.getBloodSugar() > 0 && isAlarm(r.getBloodSugar())) {
                count++;
            }
        }
        return count;
    }
}
