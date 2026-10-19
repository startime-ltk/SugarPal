package com.bloodsugar.ui;

import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.service.ExportService;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 导出报告对话框：选择格式（Excel / PDF）与保存位置，后台线程执行导出。
 * 导出范围由调用方决定（全部记录或某个筛选日期），本类只负责交互与落盘。
 */
public final class ExportDialog {

    private final Stage owner;
    private final List<BloodSugarRecord> records;
    private final ExportService exportService;

    public ExportDialog(Stage owner, List<BloodSugarRecord> records, ExportService exportService) {
        this.owner = owner;
        this.records = records;
        this.exportService = exportService;
    }

    /** 打开对话框（模态） */
    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("导出报告");
        dialog.setHeaderText("导出重要血糖数据到 Excel / PDF 报告");

        VBox box = new VBox(10);
        box.setPadding(new Insets(10));

        Label info = new Label("将导出当前筛选范围的 " + records.size() + " 条记录（明细、统计汇总、按业务日/餐别汇总）");
        info.setFont(Font.font("Microsoft YaHei", 12));
        info.setTextFill(Color.web(DialogSupport.COLOR_TEXT));

        Label formatLb = new Label("导出格式：");
        formatLb.setFont(Font.font("Microsoft YaHei", 13));
        formatLb.setTextFill(Color.web(DialogSupport.COLOR_TITLE));

        ToggleGroup formatGroup = new ToggleGroup();
        RadioButton excelRb = new RadioButton("Excel (.xlsx)");
        RadioButton pdfRb = new RadioButton("PDF (.pdf)");
        excelRb.setToggleGroup(formatGroup);
        pdfRb.setToggleGroup(formatGroup);
        excelRb.setSelected(true);
        excelRb.setStyle("-fx-text-fill: " + DialogSupport.COLOR_TEXT + "; -fx-font-size: 13px;");
        pdfRb.setStyle("-fx-text-fill: " + DialogSupport.COLOR_TEXT + "; -fx-font-size: 13px;");
        HBox formatRow = new HBox(16, excelRb, pdfRb);
        formatRow.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("Excel 包含明细/统计/按业务日/按餐别 4 个表格；PDF 为排版报告（自动嵌入中文字体）");
        hint.setFont(Font.font(11));
        hint.setTextFill(Color.web("#8A8578"));

        Button exportBtn = DialogSupport.styledButton("选择位置并导出", DialogSupport.COLOR_GREEN_LIGHT,
                DialogSupport.COLOR_GREEN_DARK, "#A9F0C4", DialogSupport.COLOR_GREEN_LIGHT, "#1E9E50", "#7DE3A4");
        exportBtn.setOnAction(e -> {
            boolean excel = excelRb.isSelected();
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择导出保存位置");
            String stamp = LocalDateTime.now().format(DialogFormats.FILE_STAMP_FMT);
            if (excel) {
                chooser.setInitialFileName("糖伴SugarPal-血糖记录报告_" + stamp + ".xlsx");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel 文件 (*.xlsx)", "*.xlsx"));
            } else {
                chooser.setInitialFileName("糖伴SugarPal-血糖记录报告_" + stamp + ".pdf");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF 文件 (*.pdf)", "*.pdf"));
            }
            File target = chooser.showSaveDialog(owner);
            if (target == null) {
                return;
            }
            exportBtn.setDisable(true);
            exportBtn.setText("正在导出…");
            new Thread(() -> {
                try {
                    if (excel) {
                        exportService.exportExcel(records, target.getAbsolutePath());
                    } else {
                        exportService.exportPdf(records, target.getAbsolutePath());
                    }
                    Platform.runLater(() -> {
                        dialog.close();
                        Alert ok = new Alert(Alert.AlertType.INFORMATION);
                        ok.initOwner(owner);
                        ok.setTitle("导出成功");
                        ok.setHeaderText("报告已导出");
                        ok.setContentText("文件已保存到：\n" + target.getAbsolutePath());
                        DialogSupport.styleDialogPane(ok.getDialogPane());
                        ok.showAndWait();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> DialogSupport.showAlert(owner, "导出失败：" + ex.getMessage()));
                } finally {
                    Platform.runLater(() -> {
                        exportBtn.setDisable(false);
                        exportBtn.setText("选择位置并导出");
                    });
                }
            }).start();
        });

        box.getChildren().addAll(info, formatLb, formatRow, hint, exportBtn);

        dialog.getDialogPane().setContent(box);
        DialogSupport.styleDialogPane(dialog.getDialogPane());
        DialogSupport.addPinkCloseButton(dialog.getDialogPane());
        dialog.showAndWait();
    }
}
