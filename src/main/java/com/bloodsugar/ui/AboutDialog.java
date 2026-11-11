package com.bloodsugar.ui;

import com.bloodsugar.config.DataLocation;
import com.bloodsugar.config.UpdateConfig;
import com.bloodsugar.config.VersionInfo;
import com.bloodsugar.service.BackupService;
import com.bloodsugar.service.BloodSugarService;
import com.bloodsugar.service.UpdateService;
import com.bloodsugar.service.UpdateService.UpdateException;
import com.bloodsugar.service.UpdateService.UpdateInfo;
import com.bloodsugar.util.AppLog;

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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 关于对话框：产品信息（名称/版本/简介）+ 数据位置（展示当前目录并提供更改入口）
 * + 更新源配置（可编辑）+ 一键更新。
 *
 * <p>一键更新流程：检查更新 → 有新版则确认 → 下载安装包到临时目录 →
 * 静默运行安装程序（/VERYSILENT /SUPPRESSMSGBOXES /NORESTART）并提示重新打开。
 * 更新源默认 GitHub Releases API，下载支持国内加速镜像前缀回退直连。
 *
 * <p>数据位置入口由调用方以 {@code openDataLocation} 动作注入，避免本类反向依赖 MainUI 实例。
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

    /** 弹出关于对话框（模态，owner 为主窗口；openDataLocation 为打开“数据位置设置”的动作） */
    public static void show(Stage owner, Runnable openDataLocation) {
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

        Label mirrorTitle = new Label("加速镜像前缀（已默认启用，可用英文逗号填多个；填 none 则仅直连）");
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

        // 数据位置（在关于中集中管理）：展示当前数据目录，并提供更改入口
        Label dataLocTitle = new Label("数据位置");
        dataLocTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        dataLocTitle.setTextFill(Color.web(COLOR_TITLE));

        Path currentDataRoot = DataLocation.getStartupDataRoot();
        TextField dataLocField = new TextField(currentDataRoot.toString());
        dataLocField.setEditable(false);
        dataLocField.setPrefColumnCount(46);
        dataLocField.setStyle("-fx-background-color: #ECEFF3; -fx-text-fill: #667080; "
                + "-fx-border-color: #D2D8E0; -fx-border-radius: 6; -fx-background-radius: 6;");
        dataLocField.setFocusTraversable(false);

        Button changeLocBtn = smallGrayButton("更改数据位置");
        changeLocBtn.setOnAction(e -> openDataLocation.run());

        HBox dataLocRow = new HBox(8, dataLocField, changeLocBtn);
        dataLocRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(dataLocField, Priority.ALWAYS);
        VBox dataLocBox = new VBox(4, dataLocTitle, dataLocRow);

        // 数据备份 / 恢复：备份用 H2 一致性快照，恢复前强制先另存现有数据
        Label backupTitle = new Label("数据备份");
        backupTitle.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        backupTitle.setTextFill(Color.web(COLOR_TITLE));

        Label backupHint = new Label("每天首次启动会自动留一份快照（保留最近 7 天）；也可以手动导出到 U 盘或网盘。");
        backupHint.setWrapText(true);
        backupHint.setFont(Font.font("Microsoft YaHei", 11));
        backupHint.setTextFill(Color.web(COLOR_GRAY));

        Button backupBtn = smallGrayButton("立即备份…");
        Button restoreBtn = smallGrayButton("备份与恢复…");
        Label backupStatus = new Label("");
        backupStatus.setFont(Font.font("Microsoft YaHei", 11));
        backupStatus.setTextFill(Color.web(COLOR_GRAY));
        backupStatus.setWrapText(true);

        backupBtn.setOnAction(e -> runBackupFlow(owner, backupBtn, restoreBtn, backupStatus));
        restoreBtn.setOnAction(e -> showBackupListDialog(owner, backupBtn, restoreBtn, backupStatus));

        HBox backupRow = new HBox(8, backupBtn, restoreBtn, backupStatus);
        backupRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(backupStatus, Priority.ALWAYS);
        VBox backupBox = new VBox(4, backupTitle, backupHint, backupRow);

        box.getChildren().addAll(header, intro, new Region(), dataLocBox, backupBox, sourceBox, updateTitle, updateRow);
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
            Path installer = updateService.download(info, tmpDir, UpdateConfig.getMirrors());
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

    /** 备份：选保存位置 → 后台生成一致性快照 → 提示结果 */
    private static void runBackupFlow(Stage owner, Button backupBtn, Button restoreBtn, Label status) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择备份保存位置");
        chooser.setInitialFileName(BackupService.defaultFileName());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("糖伴备份文件 (*.zip)", "*.zip"));
        Path dataRoot = DataLocation.getStartupDataRoot();
        if (dataRoot.toFile().isDirectory()) {
            chooser.setInitialDirectory(dataRoot.toFile());
        }
        File target = chooser.showSaveDialog(owner);
        if (target == null) {
            return;
        }

        backupBtn.setDisable(true);
        restoreBtn.setDisable(true);
        status.setText("正在备份，请稍候……");

        new Thread(() -> {
            try {
                BackupService.BackupResult r = new BackupService().backupTo(target.toPath());
                Platform.runLater(() -> {
                    status.setText("备份完成：" + r.file.getFileName());
                    showInfo(owner, "备份完成",
                            "数据已备份到：\n" + r.file
                                    + "\n\n大小：" + formatSize(r.size)
                                    + "\n记录条数：" + (r.meta.recordCount < 0 ? "未知" : r.meta.recordCount + " 条")
                                    + "\n\n建议把这个文件再复制一份到 U 盘或网盘，多一层保险。");
                });
            } catch (Exception ex) {
                AppLog.error("界面层备份失败", ex);
                Platform.runLater(() -> {
                    status.setText("备份失败");
                    showInfo(owner, "备份失败", ex.getMessage());
                });
            } finally {
                Platform.runLater(() -> {
                    backupBtn.setDisable(false);
                    restoreBtn.setDisable(false);
                });
            }
        }, "sugarpal-backup").start();
    }

    /**
     * 「备份与恢复」清单：把自动备份、恢复前快照都列出来，可以直接选任意一个恢复。
     *
     * <p>这样"撤销上一次恢复"就不需要单独的按钮了——恢复前生成的快照本身就在列表里。
     */
    private static void showBackupListDialog(Stage owner, Button backupBtn, Button restoreBtn, Label status) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("备份与恢复");
        dialog.setHeaderText(null);
        DialogPane pane = dialog.getDialogPane();
        styleDialogPane(pane);
        ButtonType closeType = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().add(closeType);

        VBox box = new VBox(10);
        box.setPadding(new Insets(12));
        box.setPrefWidth(580);

        Label title = new Label("可恢复的备份");
        title.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 15));
        title.setTextFill(Color.web(COLOR_TITLE));

        Label hint = new Label("「自动备份」每天首次启动时生成，保留最近 "
                + BackupService.AUTO_KEEP + " 天；「恢复前快照」是每次恢复时自动留存的旧数据。"
                + "选任意一条都能回到那个时间点。");
        hint.setWrapText(true);
        hint.setFont(Font.font("Microsoft YaHei", 11));
        hint.setTextFill(Color.web(COLOR_GRAY));

        VBox rows = new VBox(6);
        List<BackupService.BackupEntry> entries;
        try {
            entries = new BackupService().listRestorable();
        } catch (Exception ex) {
            AppLog.warn("读取备份列表失败", ex);
            entries = new java.util.ArrayList<>();
        }
        if (entries.isEmpty()) {
            Label empty = new Label("暂时没有可恢复的备份。点「立即备份…」可以先做一个。");
            empty.setFont(Font.font("Microsoft YaHei", 12));
            empty.setTextFill(Color.web(COLOR_GRAY));
            rows.getChildren().add(empty);
        } else {
            for (BackupService.BackupEntry e : entries) {
                rows.getChildren().add(buildBackupRow(owner, dialog, e, backupBtn, restoreBtn, status));
            }
        }

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(250);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        Button pickBtn = smallGrayButton("选择其他备份文件…");
        pickBtn.setOnAction(e -> {
            dialog.close();
            pickAndRestore(owner, backupBtn, restoreBtn, status);
        });

        box.getChildren().addAll(title, hint, scroll, pickBtn);
        pane.setContent(box);

        Button closeBtn = (Button) pane.lookupButton(closeType);
        if (closeBtn != null) {
            stylePinkClose(closeBtn);
        }
        dialog.showAndWait();
    }

    /** 清单里的一行：类型 / 时间 / 条数与大小 / 恢复按钮 */
    private static HBox buildBackupRow(Stage owner, Dialog<ButtonType> dialog,
                                       BackupService.BackupEntry e,
                                       Button backupBtn, Button restoreBtn, Label status) {
        boolean auto = e.kind.startsWith("自动");

        Label kind = new Label(e.kind);
        kind.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 12));
        kind.setTextFill(Color.web(auto ? COLOR_GREEN_DARK : COLOR_GRAY_DARK));
        kind.setMinWidth(88);

        Label time = new Label(e.timeText);
        time.setFont(Font.font("Microsoft YaHei", 12));
        time.setTextFill(Color.web(COLOR_TEXT));
        time.setMinWidth(140);

        Label detail = new Label((e.recordCount < 0 ? "条数未知" : e.recordCount + " 条")
                + " · " + BackupService.formatSize(e.size));
        detail.setFont(Font.font("Microsoft YaHei", 11));
        detail.setTextFill(Color.web(COLOR_GRAY));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button restore = smallGrayButton("恢复");
        restore.setOnAction(ev -> {
            dialog.close();
            confirmAndRestore(owner, e.path, e.kind, e.recordCount, e.timeText,
                    backupBtn, restoreBtn, status);
        });

        HBox row = new HBox(10, kind, time, detail, spacer, restore);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.setStyle("-fx-background-color: #FDFEFB; -fx-background-radius: 10; "
                + "-fx-border-color: " + COLOR_BORDER + "; -fx-border-radius: 10;");
        return row;
    }

    /** 手动挑一个 zip 备份文件来恢复 */
    private static void pickAndRestore(Stage owner, Button backupBtn, Button restoreBtn, Label status) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要恢复的备份文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("糖伴备份文件 (*.zip)", "*.zip"));
        Path dataRoot = DataLocation.getStartupDataRoot();
        if (dataRoot.toFile().isDirectory()) {
            chooser.setInitialDirectory(dataRoot.toFile());
        }
        File src = chooser.showOpenDialog(owner);
        if (src == null) {
            return;
        }
        BackupService.BackupMeta meta;
        try {
            meta = new BackupService().inspect(src.toPath());
        } catch (Exception ex) {
            showInfo(owner, "无法恢复", ex.getMessage());
            return;
        }
        String kind = meta.official ? "手动备份（" + (meta.version.isEmpty() ? "版本未知" : "v" + meta.version) + "）"
                : "手动备份（无说明文件）";
        String time = meta.createdAt.isEmpty() ? "时间未知" : meta.createdAt;
        confirmAndRestore(owner, src.toPath(), kind, meta.recordCount, time, backupBtn, restoreBtn, status);
    }

    /** 恢复前的统一确认：先对比「当前数据」和「备份数据」，差异大就红字警告 */
    private static void confirmAndRestore(Stage owner, Path source, String kindLabel, int backupCount,
                                          String backupTime, Button backupBtn, Button restoreBtn, Label status) {
        int currentCount = -1;
        try {
            currentCount = new BloodSugarService().getAllRecords().size();
        } catch (Exception ex) {
            AppLog.warn("恢复前统计当前记录数失败", ex);
        }

        VBox content = new VBox(6);
        content.setPrefWidth(470);
        Label l1 = infoLine("备份来源：" + kindLabel);
        Label l2 = infoLine("备份时间：" + backupTime);
        Label l3 = infoLine("备份记录：" + (backupCount < 0 ? "未知" : backupCount + " 条"));
        Label l4 = infoLine("当前数据：" + (currentCount < 0 ? "未知" : currentCount + " 条"));

        Label warn = new Label();
        warn.setWrapText(true);
        warn.setFont(Font.font("Microsoft YaHei", 12));
        if (currentCount >= 0 && backupCount >= 0 && backupCount < currentCount) {
            warn.setText("注意：这个备份比当前数据少 " + (currentCount - backupCount)
                    + " 条记录，恢复后这些记录会从主界面消失。\n"
                    + "（当前数据会先另存为「恢复前快照」，之后仍可在清单里选回来）");
            warn.setTextFill(Color.web("#C0392B"));
        } else if (currentCount >= 0 && backupCount > currentCount) {
            warn.setText("恢复后会多出 " + (backupCount - currentCount) + " 条记录。");
            warn.setTextFill(Color.web(COLOR_GREEN_DARK));
        } else if (currentCount >= 0 && backupCount >= 0) {
            warn.setText("记录条数与当前一致。");
            warn.setTextFill(Color.web(COLOR_GRAY));
        } else {
            warn.setText("条数无法确认，请自行判断是否继续。");
            warn.setTextFill(Color.web(COLOR_GRAY));
        }

        content.getChildren().addAll(l1, l2, l3, l4, warn);

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(owner);
        confirm.setTitle("确认恢复");
        confirm.setHeaderText("要用这个备份覆盖当前数据吗？");
        confirm.getDialogPane().setContent(content);
        ButtonType okBtn = new ButtonType("确认恢复", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        confirm.getButtonTypes().setAll(okBtn, cancelBtn);
        styleAlert(confirm);
        Optional<ButtonType> res = confirm.showAndWait();
        if (res.isEmpty() || res.get() != okBtn) {
            return;
        }

        backupBtn.setDisable(true);
        restoreBtn.setDisable(true);
        status.setText("正在恢复，请稍候……");

        new Thread(() -> {
            try {
                Path safeDir = new BackupService().restoreFrom(source);
                Platform.runLater(() -> {
                    status.setText("恢复完成，需重启生效");
                    offerRestart(owner, safeDir);
                });
            } catch (Exception ex) {
                AppLog.error("界面层恢复失败", ex);
                Platform.runLater(() -> {
                    status.setText("恢复失败");
                    showInfo(owner, "恢复失败", ex.getMessage());
                    backupBtn.setDisable(false);
                    restoreBtn.setDisable(false);
                });
            }
        }, "sugarpal-restore").start();
    }

    private static Label infoLine(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("Microsoft YaHei", 12));
        l.setTextFill(Color.web(COLOR_TEXT));
        return l;
    }

    /** 粉色关闭按钮样式 */
    private static void stylePinkClose(Button btn) {
        btn.setStyle("-fx-background-color: linear-gradient(to bottom, " + COLOR_PINK_LIGHT + ", " + COLOR_PINK + "); "
                + "-fx-text-fill: #8C3B52; -fx-font-size: 13px; -fx-padding: 8 20; "
                + "-fx-background-radius: 18; -fx-border-radius: 18; "
                + "-fx-border-color: #E87FA5; -fx-border-width: 0 0 3 0; "
                + "-fx-cursor: hand; -fx-font-weight: bold;");
        btn.setEffect(new DropShadow(4, 2, 3, Color.web(COLOR_PINK_LIGHT)));
    }

    /**
     * 恢复后数据库连接池已关闭，程序必须退出重开——否则后续读写都会失败。
     * 所以这里只给「立即重启」和「退出程序」两个选项，都会结束当前进程。
     */
    private static void offerRestart(Stage owner, Path safeDir) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("恢复完成");
        alert.setHeaderText("数据已恢复，需要重启糖伴");
        alert.setContentText("恢复前的旧数据已保存到：\n" + safeDir
                + "\n\n数据库连接已关闭，必须重新打开糖伴才能继续使用。");
        ButtonType restartBtn = new ButtonType("立即重启", ButtonBar.ButtonData.OK_DONE);
        ButtonType exitBtn = new ButtonType("退出程序", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(restartBtn, exitBtn);
        styleAlert(alert);
        Optional<ButtonType> r = alert.showAndWait();
        if (r.isPresent() && r.get() == restartBtn) {
            restartSelf();
        } else {
            Platform.exit();
        }
    }

    /** 用当前进程的可执行文件路径重新拉起自己 */
    private static void restartSelf() {
        try {
            String exe = ProcessHandle.current().info().command().orElse(null);
            if (exe != null && !exe.isBlank()) {
                new ProcessBuilder(exe).start();
            }
        } catch (Exception ex) {
            AppLog.warn("自动重启失败，请手动重新打开糖伴", ex);
        }
        Platform.exit();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " 字节";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

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
