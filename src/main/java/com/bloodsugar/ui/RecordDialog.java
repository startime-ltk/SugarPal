package com.bloodsugar.ui;

import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.service.BloodSugarService;
import com.bloodsugar.util.PeriodClassifier;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.layout.GridPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.function.Consumer;

/**
 * 血糖记录对话框：新增 / 修改 / 日历补填三种状态合一。
 * <ul>
 *   <li>{@code existing == null && presetDate == null}：新增记录；</li>
 *   <li>{@code existing == null && presetDate != null}：日历「补填」，日期预选为选中日；</li>
 *   <li>{@code existing != null}：修改已有记录。</li>
 * </ul>
 * 餐别不手动选择，由 {@link MealTypeAutoRecognizer} 按测量时间自动识别；
 * 多维健康字段（胰岛素 / 碳水 / 运动 / 体重 / 脉搏 / 血压）选填，随记录一并保存。
 */
public final class RecordDialog {

    private RecordDialog() {
    }

    /**
     * @param onSaved 保存成功回调；参数为被修改的记录（新增时为 null），
     *                调用方负责刷新界面与取消勾选
     */
    public static void show(Stage owner, BloodSugarService service, MealTypeAutoRecognizer recognizer,
                            BloodSugarRecord existing, LocalDate presetDate,
                            Consumer<BloodSugarRecord> onSaved) {
        boolean editing = existing != null;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(editing ? "糖伴SugarPal - 修改血糖记录" : "糖伴SugarPal - 添加血糖记录");
        dialog.setHeaderText(editing ? "修改血糖测量信息"
                : presetDate != null ? "补填 " + presetDate + " 血糖记录" : "请输入血糖测量信息");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(20));

        LocalDateTime rt = editing ? existing.getRecordTime() : null;
        DatePicker datePicker = new DatePicker(rt != null ? rt.toLocalDate()
                : presetDate != null ? presetDate : LocalDate.now());
        grid.add(new Label("测量日期:"), 0, 0);
        grid.add(datePicker, 1, 0);

        TextField timeField = new TextField(rt != null ? rt.format(DialogFormats.TIME_FMT)
                : LocalTime.now().format(DialogFormats.TIME_FMT));
        timeField.setPromptText("HH:mm");
        // 用户输入 "." 时自动换成 ":"
        timeField.setTextFormatter(new TextFormatter<>(change -> {
            if (change.getText().contains(".")) {
                change.setText(change.getText().replace('.', ':'));
            }
            return change;
        }));
        grid.add(new Label("测量时间:"), 0, 1);
        grid.add(timeField, 1, 1);

        TextField sugarField = new TextField(editing ? String.valueOf(existing.getBloodSugar()) : "");
        sugarField.setPromptText("例如 5.6");
        grid.add(new Label("血糖值 (mmol/L):"), 0, 2);
        grid.add(sugarField, 1, 2);

        // 餐别自动识别：使用 Label 显示，对话框打开时实时计算
        Label mealTypeLabel = new Label("空腹");
        mealTypeLabel.setStyle("-fx-background-color: linear-gradient(to bottom, " + DialogSupport.COLOR_GREEN_LIGHT
                + ", " + DialogSupport.COLOR_GREEN_DARK + "); "
                + "-fx-text-fill: white; "
                + "-fx-font-weight: bold; -fx-padding: 5 14; -fx-background-radius: 14;");
        grid.add(new Label("餐别(自动):"), 0, 3);
        grid.add(mealTypeLabel, 1, 3);

        TextField noteField = new TextField(editing && existing.getNote() != null ? existing.getNote() : "");
        noteField.setPromptText("选填");
        grid.add(new Label("备注:"), 0, 4);
        grid.add(noteField, 1, 4);

        // ── 多维健康录入（选填，随本次记录一并保存；编辑时回填原值）──
        TextField dInsulinField = DialogSupport.createHealthField("胰岛素 (U)");
        TextField dCarbsField = DialogSupport.createHealthField("碳水 (g)");
        TextField dActivityField = DialogSupport.createHealthField("运动 (分钟)");
        TextField dWeightField = DialogSupport.createHealthField("体重 (kg)");
        TextField dPulseField = DialogSupport.createHealthField("脉搏 (次/分)");
        TextField dBpField = DialogSupport.createHealthField("血压 120/80");
        TextField[] healthFields = {dInsulinField, dCarbsField, dActivityField, dWeightField, dPulseField, dBpField};
        String[] healthNames = {"胰岛素", "碳水", "运动", "体重", "脉搏", "血压"};
        if (editing) {
            setHealthFieldText(dInsulinField, existing.getInsulin());
            setHealthFieldText(dCarbsField, existing.getCarbs());
            setHealthFieldText(dActivityField, existing.getActivity());
            setHealthFieldText(dWeightField, existing.getWeight());
            setHealthFieldText(dPulseField, existing.getPulse());
            if (existing.getBloodPressure() != null) {
                dBpField.setText(existing.getBloodPressure());
            }
        }

        Label healthTitle = new Label("多维健康（选填）");
        healthTitle.setTextFill(Color.web(DialogSupport.COLOR_BLUE_DARK));
        healthTitle.setStyle("-fx-font-weight: bold;");
        grid.add(healthTitle, 0, 5, 2, 1);

        for (int i = 0; i < healthNames.length; i++) {
            grid.add(new Label(healthNames[i] + ":"), 0, 6 + i);
            grid.add(healthFields[i], 1, 6 + i);
        }

        Label hintLabel = new Label("餐别根据最近用餐记录自动识别，无需手动选择");
        hintLabel.setFont(Font.font(11));
        hintLabel.setTextFill(Color.web("#8A8578"));
        grid.add(hintLabel, 1, 12);

        // 测量日期/时间变化时，实时刷新自动识别的餐别
        Runnable refreshMealType = () -> {
            try {
                LocalDate d = datePicker.getValue();
                LocalTime t = LocalTime.parse(timeField.getText().trim().replace('.', ':'));
                LocalDateTime recordTime = LocalDateTime.of(d, t);
                MealTypeAutoRecognizer.MealTimeInfo info = recognizer.resolve(recordTime);
                mealTypeLabel.setText(recognizer.describe(recordTime, info));
            } catch (Exception ex) {
                mealTypeLabel.setText("空腹");
            }
        };
        datePicker.valueProperty().addListener((obs, o, n) -> refreshMealType.run());
        timeField.textProperty().addListener((obs, o, n) -> refreshMealType.run());

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        DialogSupport.styleDialogPane(dialog.getDialogPane());

        // 对话框展示后立即执行一次自动识别
        dialog.setOnShown(e -> refreshMealType.run());

        dialog.setResultConverter(btn -> {
            if (btn != ButtonType.OK) {
                return null;
            }
            try {
                LocalDate date = datePicker.getValue();
                LocalTime time = LocalTime.parse(timeField.getText().trim().replace('.', ':'));
                LocalDateTime recordTime = LocalDateTime.of(date, time);
                double bloodSugar = Double.parseDouble(sugarField.getText().trim());
                if (bloodSugar <= 0 || bloodSugar > 50) {
                    DialogSupport.showAlert(owner, "血糖值应在 0 ~ 50 mmol/L 之间");
                    return null;
                }
                MealTypeAutoRecognizer.MealTimeInfo mealInfo = recognizer.resolve(recordTime);
                LocalDateTime mealTime = mealInfo != null ? mealInfo.time : null;
                String mealType = recognizer.describe(recordTime, mealInfo);
                String note = noteField.getText().trim();
                // 多维健康字段（选填，空视为 0/空）
                double hInsulin = parseDialogDouble(dInsulinField, "胰岛素");
                double hCarbs = parseDialogDouble(dCarbsField, "碳水");
                double hActivity = parseDialogDouble(dActivityField, "运动");
                double hWeight = parseDialogDouble(dWeightField, "体重");
                double hPulse = parseDialogDouble(dPulseField, "脉搏");
                String hBp = dBpField.getText().trim();
                if (!hBp.isEmpty() && !hBp.matches("\\d{2,3}/\\d{2,3}")) {
                    DialogSupport.showAlert(owner, "血压格式应为 收缩压/舒张压，例如 120/80");
                    return null;
                }

                if (editing) {
                    existing.setRecordTime(recordTime);
                    existing.setBloodSugar(bloodSugar);
                    existing.setMealTime(mealTime);
                    existing.setMealType(mealType);
                    existing.setNote(note);
                    existing.setInsulin(hInsulin);
                    existing.setCarbs(hCarbs);
                    existing.setActivity(hActivity);
                    existing.setWeight(hWeight);
                    existing.setPulse(hPulse);
                    existing.setBloodPressure(hBp);
                    service.updateRecord(existing);
                    onSaved.accept(existing);
                } else {
                    service.addRecord(recordTime, bloodSugar, mealTime, mealType, note,
                            hInsulin, hCarbs, hActivity, hWeight, hPulse, hBp);
                    onSaved.accept(null);
                    String period = PeriodClassifier.classify(recordTime, mealTime);
                    showAdviceDialog(owner, bloodSugar, period);
                }
                return btn;
            } catch (Exception ex) {
                DialogSupport.showAlert(owner, "输入格式有误：" + ex.getMessage());
                return null;
            }
        });
        dialog.showAndWait();
    }

    /** 保存成功后的血糖建议弹窗 */
    private static void showAdviceDialog(Stage owner, double bloodSugar, String period) {
        String advice = HealthAdvice.generate(bloodSugar, period);
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("血糖建议");
        alert.setHeaderText(String.format("测量结果：%.1f mmol/L  [%s]", bloodSugar, period));
        Label content = new Label(advice);
        content.setFont(Font.font("Microsoft YaHei", 14));
        content.setWrapText(true);
        content.setMaxWidth(400);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setStyle("-fx-background-color: linear-gradient(to bottom right, "
                + DialogSupport.COLOR_MEAL_BG + ", #FFFFFF, #FFF0F6); "
                + "-fx-background-radius: 20; -fx-border-color: " + DialogSupport.COLOR_MEAL_BORDER
                + "; -fx-border-radius: 20;");
        DialogSupport.styleDialogPane(alert.getDialogPane());
        alert.showAndWait();
    }

    /** 对话框内数字字段解析：空返回 0，非法抛 NumberFormatException */
    private static double parseDialogDouble(TextField field, String name) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return 0;
        }
        double v = Double.parseDouble(text);
        if (v < 0) {
            throw new NumberFormatException(name + "不能为负数");
        }
        return v;
    }

    /** 健康数字字段回填：原值 > 0 才显示，避免把"未记录"显示成 0.0 */
    private static void setHealthFieldText(TextField field, double value) {
        if (value > 0) {
            field.setText(String.valueOf(value));
        }
    }
}
