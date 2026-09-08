package com.bloodsugar.ui;

import com.bloodsugar.config.AiConfig;
import com.bloodsugar.config.DataLocation;
import com.bloodsugar.config.DatabaseConfig;
import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.service.AiSuggestionService;
import com.bloodsugar.service.AiSuggestionService.AiException;
import com.bloodsugar.service.BloodSugarService;
import com.bloodsugar.service.ExportService;
import com.bloodsugar.util.PeriodClassifier;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class MainUI {

    private final BloodSugarService service = new BloodSugarService();
    private final AiConfig aiConfig = AiConfig.load();
    private final AiSuggestionService aiService = new AiSuggestionService();
    private final ExportService exportService = new ExportService();

    private TableView<BloodSugarRecord> table;
    private LineChart<String, Number> chart;
    private NumberAxis yAxis;
    private ComboBox<String> dateFilterCombo;
    private Label statusLabel;

    private TextField breakfastField, lunchField, dinnerField, extraMealField;

    // 用餐时间面板当前展示的业务日（凌晨4点为新一天起点），用于跨日自动清零判断
    private LocalDate currentMealBusinessDate;
    private Timeline mealRolloverTimer;

    // 记录 X 轴标签 → 记录 ID 的映射，用于图表右键删除
    private Map<String, Integer> chartLabelToId = new HashMap<>();
    private List<BloodSugarRecord> currentChartRecords = new ArrayList<>();

    // 表格复选框选中状态
    private Map<Integer, BooleanProperty> rowSelected = new HashMap<>();

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter CHART_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    // 糖果马卡龙配色，鲜艳一点，别那么灰
    private static final String COLOR_BG = "#EAFCF0";          // 主背景 薄荷糖白
    private static final String COLOR_PANEL = "#FFFFFF";       // 面板 纯白
    private static final String COLOR_BORDER = "#B8EED0";      // 边框 亮薄荷绿
    private static final String COLOR_TEXT = "#3A4A42";        // 正文 深灰绿
    private static final String COLOR_TITLE = "#2BB673";       // 标题 糖果绿
    private static final String COLOR_TITLE_DARK = "#12A35C";  // 标题渐变深端
    private static final String COLOR_TITLE_LIGHT = "#7FE8B0"; // 标题渐变浅端
    private static final String COLOR_GREEN = "#3BC96E";       // 主按钮 亮糖绿
    private static final String COLOR_GREEN_LIGHT = "#7DE3A4"; // 主按钮浅端
    private static final String COLOR_GREEN_DARK = "#22A856";  // 主按钮深端
    private static final String COLOR_BLUE = "#3FB8FF";        // 次按钮 天蓝
    private static final String COLOR_BLUE_LIGHT = "#7FD3FF";  // 次按钮浅端
    private static final String COLOR_BLUE_DARK = "#1E9FE8";   // 次按钮深端
    private static final String COLOR_ORANGE = "#FFA45E";      // 刷新按钮 蜜桃橙
    private static final String COLOR_ORANGE_LIGHT = "#FFC48C";// 刷新按钮浅端
    private static final String COLOR_ORANGE_DARK = "#F58B36"; // 刷新按钮深端
    private static final String COLOR_PINK = "#FF8FB3";        // 樱粉 装饰
    private static final String COLOR_PINK_LIGHT = "#FFC4D9";  // 樱粉浅 装饰
    private static final String COLOR_MEAL_BG = "#FFF6E9";     // 用餐卡片 奶油蜜桃
    private static final String COLOR_MEAL_BG2 = "#FFE3C9";    // 用餐卡片渐变浅端
    private static final String COLOR_MEAL_BORDER = "#FFD2A8"; // 用餐卡片边框
    private static final String COLOR_NORMAL = "#2FC86B";      // 正常血糖 清新亮绿
    private static final String COLOR_HIGH = "#FF8A65";        // 偏高血糖 蜜桃珊瑚
    private static final String COLOR_HEADER = "#D9F9E4";      // 表头 亮薄荷
    private static final String COLOR_STATUS = "#EFFBF2";      // 状态栏 薄荷白

    // 标题用的绿色渐变
    private static final LinearGradient GREEN_GRADIENT = new LinearGradient(0, 0, 1, 1, true,
            CycleMethod.NO_CYCLE,
            new Stop(0, Color.web(COLOR_TITLE_DARK)),
            new Stop(0.55, Color.web(COLOR_TITLE)),
            new Stop(1, Color.web(COLOR_TITLE_LIGHT)));

    // 主标题用的糖果渐变：樱粉→蜜桃橙→亮糖绿
    private static final LinearGradient CANDY_GRADIENT = new LinearGradient(0, 0, 1, 1, true,
            CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#FF7EB3")),
            new Stop(0.5, Color.web("#FFA45E")),
            new Stop(1, Color.web("#2FC86B")));

    public Scene createScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: linear-gradient(to bottom right, #F4FEF8, " + COLOR_BG + ", #CFF7E2);");
        root.setTop(buildToolbar(stage));
        root.setLeft(buildLeftPanel());
        root.setCenter(buildChartPanel());
        root.setBottom(buildStatusBar());
        refreshAll();
        refreshMealPanelFromDb();
        startMealRolloverTimer();
        Scene scene = new Scene(root, 1150, 720);
        // 表格渲染完成后设置表头糖果渐变圆角背景
        Platform.runLater(() -> {
            Node header = table.lookup(".column-header-background");
            if (header != null) {
                header.setStyle("-fx-background-color: linear-gradient(to bottom, " + COLOR_HEADER + ", #B5F0CE); "
                        + "-fx-background-radius: 18 18 0 0; -fx-border-color: transparent;");
            }
        });
        return scene;
    }

    // 顶部工具栏
    private HBox buildToolbar(Stage stage) {
        HBox toolbar = new HBox(10);
        toolbar.setPadding(new Insets(12, 16, 12, 16));
        toolbar.setStyle("-fx-background-color: linear-gradient(to right, #FFFFFF, #E3FBF0, #FFF0F6); "
                + "-fx-border-color: " + COLOR_BORDER + "; -fx-border-width: 0 0 2 0;");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // 糖果圆点装饰条
        Circle dot1 = new Circle(5, Color.web(COLOR_PINK));
        Circle dot2 = new Circle(5, Color.web(COLOR_ORANGE));
        Circle dot3 = new Circle(5, Color.web(COLOR_GREEN));
        HBox dots = new HBox(4, dot1, dot2, dot3);
        dots.setAlignment(Pos.CENTER);

        // 艺术字主标题：糖果渐变 + 白色描边 + 彩色立体阴影
        Text title = new Text("糖伴SugarPal");
        title.setFont(Font.font("YouYuan", FontWeight.BOLD, 24));
        title.setFill(CANDY_GRADIENT);
        title.setStroke(Color.WHITE);
        title.setStrokeWidth(1.2);
        title.setEffect(new DropShadow(6, 3, 3, Color.web("#8FE8B8")));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button addBtn = styledButton("+ 添加记录", COLOR_GREEN_LIGHT, COLOR_GREEN_DARK, "#A9F0C4", COLOR_GREEN_LIGHT, "#1E9E50", "#7DE3A4");
        addBtn.setOnAction(e -> showAddDialog(stage));

        // 日历入口：查看某天数据、补填三餐时间与血糖
        Button calendarBtn = styledButton("日历", "#FFD36E", "#F5A623", "#FFE3A1", "#FFD36E", "#C87F0A", "#FFD36E");
        calendarBtn.setOnAction(e -> showCalendar(stage));

        Button summaryBtn = styledButton("生成总结", COLOR_BLUE_LIGHT, COLOR_BLUE_DARK, "#B5E7FF", COLOR_BLUE_LIGHT, "#1899E0", "#7FD3FF");
        summaryBtn.setOnAction(e -> showSummaryDialog(stage));

        Button aiBtn = styledButton("AI 建议", "#FF9EC5", "#F36AA9", "#FFC0DB", "#FF9EC5", "#D74F8E", "#FFB9D6");
        aiBtn.setOnAction(e -> showAiDialog(stage));

        Button exportBtn = styledButton("导出报告", "#C9B2FF", "#9B6BFF", "#E0D2FF", "#B78DFF", "#7F4FD8", "#CDB4FF");
        exportBtn.setOnAction(e -> showExportDialog(stage));

        // 数据位置设置：查看/修改数据存放目录（重启生效），浅银灰风格区别于功能按钮
        Button dataLocationBtn = styledButton("数据位置", "#C9CDD6", "#98A1AF", "#E2E6EC", "#B4BBC8", "#667080", "#D2D8E0");
        dataLocationBtn.setOnAction(e -> showDataLocationDialog(stage));

        Button refreshBtn = styledButton("刷新", COLOR_ORANGE_LIGHT, COLOR_ORANGE_DARK, "#FFDDB8", COLOR_ORANGE_LIGHT, "#F08A33", "#FFC48C");
        refreshBtn.setOnAction(e -> refreshAll());

        toolbar.getChildren().addAll(dots, title, spacer, addBtn, calendarBtn, summaryBtn, aiBtn, exportBtn, dataLocationBtn, refreshBtn);
        return toolbar;
    }

    /** 糖果按钮样式：亮渐变、大圆角、底部深色边 */
    private String buttonStyle(String from, String to, String border) {
        return "-fx-background-color: linear-gradient(to bottom, " + from + ", " + to + "); "
                + "-fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 11 22; "
                + "-fx-background-radius: 20; -fx-border-radius: 20; "
                + "-fx-border-color: " + border + "; -fx-border-width: 0 0 4 0; "
                + "-fx-cursor: hand; -fx-font-weight: bold;";
    }

    /** 糖果按钮：圆角大、带彩色阴影，鼠标放上去变亮 */
    private Button styledButton(String text, String from, String to, String hoverFrom, String hoverTo,
                                String border, String shadow) {
        Button btn = new Button(text);
        btn.setStyle(buttonStyle(from, to, border));
        btn.setEffect(new DropShadow(5, 3, 4, Color.web(shadow)));
        btn.setOnMouseEntered(e -> btn.setStyle(buttonStyle(hoverFrom, hoverTo, border)));
        btn.setOnMouseExited(e -> btn.setStyle(buttonStyle(from, to, border)));
        return btn;
    }

    /** 渐变艺术字小标题 */
    private Label artLabel(String text, double size) {
        Label label = new Label(text);
        label.setFont(Font.font("YouYuan", FontWeight.BOLD, size));
        label.setTextFill(CANDY_GRADIENT);
        label.setEffect(new DropShadow(2, 1, 1, Color.web("#FFD3E2")));
        return label;
    }

    /** 统一对话框样式：奶油渐变背景、大圆角、渐变按钮 */
    void styleDialogPane(DialogPane pane) {
        pane.setStyle("-fx-background-color: linear-gradient(to bottom right, #FFF6E9, " + COLOR_BG + ", #FFF0F6); "
                + "-fx-background-radius: 20; -fx-border-color: " + COLOR_BORDER + "; -fx-border-radius: 20;");
        Button ok = (Button) pane.lookupButton(ButtonType.OK);
        if (ok != null) {
            ok.setStyle(buttonStyle(COLOR_GREEN_LIGHT, COLOR_GREEN_DARK, "#1E9E50"));
            ok.setEffect(new DropShadow(4, 2, 3, Color.web("#7DE3A4")));
            ok.setOnMouseEntered(e -> ok.setStyle(buttonStyle("#A9F0C4", COLOR_GREEN_LIGHT, "#1E9E50")));
            ok.setOnMouseExited(e -> ok.setStyle(buttonStyle(COLOR_GREEN_LIGHT, COLOR_GREEN_DARK, "#1E9E50")));
        }
        Button cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
        if (cancel != null) {
            String normal = "-fx-background-color: linear-gradient(to bottom, #FFD3E2, #FFA8C5); "
                    + "-fx-text-fill: #8C3B52; -fx-font-size: 13px; -fx-padding: 8 20; "
                    + "-fx-background-radius: 18; -fx-border-radius: 18; "
                    + "-fx-border-color: #E87FA5; -fx-border-width: 0 0 3 0; "
                    + "-fx-cursor: hand; -fx-font-weight: bold;";
            String hover = "-fx-background-color: linear-gradient(to bottom, #FFE0EA, #FFB9D1); "
                    + "-fx-text-fill: #8C3B52; -fx-font-size: 13px; -fx-padding: 8 20; "
                    + "-fx-background-radius: 18; -fx-border-radius: 18; "
                    + "-fx-border-color: #E87FA5; -fx-border-width: 0 0 3 0; "
                    + "-fx-cursor: hand; -fx-font-weight: bold;";
            cancel.setStyle(normal);
            cancel.setEffect(new DropShadow(4, 2, 3, Color.web("#FFC4D9")));
            cancel.setOnMouseEntered(e -> cancel.setStyle(hover));
            cancel.setOnMouseExited(e -> cancel.setStyle(normal));
        }
    }

    // 左侧面板
    private VBox buildLeftPanel() {
        VBox left = new VBox(8);
        left.setPadding(new Insets(12));
        left.setPrefWidth(400);
        left.setStyle("-fx-background-color: " + COLOR_PANEL + "; "
                + "-fx-border-color: " + COLOR_BORDER + "; -fx-border-width: 0 2 0 0;");

        // 糖果圆点装饰条
        HBox deco = new HBox(5);
        deco.getChildren().addAll(
                new Circle(4, Color.web(COLOR_PINK)),
                new Circle(4, Color.web(COLOR_ORANGE)),
                new Circle(4, Color.web(COLOR_GREEN)),
                new Circle(4, Color.web(COLOR_BLUE)));
        Label filterLabel = artLabel("日期筛选", 13);
        dateFilterCombo = new ComboBox<>();
        dateFilterCombo.setPromptText("全部日期");
        dateFilterCombo.setPrefWidth(Double.MAX_VALUE);
        dateFilterCombo.setStyle("-fx-background-radius: 8; -fx-border-radius: 8; "
                + "-fx-border-color: " + COLOR_MEAL_BORDER + "; -fx-background-color: #FFFFFF;");
        dateFilterCombo.setOnAction(e -> refreshTableAndChart());

        Label tableLabel = artLabel("糖伴SugarPal", 13);

        table = new TableView<>();
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setStyle("-fx-background-color: transparent; -fx-background-radius: 12; "
                + "-fx-border-color: " + COLOR_BORDER + "; -fx-border-radius: 12; "
                + "-fx-border-width: 1; -fx-control-inner-background: #FFFFFF; "
                + "-fx-table-cell-border-color: transparent;");

        TableColumn<BloodSugarRecord, Boolean> checkCol = new TableColumn<>("");
        checkCol.setCellValueFactory(p -> {
            BloodSugarRecord rec = p.getValue();
            BooleanProperty prop = rowSelected.computeIfAbsent(rec.getId(), k -> new SimpleBooleanProperty(false));
            // 列表数据变化时清理孤立选项
            prop.addListener((obs, oldV, newV) -> {});
            return prop;
        });
        checkCol.setCellFactory(CheckBoxTableCell.forTableColumn(checkCol));
        checkCol.setPrefWidth(32);
        checkCol.setSortable(false);

        TableColumn<BloodSugarRecord, String> timeCol = new TableColumn<>("时间");
        timeCol.setCellValueFactory(p -> {
            LocalDateTime t = p.getValue().getRecordTime();
            return new javafx.beans.property.SimpleStringProperty(
                    t != null ? t.format(CHART_FMT) : "");
        });
        timeCol.setPrefWidth(85);

        TableColumn<BloodSugarRecord, Double> sugarCol = new TableColumn<>("血糖");
        sugarCol.setCellValueFactory(new PropertyValueFactory<>("bloodSugar"));
        sugarCol.setPrefWidth(55);
        sugarCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null) { setText(null); setStyle(""); return; }
                setText(String.format("%.1f", val));
                BloodSugarRecord row = getTableView().getItems().get(getIndex());
                boolean normal = PeriodClassifier.isNormal(row.getMealPeriod(), val);
                setTextFill(normal ? Color.web(COLOR_NORMAL) : Color.web(COLOR_HIGH));
                setStyle("-fx-font-weight: bold;");
            }
        });

        TableColumn<BloodSugarRecord, String> periodCol = new TableColumn<>("时段");
        periodCol.setCellValueFactory(new PropertyValueFactory<>("mealPeriod"));
        periodCol.setPrefWidth(70);

        TableColumn<BloodSugarRecord, String> typeCol = new TableColumn<>("餐别");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("mealType"));
        typeCol.setPrefWidth(55);

        TableColumn<BloodSugarRecord, String> noteCol = new TableColumn<>("备注");
        noteCol.setCellValueFactory(new PropertyValueFactory<>("note"));
        noteCol.setPrefWidth(105);

        table.getColumns().addAll(checkCol, timeCol, sugarCol, periodCol, typeCol, noteCol);

        HBox btnBar = new HBox(6);
        Button editBtn = new Button("修改");
        editBtn.setStyle("-fx-text-fill: " + COLOR_BLUE + "; -fx-font-size: 12px; -fx-cursor: hand; -fx-font-weight: bold;");
        editBtn.setOnAction(e -> editSelected());

        Button deleteBtn = new Button("删除");
        deleteBtn.setStyle("-fx-text-fill: " + COLOR_HIGH + "; -fx-font-size: 12px; -fx-cursor: hand; -fx-font-weight: bold;");
        deleteBtn.setOnAction(e -> deleteSelected());
        btnBar.getChildren().addAll(editBtn, deleteBtn);

        VBox mealPanel = buildMealPanel();
        Button healthEntryBtn = buildHealthEntryButton();
        VBox.setVgrow(table, Priority.ALWAYS);
        left.getChildren().addAll(deco, filterLabel, dateFilterCombo, tableLabel, table, btnBar, mealPanel, healthEntryBtn);
        return left;
    }

    private VBox buildMealPanel() {
        VBox panel = new VBox(6);
        panel.setPadding(new Insets(12));
        panel.setStyle("-fx-background-color: linear-gradient(to bottom, " + COLOR_MEAL_BG + ", " + COLOR_MEAL_BG2 + "); "
                + "-fx-background-radius: 20; "
                + "-fx-border-color: " + COLOR_MEAL_BORDER + "; -fx-border-radius: 20; "
                + "-fx-border-width: 2;");
        panel.setEffect(new DropShadow(6, 3, 4, Color.web("#FFD2A8")));

        Label title = artLabel("今日用餐时间", 13);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);

        breakfastField = createMealTimeField("早餐");
        lunchField = createMealTimeField("午餐");
        dinnerField = createMealTimeField("晚餐");
        extraMealField = createMealTimeField("加餐");

        Label breakfastLb = new Label("早餐");
        breakfastLb.setTextFill(Color.web(COLOR_TITLE));
        Label lunchLb = new Label("午餐");
        lunchLb.setTextFill(Color.web(COLOR_TITLE));
        Label dinnerLb = new Label("晚餐");
        dinnerLb.setTextFill(Color.web(COLOR_TITLE));
        Label extraLb = new Label("加餐");
        extraLb.setTextFill(Color.web(COLOR_TITLE));

        grid.add(breakfastLb, 0, 0);
        grid.add(breakfastField, 1, 0);
        grid.add(lunchLb, 0, 1);
        grid.add(lunchField, 1, 1);
        grid.add(dinnerLb, 0, 2);
        grid.add(dinnerField, 1, 2);
        grid.add(extraLb, 0, 3);
        grid.add(extraMealField, 1, 3);

        Button saveMealBtn = styledButton("保存用餐时间", COLOR_GREEN_LIGHT, COLOR_GREEN_DARK, "#A9F0C4", COLOR_GREEN_LIGHT, "#1E9E50", "#7DE3A4");
        saveMealBtn.setStyle(saveMealBtn.getStyle() + " -fx-font-size: 12px; -fx-padding: 9 18;");
        String mealNormal = saveMealBtn.getStyle();
        String mealHover = buttonStyle("#A9F0C4", COLOR_GREEN_LIGHT, "#1E9E50") + " -fx-font-size: 12px; -fx-padding: 9 18;";
        saveMealBtn.setOnMouseEntered(e -> saveMealBtn.setStyle(mealHover));
        saveMealBtn.setOnMouseExited(e -> saveMealBtn.setStyle(mealNormal));
        saveMealBtn.setOnAction(e -> saveMealTimes());

        panel.getChildren().addAll(title, grid, saveMealBtn);
        return panel;
    }

    /**
     * 保存左侧面板的用餐时间到 meal_times 表。
     * 同一业务日（凌晨4点边界）同一餐别只保留最新一条，重复保存覆盖旧值。
     */
    private void saveMealTimes() {
        TextField[] fields = {breakfastField, lunchField, dinnerField, extraMealField};
        String[] names = {"早餐", "午餐", "晚餐", "加餐"};
        int savedCount = 0;
        StringBuilder errors = new StringBuilder();
        try {
            for (int i = 0; i < fields.length; i++) {
                String text = fields[i].getText().trim();
                if (text.isEmpty()) continue;
                LocalDateTime mealTime = parseMealField(fields[i]);
                if (mealTime == null) {
                    errors.append(names[i]).append(" 时间格式不正确（应为 HH:mm）\n");
                    continue;
                }
                service.saveMealTime(names[i], mealTime);
                savedCount++;
            }
        } catch (SQLException ex) {
            showAlert("保存用餐时间失败：" + ex.getMessage());
            return;
        }
        if (errors.length() > 0) {
            showAlert(errors.toString());
        }
        if (savedCount > 0) {
            statusLabel.setText("已保存 " + savedCount + " 项用餐时间（同餐别自动覆盖旧值）");
        } else if (errors.length() == 0) {
            statusLabel.setText("请先填写要保存的用餐时间");
        }
    }

    /**
     * 从 meal_times 表加载"当前业务日（凌晨4点为界）"已保存的用餐时间并填入面板，
     * 让输入保存后重启/切界面都一直显示。启动时调用；若当前已进入新业务日，
     * 昨日数据不会被加载（自动清零），保证不显示上一个业务日的用餐时间。
     */
    void refreshMealPanelFromDb() {
        LocalDate biz = PeriodClassifier.getBusinessDate(LocalDateTime.now());
        Map<String, LocalDateTime> saved;
        try {
            saved = service.getMealTimesByBusinessDate(biz);
        } catch (SQLException ex) {
            // 读取失败时保持输入框现状，避免误清掉用户正在填写的内容
            return;
        }
        currentMealBusinessDate = biz;
        breakfastField.setText(formatMealTimeText(saved.get("早餐")));
        lunchField.setText(formatMealTimeText(saved.get("午餐")));
        dinnerField.setText(formatMealTimeText(saved.get("晚餐")));
        extraMealField.setText(formatMealTimeText(saved.get("加餐")));
    }

    private String formatMealTimeText(LocalDateTime t) {
        return t == null ? "" : t.format(TIME_FMT);
    }

    /**
     * 每 30 秒检查一次业务日是否已变化：应用一直开着跨过凌晨 4 点时，
     * 自动把"今日用餐时间"面板刷新成新业务日的用餐时间（新的一天通常为空，即清零）。
     */
    private void startMealRolloverTimer() {
        if (mealRolloverTimer != null) return;
        mealRolloverTimer = new Timeline(new KeyFrame(Duration.seconds(30), e -> {
            try {
                LocalDate biz = PeriodClassifier.getBusinessDate(LocalDateTime.now());
                if (currentMealBusinessDate != null && !biz.equals(currentMealBusinessDate)) {
                    refreshMealPanelFromDb();
                    if (statusLabel != null) {
                        statusLabel.setText("已进入新的一天，今日用餐时间已自动清零（凌晨4点）");
                    }
                }
            } catch (Exception ignored) {
                // 定时检查失败不影响主流程，下一轮会继续尝试
            }
        }));
        mealRolloverTimer.setCycleCount(Animation.INDEFINITE);
        mealRolloverTimer.play();
    }

    /**
     * 多维健康录入入口：糖果风胖按钮，位于"今日用餐时间"卡片下方。
     * 点击打开独立对话框，可分别保存各健康维度，并查看趋势曲线。
     */
    private Button buildHealthEntryButton() {
        Button btn = styledButton("多维健康录入", COLOR_BLUE_LIGHT, COLOR_BLUE_DARK, "#A9E4FF", COLOR_BLUE_LIGHT, "#1E9FE8", "#7FD3FF");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setStyle(btn.getStyle() + " -fx-font-size: 14px; -fx-padding: 14 22;");
        String normal = btn.getStyle();
        String hover = buttonStyle("#A9E4FF", COLOR_BLUE_LIGHT, "#1E9FE8") + " -fx-font-size: 14px; -fx-padding: 14 22;";
        btn.setOnMouseEntered(e -> btn.setStyle(hover));
        btn.setOnMouseExited(e -> btn.setStyle(normal));
        btn.setOnAction(e -> {
            Stage stage = (Stage) table.getScene().getWindow();
            showHealthDialog(stage);
        });
        return btn;
    }

    /**
     * 多维健康录入对话框：胰岛素/碳水/运动/体重/脉搏/血压可分别保存（留空保持原值），
     * 下方 TabPane 按维度展示各健康指标随日期的趋势曲线。
     */
    private void showHealthDialog(Stage owner) {
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

        TextField hInsulinField = createHealthField("胰岛素 (U)");
        TextField hCarbsField = createHealthField("碳水 (g)");
        TextField hActivityField = createHealthField("运动 (分钟)");
        TextField hWeightField = createHealthField("体重 (kg)");
        TextField hPulseField = createHealthField("脉搏 (次/分)");
        TextField hBpField = createHealthField("血压 120/80");

        String[] names = {"胰岛素", "碳水", "运动", "体重", "脉搏", "血压"};
        TextField[] hFields = {hInsulinField, hCarbsField, hActivityField, hWeightField, hPulseField, hBpField};

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        for (int i = 0; i < names.length; i++) {
            Label lb = new Label(names[i]);
            lb.setTextFill(Color.web(COLOR_BLUE_DARK));
            lb.setMinWidth(44);
            final int idx = i;
            Button singleBtn = new Button("保存");
            singleBtn.setStyle("-fx-background-color: linear-gradient(to bottom, #A9E4FF, #3FB8FF); -fx-text-fill: white; "
                    + "-fx-font-size: 11px; -fx-padding: 4 12; -fx-background-radius: 14; -fx-border-radius: 14; "
                    + "-fx-border-color: #1E9FE8; -fx-border-width: 0 0 3 0; -fx-cursor: hand; -fx-font-weight: bold;");
            singleBtn.setOnAction(e -> saveSingleHealthData(hFields[idx], names[idx]));
            grid.add(lb, 0, i);
            grid.add(hFields[i], 1, i);
            grid.add(singleBtn, 2, i);
        }

        Button saveAllBtn = styledButton("一键保存已填项", COLOR_BLUE_LIGHT, COLOR_BLUE_DARK, "#A9E4FF", COLOR_BLUE_LIGHT, "#1E9FE8", "#7FD3FF");
        saveAllBtn.setStyle(saveAllBtn.getStyle() + " -fx-font-size: 13px; -fx-padding: 10 20;");
        saveAllBtn.setOnAction(e -> saveHealthFieldsFromDialog(hFields));

        Label trendTitle = artLabel("各维度趋势曲线（按日期）", 13);

        root.getChildren().addAll(tip, grid, saveAllBtn,
                new javafx.scene.control.Separator(), trendTitle, buildHealthTrendTabs());
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().setPrefSize(620, 620);

        ButtonType closeType = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeType);
        styleDialogPane(dialog.getDialogPane());
        Button closeBtn = (Button) dialog.getDialogPane().lookupButton(closeType);
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

    /** 分别保存：单独保存某一项健康数据到最新一条血糖记录 */
    private void saveSingleHealthData(TextField field, String name) {
        try {
            java.util.List<BloodSugarRecord> all = service.getAllRecords();
            if (all.isEmpty()) {
                showAlert("暂无血糖记录，请先添加一条血糖记录后再保存健康数据");
                return;
            }
            String text = field.getText().trim();
            if (text.isEmpty()) {
                statusLabel.setText("请先填写" + name + "的值");
                return;
            }
            BloodSugarRecord rec = all.get(0); // findAll 按时间倒序，第一条即最新
            if ("血压".equals(name)) {
                if (!text.matches("\\d{2,3}/\\d{2,3}")) {
                    showAlert("血压格式应为 收缩压/舒张压，例如 120/80");
                    return;
                }
                rec.setBloodPressure(text);
            } else {
                double v = Double.parseDouble(text);
                if (v < 0) throw new NumberFormatException(name + "不能为负数");
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
            refreshAll();
            statusLabel.setText("已保存" + name + "到最新记录 " + rec.getRecordTime().format(TIME_FMT));
        } catch (SQLException ex) {
            showAlert("保存健康数据失败：" + ex.getMessage());
        } catch (NumberFormatException ex) {
            showAlert("数字格式有误：" + ex.getMessage());
        }
    }

    /** 一键保存：保存对话框内所有非空字段，留空保持原值 */
    private void saveHealthFieldsFromDialog(TextField[] fields) {
        try {
            java.util.List<BloodSugarRecord> all = service.getAllRecords();
            if (all.isEmpty()) {
                showAlert("暂无血糖记录，请先添加一条血糖记录后再保存健康数据");
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
                    showAlert("血压格式应为 收缩压/舒张压，例如 120/80");
                    return;
                }
                rec.setBloodPressure(bp);
                any = true;
            }

            if (!any) {
                statusLabel.setText("请至少填写一项健康数据");
                return;
            }
            service.updateRecord(rec);
            for (TextField f : fields) f.clear();
            refreshAll();
            statusLabel.setText("已保存健康数据到最新记录 " + rec.getRecordTime().format(TIME_FMT));
        } catch (SQLException ex) {
            showAlert("保存健康数据失败：" + ex.getMessage());
        } catch (NumberFormatException ex) {
            showAlert("数字字段格式有误，请检查填写内容");
        }
    }

    /** 多维健康趋势曲线：TabPane 按维度切换，按日期展示各维度数值变化 */
    private TabPane buildHealthTrendTabs() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefHeight(300);
        tabs.setStyle("-fx-background-color: #FDFEFB; -fx-background-radius: 14; -fx-border-radius: 14; "
                + "-fx-border-color: " + COLOR_BORDER + ";");

        java.util.List<BloodSugarRecord> records;
        try {
            records = service.getAllRecords();
        } catch (SQLException e) {
            records = new java.util.ArrayList<>();
        }
        records.sort(Comparator.comparing(BloodSugarRecord::getRecordTime,
                Comparator.nullsLast(Comparator.naturalOrder())));

        tabs.getTabs().addAll(
                buildDimensionTab("血糖", "mmol/L", records, r -> r.getBloodSugar(), true),
                buildDimensionTab("胰岛素", "U", records, r -> r.getInsulin(), false),
                buildDimensionTab("碳水", "g", records, r -> r.getCarbs(), false),
                buildDimensionTab("运动", "分钟", records, r -> r.getActivity(), false),
                buildDimensionTab("体重", "kg", records, r -> r.getWeight(), false),
                buildDimensionTab("脉搏", "次/分", records, r -> r.getPulse(), false),
                buildDimensionTab("血压(收缩压)", "mmHg", records, r -> parseSystolic(r.getBloodPressure()), false));
        return tabs;
    }

    private Tab buildDimensionTab(String name, String unit, java.util.List<BloodSugarRecord> records,
                                  java.util.function.ToDoubleFunction<BloodSugarRecord> extractor, boolean always) {
        Tab tab = new Tab(name);
        javafx.scene.chart.CategoryAxis xAxis = new javafx.scene.chart.CategoryAxis();
        xAxis.setLabel("日期");
        NumberAxis yAxis = new NumberAxis(name + " (" + unit + ")", 0, 10, 1);
        LineChart<String, Number> line = new LineChart<>(xAxis, yAxis);
        line.setAnimated(false);
        line.setLegendVisible(false);
        line.setCreateSymbols(true);
        line.setPrefHeight(260);
        line.setStyle("-fx-background-color: #FDFEFB; -fx-background-radius: 10; "
                + "-fx-border-color: " + COLOR_BORDER + "; -fx-border-radius: 10; -fx-border-width: 1;");
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName(name);
        boolean hasAny = false;
        for (BloodSugarRecord r : records) {
            if (r.getRecordTime() == null) continue;
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
            case "血糖": return COLOR_NORMAL;
            case "胰岛素": return COLOR_BLUE;
            case "碳水": return COLOR_ORANGE;
            case "运动": return COLOR_PINK;
            case "体重": return COLOR_TITLE;
            case "脉搏": return "#9B6BFF";
            default: return COLOR_PINK;
        }
    }

    /** 解析血压字符串"收缩压/舒张压"，返回收缩压；解析失败返回 -1 */
    private double parseSystolic(String bp) {
        if (bp == null || bp.isBlank()) return -1;
        try {
            return Double.parseDouble(bp.split("/")[0].trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private TextField createHealthField(String placeholder) {
        TextField tf = new TextField();
        tf.setPromptText(placeholder);
        tf.setPrefWidth(110);
        tf.setStyle("-fx-background-radius: 8; -fx-border-radius: 8; "
                + "-fx-border-color: #A8D8FF; -fx-background-color: #FFFFFF;");
        return tf;
    }

    /** 解析数字输入框；空返回 null，非法抛 NumberFormatException */
    private Double parseHealthNumber(TextField field) {
        String text = field.getText().trim();
        if (text.isEmpty()) return null;
        double v = Double.parseDouble(text);
        return v < 0 ? null : v;
    }

    /** 对话框内数字字段解析：空返回 0，非法抛 NumberFormatException */
    private double parseDialogDouble(TextField field, String name) {
        String text = field.getText().trim();
        if (text.isEmpty()) return 0;
        double v = Double.parseDouble(text);
        if (v < 0) throw new NumberFormatException(name + "不能为负数");
        return v;
    }

    private TextField createMealTimeField(String placeholder) {
        TextField tf = new TextField();
        tf.setPromptText(placeholder + " HH:mm");
        tf.setPrefWidth(80);
        tf.setStyle("-fx-background-radius: 8; -fx-border-radius: 8; "
                + "-fx-border-color: " + COLOR_MEAL_BORDER + "; -fx-background-color: #FFFFFF;");
        return tf;
    }

    // 一顿饭的信息：时间 + 名字
    private static class MealTimeInfo {
        final LocalDateTime time;
        final String mealName; // "早餐" / "午餐" / "晚餐" / "加餐"
        MealTimeInfo(LocalDateTime time, String mealName) {
            this.time = time;
            this.mealName = mealName;
        }
    }

    /**
     * 找 recordTime 之前最近的一顿：优先看左侧面板填的时间，
     * 面板没有再看数据库里的历史记录，返回时间+餐名。
     */
    private MealTimeInfo resolveNearestMealTime(LocalDateTime recordTime) {
        MealTimeInfo best = null;

        // 先看面板里填的四个餐别时间（面板带餐名，优先级高）
        best = updateBest(best, parseMealField(breakfastField), "早餐", recordTime);
        best = updateBest(best, parseMealField(lunchField), "午餐", recordTime);
        best = updateBest(best, parseMealField(dinnerField), "晚餐", recordTime);
        best = updateBest(best, parseMealField(extraMealField), "加餐", recordTime);

        // 再看已保存的用餐时间（meal_times 表，同一业务日同餐别只留最新一条），
        // 面板没填但之前保存过的情况用这里；没有餐名就统一叫"用餐"
        try {
            LocalDateTime savedMealTime = service.getLatestSavedMealTime(recordTime);
            if (savedMealTime != null) {
                best = updateBest(best, savedMealTime, "用餐", recordTime);
            }
        } catch (SQLException ignored) { }

        // 再看数据库当天（凌晨4点起）的历史用餐记录，没有餐名就统一叫"用餐"；
        // 当天没吃过就返回 null，最终显示"空腹"
        try {
            LocalDateTime dbMealTime = service.getLatestMealTimeBefore(recordTime);
            if (dbMealTime != null) {
                best = updateBest(best, dbMealTime, "用餐", recordTime);
            }
        } catch (SQLException ignored) { }

        return best;
    }

    private MealTimeInfo updateBest(MealTimeInfo best, LocalDateTime mt, String name, LocalDateTime recordTime) {
        if (mt == null) return best;
        if (mt.isAfter(recordTime)) return best;
        if (best == null || mt.isAfter(best.time)) {
            return new MealTimeInfo(mt, name);
        }
        return best;
    }

    /**
     * 生成餐别描述，比如"早餐后 2小时35分钟"、"空腹"、"睡前"。
     * 当天（凌晨4点起）没吃过饭就直接算空腹。
     */
    private String computeMealTypeDescription(LocalDateTime recordTime, MealTimeInfo mealInfo) {
        if (recordTime == null) return "空腹";
        // 当天没吃过饭，算空腹
        if (mealInfo == null) return "空腹";
        // 22 点以后算睡前
        if (recordTime.toLocalTime().getHour() >= 22) return "睡前";

        long minutes = java.time.Duration.between(mealInfo.time, recordTime).toMinutes();
        if (minutes < 0) return "空腹"; // 比吃饭时间还早，算空腹

        long hours = minutes / 60;
        long mins = minutes % 60;

        if (hours == 0) {
            return mealInfo.mealName + "后 " + mins + "分钟";
        }
        if (mins == 0) {
            return mealInfo.mealName + "后 " + hours + "小时";
        }
        return mealInfo.mealName + "后 " + hours + "小时" + mins + "分钟";
    }

    private LocalDateTime parseMealField(TextField field) {
        String s = field.getText().trim();
        if (s.isEmpty()) return null;
        try {
            // 面板时间按今天的业务日解析，凌晨 0~4 点算前一天
            LocalDate businessToday = PeriodClassifier.getBusinessDate(LocalDateTime.now());
            return LocalDateTime.of(businessToday, LocalTime.parse(s.replace('.', ':')));
        } catch (Exception e) {
            return null;
        }
    }

    // 图表面板
    private VBox buildChartPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(14));
        panel.setStyle("-fx-background-color: " + COLOR_PANEL + "; -fx-background-radius: 20; "
                + "-fx-border-color: " + COLOR_BORDER + "; -fx-border-radius: 20; -fx-border-width: 2;");
        panel.setEffect(new DropShadow(7, 3, 4, Color.web("#8FE8B8")));

        // 糖果圆点装饰条
        HBox deco = new HBox(5);
        deco.getChildren().addAll(
                new Circle(4, Color.web(COLOR_GREEN)),
                new Circle(4, Color.web(COLOR_BLUE)),
                new Circle(4, Color.web(COLOR_ORANGE)),
                new Circle(4, Color.web(COLOR_PINK)));

        Label chartTitle = artLabel("血糖趋势曲线", 14);

        yAxis = new NumberAxis("血糖 (mmol/L)", 0, 20, 1);
        javafx.scene.chart.CategoryAxis xAxis = new javafx.scene.chart.CategoryAxis();
        xAxis.setLabel("时间");

        chart = new LineChart<>(xAxis, yAxis);
        chart.setPrefHeight(520);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setCreateSymbols(true);
        chart.setLegendSide(javafx.geometry.Side.TOP);
        chart.setStyle("-fx-background-color: #FDFEFB; -fx-background-radius: 16; "
                + "-fx-border-color: " + COLOR_BORDER + "; -fx-border-radius: 16; "
                + "-fx-border-width: 1;");
        // 数据点右键菜单
        chart.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                handleChartRightClick();
            }
        });

        panel.getChildren().addAll(deco, chartTitle, chart);
        return panel;
    }

    private void handleChartRightClick() {
        // 挨个数据点找鼠标悬停的那个
        for (XYChart.Series<String, Number> series : chart.getData()) {
            for (XYChart.Data<String, Number> data : series.getData()) {
                Node node = data.getNode();
                if (node != null && node.isHover()) {
                    String label = data.getXValue();
                    Integer id = chartLabelToId.get(label);
                    if (id != null) {
                        showChartDeleteMenu(id, label, data.getYValue().doubleValue(), node);
                    }
                    return;
                }
            }
        }
    }

    private void showChartDeleteMenu(int recordId, String label, double bloodSugar, Node anchor) {
        ContextMenu menu = new ContextMenu();
        MenuItem deleteItem = new MenuItem(String.format("删除 %s (%.1f mmol/L)", label, bloodSugar));
        deleteItem.setStyle("-fx-text-fill: " + COLOR_HIGH + ";");
        deleteItem.setOnAction(e -> {
            try {
                service.deleteRecord(recordId);
                refreshAll();
                statusLabel.setText("已删除记录");
            } catch (SQLException ex) {
                showAlert("删除失败：" + ex.getMessage());
            }
        });
        menu.getItems().add(deleteItem);
        menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    // 底部状态栏
    private HBox buildStatusBar() {
        HBox bar = new HBox();
        bar.setPadding(new Insets(8, 16, 8, 16));
        bar.setStyle("-fx-background-color: linear-gradient(to right, " + COLOR_STATUS + ", #D9FBE9, #FFE9F1); "
                + "-fx-border-color: " + COLOR_BORDER + "; -fx-border-width: 2 0 0 0;");
        statusLabel = new Label("就绪");
        statusLabel.setFont(Font.font("YouYuan", 12));
        statusLabel.setTextFill(Color.web(COLOR_TEXT));
        bar.getChildren().add(statusLabel);
        return bar;
    }

    // 添加记录
    private void showAddDialog(Stage owner) {
        showAddDialog(owner, null);
    }

    // 添加记录；presetDate 非空时为日历"补填"场景，日期预选为选中日期
    void showAddDialog(Stage owner, LocalDate presetDate) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("糖伴SugarPal - 添加血糖记录");
        dialog.setHeaderText(presetDate != null ? "补填 " + presetDate + " 血糖记录" : "请输入血糖测量信息");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(20));

        DatePicker datePicker = new DatePicker(presetDate != null ? presetDate : LocalDate.now());
        grid.add(new Label("测量日期:"), 0, 0);
        grid.add(datePicker, 1, 0);

        TextField timeField = new TextField(LocalTime.now().format(TIME_FMT));
        timeField.setPromptText("HH:mm");
        // 用户输入 "." 时自动换成 ":"
        timeField.setTextFormatter(new TextFormatter<String>(change -> {
            if (change.getText().contains(".")) {
                change.setText(change.getText().replace('.', ':'));
            }
            return change;
        }));
        grid.add(new Label("测量时间:"), 0, 1);
        grid.add(timeField, 1, 1);

        TextField sugarField = new TextField();
        sugarField.setPromptText("例如 5.6");
        grid.add(new Label("血糖值 (mmol/L):"), 0, 2);
        grid.add(sugarField, 1, 2);

        // 餐别自动识别：使用 Label 显示，对话框打开时实时计算
        Label mealTypeLabel = new Label("空腹");
        mealTypeLabel.setStyle("-fx-background-color: linear-gradient(to bottom, " + COLOR_GREEN_LIGHT + ", " + COLOR_GREEN_DARK + "); "
                + "-fx-text-fill: white; "
                + "-fx-font-weight: bold; -fx-padding: 5 14; -fx-background-radius: 14;");
        grid.add(new Label("餐别(自动):"), 0, 3);
        grid.add(mealTypeLabel, 1, 3);

        TextField noteField = new TextField();
        noteField.setPromptText("选填");
        grid.add(new Label("备注:"), 0, 4);
        grid.add(noteField, 1, 4);

        // ── 多维健康录入（与主界面卡片联动，随本次记录一并保存）──
        TextField dInsulinField = createHealthField("胰岛素 (U)");
        TextField dCarbsField = createHealthField("碳水 (g)");
        TextField dActivityField = createHealthField("运动 (分钟)");
        TextField dWeightField = createHealthField("体重 (kg)");
        TextField dPulseField = createHealthField("脉搏 (次/分)");
        TextField dBpField = createHealthField("血压 120/80");

        Label healthTitle = new Label("多维健康（选填）");
        healthTitle.setTextFill(Color.web(COLOR_BLUE_DARK));
        healthTitle.setStyle("-fx-font-weight: bold;");
        grid.add(healthTitle, 0, 5, 2, 1);

        grid.add(new Label("胰岛素:"), 0, 6);
        grid.add(dInsulinField, 1, 6);
        grid.add(new Label("碳水:"), 0, 7);
        grid.add(dCarbsField, 1, 7);
        grid.add(new Label("运动:"), 0, 8);
        grid.add(dActivityField, 1, 8);
        grid.add(new Label("体重:"), 0, 9);
        grid.add(dWeightField, 1, 9);
        grid.add(new Label("脉搏:"), 0, 10);
        grid.add(dPulseField, 1, 10);
        grid.add(new Label("血压:"), 0, 11);
        grid.add(dBpField, 1, 11);

        Label hintLabel = new Label("餐别根据最近用餐记录自动识别，无需手动选择");
        hintLabel.setFont(Font.font(11));
        hintLabel.setTextFill(Color.web("#8A8578"));
        grid.add(hintLabel, 1, 12);

        // 测量日期/时间变化时，实时刷新自动识别的餐别
        Runnable refreshMealType = () -> {
            try {
                LocalDate d = datePicker.getValue();
                LocalTime t = LocalTime.parse(timeField.getText().trim().replace('.', ':'));
                LocalDateTime rt = LocalDateTime.of(d, t);
                MealTimeInfo info = resolveNearestMealTime(rt);
                mealTypeLabel.setText(computeMealTypeDescription(rt, info));
            } catch (Exception ex) {
                mealTypeLabel.setText("空腹");
            }
        };
        datePicker.valueProperty().addListener((obs, o, n) -> refreshMealType.run());
        timeField.textProperty().addListener((obs, o, n) -> refreshMealType.run());

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        styleDialogPane(dialog.getDialogPane());

        // 对话框展示后立即执行一次自动识别
        dialog.setOnShown(e -> refreshMealType.run());

        dialog.setResultConverter(btn -> {
            if (btn != ButtonType.OK) return null;
            try {
                LocalDate date = datePicker.getValue();
                LocalTime time = LocalTime.parse(timeField.getText().trim().replace('.', ':'));
                LocalDateTime recordTime = LocalDateTime.of(date, time);
                double bloodSugar = Double.parseDouble(sugarField.getText().trim());
                if (bloodSugar <= 0 || bloodSugar > 50) {
                    showAlert("血糖值应在 0 ~ 50 mmol/L 之间");
                    return null;
                }
                MealTimeInfo mealInfo = resolveNearestMealTime(recordTime);
                LocalDateTime mealTime = mealInfo != null ? mealInfo.time : null;
                String mealType = computeMealTypeDescription(recordTime, mealInfo);
                // 读取多维健康字段（选填，空视为 0/空）
                double hInsulin = parseDialogDouble(dInsulinField, "胰岛素");
                double hCarbs = parseDialogDouble(dCarbsField, "碳水");
                double hActivity = parseDialogDouble(dActivityField, "运动");
                double hWeight = parseDialogDouble(dWeightField, "体重");
                double hPulse = parseDialogDouble(dPulseField, "脉搏");
                String hBp = dBpField.getText().trim();
                if (!hBp.isEmpty() && !hBp.matches("\\d{2,3}/\\d{2,3}")) {
                    showAlert("血压格式应为 收缩压/舒张压，例如 120/80");
                    return null;
                }
                service.addRecord(recordTime, bloodSugar, mealTime, mealType, noteField.getText().trim(),
                        hInsulin, hCarbs, hActivity, hWeight, hPulse, hBp);
                refreshAll();

                String period = com.bloodsugar.util.PeriodClassifier.classify(recordTime, mealTime);
                showAdviceDialog(owner, bloodSugar, period);
                return btn;
            } catch (Exception ex) {
                showAlert("输入格式有误：" + ex.getMessage());
                return null;
            }
        });
        dialog.showAndWait();
    }

    // 健康建议
    private void showAdviceDialog(Stage owner, double bloodSugar, String period) {
        String advice = generateAdvice(bloodSugar, period);
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("血糖建议");
        alert.setHeaderText(String.format("测量结果：%.1f mmol/L  [%s]", bloodSugar, period));
        Label content = new Label(advice);
        content.setFont(Font.font("Microsoft YaHei", 14));
        content.setWrapText(true);
        content.setMaxWidth(400);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setStyle("-fx-background-color: linear-gradient(to bottom right, " + COLOR_MEAL_BG + ", #FFFFFF, #FFF0F6); "
                + "-fx-background-radius: 20; -fx-border-color: " + COLOR_MEAL_BORDER + "; -fx-border-radius: 20;");
        styleDialogPane(alert.getDialogPane());
        alert.showAndWait();
    }

    private String generateAdvice(double bloodSugar, String period) {
        double[] range = PeriodClassifier.getNormalRange(period);
        if (bloodSugar < 3.0)
            return "⚠ 血糖严重偏低，请立即补充糖分（喝果汁、吃糖果），15分钟后复测。如持续偏低请就医。";
        if (bloodSugar < 3.9)
            return "血糖偏低，建议立即进食含碳水化合物的食物（饼干、面包等），稍后复测。";
        if (bloodSugar >= 3.9 && bloodSugar <= range[1]) {
            if (period != null && period.contains("空腹"))
                return "空腹血糖正常，控制良好。建议继续保持规律饮食和运动。";
            return "餐后血糖在正常范围内，饮食控制得当。建议餐后适度散步有助于血糖平稳。";
        }
        if (bloodSugar > range[1] && bloodSugar <= range[1] + 2.0)
            return "血糖略偏高，注意本次饮食中的碳水化合物摄入量（米饭/面食/甜食），建议餐后散步30分钟。";
        return String.format("血糖明显偏高（正常上限 %.1f mmol/L），请避免高糖食物，增加运动量。如多次偏高请咨询医生。", range[1]);
    }

    // AI 智能建议
    private void showAiDialog(Stage owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("AI 智能控糖建议");
        dialog.setHeaderText("让大模型根据近期血糖记录，生成个性化控糖建议");

        VBox box = new VBox(10);
        box.setPadding(new Insets(10));

        // 模型切换：下拉框一键切换智谱 GLM / DeepSeek
        Label modelLb = new Label("模型：");
        modelLb.setTextFill(Color.web(COLOR_TITLE));
        ComboBox<String> modelCombo = new ComboBox<>();
        for (String pid : AiConfig.getProviderIds()) {
            modelCombo.getItems().add(AiConfig.displayName(pid));
        }
        modelCombo.setValue(AiConfig.displayName(aiConfig.getActiveProvider()));
        modelCombo.setStyle("-fx-background-radius: 8; -fx-border-radius: 8; "
                + "-fx-border-color: " + COLOR_MEAL_BORDER + "; -fx-background-color: #FFFFFF;");

        Button settingsBtn = new Button("设置 API Key");
        settingsBtn.setStyle("-fx-text-fill: " + COLOR_BLUE + "; -fx-font-size: 12px; -fx-cursor: hand; -fx-font-weight: bold;");
        settingsBtn.setOnAction(e -> showAiSettingsDialog(owner, aiConfig));

        Label keyStatus = new Label();
        keyStatus.setFont(Font.font(11));

        HBox topRow = new HBox(8, modelLb, modelCombo, settingsBtn, keyStatus);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Button generateBtn = styledButton("生成建议", COLOR_GREEN_LIGHT, COLOR_GREEN_DARK, "#A9F0C4", COLOR_GREEN_LIGHT, "#1E9E50", "#7DE3A4");

        TextArea resultArea = new TextArea();
        resultArea.setEditable(false);
        resultArea.setWrapText(true);
        resultArea.setPromptText("点击「生成建议」，这里会显示 AI 给出的控糖建议……");
        resultArea.setFont(Font.font("Microsoft YaHei", 13));
        resultArea.setPrefSize(560, 360);
        resultArea.setStyle("-fx-control-inner-background: #FDFEFB; -fx-background-radius: 14; "
                + "-fx-border-radius: 14; -fx-border-color: " + COLOR_BORDER + ";");

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
            aiConfig.setActiveProvider(provider);
            try {
                aiConfig.save();
            } catch (RuntimeException ignored) {
                // 保存失败不阻塞切换，下次打开仍可用内存中的选择
            }
            boolean hasKey = aiConfig.getApiKey(provider) != null && !aiConfig.getApiKey(provider).isBlank();
            keyStatus.setText(hasKey ? "✓ Key 已配置" : "⚠ 未配置 Key");
            keyStatus.setTextFill(hasKey ? Color.web(COLOR_NORMAL) : Color.web(COLOR_HIGH));
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
                    List<BloodSugarRecord> records = service.getAllRecords();
                    String advice = aiService.generateSuggestion(records, aiConfig, provider);
                    Platform.runLater(() -> {
                        resultArea.setText(advice);
                        statusLabel.setText("AI 建议已生成（" + AiConfig.displayName(provider) + "）");
                    });
                } catch (AiException ex) {
                    Platform.runLater(() -> {
                        resultArea.clear();
                        showAlert(ex.getMessage());
                    });
                } catch (SQLException ex) {
                    Platform.runLater(() -> {
                        resultArea.clear();
                        showAlert("读取血糖记录失败：" + ex.getMessage());
                    });
                } finally {
                    Platform.runLater(() -> generateBtn.setDisable(false));
                }
            }).start();
        });

        dialog.getDialogPane().setContent(box);
        ButtonType closeType = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeType);
        styleDialogPane(dialog.getDialogPane());
        Button closeBtn = (Button) dialog.getDialogPane().lookupButton(closeType);
        if (closeBtn != null) {
            String normal = "-fx-background-color: linear-gradient(to bottom, #FFD3E2, #FFA8C5); "
                    + "-fx-text-fill: #8C3B52; -fx-font-size: 13px; -fx-padding: 8 20; "
                    + "-fx-background-radius: 18; -fx-border-radius: 18; "
                    + "-fx-border-color: #E87FA5; -fx-border-width: 0 0 3 0; "
                    + "-fx-cursor: hand; -fx-font-weight: bold;";
            closeBtn.setStyle(normal);
            closeBtn.setEffect(new DropShadow(4, 2, 3, Color.web("#FFC4D9")));
        }
        dialog.showAndWait();
    }

    // 导出报告：选择格式（Excel/PDF）和保存位置，导出重要数据到报告文件
    private void showExportDialog(Stage owner) {
        // 导出范围保持原语义：选中"全部"时导出全部记录（不受主界面近8条展示限制），
        // 选中具体日期时导出该日记录
        List<BloodSugarRecord> fetched;
        try {
            String selected = dateFilterCombo.getValue();
            if (selected == null || "全部".equals(selected)) {
                fetched = service.getAllRecords();
            } else {
                fetched = currentChartRecords;
            }
        } catch (SQLException ex) {
            fetched = currentChartRecords;
        }
        final List<BloodSugarRecord> records = fetched;
        if (records == null || records.isEmpty()) {
            showAlert("暂无记录可导出，请先添加血糖数据");
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("导出报告");
        dialog.setHeaderText("导出重要血糖数据到 Excel / PDF 报告");

        VBox box = new VBox(10);
        box.setPadding(new Insets(10));

        Label info = new Label("将导出当前筛选范围的 " + records.size() + " 条记录（明细、统计汇总、按业务日/餐别汇总）");
        info.setFont(Font.font("Microsoft YaHei", 12));
        info.setTextFill(Color.web(COLOR_TEXT));

        Label formatLb = new Label("导出格式：");
        formatLb.setFont(Font.font("Microsoft YaHei", 13));
        formatLb.setTextFill(Color.web(COLOR_TITLE));

        ToggleGroup formatGroup = new ToggleGroup();
        RadioButton excelRb = new RadioButton("Excel (.xlsx)");
        RadioButton pdfRb = new RadioButton("PDF (.pdf)");
        excelRb.setToggleGroup(formatGroup);
        pdfRb.setToggleGroup(formatGroup);
        excelRb.setSelected(true);
        excelRb.setStyle("-fx-text-fill: " + COLOR_TEXT + "; -fx-font-size: 13px;");
        pdfRb.setStyle("-fx-text-fill: " + COLOR_TEXT + "; -fx-font-size: 13px;");
        HBox formatRow = new HBox(16, excelRb, pdfRb);
        formatRow.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("Excel 包含明细/统计/按业务日/按餐别 4 个表格；PDF 为排版报告（自动嵌入中文字体）");
        hint.setFont(Font.font(11));
        hint.setTextFill(Color.web("#8A8578"));

        Button exportBtn = styledButton("选择位置并导出", COLOR_GREEN_LIGHT, COLOR_GREEN_DARK, "#A9F0C4", COLOR_GREEN_LIGHT, "#1E9E50", "#7DE3A4");
        exportBtn.setOnAction(e -> {
            boolean excel = excelRb.isSelected();
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择导出保存位置");
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            if (excel) {
                chooser.setInitialFileName("糖伴SugarPal-血糖记录报告_" + stamp + ".xlsx");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel 文件 (*.xlsx)", "*.xlsx"));
            } else {
                chooser.setInitialFileName("糖伴SugarPal-血糖记录报告_" + stamp + ".pdf");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF 文件 (*.pdf)", "*.pdf"));
            }
            java.io.File target = chooser.showSaveDialog(owner);
            if (target == null) return;
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
                        styleDialogPane(ok.getDialogPane());
                        ok.showAndWait();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> showAlert("导出失败：" + ex.getMessage()));
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
        ButtonType closeType = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(closeType);
        styleDialogPane(dialog.getDialogPane());
        Button closeBtn = (Button) dialog.getDialogPane().lookupButton(closeType);
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

    // AI 模型设置：内置各服务商 + 自定义 OpenAI 兼容，每个模型一套接口地址/模型名/API Key
    private void showAiSettingsDialog(Stage owner, AiConfig config) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("AI 模型设置");
        dialog.setHeaderText("为各模型填写 API Key 与接口配置（Key 仅保存在本地配置文件，不会上传、不写入日志）");

        VBox cards = new VBox(14);
        cards.setPadding(new Insets(6));

        java.util.Map<String, TextField> urlFields = new java.util.HashMap<>();
        java.util.Map<String, TextField> modelFields = new java.util.HashMap<>();
        java.util.Map<String, TextField> keyFields = new java.util.HashMap<>();

        for (String pid : AiConfig.getProviderIds()) {
            GridPane card = new GridPane();
            card.setHgap(10);
            card.setVgap(8);
            card.setPadding(new Insets(14));
            card.setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 12; -fx-border-radius: 12; "
                    + "-fx-border-color: " + COLOR_BORDER + ";");

            Label title = artLabel(AiConfig.displayName(pid), 12);
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
        styleDialogPane(dialog.getDialogPane());

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
                statusLabel.setText("AI 配置已保存");
            } catch (RuntimeException ex) {
                showAlert("保存配置失败：" + ex.getMessage());
            }
            return btn;
        });
        dialog.showAndWait();
    }

    // 删除记录相关
    private void deleteSelected() {
        List<BloodSugarRecord> selected = getCheckedRecords();
        if (selected.isEmpty()) {
            showAlert("请先勾选要删除的记录");
            return;
        }
        String names = selected.stream()
                .map(r -> r.getRecordTime() != null ? r.getRecordTime().format(TIME_FMT) + " " + r.getBloodSugar() : "")
                .collect(Collectors.joining("、"));
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("确定要删除这 " + selected.size() + " 条记录吗？");
        confirm.setContentText(names);
        confirm.showAndWait().ifPresent(r -> {
            if (r == ButtonType.OK) {
                try {
                    for (BloodSugarRecord rec : selected) {
                        service.deleteRecord(rec.getId());
                    }
                    refreshAll();
                    statusLabel.setText("已删除 " + selected.size() + " 条");
                } catch (SQLException e) {
                    showAlert("删除失败：" + e.getMessage());
                }
            }
        });
    }

    private void editSelected() {
        List<BloodSugarRecord> selected = getCheckedRecords();
        if (selected.isEmpty()) {
            showAlert("请先勾选一条记录进行修改");
            return;
        }
        if (selected.size() > 1) {
            showAlert("一次只能修改一条记录，请只勾选一条");
            return;
        }
        Stage stage = (Stage) table.getScene().getWindow();
        showEditDialog(stage, selected.get(0));
    }

    private List<BloodSugarRecord> getCheckedRecords() {
        return table.getItems().stream()
                .filter(r -> {
                    BooleanProperty prop = rowSelected.get(r.getId());
                    return prop != null && prop.get();
                })
                .collect(Collectors.toList());
    }

    private void showEditDialog(Stage owner, BloodSugarRecord rec) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("糖伴SugarPal - 修改血糖记录");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(20));

        LocalDateTime rt = rec.getRecordTime();
        DatePicker datePicker = new DatePicker(
                rt != null ? rt.toLocalDate() : LocalDate.now());
        grid.add(new Label("测量日期:"), 0, 0);
        grid.add(datePicker, 1, 0);

        TextField timeField = new TextField(
                rt != null ? rt.format(TIME_FMT) : LocalTime.now().format(TIME_FMT));
        timeField.setPromptText("HH:mm");
        // 用户输入 "." 时自动换成 ":"
        timeField.setTextFormatter(new TextFormatter<String>(change -> {
            if (change.getText().contains(".")) {
                change.setText(change.getText().replace('.', ':'));
            }
            return change;
        }));
        grid.add(new Label("测量时间:"), 0, 1);
        grid.add(timeField, 1, 1);

        TextField sugarField = new TextField(String.valueOf(rec.getBloodSugar()));
        sugarField.setPromptText("例如 5.6");
        grid.add(new Label("血糖值 (mmol/L):"), 0, 2);
        grid.add(sugarField, 1, 2);

        // 餐别自动识别（编辑时仍按新时间重新计算）
        Label editMealTypeLabel = new Label("空腹");
        editMealTypeLabel.setStyle("-fx-background-color: linear-gradient(to bottom, " + COLOR_GREEN_LIGHT + ", " + COLOR_GREEN_DARK + "); "
                + "-fx-text-fill: white; "
                + "-fx-font-weight: bold; -fx-padding: 5 14; -fx-background-radius: 14;");
        grid.add(new Label("餐别(自动):"), 0, 3);
        grid.add(editMealTypeLabel, 1, 3);

        TextField noteField = new TextField(rec.getNote() != null ? rec.getNote() : "");
        noteField.setPromptText("选填");
        grid.add(new Label("备注:"), 0, 4);
        grid.add(noteField, 1, 4);

        // ── 多维健康录入（编辑时一并修改）──
        TextField dInsulinField = createHealthField("胰岛素 (U)");
        dInsulinField.setText(rec.getInsulin() > 0 ? String.valueOf(rec.getInsulin()) : "");
        TextField dCarbsField = createHealthField("碳水 (g)");
        dCarbsField.setText(rec.getCarbs() > 0 ? String.valueOf(rec.getCarbs()) : "");
        TextField dActivityField = createHealthField("运动 (分钟)");
        dActivityField.setText(rec.getActivity() > 0 ? String.valueOf(rec.getActivity()) : "");
        TextField dWeightField = createHealthField("体重 (kg)");
        dWeightField.setText(rec.getWeight() > 0 ? String.valueOf(rec.getWeight()) : "");
        TextField dPulseField = createHealthField("脉搏 (次/分)");
        dPulseField.setText(rec.getPulse() > 0 ? String.valueOf(rec.getPulse()) : "");
        TextField dBpField = createHealthField("血压 120/80");
        dBpField.setText(rec.getBloodPressure() != null ? rec.getBloodPressure() : "");

        Label healthTitle = new Label("多维健康（选填）");
        healthTitle.setTextFill(Color.web(COLOR_BLUE_DARK));
        healthTitle.setStyle("-fx-font-weight: bold;");
        grid.add(healthTitle, 0, 5, 2, 1);

        grid.add(new Label("胰岛素:"), 0, 6);
        grid.add(dInsulinField, 1, 6);
        grid.add(new Label("碳水:"), 0, 7);
        grid.add(dCarbsField, 1, 7);
        grid.add(new Label("运动:"), 0, 8);
        grid.add(dActivityField, 1, 8);
        grid.add(new Label("体重:"), 0, 9);
        grid.add(dWeightField, 1, 9);
        grid.add(new Label("脉搏:"), 0, 10);
        grid.add(dPulseField, 1, 10);
        grid.add(new Label("血压:"), 0, 11);
        grid.add(dBpField, 1, 11);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        styleDialogPane(dialog.getDialogPane());

        // 日期/时间变化时实时刷新餐别
        Runnable refreshEditMealType = () -> {
            try {
                LocalDate d = datePicker.getValue();
                LocalTime t = LocalTime.parse(timeField.getText().trim().replace('.', ':'));
                LocalDateTime newRt = LocalDateTime.of(d, t);
                MealTimeInfo info = resolveNearestMealTime(newRt);
                editMealTypeLabel.setText(computeMealTypeDescription(newRt, info));
            } catch (Exception ex) {
                editMealTypeLabel.setText("空腹");
            }
        };
        datePicker.valueProperty().addListener((obs, o, n) -> refreshEditMealType.run());
        timeField.textProperty().addListener((obs, o, n) -> refreshEditMealType.run());

        // 对话框展示后立即执行一次自动识别
        dialog.setOnShown(e -> refreshEditMealType.run());

        dialog.setResultConverter(btn -> {
            if (btn != ButtonType.OK) return null;
            try {
                LocalDate date = datePicker.getValue();
                LocalTime time = LocalTime.parse(timeField.getText().trim().replace('.', ':'));
                double bloodSugar = Double.parseDouble(sugarField.getText().trim());
                if (bloodSugar <= 0 || bloodSugar > 50) {
                    showAlert("血糖值应在 0 ~ 50 mmol/L 之间");
                    return null;
                }
                LocalDateTime recordTime = LocalDateTime.of(date, time);
                MealTimeInfo mealInfo = resolveNearestMealTime(recordTime);
                rec.setRecordTime(recordTime);
                rec.setBloodSugar(bloodSugar);
                rec.setMealTime(mealInfo != null ? mealInfo.time : null);
                rec.setMealType(computeMealTypeDescription(recordTime, mealInfo));
                rec.setNote(noteField.getText().trim());
                // 多维健康字段
                rec.setInsulin(parseDialogDouble(dInsulinField, "胰岛素"));
                rec.setCarbs(parseDialogDouble(dCarbsField, "碳水"));
                rec.setActivity(parseDialogDouble(dActivityField, "运动"));
                rec.setWeight(parseDialogDouble(dWeightField, "体重"));
                rec.setPulse(parseDialogDouble(dPulseField, "脉搏"));
                String eBp = dBpField.getText().trim();
                if (!eBp.isEmpty() && !eBp.matches("\\d{2,3}/\\d{2,3}")) {
                    showAlert("血压格式应为 收缩压/舒张压，例如 120/80");
                    return null;
                }
                rec.setBloodPressure(eBp);
                service.updateRecord(rec);
                // 更新后取消勾选
                BooleanProperty prop = rowSelected.get(rec.getId());
                if (prop != null) prop.set(false);
                refreshAll();
                statusLabel.setText("已更新");
                return btn;
            } catch (Exception ex) {
                showAlert("输入格式有误：" + ex.getMessage());
                return null;
            }
        });
        dialog.showAndWait();
    }

    // 总结报告
    private void showSummaryDialog(Stage owner) {
        if (currentChartRecords.isEmpty()) {
            showAlert("暂无记录，请先添加血糖数据");
            return;
        }

        List<BloodSugarRecord> records = currentChartRecords;
        int total = records.size();

        // 基础统计
        DoubleSummaryStatistics stats = records.stream()
                .mapToDouble(BloodSugarRecord::getBloodSugar).summaryStatistics();

        long normalCount = records.stream()
                .filter(r -> PeriodClassifier.isNormal(r.getMealPeriod(), r.getBloodSugar())).count();

        int highCount = (int) (total - normalCount);

        // 按空腹/餐后分组
        List<BloodSugarRecord> fasting = records.stream()
                .filter(r -> "空腹".equals(r.getMealPeriod()))
                .sorted(Comparator.comparing(BloodSugarRecord::getRecordTime))
                .collect(Collectors.toList());

        List<BloodSugarRecord> post2h = records.stream()
                .filter(r -> "餐后2h".equals(r.getMealPeriod()))
                .sorted(Comparator.comparing(BloodSugarRecord::getRecordTime))
                .collect(Collectors.toList());

        List<BloodSugarRecord> post1h = records.stream()
                .filter(r -> "餐后1h".equals(r.getMealPeriod()))
                .collect(Collectors.toList());

        List<BloodSugarRecord> post3h = records.stream()
                .filter(r -> "餐后3h".equals(r.getMealPeriod()))
                .collect(Collectors.toList());

        // 糖尿病风险评估
        String diabetesRisk = assessDiabetesRisk(fasting, post2h, post1h, records);
        String insulinRisk = assessInsulinResistanceRisk(fasting, post1h, post2h, records);

        // 构建总结文本
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("记录总数：%d 条\n", total));
        sb.append(String.format("测量时间范围：%s  ~  %s\n\n",
                records.get(0).getRecordTime().format(CHART_FMT),
                records.get(records.size() - 1).getRecordTime().format(CHART_FMT)));

        sb.append("── 血糖统计 ──\n");
        sb.append(String.format("  平均值：%.1f mmol/L\n", stats.getAverage()));
        sb.append(String.format("  最高值：%.1f mmol/L\n", stats.getMax()));
        sb.append(String.format("  最低值：%.1f mmol/L\n", stats.getMin()));
        sb.append(String.format("  正常率：%d/%d (%.0f%%)\n", normalCount, total, 100.0 * normalCount / total));
        sb.append(String.format("  偏高次数：%d 次\n\n", highCount));

        if (!fasting.isEmpty()) {
            DoubleSummaryStatistics fs = fasting.stream().mapToDouble(BloodSugarRecord::getBloodSugar).summaryStatistics();
            sb.append("── 空腹血糖（" + fasting.size() + " 次）──\n");
            sb.append(String.format("  平均：%.1f  最高：%.1f  最低：%.1f\n\n", fs.getAverage(), fs.getMax(), fs.getMin()));
        }
        if (!post1h.isEmpty()) {
            DoubleSummaryStatistics ps = post1h.stream().mapToDouble(BloodSugarRecord::getBloodSugar).summaryStatistics();
            sb.append("── 餐后1h（" + post1h.size() + " 次）──\n");
            sb.append(String.format("  平均：%.1f  最高：%.1f  最低：%.1f\n\n", ps.getAverage(), ps.getMax(), ps.getMin()));
        }
        if (!post2h.isEmpty()) {
            DoubleSummaryStatistics ps = post2h.stream().mapToDouble(BloodSugarRecord::getBloodSugar).summaryStatistics();
            sb.append("── 餐后2h（" + post2h.size() + " 次）──\n");
            sb.append(String.format("  平均：%.1f  最高：%.1f  最低：%.1f\n\n", ps.getAverage(), ps.getMax(), ps.getMin()));
        }
        if (!post3h.isEmpty()) {
            DoubleSummaryStatistics ps = post3h.stream().mapToDouble(BloodSugarRecord::getBloodSugar).summaryStatistics();
            sb.append("── 餐后3h（" + post3h.size() + " 次）──\n");
            sb.append(String.format("  平均：%.1f  最高：%.1f  最低：%.1f\n\n", ps.getAverage(), ps.getMax(), ps.getMin()));
        }

        sb.append("── 估算 HbA1c（近 90 天）──\n");
        try {
            Double hba1c = service.getHbA1cEstimate();
            Double recentAvg = service.getRecentAvgBloodSugar();
            long recentCount = service.getRecentRecordCount();
            if (hba1c == null || recentAvg == null || recentCount == 0) {
                sb.append("  近 90 天暂无数据，无法估算。\n\n");
            } else {
                sb.append(String.format("  近 90 天记录：%d 条\n", recentCount));
                sb.append(String.format("  平均血糖：%.1f mmol/L（≈ %.0f mg/dL）\n", recentAvg, recentAvg * 18.0));
                sb.append(String.format("  估算 HbA1c：%.1f %%\n", hba1c));
                if (hba1c < 5.7) {
                    sb.append("  趋势：低于 5.7%，处于正常范围，继续保持。\n");
                } else if (hba1c < 6.5) {
                    sb.append("  趋势：5.7% ~ 6.5%，处于糖尿病前期区间，建议控制饮食、增加运动。\n");
                } else {
                    sb.append("  趋势：≥ 6.5%，符合糖尿病诊断参考标准，建议尽快就医做正规 HbA1c 检测。\n");
                }
                sb.append("  （公式：mmol/L ×18 → mg/dL，HbA1c% ≈ (mg/dL + 46.7) / 28.7）\n\n");
            }
        } catch (SQLException ex) {
            sb.append("  估算 HbA1c 失败：" + ex.getMessage() + "\n\n");
        }

        sb.append("── 健康数据汇总（近 30 天）──\n");
        try {
            sb.append(buildHealthSummaryText(service.getAllRecords()));
        } catch (SQLException ex) {
            sb.append("  读取健康数据失败：" + ex.getMessage() + "\n\n");
        }

        sb.append("── 风险评估 ──\n");
        sb.append(diabetesRisk).append("\n");
        sb.append(insulinRisk).append("\n\n");
        sb.append("⚠ 以上为程序自动分析，仅供参考，不能替代医生诊断。如有疑虑请及时就医。");

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("血糖总结报告");
        alert.setHeaderText("统计数据与风险评估");

        TextArea textArea = new TextArea(sb.toString());
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setFont(Font.font("Microsoft YaHei", 13));
        textArea.setPrefSize(520, 440);
        textArea.setStyle("-fx-control-inner-background: #FDFEFB; -fx-background-radius: 14; "
                + "-fx-border-radius: 14; -fx-border-color: " + COLOR_BORDER + ";");
        alert.getDialogPane().setContent(textArea);
        styleDialogPane(alert.getDialogPane());
        alert.showAndWait();
    }

    /** 近 30 天健康数据汇总文本：均值/最新值/趋势，各维度有数据才展示 */
    private String buildHealthSummaryText(List<BloodSugarRecord> all) {
        StringBuilder sb = new StringBuilder();
        if (all == null || all.isEmpty()) {
            sb.append("  暂无记录。\n\n");
            return sb.toString();
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusDays(30);
        List<BloodSugarRecord> r30 = all.stream()
                .filter(r -> r.getRecordTime() != null && !r.getRecordTime().isBefore(cutoff))
                .sorted(Comparator.comparing(BloodSugarRecord::getRecordTime))
                .collect(Collectors.toList());
        if (r30.isEmpty()) {
            sb.append("  近 30 天暂无记录。\n\n");
            return sb.toString();
        }
        sb.append(String.format("  近 30 天记录：%d 条\n", r30.size()));

        double insulinAvg = avgPositive(r30, BloodSugarRecord::getInsulin);
        if (insulinAvg > 0) sb.append(String.format("  胰岛素平均：%.1f U/次\n", insulinAvg));
        double carbsAvg = avgPositive(r30, BloodSugarRecord::getCarbs);
        if (carbsAvg > 0) sb.append(String.format("  碳水平均：%.0f g/次\n", carbsAvg));
        double activityAvg = avgPositive(r30, BloodSugarRecord::getActivity);
        if (activityAvg > 0) sb.append(String.format("  运动平均：%.0f 分钟/次\n", activityAvg));
        double pulseAvg = avgPositive(r30, BloodSugarRecord::getPulse);
        if (pulseAvg > 0) sb.append(String.format("  脉搏平均：%.0f 次/分\n", pulseAvg));

        BloodSugarRecord last = r30.get(r30.size() - 1);
        if (last.getWeight() > 0) sb.append(String.format("  最新体重：%.1f kg\n", last.getWeight()));
        if (last.getBloodPressure() != null && !last.getBloodPressure().isBlank()) {
            sb.append("  最新血压：" + last.getBloodPressure() + "\n");
        }

        BloodSugarRecord first = r30.get(0);
        if (first.getWeight() > 0 && last.getWeight() > 0) {
            double diff = last.getWeight() - first.getWeight();
            sb.append(String.format("  体重趋势：%.1f → %.1f kg（%s%.1f kg）\n", first.getWeight(), last.getWeight(),
                    diff >= 0 ? "+" : "", diff));
        }
        sb.append("\n");
        return sb.toString();
    }

    /** 有值记录的均值（只统计 > 0 的项） */
    private double avgPositive(List<BloodSugarRecord> records, java.util.function.ToDoubleFunction<BloodSugarRecord> fn) {
        return records.stream().mapToDouble(fn).filter(v -> v > 0).average().orElse(0);
    }

    private String assessDiabetesRisk(List<BloodSugarRecord> fasting, List<BloodSugarRecord> post2h,
            List<BloodSugarRecord> post1h, List<BloodSugarRecord> all) {
        StringBuilder risk = new StringBuilder();

        boolean fastingHigh = false, fastingImpaired = false;
        if (!fasting.isEmpty()) {
            double maxFasting = fasting.stream().mapToDouble(BloodSugarRecord::getBloodSugar).max().orElse(0);
            double avgFasting = fasting.stream().mapToDouble(BloodSugarRecord::getBloodSugar).average().orElse(0);
            if (maxFasting >= 7.0) {
                fastingHigh = true;
            } else if (maxFasting >= 6.1) {
                fastingImpaired = true;
            }
            risk.append(String.format("  空腹最高 %.1f，平均 %.1f\n", maxFasting, avgFasting));
        }

        boolean post2hHigh = false, post2hImpaired = false;
        if (!post2h.isEmpty()) {
            double maxPost2h = post2h.stream().mapToDouble(BloodSugarRecord::getBloodSugar).max().orElse(0);
            if (maxPost2h >= 11.1) {
                post2hHigh = true;
            } else if (maxPost2h >= 7.8) {
                post2hImpaired = true;
            }
            risk.append(String.format("  餐后2h最高 %.1f\n", maxPost2h));
        }

        if (fastingHigh || post2hHigh) {
            risk.append("  【高风险】空腹≥7.0 或 餐后2h≥11.1，符合糖尿病诊断标准，强烈建议尽快就医做OGTT和HbA1c检查。\n");
        } else if (fastingImpaired || post2hImpaired) {
            risk.append("  【中风险】空腹6.1~7.0 或 餐后2h 7.8~11.1，属于糖尿病前期（空腹血糖受损/糖耐量减低），建议控制饮食、增加运动，并就医评估。\n");
        } else {
            risk.append("  【低风险】当前数据未达糖尿病或糖尿病前期标准。继续保持健康生活方式。\n");
        }

        // 随机血糖检查
        boolean randomHigh = all.stream().anyMatch(r -> r.getBloodSugar() >= 11.1
                && !"空腹".equals(r.getMealPeriod()));
        if (randomHigh) {
            risk.append("  ⚠ 存在随机血糖≥11.1 的记录（非空腹），也需警惕。\n");
        }

        return "【糖尿病风险评估】\n" + risk;
    }

    private String assessInsulinResistanceRisk(List<BloodSugarRecord> fasting, List<BloodSugarRecord> post1h,
            List<BloodSugarRecord> post2h, List<BloodSugarRecord> all) {
        StringBuilder risk = new StringBuilder();

        if (fasting.isEmpty() && post1h.isEmpty() && post2h.isEmpty()) {
            return "【胰岛素抵抗评估】\n  数据不足，需要空腹+餐后血糖记录才能评估。";
        }

        // 特征1：空腹正常但餐后飙升（胰岛素抵抗典型表现）
        boolean fastingNormal = false, post1hHigh = false;
        if (!fasting.isEmpty()) {
            double avgFasting = fasting.stream().mapToDouble(BloodSugarRecord::getBloodSugar).average().orElse(0);
            fastingNormal = avgFasting < 6.1;
            risk.append(String.format("  空腹平均值：%.1f mmol/L %s\n", avgFasting, fastingNormal ? "（正常）" : "（偏高）"));
        }
        if (!post1h.isEmpty()) {
            double avgPost1h = post1h.stream().mapToDouble(BloodSugarRecord::getBloodSugar).average().orElse(0);
            post1hHigh = avgPost1h > 10.0;
            risk.append(String.format("  餐后1h平均值：%.1f mmol/L\n", avgPost1h));
        }

        // 特征2：血糖波动幅度大
        double maxSugar = all.stream().mapToDouble(BloodSugarRecord::getBloodSugar).max().orElse(0);
        double minSugar = all.stream().mapToDouble(BloodSugarRecord::getBloodSugar).min().orElse(0);
        double range = maxSugar - minSugar;
        boolean wideFluctuation = range > 5.0;
        risk.append(String.format("  血糖波动幅度：%.1f (%.1f ~ %.1f)%s\n", range, minSugar, maxSugar,
                wideFluctuation ? " ← 波动偏大" : ""));

        // 综合判断
        int score = 0;
        if (fastingNormal && post1hHigh) score += 2; // 空腹正常+餐后飙升
        if (wideFluctuation) score += 1;
        double avgBloodSugar = all.stream().mapToDouble(BloodSugarRecord::getBloodSugar).average().orElse(0);
        if (avgBloodSugar > 7.0) score += 1;

        if (score >= 2) {
            risk.append("  【高风险】空腹正常但餐后明显升高 + 波动大，符合胰岛素抵抗典型特征。建议就医查空腹胰岛素、HOMA-IR 指数。\n");
        } else if (score == 1) {
            risk.append("  【中风险】部分指标提示可能存在胰岛素抵抗倾向，建议关注并定期监测。\n");
        } else {
            risk.append("  【低风险】当前数据未显示明显的胰岛素抵抗特征。\n");
        }

        return "【胰岛素抵抗评估】\n" + risk;
    }

    // 日历入口：弹出月份日历，查看某天数据、补填三餐时间与血糖
    void showCalendar(Stage stage) {
        new CalendarDialog(stage, this).show();
    }

    // 刷新数据
    void refreshAll() {
        try {
            refreshDates();
            refreshTableAndChart();
        } catch (Exception e) {
            statusLabel.setText("刷新失败：" + e.getMessage());
        }
    }

    private void refreshDates() {
        try {
            String current = dateFilterCombo.getValue();
            dateFilterCombo.getItems().clear();
            dateFilterCombo.getItems().add("全部");
            dateFilterCombo.getItems().addAll(service.getRecordDates());
            if (current != null) dateFilterCombo.setValue(current);
            else dateFilterCombo.setValue("全部");
        } catch (SQLException ignored) {
        }
    }

    private void refreshTableAndChart() {
        try {
            String selected = dateFilterCombo.getValue();
            if (selected == null || "全部".equals(selected)) {
                // 主页面默认只展示最近 8 条记录；更早数据通过日历查看
                currentChartRecords = service.getLatestRecords(8);
            } else {
                LocalDate date = LocalDate.parse(selected);
                // 按凌晨 4 点的业务日边界筛选：[当天4:00, 次日4:00)
                currentChartRecords = service.getRecordsByDateRange(
                        PeriodClassifier.getBusinessDayStart(date),
                        PeriodClassifier.getBusinessDayEnd(date));
            }

            table.setItems(FXCollections.observableArrayList(currentChartRecords));
            // 清理旧记录的复选框状态
            Set<Integer> currentIds = currentChartRecords.stream()
                    .map(BloodSugarRecord::getId).collect(Collectors.toSet());
            rowSelected.keySet().retainAll(currentIds);
            // 图表按时间升序（从左到右递增）
            List<BloodSugarRecord> chartData = new ArrayList<>(currentChartRecords);
            chartData.sort(Comparator.comparing(BloodSugarRecord::getRecordTime));
            updateChart(chartData);
        } catch (Exception e) {
            statusLabel.setText("加载失败：" + e.getMessage());
        }
    }

    // 趋势曲线
    private void updateChart(List<BloodSugarRecord> records) {
        chart.getData().clear();
        chartLabelToId.clear();

        // Y 轴上限按数据最大值动态调
        double maxSugar = records.stream().mapToDouble(BloodSugarRecord::getBloodSugar).max().orElse(8);
        double upperBound = Math.max(10, Math.ceil(maxSugar * 1.3));
        double tickUnit = upperBound <= 15 ? 1 : upperBound <= 25 ? 2 : 5;
        yAxis.setUpperBound(upperBound);
        yAxis.setTickUnit(tickUnit);

        // 所有记录画成一条连续血糖曲线（蓝色），正常/异常仅体现在标点颜色
        XYChart.Series<String, Number> sugarSeries = new XYChart.Series<>();
        sugarSeries.setName("血糖");
        XYChart.Series<String, Number> upperLine = new XYChart.Series<>();
        upperLine.setName("正常上限");
        XYChart.Series<String, Number> lowerLine = new XYChart.Series<>();
        lowerLine.setName("正常下限");

        // 记录按测量时间升序（调用方已排序，此处按序添加保持连续）
        Map<String, BloodSugarRecord> labelToRecord = new HashMap<>();
        for (BloodSugarRecord r : records) {
            String label = r.getRecordTime() != null
                    ? r.getRecordTime().format(CHART_FMT) : "";
            double sugar = r.getBloodSugar();
            chartLabelToId.put(label, r.getId());
            labelToRecord.put(label, r);
            // 参考线随餐别动态取值（上限 6.1/8.9/7.8，下限 3.9）
            double[] range = PeriodClassifier.getNormalRange(r.getMealPeriod());

            sugarSeries.getData().add(new XYChart.Data<>(label, sugar));
            upperLine.getData().add(new XYChart.Data<>(label, range[1]));
            lowerLine.getData().add(new XYChart.Data<>(label, range[0]));
        }

        chart.getData().addAll(upperLine, lowerLine, sugarSeries);

        Platform.runLater(() -> {
            // 参考线：上限红色（曲线上方）、下限绿色（曲线下方）
            colorSeries(upperLine, "#FF5252");
            colorSeries(lowerLine, "#2FC86B");
            // 血糖曲线：蓝色连续线
            colorSeries(sugarSeries, COLOR_BLUE);
            // 标点按正常/异常染色 + 悬停提示
            styleSugarPoints(sugarSeries, labelToRecord);
        });
    }

    private void colorSeries(XYChart.Series<String, Number> series, String color) {
        // 数据点 node 在图表渲染后才创建：监听 nodeProperty，创建后立即应用样式，避免样式丢失
        for (XYChart.Data<String, Number> data : series.getData()) {
            applyWhenNodeReady(data, node -> node.setStyle("-fx-background-color: " + color + ", white; "
                    + "-fx-background-radius: 8; -fx-background-insets: 0, 3; "
                    + "-fx-padding: 6;  -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 4, 0, 1, 1);"));
        }
        // 系列线颜色：渲染后 lookup 设置
        applyWhenSeriesNodeReady(series, node -> {
            Node line = node.lookup(".chart-series-line");
            if (line != null) {
                line.setStyle("-fx-stroke: " + color + "; -fx-stroke-width: 2;");
            }
        });
    }

    // 工具：data node 尚未创建时挂监听，创建后立即执行 action
    private void applyWhenNodeReady(XYChart.Data<String, Number> data,
                                    java.util.function.Consumer<Node> action) {
        Node node = data.getNode();
        if (node != null) {
            action.accept(node);
            return;
        }
        data.nodeProperty().addListener(new javafx.beans.value.ChangeListener<Node>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Node> obs,
                                Node oldNode, Node newNode) {
                if (newNode != null) {
                    action.accept(newNode);
                    data.nodeProperty().removeListener(this);
                }
            }
        });
    }

    // 工具：series node 尚未创建时挂监听，创建后立即执行 action
    private void applyWhenSeriesNodeReady(XYChart.Series<String, Number> series,
                                          java.util.function.Consumer<Node> action) {
        Node node = series.getNode();
        if (node != null) {
            action.accept(node);
            return;
        }
        series.nodeProperty().addListener(new javafx.beans.value.ChangeListener<Node>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Node> obs,
                                Node oldNode, Node newNode) {
                if (newNode != null) {
                    action.accept(newNode);
                    series.nodeProperty().removeListener(this);
                }
            }
        });
    }

    // 血糖标点：统一蓝色空心圆（外圈蓝色+白色中心），并绑定悬停提示
    private void styleSugarPoints(XYChart.Series<String, Number> series,
                                  Map<String, BloodSugarRecord> labelToRecord) {
        for (XYChart.Data<String, Number> data : series.getData()) {
            BloodSugarRecord r = labelToRecord.get(data.getXValue());
            if (r == null) continue;

            double sugar = r.getBloodSugar();
            String period = r.getMealPeriod();
            double[] range = PeriodClassifier.getNormalRange(period);
            String tipText = buildChartTooltip(r, sugar, period, range);

            // 数据点 node 在图表渲染后才创建：监听 nodeProperty，创建后立即绑定样式与 Tooltip
            applyWhenNodeReady(data, node -> {
                // 标点着色：高于正常上限红色 / 低于正常下限绿色 / 正常范围内蓝色（保留空心圆样式）
                String pointColor;
                if (sugar > range[1]) {
                    pointColor = "#FF5252";          // 高于正常上限 → 红
                } else if (sugar < range[0]) {
                    pointColor = COLOR_NORMAL;       // 低于正常下限 → 绿
                } else {
                    pointColor = COLOR_BLUE;         // 正常范围内 → 蓝
                }
                node.setStyle("-fx-background-color: " + pointColor + ", white; "
                        + "-fx-background-radius: 50%; -fx-background-insets: 0, 2; "
                        + "-fx-padding: 6;  -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 4, 0, 1, 1);");

                // 鼠标悬停自动弹出提示：测量时间 / 数值 / 正常区间 / 建议
                // 改用显式事件绑定（不依赖 TooltipBehavior）：进入/移动即显示，离开即隐藏
                Tooltip tooltip = new Tooltip(tipText);
                tooltip.setStyle("-fx-background-color: #FFFDF5; -fx-background-radius: 10; "
                        + "-fx-border-color: " + COLOR_BORDER + "; -fx-border-radius: 10; "
                        + "-fx-text-fill: " + COLOR_TEXT + "; -fx-font-size: 13px; -fx-padding: 8 12 8 12;");
                tooltip.setAutoHide(true);
                node.setPickOnBounds(true);
                node.setOnMouseEntered(e -> tooltip.show(node, e.getScreenX(), e.getScreenY() + 18));
                node.setOnMouseMoved(e -> tooltip.show(node, e.getScreenX(), e.getScreenY() + 18));
                node.setOnMouseExited(e -> tooltip.hide());
            });
        }
    }

    // 构建悬停提示文本
    private String buildChartTooltip(BloodSugarRecord r, double sugar, String period, double[] range) {
        String time = r.getRecordTime() != null
                ? r.getRecordTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : "-";
        String periodSafe = (period == null || period.isEmpty()) ? "空腹" : period;
        boolean normal = PeriodClassifier.isNormal(periodSafe, sugar);
        String status = normal ? "正常" : (sugar < range[0] ? "偏低" : "偏高");
        return String.format("时间：%s\n数值：%.1f mmol/L\n餐别：%s\n正常区间：%.1f ~ %.1f mmol/L\n状态：%s\n建议：%s",
                time, sugar, periodSafe, range[0], range[1], status,
                generateAdvice(sugar, periodSafe));
    }

    /**
     * “数据位置”设置对话框：查看“当前数据位置 → 迁移到新位置”。
     * 以本进程启动时实际绑定的目录为迁移源（保证即使上一次修改尚未重启也能正确迁移），
     * 新位置为空则全量迁移；新位置已有本应用数据则弹冲突确认（覆盖/保留/取消），
     * 保存后需重启应用生效。
     */
    private void showDataLocationDialog(Stage owner) {
        Path current = DataLocation.getStartupDataRoot();
        Path recorded = DataLocation.getDataRoot();
        boolean pendingRestart = !current.equals(recorded);

        Label currentTitle = new Label("当前数据位置（本应用正在使用的目录）");
        currentTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #667080;");
        TextField currentField = new TextField(current.toString());
        currentField.setEditable(false);
        currentField.setPrefColumnCount(42);
        currentField.setStyle("-fx-background-color: #ECEFF3; -fx-text-fill: #667080; "
                + "-fx-border-color: #D2D8E0; -fx-border-radius: 6; -fx-background-radius: 6;");
        currentField.setFocusTraversable(false);

        Label arrow = new Label("↓  迁移到  ↓");
        arrow.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #98A1AF;");

        Label targetTitle = new Label("新数据位置");
        targetTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #667080;");

        TextField pathField = new TextField();
        pathField.setPromptText("请选择或输入要把数据迁移到的目录");
        pathField.setPrefColumnCount(42);
        Button browseBtn = new Button("浏览…");
        browseBtn.setStyle("-fx-background-color: linear-gradient(to bottom, #C9CDD6, #98A1AF); "
                + "-fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 8 18; "
                + "-fx-background-radius: 16; -fx-border-radius: 16; "
                + "-fx-border-color: #667080; -fx-border-width: 0 0 3 0; -fx-cursor: hand; -fx-font-weight: bold;");
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择数据存放目录");
            String typed = pathField.getText() == null ? "" : pathField.getText().trim();
            Path init = typed.isEmpty() ? current : Path.of(typed);
            if (Files.isDirectory(init)) {
                chooser.setInitialDirectory(init.toFile());
            }
            File dir = chooser.showDialog(owner);
            if (dir != null) {
                pathField.setText(dir.getAbsolutePath());
            }
        });
        HBox picker = new HBox(8, pathField, browseBtn);
        picker.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pathField, Priority.ALWAYS);

        Label hint = new Label("新位置为空时：自动把正在使用的全部数据文件（数据库、AI 配置等）完整迁移过去。\n"
                + "新位置已有本应用数据时：会先弹出冲突确认（覆盖目标数据 / 保留目标数据 / 取消），不会静默覆盖。\n"
                + "保存后请完全退出并重新启动糖伴SugarPal，之后所有数据读写将使用新位置。");
        hint.setWrapText(true);
        hint.setTextFill(Color.web(COLOR_TEXT));
        hint.setStyle("-fx-font-size: 12px;");

        VBox currentBox = new VBox(4, currentTitle, currentField);
        VBox targetBox = new VBox(4, targetTitle, pathField);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("数据位置设置");
        dialog.setHeaderText("选择数据存放位置");
        DialogPane pane = dialog.getDialogPane();
        styleDialogPane(pane);
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        VBox content = new VBox(10);
        content.setPadding(new Insets(8));
        content.getChildren().addAll(currentBox, arrow, targetBox, picker, hint);
        if (pendingRestart) {
            Label pendingLabel = new Label("注意：上一次保存的位置尚未重启生效。本次迁移将以“当前数据位置”（正在使用的目录）为源执行。");
            pendingLabel.setWrapText(true);
            pendingLabel.setTextFill(Color.web("#B5493A"));
            pendingLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
            content.getChildren().add(pendingLabel);
        }
        pane.setContent(content);

        Button ok = (Button) pane.lookupButton(ButtonType.OK);
        if (ok != null) {
            ok.setText("保存");
        }

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        String newText = pathField.getText() == null ? "" : pathField.getText().trim();
        if (newText.isEmpty()) {
            showAlert("请填写或选择要存放数据的文件夹路径。");
            return;
        }
        Path target;
        try {
            target = Path.of(newText).toAbsolutePath().normalize();
        } catch (Exception ex) {
            showAlert("路径无效：" + newText);
            return;
        }
        if (Files.exists(target) && !Files.isDirectory(target)) {
            showAlert("目标不是一个文件夹，请重新选择。");
            return;
        }
        if (target.equals(current)) {
            showAlert("该目录已经是当前数据目录，无需修改。");
            return;
        }

        try {
            String message = applyDataLocationChange(current, target);
            if (message == null) {
                return; // 用户在冲突确认中选择了取消
            }
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.initOwner(owner);
            info.setTitle("数据位置已更新");
            info.setHeaderText(null);
            info.setContentText(message);
            styleDialogPane(info.getDialogPane());
            info.showAndWait();
        } catch (Exception ex) {
            showAlert("设置数据位置失败：" + ex.getMessage());
        }
    }

    /** 需要随数据位置一起迁移的本应用数据文件（白名单；data.lock.db 是运行期锁文件，不迁移） */
    private static final List<String> DATA_FILE_NAMES = List.of(
            "data.mv.db", "config.properties", "data.trace.db");

    /**
     * 执行数据目录切换：校验 → 冲突确认 → 关闭连接池 → 迁移/覆盖（可回滚） → 写入 location.properties。
     *
     * <p>迁移源为 {@code sourceRoot}（调用方传入本进程启动时实际绑定的目录），目标为新目录：
     * <ul>
     *   <li>源无数据：不迁移，仅保存新位置，重启后全新初始化；</li>
     *   <li>目标已有本应用数据：弹三选确认（覆盖目标数据 / 保留目标数据 / 取消），绝不静默覆盖；
     *       选择覆盖时先把目标原有数据备份到 {@code 目标/backup_时间戳}，失败自动回滚；</li>
     *   <li>目标为空：完整复制全部数据文件，失败删除已复制文件并报错，源数据保持不动。</li>
     * </ul>
     *
     * @return 面向用户的迁移结果说明；用户取消冲突处理时返回 {@code null}
     */
    private String applyDataLocationChange(Path sourceRoot, Path targetRoot) throws IOException {
        boolean sourceHasDb = Files.exists(sourceRoot.resolve("data.mv.db"));
        boolean sourceHasConfig = Files.exists(sourceRoot.resolve("config.properties"));
        boolean targetHasDb = Files.exists(targetRoot.resolve("data.mv.db"));
        boolean targetHasConfig = Files.exists(targetRoot.resolve("config.properties"));
        boolean sourceHasData = sourceHasDb || sourceHasConfig;
        boolean targetHasData = targetHasDb || targetHasConfig;

        // 目标目录创建与可写校验放在关闭连接池之前：失败时不破坏当前会话
        try {
            Files.createDirectories(targetRoot);
        } catch (IOException e) {
            throw new IOException("无法创建目标目录：" + targetRoot + "（" + e.getMessage() + "）", e);
        }
        if (!Files.isWritable(targetRoot)) {
            throw new IOException("目标目录不可写，请检查权限后重试：" + targetRoot);
        }

        if (!sourceHasData) {
            // 当前数据目录无数据：无需迁移，直接保存新位置，重启后全新初始化
            DataLocation.saveDataRoot(targetRoot);
            return "当前数据位置没有本应用数据（未找到 data.mv.db / config.properties），无需迁移。\n"
                    + "设置已保存：重启后将在新位置全新初始化数据库。\n\n新位置：" + targetRoot;
        }

        // 目标已有本应用数据 → 冲突确认（在关闭连接池之前完成，取消不破坏当前会话）
        if (targetHasData) {
            ButtonType overwrite = new ButtonType("覆盖目标数据", ButtonBar.ButtonData.OK_DONE);
            ButtonType keepTarget = new ButtonType("保留目标数据", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("数据冲突");
            confirm.setHeaderText("新位置已存在本应用数据");
            confirm.setContentText("目标位置检测到本应用数据，请选择处理方式：\n\n"
                    + "「覆盖目标数据」：用当前数据替换目标位置数据（目标原数据会先备份到目标位置下 backup_时间戳 目录，可恢复）。\n"
                    + "「保留目标数据」：不迁移当前数据，重启后直接使用目标位置已有数据（当前数据保留不动）。\n"
                    + "「取消」：不做任何修改。\n\n"
                    + "当前数据位置：" + sourceRoot + "\n"
                    + "目标位置：" + targetRoot);
            styleDialogPane(confirm.getDialogPane());
            confirm.getDialogPane().getButtonTypes().setAll(overwrite, keepTarget, cancel);
            Optional<ButtonType> choice = confirm.showAndWait();
            if (choice.isEmpty() || choice.get() == cancel) {
                return null;
            }
            if (choice.get() == keepTarget) {
                DataLocation.saveDataRoot(targetRoot);
                return "已选择保留目标位置数据，当前数据未迁移。\n"
                        + "设置已保存：重启后将直接使用目标位置已有数据。\n\n目标位置：" + targetRoot;
            }
            // 覆盖：先把目标原数据移动到备份目录（逐文件移动，中途失败立即恢复已备份文件）
            DatabaseConfig.closeDataSource();
            Path backupDir = targetRoot.resolve("backup_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
            Files.createDirectories(backupDir);
            try {
                for (String name : DATA_FILE_NAMES) {
                    Path src = targetRoot.resolve(name);
                    if (Files.exists(src)) {
                        Files.move(src, backupDir.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (IOException ex) {
                for (String name : DATA_FILE_NAMES) {
                    Path backed = backupDir.resolve(name);
                    if (Files.exists(backed)) {
                        Files.move(backed, targetRoot.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                throw new IOException("备份目标位置原数据失败，未做任何修改（数据库连接已关闭，请重启应用后再试）。原因：" + ex.getMessage(), ex);
            }
            try {
                migrateDataFiles(sourceRoot, targetRoot);
                DataLocation.saveDataRoot(targetRoot);
                return "已用当前数据覆盖目标位置数据，并保留以下备份可随时恢复：\n" + backupDir + "\n\n"
                        + "设置已保存。请完全退出并重新启动糖伴SugarPal，之后所有数据读写将使用：\n" + targetRoot;
            } catch (IOException ex) {
                rollbackMigrate(targetRoot, backupDir);
                throw new IOException("迁移到目标位置失败，已回滚并恢复目标原数据。"
                        + "当前数据仍在：" + sourceRoot + "。原因：" + ex.getMessage()
                        + "（数据库连接已关闭，请重启应用后再试）", ex);
            }
        }

        // 常规迁移：源有数据、目标为空
        DatabaseConfig.closeDataSource();
        try {
            migrateDataFiles(sourceRoot, targetRoot);
        } catch (IOException ex) {
            rollbackMigrate(targetRoot, null);
            throw new IOException("迁移失败，已清理目标位置已复制的文件，当前数据未受影响（仍在 "
                    + sourceRoot + "）。原因：" + ex.getMessage()
                    + "（数据库连接已关闭，请重启应用后再试）", ex);
        }
        DataLocation.saveDataRoot(targetRoot);
        StringBuilder report = new StringBuilder("已将当前数据完整迁移到新位置：\n");
        for (String name : DATA_FILE_NAMES) {
            if (Files.exists(targetRoot.resolve(name))) {
                report.append("· ").append(name).append("\n");
            }
        }
        report.append("\n设置已保存。请完全退出并重新启动糖伴SugarPal，之后所有数据读写（血糖记录、用餐时间、日历、导出等）将使用：\n")
                .append(targetRoot);
        return report.toString();
    }

    /**
     * 复制全部本应用数据文件（data.mv.db / config.properties 为关键文件，失败即抛出触发回滚；
     * data.trace.db 为日志文件，复制失败仅跳过不阻断迁移，避免因日志句柄占用导致整个迁移失败）。
     */
    private void migrateDataFiles(Path srcRoot, Path dstRoot) throws IOException {
        for (String name : DATA_FILE_NAMES) {
            Path src = srcRoot.resolve(name);
            if (!Files.exists(src)) {
                continue;
            }
            Path dst = dstRoot.resolve(name);
            boolean critical = name.equals("data.mv.db") || name.equals("config.properties");
            try {
                Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException e) {
                if (critical) {
                    throw new IOException("复制 " + name + " 失败: " + e.getMessage(), e);
                }
                // data.trace.db 复制失败不阻断：仅为调试日志，可缺失
            }
        }
    }

    /**
     * 迁移失败回滚：删除本次复制到目标位置的数据文件；若覆盖场景提供了备份目录，
     * 则同时把备份的目标原数据移回原位，保证目标位置恢复原状。
     */
    private void rollbackMigrate(Path dstRoot, Path backupDir) throws IOException {
        for (String name : DATA_FILE_NAMES) {
            Path f = dstRoot.resolve(name);
            if (!Files.exists(f)) {
                continue;
            }
            if (backupDir != null) {
                Path backup = backupDir.resolve(name);
                if (Files.exists(backup)) {
                    Files.deleteIfExists(f);
                    Files.move(backup, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    continue;
                }
            }
            Files.deleteIfExists(f);
        }
    }

    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        styleDialogPane(alert.getDialogPane());
        alert.showAndWait();
    }
}
