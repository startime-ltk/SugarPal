package com.bloodsugar.ui;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 界面与文件名共用的日期时间格式。
 * 原分散在 MainUI / CalendarDialog / 各对话框中的重复格式化常量统一收拢到这里。
 */
public final class DialogFormats {

    /** yyyy-MM-dd，如 2026-10-19 */
    public static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** HH:mm，如 14:30 */
    public static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    /** MM-dd HH:mm，图表 X 轴紧凑标签 */
    public static final DateTimeFormatter CHART_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** yyyy-MM-dd HH:mm，图表悬停提示完整时间 */
    public static final DateTimeFormatter FULL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** yyyy年M月，日历月份标题 */
    public static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy年M月");
    /** M月d日 EEEE，日历选中日标题（中文星期） */
    public static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA);
    /** yyyyMMdd_HHmmss，导出文件名时间戳 */
    public static final DateTimeFormatter FILE_STAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private DialogFormats() {
    }
}
