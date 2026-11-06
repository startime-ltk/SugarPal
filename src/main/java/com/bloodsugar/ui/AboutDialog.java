package com.bloodsugar.ui;

import com.bloodsugar.config.UpdateConfig;
import com.bloodsugar.config.VersionInfo;
import com.bloodsugar.service.UpdateService;
import com.bloodsugar.service.UpdateService.UpdateException;
import com.bloodsugar.service.UpdateService.UpdateInfo;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 关于对话框：产品信息（名称/版本/简介）+ 更新源配置（可编辑）+ 一键更新。
 *
 * <p>一键更新流程：检查更新 → 有新版则确认 → 下载安装包到临时目录 →
 * 静默运行安装程序（/VERYSILENT /SUPPRESSMSGBOXES /NORESTART）并提示重新打开。
 * 更新源默认 GitHub Releases API，下载支持国内加速镜像前缀回退直连。
 */
public class AboutDialog {

    // 糖果马卡龙配色（与 MainUI 保持一致）
    private static final String COLOR_BG = "#EAFCF0";
    private static final String COLOR_BORDER = "#B8EED0";
    private static final String COLOR_TEXT = "#3A4A42";
    private static final String COLOR_TITLE = "#2BB673";
    private static final String COLOR_GREEN_LIGHT = "#7DE3A4";
    private static final String COLOR_GREEN_DARK = "#22A856";
    private static final String COLOR_PINK_LIGHT = "#FFC4D9";
    private static final String COLOR_PINK = "#FF8FB3";
    private static final String COLOR_GRAY = "#98A1AF";
    private static final String COLOR_GRAY_LIGHT = "#C9CDD6";
    private static final String COLOR_GRAY_DARK = "#667080";

    private static final UpdateService updateService = new UpdateService();

    private AboutDialog() {
    }

    /** 弹出关于对话框（模态，owner 为主窗口） */
    public static void show(Stage owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("关于糖伴SugarPal");
        dialog.setHeaderText(null);

        DialogPane pane = dialog.getDialogPane();
        styleDialogPane(pane);
        ButtonType closeType = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().addAll(closeType);

        VBox box = new VBox(12);
        box.setPadding(new Insets(12));
        box.setPrefWidth(520);

        // 产品名 + 版本
        Label name = new Label("糖伴SugarPal");
        name.setFont(Font.font("YouYuan", FontWeight.BOLD, 26));
        name.setTextFill(Color.web(COLOR_TITLE));
        name.setEffect(new DropShadow(3, 2, 2, Color.web("#9BE8C0")));

        Label version = new Label("v" + VersionInfo.getVersion());
        version.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        version.setTextFill(Color.web(COLOR_GRAY_DARK));

        Label intro = new Label("一款简洁可爱的本地血糖记录工具：\n"
                + "记录三餐与随机血糖、趋势曲线、日历补记备注、AGP 报告、健康摘要分享与 AI 控糖建议，数据完全保存在本机。");
        intro.setWrapText(true);
        intro.setFont(Font.font("Microsoft YaHei", 12));
        intro.setTextFill(Color.web(COLOR_TEXT));

        HBox header = new HBox(10, name, version);
        header.setAlignment(Pos.CENTER_LEFT);

        // 更新源配置
        Label sourceTitle = new Label("更新源地址（GitHub Releases API）");
        sourceTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 12));
        sourceTitle.setTextFill(Color.web(COLOR_GRAY_DARK));
        TextField sourceField = new TextField(UpdateConfig.getSource());
        sourceField.setPrefColumnCount(46);

        Label mirrorTitle = new Label("加速镜像前缀（留空则仅直连；例如 https://ghproxy.net/）");
        mirrorTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 12));
        mirrorTitle.setTextFill(Color.web(COLOR_GRAY_DARK));
        TextField mirrorField = new TextField(UpdateConfig.getMirror());

        Button saveCfgBtn = smallGrayButton("保存配置");
        saveCfgBtn.setOnAction(e -> {
            try {
                UpdateConfig.saveSource(sourceField.getText());
                UpdateConfig.saveMirror(mirrorField.getText());
                showInfo(owner, "更新配置已保存", "更新源地址与镜像前缀已保存到本地配置。");
            } catch (RuntimeException ex) {
                showWarn(owner, "保存失败", ex.getMessage());
            }
        });
        HBox cfgRow = new HBox(8, saveCfgBtn);
        cfgRow.setAlignment(Pos.CENTER_RIGHT);

        VBox sourceBox = new VBox(4, sourceTitle, sourceField, mirrorTitle, mirrorField, cfgRow);

        // 一键更新区
        Label updateTitle = new Label("一键更新");
        updateTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        updateTitle.setTextFill(Color.web(COLOR_TITLE));

        Label status = new Label("点击「检查更新」获取最新版本。");
        status.setWrapText(true);
        status.setFont(Font.font("Microsoft YaHei", 12));
        status.setTextFill(Color.web(COLOR_TEXT));

        Button updateBtn = gradientButton("检查更新", COLOR_GREEN_LIGHT, COLOR_GREEN_DARK);
        updateBtn.setOnAction(e -> runUpdateFlow(owner, updateBtn, status, sourceField::getText));

        HBox updateRow = new HBox(10, updateBtn, status);
        updateRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(status, Priority.ALWAYS);

        box.getChildren().addAll(header, intro, new Region(), sourceBox, updateTitle, updateRow);
        pane.setContent(box);

        Button closeBtn = (Button) pane.lookupButton(closeType);
        if (closeBtn != null) {
            closeBtn.setStyle("-fx-background-color: linear-gradient(to bottom, #FFD3E2, #FFA8C5); "
                    + "-fx-text-fill: #8C3B52; -fx-font-size: 13px; -fx-padding: 8 20; "
                    + "-fx-background-radius: 18; -fx-border-radius: 18; "
                    + "-fx-border-color: #E87FA5; -fx-border-width: 0 0 3 0; "
                    + "-fx-cursor: hand; -fx-font-weight: bold;");
            closeBtn.setEffect(new DropShadow(4, 2, 3, Color.web("#FFC4D9")));
        }

        dialog.showAndWait();
    }

    /** 一键更新主流程（后台线程执行网络/下载，UI 更新切回 JavaFX 线程） */
    private static void runUpdateFlow(Stage owner, Button updateBtn, Label status,
                                      java.util.function.Supplier<String> sourceSupplier) {
        updateBtn.setDisable(true);
        status.setText("正在检查更新，请稍候……");
        new Thread(() -> {
            UpdateInfo update;
            try {
                update = updateService.checkForUpdate(
                        UpdateService.candidateSources(sourceSupplier.get()), VersionInfo.getVersion());
            } catch (UpdateException ex) {
                Platform.runLater(() -> {
                    status.setText("检查更新失败：" + ex.getMessage());
                    updateBtn.setDisable(false);
                });
                return;
            }

            if (update == null) {
                Platform.runLater(() -> {
                    status.setText("当前已是最新版本（v" + VersionInfo.getVersion() + "）。");
                    updateBtn.setDisable(false);
                });
                return;
            }

            // 有新版：确认框显示新版号与说明
            final UpdateInfo info = update;
            final boolean[] confirmed = {false};
            Platform.runLater(() -> {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.initOwner(owner);
                confirm.setTitle("发现新版本");
                confirm.setHeaderText("糖伴SugarPal v" + info.latestVersion + " 已发布");
                String notes = (info.notes == null || info.notes.isBlank())
                        ? "（本次发布未附带说明）" : info.notes;
                confirm.setContentText("当前版本：v" + VersionInfo.getVersion()
                        + "\n最新版本：v" + info.latestVersion
                        + "\n\n发布说明：\n" + notes
                        + "\n\n是否立即下载并安装？");
                styleAlert(confirm);
                confirm.getDialogPane().getButtonTypes().setAll(
                        new ButtonType("立即更新", ButtonBar.ButtonData.OK_DONE),
                        new ButtonType("暂不更新", ButtonBar.ButtonData.CANCEL_CLOSE));
                Optional<ButtonType> result = confirm.showAndWait();
                confirmed[0] = result.isPresent() && result.get().getButtonData() == ButtonBar.ButtonData.OK_DONE;
                if (!confirmed[0]) {
                    status.setText("已取消更新，仍使用当前版本（v" + VersionInfo.getVersion() + "）。");
                    updateBtn.setDisable(false);
                    return;
                }
                status.setText("正在下载 v" + info.latestVersion + " 安装包，请稍候……");
                new Thread(() -> downloadAndInstall(owner, updateBtn, status, info)).start();
            });
        }).start();
    }

    /** 下载 → 静默安装 */
    private static void downloadAndInstall(Stage owner, Button updateBtn, Label status, UpdateInfo info) {
        try {
            Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "sugarpal-update");
            Path installer = updateService.download(info, tmpDir, UpdateConfig.getMirror());
            Platform.runLater(() -> {
                status.setText("下载完成，正在静默安装，请稍候……");
            });
            updateService.runInstaller(installer);
            Platform.runLater(() -> {
                status.setText("安装程序已启动，安装完成后请重新打开糖伴SugarPal。");
                updateBtn.setDisable(false);
                showInfo(owner, "更新已就绪",
                        "新版安装程序已启动并正在后台静默安装。\n\n安装完成后请重新打开糖伴SugarPal 即可使用 v" + info.latestVersion + "。");
            });
        } catch (UpdateException ex) {
            Platform.runLater(() -> {
                status.setText("更新失败：" + ex.getMessage());
                updateBtn.setDisable(false);
                showWarn(owner, "更新失败", ex.getMessage());
            });
        }
    }

    // ---------- 样式辅助 ----------

    private static void styleDialogPane(DialogPane pane) {
        pane.setStyle("-fx-background-color: linear-gradient(to bottom right, #FFF6E9, " + COLOR_BG + ", #FFF0F6); "
                + "-fx-background-radius: 20; -fx-border-color: " + COLOR_BORDER + "; -fx-border-radius: 20;");
    }

    private static void styleAlert(Alert alert) {
        alert.getDialogPane().setStyle("-fx-background-color: linear-gradient(to bottom right, #FFF6E9, "
                + COLOR_BG + ", #FFF0F6); -fx-background-radius: 20; -fx-border-color: "
                + COLOR_BORDER + "; -fx-border-radius: 20;");
        Button ok = (Button) alert.getDialogPane().lookupButton(ButtonType.OK);
        if (ok != null) {
            ok.setStyle(gradientStyle(COLOR_GREEN_LIGHT, COLOR_GREEN_DARK));
        }
    }

    private static Button gradientButton(String text, String from, String to) {
        Button btn = new Button(text);
        btn.setStyle(gradientStyle(from, to));
        btn.setOnMouseEntered(e -> btn.setStyle(gradientStyle("#A9F0C4", COLOR_GREEN_LIGHT)));
        btn.setOnMouseExited(e -> btn.setStyle(gradientStyle(from, to)));
        return btn;
    }

    private static String gradientStyle(String from, String to) {
        return "-fx-background-color: linear-gradient(to bottom, " + from + ", " + to + "); "
                + "-fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 11 22; "
                + "-fx-background-radius: 20; -fx-border-radius: 20; "
                + "-fx-border-color: #1E9E50; -fx-border-width: 0 0 4 0; "
                + "-fx-cursor: hand; -fx-font-weight: bold;";
    }

    private static Button smallGrayButton(String text) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: linear-gradient(to bottom, " + COLOR_GRAY_LIGHT + ", " + COLOR_GRAY + "); "
                + "-fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 6 14; "
                + "-fx-background-radius: 14; -fx-border-radius: 14; "
                + "-fx-border-color: " + COLOR_GRAY_DARK + "; -fx-border-width: 0 0 3 0; "
                + "-fx-cursor: hand; -fx-font-weight: bold;");
        return btn;
    }

    private static void showInfo(Stage owner, String title, String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        styleAlert(alert);
        alert.showAndWait();
    }

    private static void showWarn(Stage owner, String title, String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        styleAlert(alert);
        alert.showAndWait();
    }
}
