package com.bloodsugar.service;

import com.bloodsugar.model.AgpReport;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.RGBColor;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * AGP 标准化葡萄糖图谱 PDF 导出：核心指标表 + 24 小时分位曲线图 + 分时段/餐别统计表 + 口径说明。
 * 曲线使用 OpenPDF 矢量绘制（目标范围底色 + p25~p75 分位带 + 中位数折线），糖果风格配色。
 */
public class AgpPdfExporter {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // 糖果风配色（与界面一致）
    private static final RGBColor CANDY_GREEN = new RGBColor(0x2B, 0xB6, 0x73);
    private static final RGBColor CANDY_GREEN_LIGHT = new RGBColor(0xD9, 0xF9, 0xE4);
    private static final RGBColor CANDY_MINT = new RGBColor(0xEA, 0xFC, 0xF0);
    private static final RGBColor CANDY_PINK = new RGBColor(0xFF, 0x8F, 0xB3);
    private static final RGBColor CANDY_ORANGE = new RGBColor(0xFF, 0xA4, 0x5E);
    private static final RGBColor BAND_COLOR = new RGBColor(0x9F, 0xE0, 0xBD);
    private static final RGBColor TARGET_BG = new RGBColor(0xE4, 0xF9, 0xEC);
    private static final RGBColor GRID_COLOR = new RGBColor(0xDD, 0xDD, 0xDD);
    private static final RGBColor TEXT_GRAY = new RGBColor(0x6B, 0x6B, 0x6B);

    public void export(AgpReport report, String filePath) throws Exception {
        BaseFont bf = ExportService.loadChineseBaseFont();
        Font titleFont = new Font(bf, 17, Font.BOLD, CANDY_GREEN);
        Font subFont = new Font(bf, 10, Font.NORMAL, TEXT_GRAY);
        Font headFont = new Font(bf, 11, Font.BOLD, CANDY_GREEN);
        Font bodyFont = new Font(bf, 9.5f, Font.NORMAL);
        Font smallFont = new Font(bf, 8, Font.NORMAL, TEXT_GRAY);

        Document doc = new Document(PageSize.A4, 40, 40, 42, 42);
        try (OutputStream out = new FileOutputStream(filePath)) {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            doc.open();

            // ===== 标题 =====
            Paragraph title = new Paragraph("糖伴SugarPal · AGP 标准化葡萄糖图谱报告", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            doc.add(title);

            String range = (report.startDate != null ? report.startDate.format(DATE_FMT) : "-")
                    + " 至 " + (report.endDate != null ? report.endDate.format(DATE_FMT) : "-");
            Paragraph sub = new Paragraph("统计范围：" + range + "（近 " + report.rangeDays + " 天）　"
                    + "记录数：" + report.recordCount + " 条　覆盖天数：" + report.dayCount + " 天　"
                    + "生成时间：" + LocalDateTime.now().format(DATETIME_FMT), subFont);
            sub.setAlignment(Element.ALIGN_CENTER);
            doc.add(sub);
            doc.add(new Paragraph(" "));

            if (report.recordCount == 0) {
                doc.add(new Paragraph("所选范围内暂无血糖记录，无法生成 AGP 报告。", bodyFont));
                addFooter(doc, bf);
                doc.close();
                return;
            }

            // ===== 一、核心指标 =====
            doc.add(sectionTitle("一、核心指标", bf));
            PdfPTable metric = new PdfPTable(4);
            metric.setWidthPercentage(100);
            metric.setWidths(new float[]{1, 1, 1, 1});
            addMetricCard(metric, "平均血糖", String.format("%.2f mmol/L", report.mean), headFont, bodyFont);
            addMetricCard(metric, "标准差 SD", String.format("%.2f mmol/L", report.sd), headFont, bodyFont);
            addMetricCard(metric, "变异系数 CV", String.format("%.1f %%", report.cv), headFont, bodyFont);
            addMetricCard(metric, "估算 HbA1c", String.format("%.2f %%", report.hba1c), headFont, bodyFont);
            addMetricCard(metric, "TIR 达标占比", String.format("%.1f %%（%d 条）", report.tir, report.inRangeCount), headFont, bodyFont);
            addMetricCard(metric, "TAR 偏高占比", String.format("%.1f %%（%d 条）", report.tar, report.aboveCount), headFont, bodyFont);
            addMetricCard(metric, "TBR 偏低占比", String.format("%.1f %%（%d 条）", report.tbr, report.belowCount), headFont, bodyFont);
            addMetricCard(metric, "血糖波动区间", String.format("%.1f ~ %.1f", report.minValue, report.maxValue), headFont, bodyFont);
            doc.add(metric);
            doc.add(new Paragraph(" "));

            // ===== 二、AGP 曲线 =====
            doc.add(sectionTitle("二、AGP 24 小时分位曲线", bf));
            Paragraph chartHint = new Paragraph("深绿实线 = 中位数（P50）；浅绿区域 = 25%~75% 分位区间；浅绿底色为目标范围（3.9~8.9 mmol/L，上限依餐别浮动，图中取各餐别上限最大值）。", smallFont);
            doc.add(chartHint);
            doc.add(new Paragraph(" "));

            // 为曲线预留绘图区（宽 515pt，高 210pt）
            float chartW = 515f, chartH = 210f;
            float chartX = (PageSize.A4.getWidth() - 40 - 40 - chartW) / 2 + 40;
            PdfContentByte cb = writer.getDirectContent();
            Paragraph spacer = new Paragraph(" ");
            spacer.setSpacingAfter(chartH);
            doc.add(spacer);
            float chartY = writer.getVerticalPosition(false) + chartH - 8;
            drawAgpChart(cb, report, chartX, chartY, chartW, chartH, bf);

            // ===== 三、分时段统计 =====
            doc.add(sectionTitle("三、分时段统计", bf));
            PdfPTable seg = new PdfPTable(5);
            seg.setWidthPercentage(100);
            seg.setWidths(new float[]{22, 14, 20, 20, 24});
            addHeaderRow(seg, new String[]{"时段", "记录数", "平均(mmol/L)", "最高/最低", "达标率"}, headFont);
            for (AgpReport.GroupStat g : report.timeSegments) {
                addRow(seg, g.name, String.valueOf(g.count),
                        g.count == 0 ? "-" : String.format("%.2f", g.avg),
                        g.count == 0 ? "-" : String.format("%.1f / %.1f", g.max, g.min),
                        g.count == 0 ? "-" : String.format("%.1f %%", g.tirPercent), bodyFont);
            }
            doc.add(seg);
            doc.add(new Paragraph(" "));

            // ===== 四、按餐别统计 =====
            doc.add(sectionTitle("四、按餐别统计", bf));
            PdfPTable meal = new PdfPTable(5);
            meal.setWidthPercentage(100);
            meal.setWidths(new float[]{22, 14, 20, 20, 24});
            addHeaderRow(meal, new String[]{"餐别", "记录数", "平均(mmol/L)", "最高/最低", "达标率"}, headFont);
            for (AgpReport.GroupStat g : report.mealGroups) {
                addRow(meal, g.name, String.valueOf(g.count),
                        String.format("%.2f", g.avg),
                        String.format("%.1f / %.1f", g.max, g.min),
                        String.format("%.1f %%", g.tirPercent), bodyFont);
            }
            doc.add(meal);
            doc.add(new Paragraph(" "));

            // ===== 五、指标口径说明 =====
            doc.add(sectionTitle("五、指标口径说明", bf));
            List<String> notes = java.util.Arrays.asList(
                    "1. 统计范围为近 " + report.rangeDays + " 个业务日（业务日以凌晨 4 点为分界，0~4 点记录归前一天）。",
                    "2. 达标判定按每条记录所属时段的正常区间：空腹 3.9~6.1、餐后1h 3.9~8.9、餐后2h/3h 3.9~7.8 mmol/L；"
                            + "TIR 为目标范围内记录占比，TAR 为高于各自上限占比，TBR 为低于 3.9 mmol/L 占比。",
                    "3. 标准差采用样本标准差（n-1）；CV = SD ÷ 平均血糖 × 100%，一般建议控制在 36% 以内。",
                    "4. 估算 HbA1c 由本区间平均血糖换算：mmol/L × 18 → mg/dL，HbA1c% ≈ (mg/dL + 46.7) ÷ 28.7，"
                            + "与实验室检测存在偏差，仅供参考。",
                    "5. AGP 曲线以每个整点为箱中心、±30 分钟为取样窗口，取该窗口内记录的 25%/50%/75% 分位数；"
                            + "无记录的整点按相邻整点线性插值补齐，以保证曲线连续、便于与医师沟通。"
            );
            for (String line : notes) {
                Paragraph p = new Paragraph(line, smallFont);
                p.setLeading(13f);
                doc.add(p);
            }

            addFooter(doc, bf);
            doc.close();
        }
    }

    private Paragraph sectionTitle(String text, BaseFont bf) {
        Paragraph p = new Paragraph(text, new Font(bf, 12, Font.BOLD, CANDY_GREEN));
        p.setSpacingBefore(6);
        p.setSpacingAfter(6);
        return p;
    }

    private void addFooter(Document doc, BaseFont bf) throws Exception {
        doc.add(new Paragraph(" "));
        Paragraph foot = new Paragraph("本报告仅供健康管理参考，不作为医疗诊断依据。如有身体不适，请及时就医。",
                new Font(bf, 9, Font.BOLD, new RGBColor(0xC0, 0x39, 0x2B)));
        foot.setAlignment(Element.ALIGN_CENTER);
        doc.add(foot);
    }

    private void addMetricCard(PdfPTable table, String label, String value, Font headFont, Font bodyFont) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(6);
        cell.setBackgroundColor(CANDY_MINT);
        cell.setBorderColor(CANDY_GREEN_LIGHT);
        cell.setBorderWidth(1f);
        Paragraph l = new Paragraph(label, new Font(headFont.getBaseFont(), 8.5f, Font.NORMAL, TEXT_GRAY));
        l.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(l);
        Paragraph v = new Paragraph(value, new Font(headFont.getBaseFont(), 11, Font.BOLD, CANDY_GREEN));
        v.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(v);
        table.addCell(cell);
    }

    private void addHeaderRow(PdfPTable table, String[] heads, Font headFont) {
        for (String h : heads) {
            PdfPCell c = new PdfPCell(new Phrase(h, headFont));
            c.setBackgroundColor(CANDY_GREEN_LIGHT);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setPadding(5);
            table.addCell(c);
        }
    }

    private void addRow(PdfPTable table, String a, String b, String c, String d, String e, Font bodyFont) {
        for (String s : new String[]{a, b, c, d, e}) {
            PdfPCell cell = new PdfPCell(new Phrase(s, bodyFont));
            cell.setPadding(5);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }
    }

    /** 矢量绘制 AGP 曲线：坐标网格 + 目标范围底色 + 分位带 + 中位数折线 */
    private void drawAgpChart(PdfContentByte cb, AgpReport report,
                              float x, float y, float w, float h, BaseFont bf) {
        float left = x + 34, bottom = y + 20, right = x + w - 10, top = y + h - 8;
        float plotW = right - left, plotH = top - bottom;

        // Y 轴范围：至少覆盖 2~16 mmol/L，并包住数据
        double yMin = Math.min(2.0, Math.floor(report.minValue) - 1);
        double yMax = Math.max(12.0, Math.ceil(report.maxValue) + 1);

        // 目标范围底色（3.9 ~ 8.9）
        float yLow = mapY(report.targetLow, yMin, yMax, bottom, plotH);
        float yHigh = mapY(report.targetHighMax, yMin, yMax, bottom, plotH);
        cb.setColorFill(TARGET_BG);
        cb.rectangle(left, yLow, plotW, yHigh - yLow);
        cb.fill();

        // 网格与刻度
        cb.setLineWidth(0.4f);
        cb.setColorStroke(GRID_COLOR);
        for (double v = Math.ceil(yMin / 2) * 2; v <= yMax; v += 2) {
            float yy = mapY(v, yMin, yMax, bottom, plotH);
            cb.moveTo(left, yy);
            cb.lineTo(right, yy);
        }
        for (int hh = 0; hh <= 24; hh += 3) {
            float xx = left + plotW * hh / 24f;
            cb.moveTo(xx, bottom);
            cb.lineTo(xx, top);
        }
        cb.stroke();

        // 坐标轴
        cb.setLineWidth(0.8f);
        cb.setColorStroke(new RGBColor(0x99, 0x99, 0x99));
        cb.moveTo(left, bottom);
        cb.lineTo(right, bottom);
        cb.moveTo(left, bottom);
        cb.lineTo(left, top);
        cb.stroke();

        // Y 轴刻度文字
        cb.setColorFill(TEXT_GRAY);
        for (double v = Math.ceil(yMin / 2) * 2; v <= yMax; v += 2) {
            float yy = mapY(v, yMin, yMax, bottom, plotH);
            showText(cb, bf, 7, String.format("%.0f", v), left - 18, yy - 2.5f);
        }
        // X 轴刻度文字
        for (int hh = 0; hh <= 24; hh += 3) {
            float xx = left + plotW * hh / 24f;
            showText(cb, bf, 7, String.format("%02d:00", hh), xx - 11, bottom - 12);
        }

        // 中位数 + 分位带（仅绘制有数据的整点）
        List<AgpReport.CurvePoint> pts = report.curve;
        if (pts == null || pts.isEmpty()) return;

        // 分位带：p75 正向 + p25 反向闭合填充
        cb.saveState();
        cb.setColorFill(BAND_COLOR);
        com.lowagie.text.pdf.PdfGState gs = new com.lowagie.text.pdf.PdfGState();
        gs.setFillOpacity(0.55f);
        cb.setGState(gs);
        cb.moveTo(mapX(pts.get(0).hour, left, plotW), mapY(pts.get(0).p75, yMin, yMax, bottom, plotH));
        for (AgpReport.CurvePoint p : pts) {
            cb.lineTo(mapX(p.hour, left, plotW), mapY(p.p75, yMin, yMax, bottom, plotH));
        }
        for (int i = pts.size() - 1; i >= 0; i--) {
            AgpReport.CurvePoint p = pts.get(i);
            cb.lineTo(mapX(p.hour, left, plotW), mapY(p.p25, yMin, yMax, bottom, plotH));
        }
        cb.closePath();
        cb.fill();
        cb.restoreState();

        // 中位数折线
        cb.setLineWidth(2f);
        cb.setColorStroke(CANDY_GREEN);
        boolean first = true;
        for (AgpReport.CurvePoint p : pts) {
            float px = mapX(p.hour, left, plotW);
            float py = mapY(p.p50, yMin, yMax, bottom, plotH);
            if (first) {
                cb.moveTo(px, py);
                first = false;
            } else {
                cb.lineTo(px, py);
            }
        }
        cb.stroke();

        // 数据点
        cb.setColorFill(CANDY_GREEN);
        for (AgpReport.CurvePoint p : pts) {
            float px = mapX(p.hour, left, plotW);
            float py = mapY(p.p50, yMin, yMax, bottom, plotH);
            cb.circle(px, py, 2.2f);
            cb.fill();
        }

        // 图例
        cb.setColorFill(CANDY_GREEN);
        cb.rectangle(left + plotW - 150, top + 2, 14, 6);
        cb.fill();
        showText(cb, bf, 7.5f, "中位数 P50", left + plotW - 132, top - 0.5f);
        cb.setColorFill(BAND_COLOR);
        cb.rectangle(left + plotW - 150, top - 12, 14, 6);
        cb.fill();
        showText(cb, bf, 7.5f, "25%~75% 分位带", left + plotW - 132, top - 14.5f);
        cb.setColorFill(TARGET_BG);
        cb.rectangle(left + plotW - 150, top - 26, 14, 6);
        cb.fill();
        showText(cb, bf, 7.5f, "目标范围 3.9~8.9", left + plotW - 132, top - 28.5f);
    }

    private float mapX(int hour, float left, float plotW) {
        return left + plotW * hour / 24f;
    }

    private float mapY(double v, double yMin, double yMax, float bottom, float plotH) {
        double ratio = (v - yMin) / (yMax - yMin);
        if (ratio < 0) ratio = 0;
        if (ratio > 1) ratio = 1;
        return (float) (bottom + plotH * ratio);
    }

    private void showText(PdfContentByte cb, BaseFont bf, float size, String text, float x, float y) {
        cb.beginText();
        cb.setFontAndSize(bf, size);
        cb.setColorFill(TEXT_GRAY);
        cb.setTextMatrix(x, y);
        cb.showText(text);
        cb.endText();
    }
}
