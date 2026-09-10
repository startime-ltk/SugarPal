package com.bloodsugar.service;

import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.util.PeriodClassifier;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 血糖仪数据批量导入服务：支持 CSV / Excel（.csv/.txt/.xlsx/.xls）。
 *
 * 能力：
 * 1. 列名自动识别（中英文常见列名），无表头时按「第 1 列时间、第 2 列血糖」兜底；
 * 2. 常见日期时间格式兼容（yyyy-MM-dd HH:mm(:ss)、yyyy/MM/dd、yyyy.MM.dd、yyyyMMddHHmm 等，仅日期默认 08:00）；
 * 3. mmol/L 数值校验（1~40 mmol/L；单位为 mg/dL 或数值 40~600 时按 ÷18 换算）；非法行跳过并汇总；
 * 4. 去重规则：同一时间（精确到秒）+ 同一数值视为重复（含文件内部重复与库中已有记录重复）；
 * 5. 预览解析结果（含每行状态与原因），确认后写入，并给出成功/跳过/失败计数。
 */
public class CsvImportService {

    public static final double MIN_VALID = 1.0;
    public static final double MAX_VALID = 40.0;
    public static final int MAX_ROWS = 5000;

    private static final DateTimeFormatter KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter[] DATETIME_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-M-d H:m:s"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:m"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmm"),
    };

    private final BloodSugarService service = new BloodSugarService();

    // ===================== 对外数据结构 =====================

    /** 列识别结果 */
    public static class ColumnMapping {
        public int timeIdx = -1, sugarIdx = -1, periodIdx = -1, noteIdx = -1;
        public boolean headerDetected;
        public final List<String> headers = new ArrayList<>();
        public String warning = "";

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(headerDetected ? "已识别表头：" : "未检测到表头，按默认列序解析：");
            sb.append("时间=").append(colName(timeIdx)).append("，");
            sb.append("血糖=").append(colName(sugarIdx));
            if (periodIdx >= 0) sb.append("，餐别=").append(colName(periodIdx));
            if (noteIdx >= 0) sb.append("，备注=").append(colName(noteIdx));
            return sb.toString();
        }

        private String colName(int idx) {
            if (idx < 0) return "未识别";
            if (idx < headers.size() && headers.get(idx) != null && !headers.get(idx).isBlank()) {
                return "第 " + (idx + 1) + " 列(" + headers.get(idx).trim() + ")";
            }
            return "第 " + (idx + 1) + " 列";
        }
    }

    /** 预览行 */
    public static class PreviewRow {
        public final int lineNo;
        public final String rawTime, rawSugar, rawPeriod, rawNote;
        public LocalDateTime recordTime;
        public double bloodSugar;
        public LocalDateTime mealTime;
        public String mealPeriod = "空腹";
        public String mealType = "空腹";
        public String note = "";
        public String status = "可导入";
        public String message = "";

        public PreviewRow(int lineNo, String rawTime, String rawSugar, String rawPeriod, String rawNote) {
            this.lineNo = lineNo;
            this.rawTime = rawTime;
            this.rawSugar = rawSugar;
            this.rawPeriod = rawPeriod;
            this.rawNote = rawNote;
        }

        public String displayTime() {
            return recordTime == null ? (rawTime == null ? "" : rawTime) : recordTime.format(KEY_FMT);
        }

        public BloodSugarRecord toRecord() {
            BloodSugarRecord r = new BloodSugarRecord(recordTime, bloodSugar, mealTime, mealPeriod, mealType, note,
                    0, 0, 0, 0, 0, null);
            return r;
        }
    }

    /** 解析结果（供预览与确认） */
    public static class ImportPreview {
        public File file;
        public ColumnMapping mapping = new ColumnMapping();
        public final List<PreviewRow> rows = new ArrayList<>();
        public int total, importable, dupInFile, dupInDb, invalid;
        public String warning = "";
    }

    /** 导入结果 */
    public static class ImportSummary {
        public int success, skipped, failed;
        public final List<String> failures = new ArrayList<>();
    }

    // ===================== 解析 =====================

    /** 解析文件，生成预览数据（不入库） */
    public ImportPreview preview(File file) throws Exception {
        ImportPreview p = new ImportPreview();
        p.file = file;
        String name = file.getName().toLowerCase();
        List<List<String>> table;
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            table = readExcel(file);
        } else {
            table = readDelimited(file);
        }
        if (table.isEmpty()) {
            p.warning = "文件内容为空";
            return p;
        }

        // 表头识别
        ColumnMapping map = detectHeader(table.get(0));
        int startRow = 0;
        if (map.headerDetected) {
            startRow = 1;
        } else {
            map.timeIdx = map.headers.size() > 0 ? 0 : -1;
            map.sugarIdx = map.headers.size() > 1 ? 1 : -1;
            p.warning = "未检测到标准表头，已按第 1 列=时间、第 2 列=血糖 解析。";
        }
        if (map.timeIdx < 0) {
            p.mapping = map;
            p.warning = "未能识别「时间」列，请检查文件或使用下载模板重新填写。";
            return p;
        }
        if (map.sugarIdx < 0) {
            p.mapping = map;
            p.warning = "未能识别「血糖」列，请检查文件或使用下载模板重新填写。";
            return p;
        }
        p.mapping = map;

        // 库内已有记录的去重键
        Set<String> dbKeys = new HashSet<>();
        for (BloodSugarRecord r : service.getAllRecords()) {
            dbKeys.add(dupKey(r.getRecordTime(), r.getBloodSugar()));
        }

        Set<String> fileKeys = new HashSet<>();
        List<List<String>> dataRows = table.subList(startRow, table.size());
        if (dataRows.size() > MAX_ROWS) {
            p.warning = (p.warning.isEmpty() ? "" : p.warning + " ")
                    + "文件行数较多，仅解析前 " + MAX_ROWS + " 行。";
            dataRows = dataRows.subList(0, MAX_ROWS);
        }

        int lineNo = startRow;
        for (List<String> row : dataRows) {
            lineNo++;
            if (isBlankRow(row)) continue;
            p.total++;
            PreviewRow pr = new PreviewRow(lineNo,
                    cell(row, map.timeIdx), cell(row, map.sugarIdx),
                    cell(row, map.periodIdx), cell(row, map.noteIdx));

            TimeParse tp = parseDateTime(pr.rawTime);
            if (tp == null || tp.value == null) {
                pr.status = "格式错误";
                pr.message = "时间无法识别";
                p.invalid++;
                p.rows.add(pr);
                continue;
            }
            Double sugar = parseSugar(pr.rawSugar);
            if (sugar == null) {
                pr.status = "格式错误";
                pr.message = "血糖值无法识别或超出合理范围(1~40 mmol/L)";
                p.invalid++;
                p.rows.add(pr);
                continue;
            }
            pr.recordTime = tp.value;
            pr.bloodSugar = sugar;
            if (tp.dateOnly) pr.message = "仅日期，已默认 08:00";
            Double rawNum = rawNumeric(pr.rawSugar);
            if (rawNum != null && (pr.rawSugar.toLowerCase().contains("mg") || rawNum > MAX_VALID)) {
                pr.message = appendMsg(pr.message, "已按 mg/dL ÷18 换算");
            }

            String key = dupKey(pr.recordTime, pr.bloodSugar);
            if (!fileKeys.add(key)) {
                pr.status = "文件内重复";
                p.dupInFile++;
                p.rows.add(pr);
                continue;
            }
            if (dbKeys.contains(key)) {
                pr.status = "与已有记录重复";
                p.dupInDb++;
                p.rows.add(pr);
                continue;
            }

            // 餐别解析
            try {
                resolveMeal(pr);
            } catch (SQLException ex) {
                pr.mealPeriod = "空腹";
                pr.mealType = "空腹";
            }
            pr.note = pr.rawNote == null ? "" : pr.rawNote.trim();
            pr.status = "可导入";
            p.importable++;
            p.rows.add(pr);
        }
        return p;
    }

    /** 执行导入：仅写入状态为「可导入」的行 */
    public ImportSummary doImport(ImportPreview preview) {
        ImportSummary s = new ImportSummary();
        for (PreviewRow r : preview.rows) {
            if (!"可导入".equals(r.status)) {
                s.skipped++;
                continue;
            }
            try {
                service.addImportedRecord(r.toRecord());
                s.success++;
            } catch (SQLException ex) {
                s.failed++;
                s.failures.add("第 " + r.lineNo + " 行：" + ex.getMessage());
            }
        }
        return s;
    }

    /** 生成导入模板 CSV（UTF-8 带 BOM，Excel 可直接打开） */
    public void writeTemplate(File file) throws IOException {
        LocalDate today = LocalDate.now();
        StringBuilder sb = new StringBuilder();
        sb.append("时间,血糖值(mmol/L),餐别,备注\r\n");
        sb.append(today + " 07:30,5.6,空腹,起床后测量\r\n");
        sb.append(today + " 09:30,7.8,餐后1h,早餐后\r\n");
        sb.append(today + " 10:30,6.4,餐后2h,早餐后\r\n");
        sb.append(today + " 21:30,6.9,,睡前（未填餐别将自动识别）\r\n");
        Files.write(file.toPath(), ("\uFEFF" + sb).getBytes(StandardCharsets.UTF_8));
    }

    // ===================== 餐别解析 =====================

    private final Map<LocalDate, Map<String, LocalDateTime>> mealTimeCache = new HashMap<>();

    /** 填充 mealTime / mealPeriod / mealType：优先用文件餐别，其次按现有自动识别逻辑 */
    private void resolveMeal(PreviewRow pr) throws SQLException {
        LocalDateTime t = pr.recordTime;
        String p = pr.rawPeriod == null ? "" : pr.rawPeriod.trim();

        if (p.contains("餐后") || p.contains("1小时") || p.contains("2小时") || p.contains("3小时")
                || p.toLowerCase().contains("post")) {
            if (p.contains("1")) {
                pr.mealPeriod = "餐后1h";
                pr.mealTime = t.minusMinutes(45);
            } else if (p.contains("3")) {
                pr.mealPeriod = "餐后3h";
                pr.mealTime = t.minusMinutes(150);
            } else {
                pr.mealPeriod = "餐后2h";
                pr.mealTime = t.minusMinutes(90);
            }
            pr.mealType = "餐后";
            return;
        }
        if (p.contains("空腹") || p.contains("晨起")) {
            pr.mealPeriod = "空腹";
            pr.mealType = "空腹";
            return;
        }
        if (p.contains("餐前")) {
            pr.mealPeriod = "空腹";
            pr.mealType = "餐前";
            return;
        }
        if (p.contains("睡前") || p.toLowerCase().contains("bed")) {
            pr.mealPeriod = "空腹";
            pr.mealType = "睡前";
            return;
        }
        if (p.contains("早") || p.contains("午") || p.contains("晚") || p.contains("加餐")) {
            String name = p.contains("加餐") ? "加餐"
                    : p.contains("早") ? "早餐"
                    : p.contains("午") ? "午餐" : "晚餐";
            LocalDateTime mt = lookupSavedMealTime(t, name);
            if (mt != null) {
                pr.mealTime = mt;
                pr.mealPeriod = PeriodClassifier.classify(t, mt);
                pr.mealType = name;
            } else {
                pr.mealPeriod = "空腹";
                pr.mealType = name;
                pr.message = appendMsg(pr.message, "未找到当日「" + name + "」用餐时间，时段按空腹计");
            }
            return;
        }

        // 未提供餐别 → 沿用现有自动识别逻辑（取最近一次用餐时间）
        LocalDateTime mt = latestMealTimeBefore(t);
        if (mt != null) {
            pr.mealTime = mt;
            pr.mealPeriod = PeriodClassifier.classify(t, mt);
            pr.mealType = PeriodClassifier.classifyMealType(t, mt);
            pr.message = appendMsg(pr.message, "未填餐别，已按自动识别");
        } else {
            pr.mealPeriod = "空腹";
            pr.mealType = "空腹";
            pr.message = appendMsg(pr.message, "未填餐别且无用餐时间记录，按空腹计");
        }
    }

    private LocalDateTime lookupSavedMealTime(LocalDateTime t, String mealName) throws SQLException {
        LocalDate bd = PeriodClassifier.getBusinessDate(t);
        Map<String, LocalDateTime> saved = mealTimeCache.get(bd);
        if (saved == null) {
            saved = service.getMealTimesByBusinessDate(bd);
            mealTimeCache.put(bd, saved);
        }
        LocalDateTime mt = saved.get(mealName);
        if (mt == null) mt = saved.get(mealName.replace("餐", ""));
        if (mt != null && mt.isAfter(t)) mt = null;
        return mt;
    }

    private LocalDateTime latestMealTimeBefore(LocalDateTime t) {
        LocalDateTime a = null, b = null;
        try {
            a = service.getLatestSavedMealTime(t);
        } catch (SQLException ignored) {
        }
        try {
            b = service.getLatestMealTimeBefore(t);
        } catch (SQLException ignored) {
        }
        LocalDateTime r = a;
        if (r == null || (b != null && b.isAfter(r))) r = b;
        if (r != null && r.isAfter(t)) r = null;
        return r;
    }

    // ===================== 读取与解析工具 =====================

    private List<List<String>> readDelimited(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\uFFFD') >= 0) {
            text = new String(bytes, Charset.forName("GBK"));
        }
        List<String> lines = splitLines(text);
        char delim = detectDelimiter(lines);
        List<List<String>> table = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            table.add(parseLine(line, delim));
        }
        return table;
    }

    private List<List<String>> readExcel(File file) throws Exception {
        List<List<String>> table = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(file)) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                int last = Math.max(row.getLastCellNum(), 0);
                for (int i = 0; i < last; i++) {
                    Cell c = row.getCell(i);
                    if (c == null) {
                        cells.add("");
                        continue;
                    }
                    if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                        cells.add(c.getLocalDateTimeCellValue().format(KEY_FMT));
                    } else {
                        cells.add(fmt.formatCellValue(c).trim());
                    }
                }
                if (!isBlankRow(cells)) table.add(cells);
            }
        }
        return table;
    }

    /** 按引号规则切分文本行（支持字段内含逗号与换行） */
    private List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                cur.append(c);
            } else if ((c == '\n' || c == '\r') && !inQuote) {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private char detectDelimiter(List<String> lines) {
        for (String line : lines) {
            if (line.isBlank()) continue;
            int comma = count(line, ','), semi = count(line, ';'), tab = count(line, '\t');
            if (tab >= comma && tab >= semi) return '\t';
            if (semi > comma) return ';';
            return ',';
        }
        return ',';
    }

    private int count(String s, char c) {
        int n = 0;
        for (char x : s.toCharArray()) if (x == c) n++;
        return n;
    }

    private List<String> parseLine(String line, char delim) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == delim) {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().trim());
        return cells;
    }

    private ColumnMapping detectHeader(List<String> firstRow) {
        ColumnMapping map = new ColumnMapping();
        map.headers.addAll(firstRow);
        for (int i = 0; i < firstRow.size(); i++) {
            String h = norm(firstRow.get(i));
            if (h.isEmpty()) continue;
            if (map.timeIdx < 0 && isTimeCol(h)) {
                map.timeIdx = i;
            } else if (map.sugarIdx < 0 && isSugarCol(h)) {
                map.sugarIdx = i;
            } else if (map.periodIdx < 0 && isPeriodCol(h)) {
                map.periodIdx = i;
            } else if (map.noteIdx < 0 && isNoteCol(h)) {
                map.noteIdx = i;
            }
        }
        map.headerDetected = map.timeIdx >= 0 || map.sugarIdx >= 0;
        return map;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase()
                .replace("（", "(").replace("）", ")")
                .replace(" ", "").replace("\u3000", "")
                .replace("_", "").replace("-", "")
                .replace("[", "").replace("]", "")
                .replace("(", "").replace(")", "");
    }

    private static boolean isTimeCol(String h) {
        return h.contains("时间") || h.contains("日期") || h.contains("date") || h.contains("time");
    }

    private static boolean isSugarCol(String h) {
        return h.contains("血糖") || h.contains("glucose") || h.contains("sugar")
                || h.contains("mmol") || h.contains("value") || h.contains("数值")
                || h.equals("值") || h.contains("浓度") || h.contains("测量值");
    }

    private static boolean isPeriodCol(String h) {
        return h.contains("餐别") || h.contains("餐次") || h.contains("时段") || h.contains("餐型")
                || h.contains("类型") || h.contains("period") || h.contains("meal") || h.equals("type");
    }

    private static boolean isNoteCol(String h) {
        return h.contains("备注") || h.contains("说明") || h.contains("注释")
                || h.contains("note") || h.contains("remark") || h.contains("comment") || h.contains("memo");
    }

    private String cell(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size()) return "";
        return row.get(idx) == null ? "" : row.get(idx);
    }

    private boolean isBlankRow(List<String> row) {
        if (row == null || row.isEmpty()) return true;
        for (String c : row) {
            if (c != null && !c.isBlank()) return false;
        }
        return true;
    }

    /** 时间解析结果 */
    private static class TimeParse {
        LocalDateTime value;
        boolean dateOnly;
    }

    /** 取数值原始大小（不做 mg/dL 换算），用于判断是否需要提示换算 */
    private Double rawNumeric(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[^0-9.,]", "").replace('，', ',').replace(',', '.');
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 兼容常见日期时间格式；仅日期默认 08:00 */
    private TimeParse parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim()
                .replace('T', ' ')
                .replace('/', '-')
                .replace('.', '-')
                .replace('年', '-').replace('月', '-').replace("日", " ")
                .replace("\u3000", " ")
                .trim();
        while (s.contains("  ")) s = s.replace("  ", " ");
        s = s.replaceAll("\\s*(星期|周)[一二三四五六日天]\\s*", " ").trim();

        TimeParse tp = new TimeParse();
        // 年在前：yyyy-M-d H:m(:s)
        for (DateTimeFormatter f : DATETIME_FORMATS) {
            try {
                tp.value = LocalDateTime.parse(s, f);
                return tp;
            } catch (Exception ignored) {
            }
        }
        // 月/日在前：M-d-yyyy H:m(:s)，首段 >12 时按 d-M-yyyy
        String[] dateTime = s.split("\\s+", 2);
        String datePart = dateTime.length > 0 ? dateTime[0] : "";
        String timePart = dateTime.length > 1 ? dateTime[1] : "";
        String[] seg = datePart.split("-");
        if (seg.length == 3 && seg[2].length() == 4) {
            int a = parseIntSafe(seg[0]), b = parseIntSafe(seg[1]);
            String iso = seg[2] + "-" + (a > 12 ? seg[1] : seg[0]) + "-" + (a > 12 ? seg[0] : seg[1])
                    + (timePart.isBlank() ? "" : " " + timePart);
            for (DateTimeFormatter f : DATETIME_FORMATS) {
                try {
                    tp.value = LocalDateTime.parse(iso.replace('-', '-'), f);
                    return tp;
                } catch (Exception ignored) {
                }
            }
        }
        // 仅日期：yyyy-M-d 或 yyyyMMdd
        for (String pattern : new String[]{"yyyy-M-d", "yyyyMMdd"}) {
            try {
                LocalDate d = LocalDate.parse(s, DateTimeFormatter.ofPattern(pattern));
                tp.value = d.atTime(8, 0);
                tp.dateOnly = true;
                return tp;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 数值解析：清洗单位后校验；mg/dL 自动换算 */
    private Double parseSugar(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        boolean mgdl = s.toLowerCase().contains("mg");
        s = s.replaceAll("[^0-9.,]", "");
        s = s.replace('，', ',').replace(',', '.');
        if (s.isEmpty() || ".".equals(s)) return null;
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
        if (mgdl || (v > MAX_VALID && v <= 600)) v = v / 18.0;
        if (v < MIN_VALID - 0.0001 || v > MAX_VALID + 0.0001) return null;
        return Math.round(v * 10.0) / 10.0;
    }

    private static String dupKey(LocalDateTime t, double v) {
        return (t == null ? "" : t.format(KEY_FMT)) + "#" + String.format("%.1f", v);
    }

    private static String appendMsg(String old, String add) {
        if (old == null || old.isBlank()) return add;
        return old + "；" + add;
    }

    /** 供界面展示的去重统计文本 */
    public static String summaryText(ImportPreview p) {
        return "共 " + p.total + " 行：可导入 " + p.importable + " 条，重复跳过 "
                + (p.dupInFile + p.dupInDb) + " 条（文件内 " + p.dupInFile + " / 已有记录 " + p.dupInDb
                + "），格式错误 " + p.invalid + " 条";
    }

    /** 供界面展示的导入结果文本 */
    public static String resultText(ImportSummary s) {
        return "成功导入 " + s.success + " 条，跳过 " + s.skipped + " 条，失败 " + s.failed + " 条";
    }
}
