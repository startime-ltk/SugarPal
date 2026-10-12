package com.bloodsugar.service;

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

import java.awt.Color;
import java.io.FileOutputStream;

/**
 * P0-2 健康摘要 PDF 导出（PC 端）。
 *
 * 复用 AGP 报告的中文字体加载与糖果配色风格，生成一页可读性强的「健康摘要」：
 * 指标卡片（均值 / TIR / CV / 估算 HbA1c / 高低血糖报警次数等）+ 趋势说明，
 * 与完整 AGP 报告版式区分，便于通过邮件 / 微信等渠道分享给家人。
 */
public class HealthSummaryPdfExporter {

    private static final Color CANDY_GREEN = new Color(0x2B, 0xB6, 0x73);
    private static final Color CANDY_GREEN_DARK = new Color(0x12, 0xA3, 0x5C);
    private static final Color CARD_BG = new Color(0xF5, 0xFC, 0xF8);
    private static final Color CARD_BORDER = new Color(0xB8, 0xEE, 0xD0);
    private static final Color NOTE_BG = new Color(0xEA, 0xFC, 0xF0);
    private static final Color TEXT_DARK = new Color(0x3A, 0x4A, 0x42);
    private static final Color TEXT_GRAY = new Color(0x6B, 0x6B, 0x6B);

    private HealthSummaryPdfExporter() {
    }

    /** 生成健康摘要 PDF，返回文件绝对路径 */
    public static String export(HealthSummaryService.Summary s, String filePath) throws Exception {
        Document doc = new Document(PageSize.A4, 40, 40, 42, 42);
        PdfWriter.getInstance(doc, new FileOutputStream(filePath));
        doc.open();

        BaseFont bf = ExportService.loadChineseBaseFont();
        Font titleFont = new Font(bf, 17, Font.BOLD, CANDY_GREEN);
        Font subFont = new Font(bf, 9, Font.NORMAL, TEXT_GRAY);
        Font bodyFont = new Font(bf, 10, Font.NORMAL, TEXT_DARK);
        Font cardLabelFont = new Font(bf, 9, Font.NORMAL, TEXT_GRAY);
        Font cardValueFont = new Font(bf, 11, Font.BOLD, CANDY_GREEN_DARK);

        Paragraph title = new Paragraph("糖伴SugarPal · 健康摘要", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        doc.add(title);

        Paragraph sub = new Paragraph(
                String.format("统计区间 %s ~ %s（近 %d 天） · 数据仅保存在本地，不联网上传",
                        s.startDate, s.endDate, s.rangeDays),
                subFont);
        sub.setAlignment(Element.ALIGN_CENTER);
        doc.add(sub);
        doc.add(new Paragraph(" "));

        if (!s.hasData()) {
            doc.add(new Paragraph("该时间范围内暂无血糖记录，无法生成健康摘要。", bodyFont));
            doc.close();
            return filePath;
        }

        // ===== 指标卡片（2 行 × 4 列） =====
        PdfPTable cards = new PdfPTable(4);
        cards.setWidthPercentage(100);
        cards.setSpacingBefore(8);
        cards.setSpacingAfter(12);
        addCard(cards, "平均血糖", String.format("%.1f mmol/L", s.mean), cardLabelFont, cardValueFont);
        addCard(cards, "目标范围时间 TIR", String.format("%.1f%%", s.tir), cardLabelFont, cardValueFont);
        addCard(cards, "血糖波动 CV", String.format("%.1f%%", s.cv), cardLabelFont, cardValueFont);
        addCard(cards, "估算糖化 HbA1c", String.format("%.1f%%", s.hba1c), cardLabelFont, cardValueFont);
        addCard(cards, "最低 / 最高", String.format("%.1f / %.1f", s.minValue, s.maxValue), cardLabelFont, cardValueFont);
        addCard(cards, "记录次数", String.format("%d 次 / %d 天", s.recordCount, s.dayCount), cardLabelFont, cardValueFont);
        addCard(cards, "高血糖报警", s.highCount + " 次", cardLabelFont, cardValueFont);
        addCard(cards, "低血糖报警", s.lowCount + " 次", cardLabelFont, cardValueFont);
        doc.add(cards);

        // ===== 趋势说明 =====
        PdfPTable noteTable = new PdfPTable(1);
        noteTable.setWidthPercentage(100);
        PdfPCell noteCell = new PdfPCell(new Phrase(s.trendNote, bodyFont));
        noteCell.setBackgroundColor(NOTE_BG);
        noteCell.setBorderColor(CARD_BORDER);
        noteCell.setPadding(12);
        noteTable.addCell(noteCell);
        doc.add(noteTable);

        doc.add(new Paragraph(" "));
        Paragraph foot = new Paragraph("本摘要由糖伴SugarPal基于本地血糖记录生成，仅供健康参考，不构成医疗建议。",
                subFont);
        foot.setAlignment(Element.ALIGN_CENTER);
        doc.add(foot);

        doc.close();
        return filePath;
    }

    private static void addCard(PdfPTable table, String label, String value,
            Font labelFont, Font valueFont) {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(CARD_BORDER);
        cell.setBackgroundColor(CARD_BG);
        cell.setPadding(10);
        Paragraph lp = new Paragraph(label, labelFont);
        lp.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(lp);
        Paragraph vp = new Paragraph(value, valueFont);
        vp.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(vp);
        table.addCell(cell);
    }
}
