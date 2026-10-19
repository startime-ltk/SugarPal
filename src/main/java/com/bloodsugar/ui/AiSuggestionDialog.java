package com.bloodsugar.ui;

import com.bloodsugar.config.AiConfig;
import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.service.AiSuggestionService;
import com.bloodsugar.service.AiSuggestionService.AiException;
import com.bloodsugar.service.BloodSugarService;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * AI 智能控糖建议对话框：切换服务商 → 生成建议（后台线程）→ 失败可重试或
 * 一键切换为已配置好 Key 的其它模型；并入口「设置 API Key」逐模型配置
 * 接口地址 / 模型名 / Key（Key 仅保存在本地配置文件）。
 */
public final class AiSuggestionDialog {

    private final Stage owner;
    private final AiConfig config;
    private final AiSuggestionService aiService;
    private final BloodSugarService recordService;
    private final Consumer<String> statusSink;

    public AiSuggestionDialog(Stage owner, AiConfig config, AiSuggestionService aiService,
                              BloodSugarService recordService, Consumer<String> statusSink) {
        this.owner = owner;
        this.config = config;
        this.aiService = aiService;
        this.recordService = recordService;
        this.statusSink = statusSink;
    }

    /** 打开对话框（模态） */
    public void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("AI 智能控糖建议");
        dialog.setHeaderText("让大模型根据近期血糖记录，生成个性化控糖建议");

        VBox box = new VBox(10);
        box.setPadding(new Insets(10));

        // 模型切换：下拉框在所有内置服务商与自定义 OpenAI 兼容接口之间切换
        Label modelLb = new Label("模型：");
        modelLb.setTextFill(Color.web(DialogSupport.COLOR_TITLE));
        ComboBox<String> modelCombo = new ComboBox<>();
        for (String pid : AiConfig.getProviderIds()) {
            modelCombo.getItems().add(AiConfig.displayName(pid));
        }
        modelCombo.setValue(AiConfig.displayName(config.getActiveProvider()));
        modelCombo.setStyle("-fx-background-radius: 8; -fx-border-radius: 8; "
                + "-fx-border-color: " + DialogSupport.COLOR_MEAL_BORDER + "; -fx-background-color: #FFFFFF;");

        Button settingsBtn = new Button("设置 API Key");
        settingsBtn.setStyle("-fx-text-fill: " + DialogSupport.COLOR_BLUE + "; -fx-font-size: 12px; -fx-cursor: hand; -fx-font-weight: bold;");
        settingsBtn.setOnAction(e -> showSettingsDialog());

        Label keyStatus = new Label();
        keyStatus.setFont(Font.font(11));

        HBox topRow = new HBox(8, modelLb, modelCombo, settingsBtn, keyStatus);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Button generateBtn = DialogSupport.styledButton("生成建议", DialogSupport.COLOR_GREEN_LIGHT,
                DialogSupport.COLOR_GREEN_DARK, "#A9F0C4", DialogSupport.COLOR_GREEN_LIGHT, "#1E9E50", "#7DE3A4");

        TextArea resultArea = new TextArea();
        resultArea.setEditable(false);
        resultArea.setWrapText(true);
        resultArea.setPromptText("点击「生成建议」，这里会显示 AI 给出的控糖建议……");
        resultArea.setFont(Font.font("Microsoft YaHei", 13));
        resultArea.setPrefSize(560, 360);
        resultArea.setStyle("-fx-control-inner-background: #FDFEFB; -fx-background-radius: 14; "
                + "-fx-border-radius: 14; -fx-border-color: " + DialogSupport.COLOR_BORDER + ";");

        Label hint = new Label("基于最近 30 条血糖记录生成，结果仅供参考，不能替代医生诊断");
        hint.setFont(Font.font(11));
        hint.setTextFill(Color.web("#8A8578"));

        box.getChildren().addAll(topRow, generateBtn, resultArea, hint);

        // 切换模型时同步 Key 状态提示，并把当前选择保存到配置
        Runnable refreshKeyStatus = () -> {
            String provider = AiConfig.providerIdByDisplay(modelCombo.getValue());
            if (provider == null) {
                return;
            }
            config.setActiveProvider(provider);
            try {
                config.save();
            } catch (RuntimeException ignored) {
                // 保存失败不阻塞切换，下次打开仍可用内存中的选择
            }
            boolean hasKey = config.getApiKey(provider) != null && !config.getApiKey(provider).isBlank();
            keyStatus.setText(hasKey ? "✓ Key 已配置" : "⚠ 未配置 Key");
            keyStatus.setTextFill(hasKey ? Color.web(DialogSupport.COLOR_NORMAL) : Color.web(DialogSupport.COLOR_HIGH));
        };
        modelCombo.setOnAction(e -> refreshKeyStatus.run());
        refreshKeyStatus.run();

        generateBtn.setOnAction(e -> {
            String provider = AiConfig.providerIdByDisplay(modelCombo.getValue());
            if (provider == null) {
                return;
            }
            generateBtn.setDisable(true);
            resultArea.setText("正在连接 " + AiConfig.displayName(provider) + " 生成建议，请稍候……");
            new Thread(() -> {
                try {
                    List<BloodSugarRecord> records = recordService.getAllRecords();
                    String advice = aiService.generateSuggestion(records, config, provider);
                    Platform.runLater(() -> {
                        resultArea.setText(advice);
                        statusSink.accept("AI 建议已生成（" + AiConfig.displayName(provider) + "）");
                    });
                } catch (AiException | SQLException ex) {
                    Platform.runLater(() -> {
                        resultArea.clear();
                        showFailureDialog(modelCombo, generateBtn, provider, ex.getMessage());
                    });
                } finally {
                    Platform.runLater(() -> generateBtn.setDisable(false));
                }
            }).start();
        });

        dialog.getDialogPane().setContent(box);
        DialogSupport.styleDialogPane(dialog.getDialogPane());
        DialogSupport.addPinkCloseButton(dialog.getDialogPane());
        dialog.showAndWait();
    }

    /**
     * 生成失败时给出明确错误与操作入口：重试 / 切换其他已配置模型
     * （无则去设置 API Key）/ 关闭。
     */
    private void showFailureDialog(ComboBox<String> modelCombo, Button generateBtn,
                                   String failedProvider, String message) {
        // 找第一个已配置 Key 的其他 provider 作为回退候选
        String fallback = null;
        for (String pid : AiConfig.getProviderIds()) {
            if (!pid.equals(failedProvider)) {
                String k = config.getApiKey(pid);
                if (k != null && !k.isBlank()) {
                    fallback = pid;
                    break;
                }
            }
        }

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle("AI 建议生成失败");
        alert.setHeaderText(null);
        alert.setContentText(message + (fallback == null
                ? "\n\n可在「设置 API Key」中为所选模型填写接口信息后重试，或在上方下拉框切换其它模型。"
                : "\n\n可重试当前模型，或一键切换为已配置好 Key 的「" + AiConfig.displayName(fallback) + "」。"));

        ButtonType retryType = new ButtonType("重试", ButtonBar.ButtonData.OK_DONE);
        ButtonType actionType = fallback == null
                ? new ButtonType("去设置 API Key", ButtonBar.ButtonData.OTHER)
                : new ButtonType("切换为 " + AiConfig.displayName(fallback), ButtonBar.ButtonData.OTHER);
        ButtonType closeType = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().addAll(retryType, actionType, closeType);
        DialogSupport.styleDialogPane(alert.getDialogPane());

        Optional<ButtonType> r = alert.showAndWait();
        if (!r.isPresent() || r.get() == closeType) {
            return;
        }
        if (r.get() == retryType) {
            generateBtn.fire();
        } else if (r.get() == actionType) {
            if (fallback != null) {
                modelCombo.setValue(AiConfig.displayName(fallback));
                generateBtn.fire();
            } else {
                showSettingsDialog();
            }
        }
    }

    /** AI 模型设置：内置各服务商 + 自定义 OpenAI 兼容，每个模型一套接口地址 / 模型名 / API Key */
    private void showSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("AI 模型设置");
        dialog.setHeaderText("为各模型填写 API Key 与接口配置（Key 仅保存在本地配置文件，不会上传、不写入日志）");

        VBox cards = new VBox(14);
        cards.setPadding(new Insets(6));

        Map<String, TextField> urlFields = new HashMap<>();
        Map<String, TextField> modelFields = new HashMap<>();
        Map<String, TextField> keyFields = new HashMap<>();

        for (String pid : AiConfig.getProviderIds()) {
            GridPane card = new GridPane();
            card.setHgap(10);
            card.setVgap(8);
            card.setPadding(new Insets(14));
            card.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 12; -fx-border-radius: 12; "
                    + "-fx-border-color: " + DialogSupport.COLOR_BORDER + ";");

            Label title = DialogSupport.artLabel(AiConfig.displayName(pid), 12);
            card.add(title, 0, 0, 2, 1);

            TextField url = new TextField(config.getBaseUrl(pid));
            url.setPrefWidth(420);
            if (url.getText().isBlank()) {
                url.setPromptText(AiConfig.getDefaultBaseUrl(pid));
            }
            card.add(new Label("接口地址:"), 0, 1);
            card.add(url, 1, 1);

            TextField model = new TextField(config.getModel(pid));
            if (model.getText().isBlank()) {
                model.setPromptText(AiConfig.getDefaultModel(pid));
            }
            card.add(new Label("模型名:"), 0, 2);
            card.add(model, 1, 2);

            PasswordField key = new PasswordField();
            key.setText(config.getApiKey(pid));
            key.setPrefWidth(420);
            key.setPromptText("在对应服务商控制台申请后粘贴");
            card.add(new Label("API Key:"), 0, 3);
            card.add(key, 1, 3);

            urlFields.put(pid, url);
            modelFields.put(pid, model);
            keyFields.put(pid, key);
            cards.getChildren().add(card);
        }

        Label tip = new Label("提示：接口地址/模型名留空时自动使用灰字提示的默认值。也可直接编辑本地配置文件 "
                + AiConfig.getConfigFile());
        tip.setFont(Font.font(11));
        tip.setTextFill(Color.web("#8A8578"));
        tip.setWrapText(true);
        cards.getChildren().add(tip);

        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(430);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        DialogSupport.styleDialogPane(dialog.getDialogPane());

        dialog.setResultConverter(btn -> {
            if (btn != ButtonType.OK) {
                return null;
            }
            for (String pid : AiConfig.getProviderIds()) {
                TextField url = urlFields.get(pid);
                TextField model = modelFields.get(pid);
                TextField key = keyFields.get(pid);
                String urlText = url.getText().trim();
                String modelText = model.getText().trim();
                String keyText = key.getText().trim();
                config.setBaseUrl(pid, urlText.isEmpty() ? AiConfig.getDefaultBaseUrl(pid) : urlText);
                config.setModel(pid, modelText.isEmpty() ? AiConfig.getDefaultModel(pid) : modelText);
                config.setApiKey(pid, keyText);
            }
            try {
                config.save();
                statusSink.accept("AI 配置已保存");
            } catch (RuntimeException ex) {
                DialogSupport.showAlert(owner, "保存配置失败：" + ex.getMessage());
            }
            return btn;
        });
        dialog.showAndWait();
    }
}
