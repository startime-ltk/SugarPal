package com.bloodsugar.ui;

import com.bloodsugar.model.AgpReport;
import com.bloodsugar.service.AgpPdfExporter;
import com.bloodsugar.service.AgpReportService;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * AGP 标准化葡萄糖图谱对话框：选择统计范围 → 生成核心指标
 * （平均血糖 / SD / CV / 估算 HbA1c / TIR·TAR·TBR）+ 24 小时分位曲线
 * + 分时段 / 按餐别统计 → 可导出 PDF 打印。
 */
public final class AgpReportDialog {

    private final Stage owner;
    private final AgpReportService agpService;

    public AgpReportDialog(Stage owner, AgpReportService agpService) {
        this.owner = owner;
        this.agpService = agpService;
    }

    /** 打开对话框（模态） */
    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("AGP 标准化葡萄糖图谱");
        dialog.setHeaderText("Ambulatory Glucose Profile · 葡萄糖图谱与达标分析（可导出 PDF 打印）");

        Label rangeLb = new Label("统计范围：");
        rangeLb.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 13));
        rangeLb.setTextFill(Color.web(DialogSupport.COLOR_TITLE));

        ComboBox<String> rangeCombo = new ComboBox<>(FXCollections.observableArrayList("近 7 天", "近 14 天", "近 30 天"));
        rangeCombo.setValue("近 14 天");
        rangeCombo.setStyle("-fx-font-size: 13px;");

        Button genBtn = DialogSupport.styledButton("生成图谱", DialogSupport.COLOR_BLUE_LIGHT,
                DialogSupport.COLOR_BLUE_DARK, "#B5E7FF", DialogSupport.COLOR_BLUE_LIGHT, "#1899E0", "#7FD3FF");
        Button pdfBtn = DialogSupport.styledButton("导出 PDF", DialogSupport.COLOR_GREEN_LIGHT,
                DialogSupport.COLOR_GREEN_DARK, "#A9F0C4", DialogSupport.COLOR_GREEN_LIGHT, "#1E9E50", "#7DE3A4");
        pdfBtn.setDisable(true);

        HBox topRow = new HBox(10, rangeLb, rangeCombo, genBtn, pdfBtn);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label metricLb = new Label("选择统计范围后点击「生成图谱」，将按业务日（凌晨 4 点边界）汇总近 N 天数据。");
        metricLb.setWrapText(true);
        metricLb.setFont(Font.font("Microsoft YaHei", 12.5));
        metricLb.setTextFill(Color.web(DialogSupport.COLOR_TEXT));
        metricLb.setPadding(new Insets(10, 12, 10, 12));
        metricLb.setMaxWidth(Double.MAX_VALUE);
        metricLb.setStyle("-fx-background-color: linear-gradient(to right, #FFF6E9, #EAFCF0); "
                + "-fx-background-radius: 14; -fx-border-color: " + DialogSupport.COLOR_BORDER + "; -fx-border-radius: 14;");

        AreaChart<String, Number> agpChart = new AreaChart<>(new CategoryAxis(), new NumberAxis());
        agpChart.setAnimated(false);
        agpChart.setCreateSymbols(false);
        agpChart.setLegendVisible(true);
        agpChart.setPrefHeight(300);
        agpChart.setMinHeight(260);
        agpChart.setStyle("-fx-background-color: transparent; -fx-font-size: 11px;");

        TableView<AgpReport.GroupStat> segTable = buildGroupStatTable();
        TableView<AgpReport.GroupStat> mealTable = buildGroupStatTable();
        segTable.setPrefHeight(170);
        mealTable.setPrefHeight(170);
        VBox segBox = DialogSupport.titledBox("分时段统计（0-6 / 6-12 / 12-18 / 18-24）", segTable);
        VBox mealBox = DialogSupport.titledBox("按餐别统计", mealTable);
        HBox tableRow = new HBox(12, segBox, mealBox);
        HBox.setHgrow(segBox, Priority.ALWAYS);
        HBox.setHgrow(mealBox, Priority.ALWAYS);

        Label footLb = new Label("本报告仅供健康管理参考，不作为医疗诊断依据。");
        footLb.setFont(Font.font("Microsoft YaHei", 11));
        footLb.setTextFill(Color.web("#8A8578"));

        final AgpReport[] holder = new AgpReport[1];

        genBtn.setOnAction(e -> {
            String v = rangeCombo.getValue() == null ? "" : rangeCombo.getValue();
            int days = v.contains("7") ? 7 : v.contains("30") ? 30 : 14;
            try {
                AgpReport report = agpService.build(days);
                holder[0] = report;
                if (report.recordCount == 0) {
                    metricLb.setText("所选范围内暂无血糖记录，请先添加记录或通过「导入数据」批量导入。");
                    agpChart.getData().clear();
                    segTable.setItems(FXCollections.observableArrayList());
                    mealTable.setItems(FXCollections.observableArrayList());
                    pdfBtn.setDisable(true);
                    return;
                }
                metricLb.setText(agpMetricText(report));
                renderAgpChart(agpChart, report);
                segTable.setItems(FXCollections.observableArrayList(report.timeSegments));
                mealTable.setItems(FXCollections.observableArrayList(report.mealGroups));
                pdfBtn.setDisable(false);
            } catch (SQLException ex) {
                DialogSupport.showAlert(owner, "生成 AGP 报告失败：" + ex.getMessage());
            }
        });

        pdfBtn.setOnAction(e -> {
            AgpReport report = holder[0];
            if (report == null || report.recordCount == 0) {
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("导出 AGP 报告 PDF");
            String stamp = LocalDateTime.now().format(DialogFormats.FILE_STAMP_FMT);
            chooser.setInitialFileName("糖伴SugarPal-AGP葡萄糖图谱_" + stamp + ".pdf");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF 文件 (*.pdf)", "*.pdf"));
            File target = chooser.showSaveDialog(owner);
            if (target == null) {
                return;
            }
            pdfBtn.setDisable(true);
            pdfBtn.setText("正在导出…");
            new Thread(() -> {
                try {
                    new AgpPdfExporter().export(report, target.getAbsolutePath());
                    Platform.runLater(() -> {
                        Alert ok = new Alert(Alert.AlertType.INFORMATION);
                        ok.initOwner(owner);
                        ok.setTitle("导出成功");
                        ok.setHeaderText("AGP 报告已导出");
                        ok.setContentText("文件已保存到：\n" + target.getAbsolutePath()
                                + "\n\n可直接打开打印，页脚已注明「本报告仅供健康管理参考，不作为医疗诊断依据」。");
                        DialogSupport.styleDialogPane(ok.getDialogPane());
                        ok.showAndWait();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> DialogSupport.showAlert(owner, "导出失败：" + ex.getMessage()));
                } finally {
                    Platform.runLater(() -> {
                        pdfBtn.setDisable(false);
                        pdfBtn.setText("导出 PDF");
                    });
                }
            }).start();
        });

        VBox box = new VBox(10, topRow, metricLb, agpChart, tableRow, footLb);
        box.setPadding(new Insets(12));
        box.setPrefWidth(900);

        dialog.getDialogPane().setContent(box);
        DialogSupport.styleDialogPane(dialog.getDialogPane());
        DialogSupport.addPinkCloseButton(dialog.getDialogPane());
        dialog.showAndWait();
    }

    // ---------- 报告内容 ----------

    /** AGP 核心指标文本 */
    private String agpMetricText(AgpReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("平均血糖 %.1f mmol/L　标准差 SD %.2f　变异系数 CV %.1f%%　估算 HbA1c %.1f%%",
                r.mean, r.sd, r.cv, r.hba1c));
        sb.append(String.format("%nTIR 达标 %.1f%%　TAR 偏高 %.1f%%　TBR 偏低 %.1f%%（按各自时段正常区间逐条判定）",
                r.tir, r.tar, r.tbr));
        sb.append(String.format("%n记录 %d 条　覆盖 %d 个业务日　范围 %s ~ %s　最低 %.1f / 最高 %.1f mmol/L",
                r.recordCount, r.dayCount, r.startDate, r.endDate, r.minValue, r.maxValue));
        return sb.toString();
    }

    /** 绘制 AGP 24 小时分位曲线：25%~75% 分位带 + 中位数 + 目标上下限参考线 */
    private void renderAgpChart(AreaChart<String, Number> chart, AgpReport report) {
        chart.getData().clear();

        double top = Math.max(report.targetHighMax, report.maxValue);
        double yMax = Math.ceil(top + 1.0);
        if (yMax < 10) {
            yMax = 10;
        }
        NumberAxis yA = (NumberAxis) chart.getYAxis();
        yA.setAutoRanging(false);
        yA.setLowerBound(0);
        yA.setUpperBound(yMax);
        yA.setTickUnit(yMax <= 12 ? 2 : Math.ceil(yMax / 6));
        yA.setLabel("mmol/L");
        CategoryAxis xA = (CategoryAxis) chart.getXAxis();
        xA.setLabel("时间（小时）");

        String[] labels = new String[24];
        for (int i = 0; i < 24; i++) {
            labels[i] = String.format("%02d:00", i);
        }

        double[] p25 = new double[24], p50 = new double[24], p75 = new double[24];
        Arrays.fill(p25, Double.NaN);
        Arrays.fill(p50, Double.NaN);
        Arrays.fill(p75, Double.NaN);
        for (AgpReport.CurvePoint p : report.curve) {
            if (p.hour >= 0 && p.hour < 24) {
                p25[p.hour] = p.p25;
                p50[p.hour] = p.p50;
                p75[p.hour] = p.p75;
            }
        }
        interpolateHours(p25);
        interpolateHours(p50);
        interpolateHours(p75);

        XYChart.Series<String, Number> band = new XYChart.Series<>();
        band.setName("25%~75% 分位带");
        XYChart.Series<String, Number> bandBase = new XYChart.Series<>();
        bandBase.setName(" ");
        XYChart.Series<String, Number> median = new XYChart.Series<>();
        median.setName("中位数 P50");
        XYChart.Series<String, Number> up = new XYChart.Series<>();
        up.setName(String.format("目标上限 %.1f", report.targetHighMax));
        XYChart.Series<String, Number> low = new XYChart.Series<>();
        low.setName(String.format("目标下限 %.1f", report.targetLow));

        for (int i = 0; i < 24; i++) {
            band.getData().add(new XYChart.Data<>(labels[i], p75[i]));
            bandBase.getData().add(new XYChart.Data<>(labels[i], p25[i]));
            median.getData().add(new XYChart.Data<>(labels[i], p50[i]));
            up.getData().add(new XYChart.Data<>(labels[i], report.targetHighMax));
            low.getData().add(new XYChart.Data<>(labels[i], report.targetLow));
        }
        // 顺序即层级：先画 P75 色带，再用与绘图区同色的 P25 遮盖出下沿，从而形成 25%~75% 色带
        chart.getData().addAll(band, bandBase, median, up, low);

        Platform.runLater(() -> {
            Node bg = chart.lookup(".chart-plot-background");
            if (bg != null) {
                bg.setStyle("-fx-background-color: #FFFDF6;");
            }
        });
        DialogSupport.applyWhenSeriesNodeReady(band, n -> styleAreaSeriesNode(n, "rgba(59, 201, 110, 0.42)", null, 0));
        DialogSupport.applyWhenSeriesNodeReady(bandBase, n -> styleAreaSeriesNode(n, "#FFFDF6", null, 0));
        DialogSupport.applyWhenSeriesNodeReady(median, n -> styleAreaSeriesNode(n, "rgba(0, 0, 0, 0)", "#12A35C", 2.4));
        DialogSupport.applyWhenSeriesNodeReady(up, n -> styleAreaSeriesNode(n, "rgba(0, 0, 0, 0)", "#FF8A65", 1.4));
        DialogSupport.applyWhenSeriesNodeReady(low, n -> styleAreaSeriesNode(n, "rgba(0, 0, 0, 0)", "#3FB8FF", 1.4));
    }

    /** AreaChart 系列样式：填充色 + 线条色/线宽（stroke 为 null 时不动线条） */
    private void styleAreaSeriesNode(Node seriesNode, String fill, String stroke, double width) {
        Node f = seriesNode.lookup(".chart-series-area-fill");
        Node l = seriesNode.lookup(".chart-series-area-line");
        if (f != null) {
            f.setStyle("-fx-fill: " + fill + ";");
        }
        if (l != null && stroke != null) {
            l.setStyle("-fx-stroke: " + stroke + "; -fx-stroke-width: " + width + ";");
        }
    }

    /** 无数据的整点用相邻有效值线性插值补齐，避免曲线断裂（AGP 标准做法） */
    private void interpolateHours(double[] arr) {
        int n = arr.length, first = -1, last = -1;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(arr[i])) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return;
        }
        for (int i = n - 1; i >= 0; i--) {
            if (!Double.isNaN(arr[i])) {
                last = i;
                break;
            }
        }
        for (int i = 0; i < first; i++) {
            arr[i] = arr[first];
        }
        for (int i = last + 1; i < n; i++) {
            arr[i] = arr[last];
        }
        int prev = first;
        for (int i = first + 1; i <= last; i++) {
            if (Double.isNaN(arr[i])) {
                continue;
            }
            if (i - prev > 1) {
                double step = (arr[i] - arr[prev]) / (i - prev);
                for (int k = prev + 1; k < i; k++) {
                    arr[k] = arr[prev] + step * (k - prev);
                }
            }
            prev = i;
        }
    }

    /** 分组统计表（分时段 / 按餐别两张表共用同一结构） */
    private TableView<AgpReport.GroupStat> buildGroupStatTable() {
        TableView<AgpReport.GroupStat> t = DialogSupport.statTable();
        t.getColumns().add(DialogSupport.textColumn("分组", 130, g -> g.name));
        t.getColumns().add(DialogSupport.textColumn("条数", 60, g -> String.valueOf(g.count)));
        t.getColumns().add(DialogSupport.textColumn("平均", 70,
                g -> g.count == 0 ? "-" : String.format("%.1f", g.avg)));
        t.getColumns().add(DialogSupport.textColumn("最高", 70,
                g -> g.count == 0 ? "-" : String.format("%.1f", g.max)));
        t.getColumns().add(DialogSupport.textColumn("最低", 70,
                g -> g.count == 0 ? "-" : String.format("%.1f", g.min)));
        t.getColumns().add(DialogSupport.textColumn("达标率", 80,
                g -> g.count == 0 ? "-" : String.format("%.0f%%", g.tirPercent)));
        return t;
    }
}
