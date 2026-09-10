package com.bloodsugar.service;

import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.util.PeriodClassifier;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 数据导出服务：把血糖记录导出为 Excel / PDF 报告 */
public class ExportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 中文字体候选：优先纯 TTF，TTC 需带索引后缀
    private static final String[] FONT_CANDIDATES = {
            "C:\\Windows\\Fonts\\simhei.ttf",
            "C:\\Windows\\Fonts\\msyh.ttc,0",
            "C:\\Windows\\Fonts\\Deng.ttf",
            "C:\\Windows\\Fonts\\simsun.ttc,0",
            "C:\\Windows\\Fonts\\simkai.ttf"
    };

    // ==================== Excel 导出 ====================

    /** 导出 Excel：血糖记录明细 / 统计汇总 / 按业务日汇总 / 按餐别汇总 / 健康数据汇总 五个 Sheet */
    public void exportExcel(List<BloodSugarRecord> records, String filePath) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); OutputStream out = new FileOutputStream(filePath)) {
            writeDetailSheet(wb.createSheet("血糖记录明细"), records);
            writeStatSheet(wb.createSheet("统计汇总"), records);
            writeDaySheet(wb.createSheet("按业务日汇总"), records);
            writeMealSheet(wb.createSheet("按餐别汇总"), records);
            writeHealthSheet(wb.createSheet("健康数据汇总"), records);
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                wb.getSheetAt(i).setDefaultColumnWidth(18);
            }
            wb.write(out);
        }
    }

    private void writeDetailSheet(Sheet sheet, List<BloodSugarRecord> records) {
        String[] headers = {"日期", "业务日", "时间", "餐别", "血糖值(mmol/L)", "是否正常"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
        int rowIdx = 1;
        for (BloodSugarRecord r : records) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(r.getRecordTime() != null ? r.getRecordTime().format(DATE_FMT) : "");
            row.createCell(1).setCellValue(PeriodClassifier.getBusinessDate(r.getRecordTime()) != null
                    ? PeriodClassifier.getBusinessDate(r.getRecordTime()).format(DATE_FMT) : "");
            row.createCell(2).setCellValue(r.getRecordTime() != null ? r.getRecordTime().format(TIME_FMT) : "");
            row.createCell(3).setCellValue(r.getMealPeriod() != null ? r.getMealPeriod() : "");
            row.createCell(4).setCellValue(r.getBloodSugar());
            boolean normal = PeriodClassifier.isNormal(r.getMealPeriod(), r.getBloodSugar());
            row.createCell(5).setCellValue(normal ? "正常" : "异常");
        }
        styleHeader(sheet);
    }

    private void writeStatSheet(Sheet sheet, List<BloodSugarRecord> records) {
        double[] stat = computeStats(records);
        String[][] rows = {
                {"记录总数", String.format("%d 条", records.size())},
                {"平均值", String.format("%.1f mmol/L", stat[0])},
                {"最高值", String.format("%.1f mmol/L", stat[1])},
                {"最低值", String.format("%.1f mmol/L", stat[2])},
                {"正常率", String.format("%.0f%%（%d/%d）", stat[3], (int) stat[4], records.size())},
                {"空腹平均值", String.format("%.1f mmol/L", stat[5])},
                {"餐后1h平均值", String.format("%.1f mmol/L", stat[6])},
                {"餐后2h平均值", String.format("%.1f mmol/L", stat[7])},
                {"餐后3h平均值", String.format("%.1f mmol/L", stat[8])},
                {"导出时间", LocalDateTime.now().format(DATETIME_FMT)}
        };
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("统计指标");
        header.createCell(1).setCellValue("数值");
        int rowIdx = 1;
        for (String[] line : rows) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(line[0]);
            row.createCell(1).setCellValue(line[1]);
        }
        styleHeader(sheet);
    }

    private void writeDaySheet(Sheet sheet, List<BloodSugarRecord> records) {
        String[] headers = {"业务日", "记录数", "平均值", "最高值", "最低值", "正常率"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
        // 按业务日分组（TreeMap 升序）
        Map<LocalDate, List<BloodSugarRecord>> byDay = records.stream()
                .collect(Collectors.groupingBy(
                        r -> PeriodClassifier.getBusinessDate(r.getRecordTime()),
                        TreeMap::new, Collectors.toList()));
        int rowIdx = 1;
        for (Map.Entry<LocalDate, List<BloodSugarRecord>> e : byDay.entrySet()) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(e.getKey() != null ? e.getKey().format(DATE_FMT) : "");
            double[] s = computeStats(e.getValue());
            row.createCell(1).setCellValue(e.getValue().size());
            row.createCell(2).setCellValue(String.format("%.1f", s[0]));
            row.createCell(3).setCellValue(String.format("%.1f", s[1]));
            row.createCell(4).setCellValue(String.format("%.1f", s[2]));
            row.createCell(5).setCellValue(String.format("%.0f%%", s[3]));
        }
        styleHeader(sheet);
    }

    private void writeMealSheet(Sheet sheet, List<BloodSugarRecord> records) {
        String[] headers = {"餐别", "记录数", "平均值", "最高值", "最低值", "正常率"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
        // 按餐别分组，保持固定顺序：空腹、餐后1h、餐后2h、餐后3h
        Map<String, List<BloodSugarRecord>> byMeal = new LinkedHashMap<>();
        for (String period : new String[]{"空腹", "餐后1h", "餐后2h", "餐后3h"}) {
            byMeal.put(period, new ArrayList<>());
        }
        for (BloodSugarRecord r : records) {
            String p = r.getMealPeriod() != null ? r.getMealPeriod() : "空腹";
            byMeal.computeIfAbsent(p, k -> new ArrayList<>()).add(r);
        }
        int rowIdx = 1;
        for (Map.Entry<String, List<BloodSugarRecord>> e : byMeal.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(e.getKey());
            double[] s = computeStats(e.getValue());
            row.createCell(1).setCellValue(e.getValue().size());
            row.createCell(2).setCellValue(String.format("%.1f", s[0]));
            row.createCell(3).setCellValue(String.format("%.1f", s[1]));
            row.createCell(4).setCellValue(String.format("%.1f", s[2]));
            row.createCell(5).setCellValue(String.format("%.0f%%", s[3]));
        }
        styleHeader(sheet);
    }

    /** 健康数据汇总 Sheet：近 30 天各维度均值 / 最新值 / 趋势，按日期列出有值的记录 */
    private void writeHealthSheet(Sheet sheet, List<BloodSugarRecord> records) {
        Row header = sheet.createRow(0);
        String[] headers = {"日期", "胰岛素(U)", "碳水(g)", "运动(分钟)", "体重(kg)", "脉搏(次/分)", "血压(mmHg)"};
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        int rowIdx = 1;
        for (BloodSugarRecord r : records) {
            if (r.getRecordTime() == null || r.getRecordTime().isBefore(cutoff)) continue;
            if (r.getInsulin() <= 0 && r.getCarbs() <= 0 && r.getActivity() <= 0
                    && r.getWeight() <= 0 && r.getPulse() <= 0
                    && (r.getBloodPressure() == null || r.getBloodPressure().isBlank())) continue;
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(r.getRecordTime().format(DATE_FMT));
            if (r.getInsulin() > 0) row.createCell(1).setCellValue(r.getInsulin());
            if (r.getCarbs() > 0) row.createCell(2).setCellValue(r.getCarbs());
            if (r.getActivity() > 0) row.createCell(3).setCellValue(r.getActivity());
            if (r.getWeight() > 0) row.createCell(4).setCellValue(r.getWeight());
            if (r.getPulse() > 0) row.createCell(5).setCellValue(r.getPulse());
            if (r.getBloodPressure() != null && !r.getBloodPressure().isBlank()) {
                row.createCell(6).setCellValue(r.getBloodPressure());
            }
        }
        styleHeader(sheet);
    }

    private void styleHeader(Sheet sheet) {
        CellStyle style = sheet.getWorkbook().createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        org.apache.poi.ss.usermodel.Font bold = sheet.getWorkbook().createFont();
        bold.setBold(true);
        style.setFont(bold);
        Row header = sheet.getRow(0);
        if (header != null) {
            for (int i = 0; i < header.getLastCellNum(); i++) {
                Cell c = header.getCell(i);
                if (c != null) c.setCellStyle(style);
            }
        }
    }

    /** 计算统计：avg, max, min, normalRate(%), normalCount, 空腹均值, 餐后1h均值, 餐后2h均值, 餐后3h均值 */
    private double[] computeStats(List<BloodSugarRecord> records) {
        int total = records.size();
        if (total == 0) return new double[]{0, 0, 0, 0, 0, 0, 0, 0, 0};
        double avg = records.stream().mapToDouble(BloodSugarRecord::getBloodSugar).average().orElse(0);
        double max = records.stream().mapToDouble(BloodSugarRecord::getBloodSugar).max().orElse(0);
        double min = records.stream().mapToDouble(BloodSugarRecord::getBloodSugar).min().orElse(0);
        long normalCount = records.stream()
                .filter(r -> PeriodClassifier.isNormal(r.getMealPeriod(), r.getBloodSugar())).count();
        double normalRate = 100.0 * normalCount / total;
        double fastingAvg = avgOfPeriod(records, "空腹");
        double post1hAvg = avgOfPeriod(records, "餐后1h");
        double post2hAvg = avgOfPeriod(records, "餐后2h");
        double post3hAvg = avgOfPeriod(records, "餐后3h");
        return new double[]{avg, max, min, normalRate, normalCount, fastingAvg, post1hAvg, post2hAvg, post3hAvg};
    }

    private double avgOfPeriod(List<BloodSugarRecord> records, String period) {
        return records.stream()
                .filter(r -> period.equals(r.getMealPeriod()))
                .mapToDouble(BloodSugarRecord::getBloodSugar)
                .average().orElse(0);
    }

    // ==================== PDF 导出 ====================

    /** 导出 PDF 报告：标题 + 统计汇总 + 明细表 + 按业务日/餐别汇总（嵌入中文字体防乱码） */
    public void exportPdf(List<BloodSugarRecord> records, String filePath) throws Exception {
        BaseFont bf = loadChineseBaseFont();
        Font titleFont = new Font(bf, 18, Font.BOLD);
        Font subFont = new Font(bf, 10);
        Font headFont = new Font(bf, 10, Font.BOLD);
        Font bodyFont = new Font(bf, 10);

        Document doc = new Document(PageSize.A4, 36, 36, 48, 48);
        try (OutputStream out = new FileOutputStream(filePath)) {
            PdfWriter.getInstance(doc, out);
            doc.open();

            // 标题
            Paragraph title = new Paragraph("糖伴SugarPal 血糖记录报告", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            doc.add(title);
            Paragraph sub = new Paragraph("导出时间：" + LocalDateTime.now().format(DATETIME_FMT)
                    + "　　记录数：" + records.size() + " 条", subFont);
            sub.setAlignment(Element.ALIGN_CENTER);
            doc.add(sub);
            doc.add(new Paragraph(" "));

            // 统计汇总
            double[] stat = computeStats(records);
            doc.add(new Paragraph("一、统计汇总", headFont));
            doc.add(new Paragraph(" "));
            PdfPTable statTable = new PdfPTable(2);
            statTable.setWidthPercentage(100);
            addRow(statTable, "记录总数", String.format("%d 条", records.size()), headFont, bodyFont);
            addRow(statTable, "平均值", String.format("%.1f mmol/L", stat[0]), headFont, bodyFont);
            addRow(statTable, "最高值", String.format("%.1f mmol/L", stat[1]), headFont, bodyFont);
            addRow(statTable, "最低值", String.format("%.1f mmol/L", stat[2]), headFont, bodyFont);
            addRow(statTable, "正常率", String.format("%.0f%%（%d/%d）", stat[3], (int) stat[4], records.size()), headFont, bodyFont);
            addRow(statTable, "空腹平均值", String.format("%.1f mmol/L", stat[5]), headFont, bodyFont);
            addRow(statTable, "餐后1h平均值", String.format("%.1f mmol/L", stat[6]), headFont, bodyFont);
            addRow(statTable, "餐后2h平均值", String.format("%.1f mmol/L", stat[7]), headFont, bodyFont);
            addRow(statTable, "餐后3h平均值", String.format("%.1f mmol/L", stat[8]), headFont, bodyFont);
            doc.add(statTable);
            doc.add(new Paragraph(" "));

            // 明细表
            doc.add(new Paragraph("二、血糖记录明细", headFont));
            doc.add(new Paragraph(" "));
            PdfPTable detail = new PdfPTable(6);
            detail.setWidthPercentage(100);
            float[] widths = {14, 14, 10, 12, 20, 12};
            detail.setWidths(widths);
            String[] heads = {"日期", "业务日", "时间", "餐别", "血糖值(mmol/L)", "是否正常"};
            for (String h : heads) {
                PdfPCell c = new PdfPCell(new Phrase(h, headFont));
                c.setBackgroundColor(new com.lowagie.text.pdf.RGBColor(0xD9, 0xF9, 0xE4));
                c.setHorizontalAlignment(Element.ALIGN_CENTER);
                c.setPadding(4);
                detail.addCell(c);
            }
            for (BloodSugarRecord r : records) {
                addCell(detail, r.getRecordTime() != null ? r.getRecordTime().format(DATE_FMT) : "", bodyFont);
                addCell(detail, PeriodClassifier.getBusinessDate(r.getRecordTime()) != null
                        ? PeriodClassifier.getBusinessDate(r.getRecordTime()).format(DATE_FMT) : "", bodyFont);
                addCell(detail, r.getRecordTime() != null ? r.getRecordTime().format(TIME_FMT) : "", bodyFont);
                addCell(detail, r.getMealPeriod() != null ? r.getMealPeriod() : "", bodyFont);
                addCell(detail, String.format("%.1f", r.getBloodSugar()), bodyFont);
                addCell(detail, PeriodClassifier.isNormal(r.getMealPeriod(), r.getBloodSugar()) ? "正常" : "异常", bodyFont);
            }
            doc.add(detail);
            doc.add(new Paragraph(" "));

            // 按业务日汇总
            doc.add(new Paragraph("三、按业务日汇总", headFont));
            doc.add(new Paragraph(" "));
            PdfPTable dayTable = new PdfPTable(6);
            dayTable.setWidthPercentage(100);
            dayTable.setWidths(new float[]{16, 10, 14, 14, 14, 12});
            String[] dayHeads = {"业务日", "记录数", "平均值", "最高值", "最低值", "正常率"};
            for (String h : dayHeads) {
                PdfPCell c = new PdfPCell(new Phrase(h, headFont));
                c.setBackgroundColor(new com.lowagie.text.pdf.RGBColor(0xD9, 0xF9, 0xE4));
                c.setHorizontalAlignment(Element.ALIGN_CENTER);
                c.setPadding(4);
                dayTable.addCell(c);
            }
            Map<LocalDate, List<BloodSugarRecord>> byDay = records.stream()
                    .collect(Collectors.groupingBy(
                            r -> PeriodClassifier.getBusinessDate(r.getRecordTime()),
                            TreeMap::new, Collectors.toList()));
            for (Map.Entry<LocalDate, List<BloodSugarRecord>> e : byDay.entrySet()) {
                double[] s = computeStats(e.getValue());
                addCell(dayTable, e.getKey() != null ? e.getKey().format(DATE_FMT) : "", bodyFont);
                addCell(dayTable, String.valueOf(e.getValue().size()), bodyFont);
                addCell(dayTable, String.format("%.1f", s[0]), bodyFont);
                addCell(dayTable, String.format("%.1f", s[1]), bodyFont);
                addCell(dayTable, String.format("%.1f", s[2]), bodyFont);
                addCell(dayTable, String.format("%.0f%%", s[3]), bodyFont);
            }
            doc.add(dayTable);
            doc.add(new Paragraph(" "));

            // 按餐别汇总
            doc.add(new Paragraph("四、按餐别汇总", headFont));
            doc.add(new Paragraph(" "));
            PdfPTable mealTable = new PdfPTable(6);
            mealTable.setWidthPercentage(100);
            mealTable.setWidths(new float[]{14, 10, 14, 14, 14, 12});
            String[] mealHeads = {"餐别", "记录数", "平均值", "最高值", "最低值", "正常率"};
            for (String h : mealHeads) {
                PdfPCell c = new PdfPCell(new Phrase(h, headFont));
                c.setBackgroundColor(new com.lowagie.text.pdf.RGBColor(0xD9, 0xF9, 0xE4));
                c.setHorizontalAlignment(Element.ALIGN_CENTER);
                c.setPadding(4);
                mealTable.addCell(c);
            }
            Map<String, List<BloodSugarRecord>> byMeal = new LinkedHashMap<>();
            for (String period : new String[]{"空腹", "餐后1h", "餐后2h", "餐后3h"}) {
                byMeal.put(period, new ArrayList<>());
            }
            for (BloodSugarRecord r : records) {
                String p = r.getMealPeriod() != null ? r.getMealPeriod() : "空腹";
                byMeal.computeIfAbsent(p, k -> new ArrayList<>()).add(r);
            }
            for (Map.Entry<String, List<BloodSugarRecord>> e : byMeal.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                double[] s = computeStats(e.getValue());
                addCell(mealTable, e.getKey(), bodyFont);
                addCell(mealTable, String.valueOf(e.getValue().size()), bodyFont);
                addCell(mealTable, String.format("%.1f", s[0]), bodyFont);
                addCell(mealTable, String.format("%.1f", s[1]), bodyFont);
                addCell(mealTable, String.format("%.1f", s[2]), bodyFont);
                addCell(mealTable, String.format("%.0f%%", s[3]), bodyFont);
            }
            doc.add(mealTable);
            doc.add(new Paragraph(" "));

            // 健康数据汇总
            doc.add(new Paragraph("五、健康数据汇总（近 30 天）", headFont));
            doc.add(new Paragraph(" "));
            List<BloodSugarRecord> healthRecords = new ArrayList<>();
            LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
            for (BloodSugarRecord r : records) {
                if (r.getRecordTime() != null && !r.getRecordTime().isBefore(cutoff)
                        && (r.getInsulin() > 0 || r.getCarbs() > 0 || r.getActivity() > 0
                        || r.getWeight() > 0 || r.getPulse() > 0
                        || (r.getBloodPressure() != null && !r.getBloodPressure().isBlank()))) {
                    healthRecords.add(r);
                }
            }
            if (healthRecords.isEmpty()) {
                doc.add(new Paragraph("近 30 天无健康数据记录。", bodyFont));
                doc.add(new Paragraph(" "));
            } else {
                PdfPTable healthTable = new PdfPTable(7);
                healthTable.setWidthPercentage(100);
                healthTable.setWidths(new float[]{12, 12, 12, 12, 12, 12, 14});
                String[] healthHeads = {"日期", "胰岛素(U)", "碳水(g)", "运动(分钟)", "体重(kg)", "脉搏(次/分)", "血压(mmHg)"};
                for (String h : healthHeads) {
                    PdfPCell c = new PdfPCell(new Phrase(h, headFont));
                    c.setBackgroundColor(new com.lowagie.text.pdf.RGBColor(0xFF, 0xF0, 0xE0));
                    c.setHorizontalAlignment(Element.ALIGN_CENTER);
                    c.setPadding(4);
                    healthTable.addCell(c);
                }
                for (BloodSugarRecord r : healthRecords) {
                    addCell(healthTable, r.getRecordTime() != null ? r.getRecordTime().format(DATE_FMT) : "", bodyFont);
                    addCell(healthTable, r.getInsulin() > 0 ? String.format("%.1f", r.getInsulin()) : "", bodyFont);
                    addCell(healthTable, r.getCarbs() > 0 ? String.format("%.0f", r.getCarbs()) : "", bodyFont);
                    addCell(healthTable, r.getActivity() > 0 ? String.format("%.0f", r.getActivity()) : "", bodyFont);
                    addCell(healthTable, r.getWeight() > 0 ? String.format("%.1f", r.getWeight()) : "", bodyFont);
                    addCell(healthTable, r.getPulse() > 0 ? String.format("%.0f", r.getPulse()) : "", bodyFont);
                    addCell(healthTable, r.getBloodPressure() != null ? r.getBloodPressure() : "", bodyFont);
                }
                doc.add(healthTable);
                doc.add(new Paragraph(" "));
            }

            Paragraph foot = new Paragraph("以上为程序自动生成，仅供参考，不能替代医生诊断。", subFont);
            foot.setAlignment(Element.ALIGN_CENTER);
            doc.add(foot);
            doc.close();
        }
    }

    private void addRow(PdfPTable table, String k, String v, Font headFont, Font bodyFont) {
        PdfPCell kc = new PdfPCell(new Phrase(k, bodyFont));
        kc.setPadding(4);
        kc.setBackgroundColor(new com.lowagie.text.pdf.RGBColor(0xEF, 0xFB, 0xF2));
        table.addCell(kc);
        table.addCell(new PdfPCell(new Phrase(v, bodyFont)));
    }

    private void addCell(PdfPTable table, String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setPadding(4);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(c);
    }

    /** 找到系统中文字体并嵌入，避免 PDF 中文乱码（供本类与 AGP 报告导出复用） */
    public static BaseFont loadChineseBaseFont() throws Exception {
        for (String candidate : FONT_CANDIDATES) {
            String path = candidate.contains(",") ? candidate.substring(0, candidate.indexOf(',')) : candidate;
            if (new File(path).exists()) {
                try {
                    return BaseFont.createFont(candidate, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                } catch (Exception ignored) {
                    // 该字体加载失败，尝试下一个
                }
            }
        }
        throw new java.io.IOException("未找到可用的中文字体文件（simhei/msyh 等）");
    }
}
