/*
 * Copyright (c) 2026 李图康
 * SPDX-License-Identifier: MIT
 */
package com.bloodsugar;

import com.bloodsugar.config.DataLocation;
import com.bloodsugar.service.BackupService;
import com.bloodsugar.ui.MainUI;
import com.bloodsugar.util.AppLog;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/** 程序入口：JavaFX 应用启动类 */
public class BloodSugarApp extends Application {

    @Override
    public void start(Stage stage) {
        // 先把日志挂上：后续任何异常才有地方可查
        AppLog.init(DataLocation.getStartupDataRoot());
        AppLog.info("糖伴启动，数据目录=" + DataLocation.getStartupDataRoot());

        MainUI ui = new MainUI();
        Scene scene = ui.createScene(stage);
        stage.setTitle("糖伴SugarPal");
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/icon.png")));
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(550);
        stage.show();

        // 每天首次启动自动备份一份（后台执行，不拖慢开窗；失败也不影响使用）
        Thread autoBackup = new Thread(() -> new BackupService().autoBackupIfNeeded(),
                "sugarpal-auto-backup");
        autoBackup.setDaemon(true);
        autoBackup.start();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
