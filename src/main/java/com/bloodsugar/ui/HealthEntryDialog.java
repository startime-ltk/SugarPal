package com.bloodsugar.ui;

import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.service.BloodSugarService;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

/**
 * 多维健康录入对话框：胰岛素 / 碳水 / 运动 / 体重 / 脉搏 / 血压可分别保存
 * （留空保持原值，保存对象为最新一条血糖记录），
 * 下方 TabPane 按维度展示各健康指标随日期的趋势曲线。
 */
public final class HealthEntryDialog {

    private static final String[] DIMENSION_NAMES = {"胰岛素", "碳水", "运动", "体重", "脉搏", "血压"};
    private static final String[] DIMENSION_HINTS = {"胰岛素 (U)", "碳水 (g)", "运动 (分钟)",
            "体重 (kg)", "脉搏 (次/分)", "血压 120/80"};

    private final Stage owner;
    private final BloodSugarService service;
    private final Runnable onDataChanged;
    private final Consumer<String> statusSink;

    public HealthEntryDialog(Stage owner, BloodSugarService service,
                             Runnable onDataChanged, Consumer<String> statusSink) {
        this.owner = owner;
        this.service = service;
        this.onDataChanged = onDataChanged;
        this.statusSink = statusSink;
    }

    /** 打开对话框（模态） */
    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("多维健康录入");
        dialog.setHeaderText("分别记录健康数据，留空的维度不会覆盖原值");

        VBox root = new VBox(10);
        root.setPadding(new Insets(12));

        Label tip = new Label("填写后点该项右侧「保存」可单独保存，或点底部「一键保存已填项」；下方按日期展示各维度趋势曲线");
        tip.setFont(Font.font(11));
        tip.setTextFill(Color.web("#8A8578"));
        tip.setWrapText(true);

        TextField[] hFields = new TextField[DIMENSION_NAMES.length];
        for (int i = 0; i < DIMENSION_NAMES.length; i++) {
            hFields[i] = DialogSupport.createHealthField(DIMENSION_HINTS[i]);
        }

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        for (int i = 0; i < DIMENSION_NAMES.length; i++) {
            Label lb = new Label(DIMENSION_NAMES[i]);
            lb.setTextFill(Color.web(DialogSupport.COLOR_BLUE_DARK));
            lb.setMinWidth(44);
            final int idx = i;
            Button singleBtn = new Button("保存");
            singleBtn.setStyle("-fx-background-color: linear-gradient(to bottom, #A9E4FF, #3FB8FF); -fx-text-fill: white; "
                    + "-fx-font-size: 11px; -fx-padding: 4 12; -fx-background-radius: 14; -fx-border-radius: 14; "
                    + "-fx-border-color: #1E9FE8; -fx-border-width: 0 0 3 0; -fx-cursor: hand; -fx-font-weight: bold;");
            singleBtn.setOnAction(e -> saveSingle(hFields[idx], DIMENSION_NAMES[idx]));
            grid.add(lb, 0, i);
            grid.add(hFields[i], 1, i);
            grid.add(singleBtn, 2, i);
        }

        Button saveAllBtn = DialogSupport.styledButton("一键保存已填项", DialogSupport.COLOR_BLUE_LIGHT,
                DialogSupport.COLOR_BLUE_DARK, "#A9E4FF", DialogSupport.COLOR_BLUE_LIGHT, "#1E9FE8", "#7FD3FF");
        saveAllBtn.setStyle(saveAllBtn.getStyle() + " -fx-font-size: 13px; -fx-padding: 10 20;");
        saveAllBtn.setOnAction(e -> saveAll(hFields));

        Label trendTitle = DialogSupport.artLabel("各维度趋势曲线（按日期）", 13);

        root.getChildren().addAll(tip, grid, saveAllBtn,
                new Separator(), trendTitle, buildHealthTrendTabs());
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().setPrefSize(620, 620);

        DialogSupport.styleDialogPane(dialog.getDialogPane());
        DialogSupport.addPinkCloseButton(dialog.getDialogPane());
        dialog.showAndWait();
    }

    /** 分别保存：单独保存某一项健康数据到最新一条血糖记录 */
    private void saveSingle(TextField field, String name) {
        try {
            List<BloodSugarRecord> all = service.getAllRecords();
            if (all.isEmpty()) {
                DialogSupport.showAlert(owner, "暂无血糖记录，请先添加一条血糖记录后再保存健康数据");
                return;
            }
            String text = field.getText().trim();
            if (text.isEmpty()) {
                statusSink.accept("请先填写" + name + "的值");
                return;
            }
            BloodSugarRecord rec = all.get(0); // findAll 按时间倒序，第一条即最新
            if ("血压".equals(name)) {
                if (!text.matches("\\d{2,3}/\\d{2,3}")) {
                    DialogSupport.showAlert(owner, "血压格式应为 收缩压/舒张压，例如 120/80");
                    return;
                }
                rec.setBloodPressure(text);
            } else {
                double v = Double.parseDouble(text);
                if (v < 0) {
                    throw new NumberFormatException(name + "不能为负数");
                }
                switch (name) {
                    case "胰岛素": rec.setInsulin(v); break;
                    case "碳水": rec.setCarbs(v); break;
                    case "运动": rec.setActivity(v); break;
                    case "体重": rec.setWeight(v); break;
                    case "脉搏": rec.setPulse(v); break;
                    default: return;
                }
            }
            service.updateRecord(rec);
            field.clear();
            onDataChanged.run();
            statusSink.accept("已保存" + name + "到最新记录 " + rec.getRecordTime().format(DialogFormats.TIME_FMT));
        } catch (SQLException ex) {
            DialogSupport.showAlert(owner, "保存健康数据失败：" + ex.getMessage());
        } catch (NumberFormatException ex) {
            DialogSupport.showAlert(owner, "数字格式有误：" + ex.getMessage());
        }
    }

    /** 一键保存：保存对话框内所有非空字段，留空保持原值 */
    private void saveAll(TextField[] fields) {
        try {
            List<BloodSugarRecord> all = service.getAllRecords();
            if (all.isEmpty()) {
                DialogSupport.showAlert(owner, "暂无血糖记录，请先添加一条血糖记录后再保存健康数据");
                return;
            }
            BloodSugarRecord rec = all.get(0);
            boolean any = false;

            Double insulin = parseHealthNumber(fields[0]);
            if (insulin != null) { rec.setInsulin(insulin); any = true; }
            Double carbs = parseHealthNumber(fields[1]);
            if (carbs != null) { rec.setCarbs(carbs); any = true; }
            Double activity = parseHealthNumber(fields[2]);
            if (activity != null) { rec.setActivity(activity); any = true; }
            Double weight = parseHealthNumber(fields[3]);
            if (weight != null) { rec.setWeight(weight); any = true; }
            Double pulse = parseHealthNumber(fields[4]);
            if (pulse != null) { rec.setPulse(pulse); any = true; }
            String bp = fields[5].getText().trim();
            if (!bp.isEmpty()) {
                if (!bp.matches("\\d{2,3}/\\d{2,3}")) {
                    DialogSupport.showAlert(owner, "血压格式应为 收缩压/舒张压，例如 120/80");
                    return;
                }
                rec.setBloodPressure(bp);
                any = true;
            }

            if (!any) {
                statusSink.accept("请至少填写一项健康数据");
                return;
            }
            service.updateRecord(rec);
            for (TextField f : fields) {
                f.clear();
            }
            onDataChanged.run();
            statusSink.accept("已保存健康数据到最新记录 " + rec.getRecordTime().format(DialogFormats.TIME_FMT));
        } catch (SQLException ex) {
            DialogSupport.showAlert(owner, "保存健康数据失败：" + ex.getMessage());
        } catch (NumberFormatException ex) {
            DialogSupport.showAlert(owner, "数字字段格式有误，请检查填写内容");
        }
    }

    /** 多维健康趋势曲线：TabPane 按维度切换，按日期展示各维度数值变化 */
    private TabPane buildHealthTrendTabs() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefHeight(300);
        tabs.setStyle("-fx-background-color: #FDFEFB; -fx-background-radius: 14; -fx-border-radius: 14; "
                + "-fx-border-color: " + DialogSupport.COLOR_BORDER + ";");

        List<BloodSugarRecord> records;
        try {
            records = service.getAllRecords();
        } catch (SQLException e) {
            records = new ArrayList<>();
        }
        records.sort(Comparator.comparing(BloodSugarRecord::getRecordTime,
                Comparator.nullsLast(Comparator.naturalOrder())));

        tabs.getTabs().addAll(
                buildDimensionTab("血糖", "mmol/L", records, BloodSugarRecord::getBloodSugar, true),
                buildDimensionTab("胰岛素", "U", records, BloodSugarRecord::getInsulin, false),
                buildDimensionTab("碳水", "g", records, BloodSugarRecord::getCarbs, false),
                buildDimensionTab("运动", "分钟", records, BloodSugarRecord::getActivity, false),
                buildDimensionTab("体重", "kg", records, BloodSugarRecord::getWeight, false),
                buildDimensionTab("脉搏", "次/分", records, BloodSugarRecord::getPulse, false),
                buildDimensionTab("血压(收缩压)", "mmHg", records, r -> parseSystolic(r.getBloodPressure()), false));
        return tabs;
    }

    private Tab buildDimensionTab(String name, String unit, List<BloodSugarRecord> records,
                                  ToDoubleFunction<BloodSugarRecord> extractor, boolean always) {
        Tab tab = new Tab(name);
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("日期");
        NumberAxis yAxis = new NumberAxis(name + " (" + unit + ")", 0, 10, 1);
        LineChart<String, Number> line = new LineChart<>(xAxis, yAxis);
        line.setAnimated(false);
        line.setLegendVisible(false);
        line.setCreateSymbols(true);
        line.setPrefHeight(260);
        line.setStyle("-fx-background-color: #FDFEFB; -fx-background-radius: 10; "
                + "-fx-border-color: " + DialogSupport.COLOR_BORDER + "; -fx-border-radius: 10; -fx-border-width: 1;");
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName(name);
        boolean hasAny = false;
        for (BloodSugarRecord r : records) {
            if (r.getRecordTime() == null) {
                continue;
            }
            double v = extractor.applyAsDouble(r);
            if (always || v > 0) {
                series.getData().add(new XYChart.Data<>(r.getRecordTime().toLocalDate().toString(), v));
                hasAny = true;
            }
        }
        line.getData().add(series);
        if (!hasAny) {
            line.setTitle("暂无" + name + "数据");
        }
        double maxV = series.getData().stream()
                .mapToDouble(d -> d.getYValue().doubleValue()).max().orElse(0);
        if (maxV > 0) {
            double upper = Math.max(10, Math.ceil(maxV * 1.2));
            yAxis.setUpperBound(upper);
            yAxis.setTickUnit(upper <= 10 ? 1 : upper <= 50 ? 5 : 10);
        }
        final String color = dimensionColor(name);
        Platform.runLater(() -> {
            for (XYChart.Data<String, Number> d : series.getData()) {
                if (d.getNode() != null) {
                    d.getNode().setStyle("-fx-background-color: " + color + ", white; -fx-background-radius: 8; "
                            + "-fx-background-insets: 0, 3; -fx-padding: 6;");
                }
            }
            Node seriesNode = series.getNode();
            if (seriesNode != null) {
                Node lineNode = seriesNode.lookup(".chart-series-line");
                if (lineNode != null) {
                    lineNode.setStyle("-fx-stroke: " + color + "; -fx-stroke-width: 2;");
                }
            }
        });
        tab.setContent(line);
        return tab;
    }

    private String dimensionColor(String name) {
        switch (name) {
            case "血糖": return DialogSupport.COLOR_NORMAL;
            case "胰岛素": return DialogSupport.COLOR_BLUE;
            case "碳水": return DialogSupport.COLOR_ORANGE;
            case "运动": return DialogSupport.COLOR_PINK;
            case "体重": return DialogSupport.COLOR_TITLE;
            case "脉搏": return "#9B6BFF";
            default: return DialogSupport.COLOR_PINK;
        }
    }

    /** 解析血压字符串"收缩压/舒张压"，返回收缩压；解析失败返回 -1 */
    private static double parseSystolic(String bp) {
        if (bp == null || bp.isBlank()) {
            return -1;
        }
        try {
            return Double.parseDouble(bp.split("/")[0].trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 解析数字输入框；空返回 null，非法抛 NumberFormatException */
    private Double parseHealthNumber(TextField field) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        double v = Double.parseDouble(text);
        return v < 0 ? null : v;
    }
}
