package com.bloodsugar.ui;

import com.bloodsugar.service.CsvImportService;
import com.bloodsugar.service.CsvImportService.ImportPreview;
import com.bloodsugar.service.CsvImportService.ImportSummary;
import com.bloodsugar.service.CsvImportService.PreviewRow;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;

/**
 * 血糖仪数据批量导入对话框：选择文件 → 自动识别列与日期格式 →
 * 预览去重结果 → 确认写入。支持 .csv / .txt / .xlsx / .xls。
 */
public final class DataImportDialog {

    private final Stage owner;
    private final CsvImportService importService;
    private final Runnable onDataChanged;

    public DataImportDialog(Stage owner, CsvImportService importService, Runnable onDataChanged) {
        this.owner = owner;
        this.importService = importService;
        this.onDataChanged = onDataChanged;
    }

    /** 打开对话框（模态） */
    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("导入血糖数据");
        dialog.setHeaderText("批量导入血糖仪数据（CSV / Excel），自动识别列名、兼容多种日期格式并去重");

        Label fileLb = new Label("尚未选择文件");
        fileLb.setFont(Font.font("Microsoft YaHei", 12));
        fileLb.setTextFill(Color.web(DialogSupport.COLOR_TEXT));

        Button pickBtn = DialogSupport.styledButton("选择文件", DialogSupport.COLOR_BLUE_LIGHT,
                DialogSupport.COLOR_BLUE_DARK, "#B5E7FF", DialogSupport.COLOR_BLUE_LIGHT, "#1899E0", "#7FD3FF");
        Button tplBtn = DialogSupport.styledButton("下载导入模板", "#C9B2FF", "#9B6BFF", "#E0D2FF",
                "#B78DFF", "#7F4FD8", "#CDB4FF");
        HBox btnRow = new HBox(10, pickBtn, tplBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        Label tipLb = new Label("支持 .csv / .txt / .xlsx / .xls；可识别 时间/日期时间/血糖/数值/血糖值/餐别/备注 等列名（兼容英文），"
                + "兼容 yyyy-MM-dd HH:mm、yyyy/MM/dd HH:mm、yyyy-MM-dd 等格式；同一时间+数值的重复记录自动跳过。");
        tipLb.setWrapText(true);
        tipLb.setFont(Font.font("Microsoft YaHei", 11.5));
        tipLb.setTextFill(Color.web("#8A8578"));

        TableView<PreviewRow> previewTable = buildPreviewTable();

        Label sumLb = new Label("请选择文件以解析并预览");
        sumLb.setWrapText(true);
        sumLb.setFont(Font.font("Microsoft YaHei", 12.5));
        sumLb.setTextFill(Color.web(DialogSupport.COLOR_TEXT));
        sumLb.setPadding(new Insets(10, 12, 10, 12));
        sumLb.setMaxWidth(Double.MAX_VALUE);
        sumLb.setStyle("-fx-background-color: linear-gradient(to right, #FFF6E9, #EAFCF0); "
                + "-fx-background-radius: 14; -fx-border-color: " + DialogSupport.COLOR_BORDER + "; -fx-border-radius: 14;");

        Button importBtn = DialogSupport.styledButton("确认导入", DialogSupport.COLOR_GREEN_LIGHT,
                DialogSupport.COLOR_GREEN_DARK, "#A9F0C4", DialogSupport.COLOR_GREEN_LIGHT, "#1E9E50", "#7DE3A4");
        importBtn.setDisable(true);
        HBox actionRow = new HBox(10, importBtn);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        final ImportPreview[] holder = new ImportPreview[1];

        pickBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择要导入的血糖数据文件");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("CSV / Excel 文件 (*.csv, *.txt, *.xlsx, *.xls)",
                            "*.csv", "*.txt", "*.xlsx", "*.xls"),
                    new FileChooser.ExtensionFilter("所有文件", "*.*"));
            File f = chooser.showOpenDialog(owner);
            if (f == null) {
                return;
            }
            try {
                ImportPreview p = importService.preview(f);
                holder[0] = p;
                fileLb.setText("已选择：" + f.getAbsolutePath());
                int show = Math.min(50, p.rows.size());
                previewTable.setItems(FXCollections.observableArrayList(p.rows.subList(0, show)));
                StringBuilder sb = new StringBuilder(p.mapping.describe());
                if (!p.warning.isBlank()) {
                    sb.append("　注意：").append(p.warning);
                }
                sumLb.setText(sb.toString() + "\n" + CsvImportService.summaryText(p)
                        + "（预览显示前 " + show + " 行）");
                importBtn.setDisable(p.importable == 0);
            } catch (Exception ex) {
                holder[0] = null;
                importBtn.setDisable(true);
                previewTable.setItems(FXCollections.observableArrayList());
                sumLb.setText("解析失败，请确认文件格式后重试。");
                DialogSupport.showAlert(owner, "解析失败：" + ex.getMessage());
            }
        });

        tplBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("保存导入模板");
            chooser.setInitialFileName("糖伴SugarPal-血糖导入模板.csv");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV 文件 (*.csv)", "*.csv"));
            File f = chooser.showSaveDialog(owner);
            if (f == null) {
                return;
            }
            try {
                importService.writeTemplate(f);
                Alert ok = new Alert(Alert.AlertType.INFORMATION);
                ok.initOwner(owner);
                ok.setTitle("模板已生成");
                ok.setHeaderText("导入模板已保存");
                ok.setContentText("文件已保存到：\n" + f.getAbsolutePath()
                        + "\n\n表头为「时间,血糖值(mmol/L),餐别,备注」，按示例填写后即可用「选择文件」导入。");
                DialogSupport.styleDialogPane(ok.getDialogPane());
                ok.showAndWait();
            } catch (IOException ex) {
                DialogSupport.showAlert(owner, "模板保存失败：" + ex.getMessage());
            }
        });

        importBtn.setOnAction(e -> {
            ImportPreview p = holder[0];
            if (p == null || p.importable == 0) {
                return;
            }
            ImportSummary s = importService.doImport(p);
            Alert ok = new Alert(Alert.AlertType.INFORMATION);
            ok.initOwner(owner);
            ok.setTitle("导入完成");
            ok.setHeaderText("血糖数据导入结果");
            StringBuilder sb = new StringBuilder(CsvImportService.resultText(s));
            sb.append("\n重复跳过（文件内 ").append(p.dupInFile).append(" 条 / 已有记录 ")
                    .append(p.dupInDb).append(" 条），格式错误 ").append(p.invalid).append(" 条。");
            sb.append("\n导入的记录已按业务日（凌晨 4 点边界）归入表格、趋势曲线、日历与统计。");
            if (!s.failures.isEmpty()) {
                sb.append("\n\n失败明细（最多显示 10 条）：");
                for (int i = 0; i < Math.min(10, s.failures.size()); i++) {
                    sb.append("\n- ").append(s.failures.get(i));
                }
            }
            ok.setContentText(sb.toString());
            DialogSupport.styleDialogPane(ok.getDialogPane());
            ok.showAndWait();
            importBtn.setDisable(true);
            dialog.close();
            onDataChanged.run();
        });

        VBox box = new VBox(10, btnRow, fileLb, tipLb, sumLb, previewTable, actionRow);
        box.setPadding(new Insets(12));
        box.setPrefWidth(880);
        previewTable.setPrefHeight(300);

        dialog.getDialogPane().setContent(box);
        DialogSupport.styleDialogPane(dialog.getDialogPane());
        DialogSupport.addPinkCloseButton(dialog.getDialogPane());
        dialog.showAndWait();
    }

    /** 导入预览表：行号 / 时间 / 血糖 / 餐别 / 备注 / 状态 */
    private TableView<PreviewRow> buildPreviewTable() {
        TableView<PreviewRow> t = DialogSupport.statTable();
        t.setPlaceholder(new Label("请选择文件进行解析预览"));
        t.getColumns().add(DialogSupport.textColumn("行号", 55, r -> String.valueOf(r.lineNo)));
        t.getColumns().add(DialogSupport.textColumn("时间", 150, r -> r.displayTime()));
        t.getColumns().add(DialogSupport.textColumn("血糖(mmol/L)", 95,
                r -> r.bloodSugar > 0 ? String.format("%.1f", r.bloodSugar) : "-"));
        t.getColumns().add(DialogSupport.textColumn("餐别", 80, r -> r.mealPeriod == null ? "" : r.mealPeriod));
        t.getColumns().add(DialogSupport.textColumn("备注", 110, r -> r.note == null ? "" : r.note));
        t.getColumns().add(DialogSupport.textColumn("状态", 240, r ->
                (r.message == null || r.message.isEmpty()) ? r.status : r.status + " · " + r.message));
        return t;
    }
}
