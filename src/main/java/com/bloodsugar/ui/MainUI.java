package com.bloodsugar.ui;

import com.bloodsugar.config.AiConfig;
import com.bloodsugar.config.DataLocation;
import com.bloodsugar.config.DatabaseConfig;
import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.service.AgpReportService;
import com.bloodsugar.service.AiSuggestionService;
import com.bloodsugar.service.BloodSugarService;
import com.bloodsugar.service.CsvImportService;
import com.bloodsugar.service.ExportService;
import com.bloodsugar.util.PeriodClassifier;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 糖伴SugarPal 主界面：工具栏 + 左侧记录面板（日期筛选 / 记录表格 / 今日用餐时间 /
 * 多维健康入口）+ 血糖趋势曲线 + 状态栏。
 * 各功能对话框已拆分为独立类（见同包 RecordDialog / HealthEntryDialog /
 * AiSuggestionDialog / ExportDialog / SummaryReportDialog / AgpReportDialog /
 * DataImportDialog），本类只保留主界面组装、数据刷新与图表渲染。
 */
public class MainUI {

    private final BloodSugarService service = new BloodSugarService();
    private final AiConfig aiConfig = AiConfig.load();
    private final AiSuggestionService aiService = new AiSuggestionService();
    private final ExportService exportService = new ExportService();
    private final AgpReportService agpService = new AgpReportService();
    private final CsvImportService csvImportService = new CsvImportService();

    private TableView<BloodSugarRecord> table;
    private LineChart<String, Number> chart;
    private NumberAxis yAxis;
    private ComboBox<String> dateFilterCombo;
    private Label statusLabel;

    private TextField breakfastField, lunchField, dinnerField, extraMealField;

    /** 餐别自动识别器：依赖左侧面板四个用餐时间输入框，在面板构建后创建 */
    private MealTypeAutoRecognizer mealRecognizer;

    // 用餐时间面板当前展示的业务日（凌晨4点为新一天起点），用于跨日自动清零判断
    private LocalDate currentMealBusinessDate;
    private Timeline mealRolloverTimer;

    // 记录 X 轴标签 → 记录 ID 的映射，用于图表右键删除
    private Map<String, Integer> chartLabelToId = new HashMap<>();
    private List<BloodSugarRecord> currentChartRecords = new ArrayList<>();

    // 表格复选框选中状态
    private Map<Integer, BooleanProperty> rowSelected = new HashMap<>();

    public Scene createScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: linear-gradient(to bottom right, #F4FEF8, "
                + DialogSupport.COLOR_BG + ", #CFF7E2);");
        root.setTop(buildToolbar(stage));
        root.setLeft(buildLeftPanel());
        root.setCenter(buildChartPanel());
        root.setBottom(buildStatusBar());
        refreshAll();
        refreshMealPanelFromDb();
        startMealRolloverTimer();
        Scene scene = new Scene(root, 1290, 760);
        // 表格渲染完成后设置表头糖果渐变圆角背景
        Platform.runLater(() -> {
            Node header = table.lookup(".column-header-background");
            if (header != null) {
                header.setStyle("-fx-background-color: linear-gradient(to bottom, "
                        + DialogSupport.COLOR_HEADER + ", #B5F0CE); "
                        + "-fx-background-radius: 18 18 0 0; -fx-border-color: transparent;");
            }
        });
        return scene;
    }

    // ===================== 顶部工具栏 =====================

    private HBox buildToolbar(Stage stage) {
        HBox toolbar = new HBox(10);
        toolbar.setPadding(new Insets(12, 16, 12, 16));
        toolbar.setStyle("-fx-background-color: linear-gradient(to right, #FFFFFF, #E3FBF0, #FFF0F6); "
                + "-fx-border-color: " + DialogSupport.COLOR_BORDER + "; -fx-border-width: 0 0 2 0;");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // 糖果圆点装饰条
        Circle dot1 = new Circle(5, Color.web(DialogSupport.COLOR_PINK));
        Circle dot2 = new Circle(5, Color.web(DialogSupport.COLOR_ORANGE));
        Circle dot3 = new Circle(5, Color.web(DialogSupport.COLOR_GREEN));
        HBox dots = new HBox(4, dot1, dot2, dot3);
        dots.setAlignment(Pos.CENTER);

        // 艺术字主标题：糖果渐变 + 白色描边 + 彩色立体阴影
        Text title = new Text("糖伴SugarPal");
        title.setFont(Font.font("YouYuan", FontWeight.BOLD, 24));
        title.setFill(DialogSupport.CANDY_GRADIENT);
        title.setStroke(Color.WHITE);
        title.setStrokeWidth(1.2);
        title.setEffect(new DropShadow(6, 3, 3, Color.web("#8FE8B8")));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button addBtn = DialogSupport.styledButton("+ 添加记录", DialogSupport.COLOR_GREEN_LIGHT,
                DialogSupport.COLOR_GREEN_DARK, "#A9F0C4", DialogSupport.COLOR_GREEN_LIGHT, "#1E9E50", "#7DE3A4");
        addBtn.setOnAction(e -> showAddDialog(stage));

        // 日历入口：查看某天数据、补填三餐时间与血糖
        Button calendarBtn = DialogSupport.styledButton("日历", "#FFD36E", "#F5A623", "#FFE3A1",
                "#FFD36E", "#C87F0A", "#FFD36E");
        calendarBtn.setOnAction(e -> showCalendar(stage));

        Button summaryBtn = DialogSupport.styledButton("生成总结", DialogSupport.COLOR_BLUE_LIGHT,
                DialogSupport.COLOR_BLUE_DARK, "#B5E7FF", DialogSupport.COLOR_BLUE_LIGHT, "#1899E0", "#7FD3FF");
        summaryBtn.setOnAction(e -> showSummaryDialog(stage));

        // AGP 标准化葡萄糖图谱：24 小时分位曲线 + TIR/CV 等指标，可导出 PDF 打印
        Button agpBtn = DialogSupport.styledButton("AGP 报告", "#7DE3E0", "#22B0AE", "#A8EFEC",
                "#7DE3E0", "#1E8F8F", "#8FE8E4");
        agpBtn.setOnAction(e -> new AgpReportDialog(stage, agpService).show());

        // 批量导入：识别血糖仪导出的 CSV / Excel 数据，去重后预览确认写入
        Button importBtn = DialogSupport.styledButton("导入数据", "#FFAFA3", "#FF7A66", "#FFC9C0",
                "#FFAFA3", "#E5624C", "#FFC0B4");
        importBtn.setOnAction(e -> new DataImportDialog(stage, csvImportService, this::refreshAll).show());

        Button aiBtn = DialogSupport.styledButton("AI 建议", "#FF9EC5", "#F36AA9", "#FFC0DB",
                "#FF9EC5", "#D74F8E", "#FFB9D6");
        aiBtn.setOnAction(e -> new AiSuggestionDialog(stage, aiConfig, aiService, service, this::setStatus).show());

        Button exportBtn = DialogSupport.styledButton("导出报告", "#C9B2FF", "#9B6BFF", "#E0D2FF",
                "#B78DFF", "#7F4FD8", "#CDB4FF");
        exportBtn.setOnAction(e -> showExportDialog(stage));

        // 数据位置设置：查看/修改数据存放目录（重启生效），浅银灰风格区别于功能按钮
        Button dataLocationBtn = DialogSupport.styledButton("数据位置", "#C9CDD6", "#98A1AF", "#E2E6EC",
                "#B4BBC8", "#667080", "#D2D8E0");
        dataLocationBtn.setOnAction(e -> showDataLocationDialog(stage));

        Button refreshBtn = DialogSupport.styledButton("刷新", DialogSupport.COLOR_ORANGE_LIGHT,
                DialogSupport.COLOR_ORANGE_DARK, "#FFDDB8", DialogSupport.COLOR_ORANGE_LIGHT, "#F08A33", "#FFC48C");
        refreshBtn.setOnAction(e -> refreshAll());

        toolbar.getChildren().addAll(dots, title, spacer, addBtn, calendarBtn, summaryBtn, agpBtn, importBtn,
                aiBtn, exportBtn, dataLocationBtn, refreshBtn);
        return toolbar;
    }

    // ===================== 左侧面板（筛选 / 表格 / 用餐时间 / 健康入口） =====================

    private VBox buildLeftPanel() {
        VBox left = new VBox(8);
        left.setPadding(new Insets(12));
        left.setPrefWidth(400);
        left.setStyle("-fx-background-color: " + DialogSupport.COLOR_PANEL + "; "
                + "-fx-border-color: " + DialogSupport.COLOR_BORDER + "; -fx-border-width: 0 2 0 0;");

        // 糖果圆点装饰条
        HBox deco = new HBox(5);
        deco.getChildren().addAll(
                new Circle(4, Color.web(DialogSupport.COLOR_PINK)),
                new Circle(4, Color.web(DialogSupport.COLOR_ORANGE)),
                new Circle(4, Color.web(DialogSupport.COLOR_GREEN)),
                new Circle(4, Color.web(DialogSupport.COLOR_BLUE)));
        Label filterLabel = DialogSupport.artLabel("日期筛选", 13);
        dateFilterCombo = new ComboBox<>();
        dateFilterCombo.setPromptText("全部日期");
        dateFilterCombo.setPrefWidth(Double.MAX_VALUE);
        dateFilterCombo.setStyle("-fx-background-radius: 8; -fx-border-radius: 8; "
                + "-fx-border-color: " + DialogSupport.COLOR_MEAL_BORDER + "; -fx-background-color: #FFFFFF;");
        dateFilterCombo.setOnAction(e -> refreshTableAndChart());

        Label tableLabel = DialogSupport.artLabel("糖伴SugarPal", 13);

        table = new TableView<>();
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setStyle("-fx-background-color: transparent; -fx-background-radius: 12; "
                + "-fx-border-color: " + DialogSupport.COLOR_BORDER + "; -fx-border-radius: 12; "
                + "-fx-border-width: 1; -fx-control-inner-background: #FFFFFF; "
                + "-fx-table-cell-border-color: transparent;");

        TableColumn<BloodSugarRecord, Boolean> checkCol = new TableColumn<>("");
        checkCol.setCellValueFactory(p -> {
            BloodSugarRecord rec = p.getValue();
            return rowSelected.computeIfAbsent(rec.getId(), k -> new SimpleBooleanProperty(false));
        });
        checkCol.setCellFactory(CheckBoxTableCell.forTableColumn(checkCol));
        checkCol.setPrefWidth(32);
        checkCol.setSortable(false);

        TableColumn<BloodSugarRecord, String> timeCol = new TableColumn<>("时间");
        timeCol.setCellValueFactory(p -> {
            LocalDateTime t = p.getValue().getRecordTime();
            return new SimpleStringProperty(t != null ? t.format(DialogFormats.CHART_FMT) : "");
        });
        timeCol.setPrefWidth(85);

        TableColumn<BloodSugarRecord, Double> sugarCol = new TableColumn<>("血糖");
        sugarCol.setCellValueFactory(new PropertyValueFactory<>("bloodSugar"));
        sugarCol.setPrefWidth(55);
        sugarCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double val, boolean empty) {
                super.updateItem(val, empty);
                if (empty || val == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(String.format("%.1f", val));
                BloodSugarRecord row = getTableView().getItems().get(getIndex());
                boolean normal = PeriodClassifier.isNormal(row.getMealPeriod(), val);
                setTextFill(normal ? Color.web(DialogSupport.COLOR_NORMAL) : Color.web(DialogSupport.COLOR_HIGH));
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
        editBtn.setStyle("-fx-text-fill: " + DialogSupport.COLOR_BLUE + "; -fx-font-size: 12px; -fx-cursor: hand; -fx-font-weight: bold;");
        editBtn.setOnAction(e -> editSelected());

        Button deleteBtn = new Button("删除");
        deleteBtn.setStyle("-fx-text-fill: " + DialogSupport.COLOR_HIGH + "; -fx-font-size: 12px; -fx-cursor: hand; -fx-font-weight: bold;");
        deleteBtn.setOnAction(e -> deleteSelected());
        btnBar.getChildren().addAll(editBtn, deleteBtn);

        VBox mealPanel = buildMealPanel();
        Button healthEntryBtn = buildHealthEntryButton();
        VBox.setVgrow(table, Priority.ALWAYS);
        left.getChildren().addAll(deco, filterLabel, dateFilterCombo, tableLabel, table, btnBar, mealPanel, healthEntryBtn);

        // 面板输入框就绪后创建餐别自动识别器（新增/修改记录对话框共用）
        mealRecognizer = new MealTypeAutoRecognizer(service, breakfastField, lunchField, dinnerField, extraMealField);
        return left;
    }

    private VBox buildMealPanel() {
        VBox panel = new VBox(6);
        panel.setPadding(new Insets(12));
        panel.setStyle("-fx-background-color: linear-gradient(to bottom, " + DialogSupport.COLOR_MEAL_BG
                + ", " + DialogSupport.COLOR_MEAL_BG2 + "); "
                + "-fx-background-radius: 20; "
                + "-fx-border-color: " + DialogSupport.COLOR_MEAL_BORDER + "; -fx-border-radius: 20; "
                + "-fx-border-width: 2;");
        panel.setEffect(new DropShadow(6, 3, 4, Color.web("#FFD2A8")));

        Label title = DialogSupport.artLabel("今日用餐时间", 13);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);

        breakfastField = createMealTimeField("早餐");
        lunchField = createMealTimeField("午餐");
        dinnerField = createMealTimeField("晚餐");
        extraMealField = createMealTimeField("加餐");

        Label breakfastLb = new Label("早餐");
        breakfastLb.setTextFill(Color.web(DialogSupport.COLOR_TITLE));
        Label lunchLb = new Label("午餐");
        lunchLb.setTextFill(Color.web(DialogSupport.COLOR_TITLE));
        Label dinnerLb = new Label("晚餐");
        dinnerLb.setTextFill(Color.web(DialogSupport.COLOR_TITLE));
        Label extraLb = new Label("加餐");
        extraLb.setTextFill(Color.web(DialogSupport.COLOR_TITLE));

        grid.add(breakfastLb, 0, 0);
        grid.add(breakfastField, 1, 0);
        grid.add(lunchLb, 0, 1);
        grid.add(lunchField, 1, 1);
        grid.add(dinnerLb, 0, 2);
        grid.add(dinnerField, 1, 2);
        grid.add(extraLb, 0, 3);
        grid.add(extraMealField, 1, 3);

        Button saveMealBtn = DialogSupport.styledButton("保存用餐时间", DialogSupport.COLOR_GREEN_LIGHT,
                DialogSupport.COLOR_GREEN_DARK, "#A9F0C4", DialogSupport.COLOR_GREEN_LIGHT, "#1E9E50", "#7DE3A4");
        saveMealBtn.setStyle(saveMealBtn.getStyle() + " -fx-font-size: 12px; -fx-padding: 9 18;");
        String mealNormal = saveMealBtn.getStyle();
        String mealHover = DialogSupport.buttonStyle("#A9F0C4", DialogSupport.COLOR_GREEN_LIGHT, "#1E9E50")
                + " -fx-font-size: 12px; -fx-padding: 9 18;";
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
                if (text.isEmpty()) {
                    continue;
                }
                LocalDateTime mealTime = parseMealField(fields[i]);
                if (mealTime == null) {
                    errors.append(names[i]).append(" 时间格式不正确（应为 HH:mm）\n");
                    continue;
                }
                service.saveMealTime(names[i], mealTime);
                savedCount++;
            }
        } catch (SQLException ex) {
            DialogSupport.showAlert(null, "保存用餐时间失败：" + ex.getMessage());
            return;
        }
        if (errors.length() > 0) {
            DialogSupport.showAlert(null, errors.toString());
        }
        if (savedCount > 0) {
            setStatus("已保存 " + savedCount + " 项用餐时间（同餐别自动覆盖旧值）");
        } else if (errors.length() == 0) {
            setStatus("请先填写要保存的用餐时间");
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
        return t == null ? "" : t.format(DialogFormats.TIME_FMT);
    }

    /**
     * 每 30 秒检查一次业务日是否已变化：应用一直开着跨过凌晨 4 点时，
     * 自动把"今日用餐时间"面板刷新成新业务日的用餐时间（新的一天通常为空，即清零）。
     */
    private void startMealRolloverTimer() {
        if (mealRolloverTimer != null) {
            return;
        }
        mealRolloverTimer = new Timeline(new KeyFrame(Duration.seconds(30), e -> {
            try {
                LocalDate biz = PeriodClassifier.getBusinessDate(LocalDateTime.now());
                if (currentMealBusinessDate != null && !biz.equals(currentMealBusinessDate)) {
                    refreshMealPanelFromDb();
                    setStatus("已进入新的一天，今日用餐时间已自动清零（凌晨4点）");
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
        Button btn = DialogSupport.styledButton("多维健康录入", DialogSupport.COLOR_BLUE_LIGHT,
                DialogSupport.COLOR_BLUE_DARK, "#A9E4FF", DialogSupport.COLOR_BLUE_LIGHT, "#1E9FE8", "#7FD3FF");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setStyle(btn.getStyle() + " -fx-font-size: 14px; -fx-padding: 14 22;");
        String normal = btn.getStyle();
        String hover = DialogSupport.buttonStyle("#A9E4FF", DialogSupport.COLOR_BLUE_LIGHT, "#1E9FE8")
                + " -fx-font-size: 14px; -fx-padding: 14 22;";
        btn.setOnMouseEntered(e -> btn.setStyle(hover));
        btn.setOnMouseExited(e -> btn.setStyle(normal));
        btn.setOnAction(e -> new HealthEntryDialog(getMainStage(), service, this::refreshAll, this::setStatus).show());
        return btn;
    }

    private TextField createMealTimeField(String placeholder) {
        TextField tf = new TextField();
        tf.setPromptText(placeholder + " HH:mm");
        tf.setPrefWidth(80);
        tf.setStyle("-fx-background-radius: 8; -fx-border-radius: 8; "
                + "-fx-border-color: " + DialogSupport.COLOR_MEAL_BORDER + "; -fx-background-color: #FFFFFF;");
        return tf;
    }

    /** 面板时间按当前业务日解析，凌晨 0~4 点算前一天；解析失败返回 null */
    private LocalDateTime parseMealField(TextField field) {
        String s = field.getText().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            LocalDate businessToday = PeriodClassifier.getBusinessDate(LocalDateTime.now());
            return LocalDateTime.of(businessToday, LocalTime.parse(s.replace('.', ':')));
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前主窗口（对话框 owner） */
    private Stage getMainStage() {
        return (Stage) table.getScene().getWindow();
    }

    // ===================== 趋势曲线 =====================

    private VBox buildChartPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(14));
        panel.setStyle("-fx-background-color: " + DialogSupport.COLOR_PANEL + "; -fx-background-radius: 20; "
                + "-fx-border-color: " + DialogSupport.COLOR_BORDER + "; -fx-border-radius: 20; -fx-border-width: 2;");
        panel.setEffect(new DropShadow(7, 3, 4, Color.web("#8FE8B8")));

        // 糖果圆点装饰条
        HBox deco = new HBox(5);
        deco.getChildren().addAll(
                new Circle(4, Color.web(DialogSupport.COLOR_GREEN)),
                new Circle(4, Color.web(DialogSupport.COLOR_BLUE)),
                new Circle(4, Color.web(DialogSupport.COLOR_ORANGE)),
                new Circle(4, Color.web(DialogSupport.COLOR_PINK)));

        Label chartTitle = DialogSupport.artLabel("血糖趋势曲线", 14);

        yAxis = new NumberAxis("血糖 (mmol/L)", 0, 20, 1);
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("时间");

        chart = new LineChart<>(xAxis, yAxis);
        chart.setPrefHeight(520);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setCreateSymbols(true);
        chart.setLegendSide(Side.TOP);
        chart.setStyle("-fx-background-color: #FDFEFB; -fx-background-radius: 16; "
                + "-fx-border-color: " + DialogSupport.COLOR_BORDER + "; -fx-border-radius: 16; "
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
        deleteItem.setStyle("-fx-text-fill: " + DialogSupport.COLOR_HIGH + ";");
        deleteItem.setOnAction(e -> {
            try {
                service.deleteRecord(recordId);
                refreshAll();
                setStatus("已删除记录");
            } catch (SQLException ex) {
                DialogSupport.showAlert(getMainStage(), "删除失败：" + ex.getMessage());
            }
        });
        menu.getItems().add(deleteItem);
        menu.show(anchor, Side.BOTTOM, 0, 0);
    }

    // ===================== 状态栏 =====================

    private HBox buildStatusBar() {
        HBox bar = new HBox();
        bar.setPadding(new Insets(8, 16, 8, 16));
        bar.setStyle("-fx-background-color: linear-gradient(to right, " + DialogSupport.COLOR_STATUS
                + ", #D9FBE9, #FFE9F1); "
                + "-fx-border-color: " + DialogSupport.COLOR_BORDER + "; -fx-border-width: 2 0 0 0;");
        statusLabel = new Label("就绪");
        statusLabel.setFont(Font.font("YouYuan", 12));
        statusLabel.setTextFill(Color.web(DialogSupport.COLOR_TEXT));
        bar.getChildren().add(statusLabel);
        return bar;
    }

    /** 更新状态栏文案（对话框通过 Consumer 回调复用） */
    private void setStatus(String text) {
        if (statusLabel != null) {
            statusLabel.setText(text);
        }
    }

    // ===================== 功能对话框入口 =====================

    /** 添加记录（工具栏入口） */
    private void showAddDialog(Stage owner) {
        showAddDialog(owner, null);
    }

    /** 添加记录；presetDate 非空时为日历"补填"场景，日期预选为选中日期 */
    void showAddDialog(Stage owner, LocalDate presetDate) {
        RecordDialog.show(owner, service, mealRecognizer, null, presetDate, this::onRecordSaved);
    }

    /** 修改选中的记录 */
    private void showEditDialog(Stage owner, BloodSugarRecord rec) {
        RecordDialog.show(owner, service, mealRecognizer, rec, null, this::onRecordSaved);
    }

    /** 记录保存后：取消该行勾选并刷新界面 */
    private void onRecordSaved(BloodSugarRecord updated) {
        if (updated != null) {
            BooleanProperty prop = rowSelected.get(updated.getId());
            if (prop != null) {
                prop.set(false);
            }
        }
        refreshAll();
    }

    /** 多维健康录入 */
    private void showHealthDialog(Stage owner) {
        new HealthEntryDialog(owner, service, this::refreshAll, this::setStatus).show();
    }

    /** AI 智能建议 */
    private void showAiDialog(Stage owner) {
        new AiSuggestionDialog(owner, aiConfig, aiService, service, this::setStatus).show();
    }

    /** 导出报告 */
    private void showExportDialog(Stage owner) {
        // 导出范围保持原语义：选中"全部"时导出全部记录（不受主界面近8条展示限制），
        // 选中具体日期时导出该日记录
        List<BloodSugarRecord> records;
        try {
            String selected = dateFilterCombo.getValue();
            if (selected == null || "全部".equals(selected)) {
                records = service.getAllRecords();
            } else {
                records = currentChartRecords;
            }
        } catch (SQLException ex) {
            records = currentChartRecords;
        }
        if (records == null || records.isEmpty()) {
            DialogSupport.showAlert(owner, "暂无记录可导出，请先添加血糖数据");
            return;
        }
        new ExportDialog(owner, records, exportService).show();
    }

    /** 血糖总结报告 */
    private void showSummaryDialog(Stage owner) {
        if (currentChartRecords.isEmpty()) {
            DialogSupport.showAlert(owner, "暂无记录，请先添加血糖数据");
            return;
        }
        new SummaryReportDialog(owner, currentChartRecords, service).show();
    }

    // ===================== 记录勾选 / 删除 =====================

    private void deleteSelected() {
        List<BloodSugarRecord> selected = getCheckedRecords();
        if (selected.isEmpty()) {
            DialogSupport.showAlert(getMainStage(), "请先勾选要删除的记录");
            return;
        }
        String names = selected.stream()
                .map(r -> r.getRecordTime() != null
                        ? r.getRecordTime().format(DialogFormats.TIME_FMT) + " " + r.getBloodSugar() : "")
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
                    setStatus("已删除 " + selected.size() + " 条");
                } catch (SQLException e) {
                    DialogSupport.showAlert(getMainStage(), "删除失败：" + e.getMessage());
                }
            }
        });
    }

    private void editSelected() {
        List<BloodSugarRecord> selected = getCheckedRecords();
        if (selected.isEmpty()) {
            DialogSupport.showAlert(getMainStage(), "请先勾选一条记录进行修改");
            return;
        }
        if (selected.size() > 1) {
            DialogSupport.showAlert(getMainStage(), "一次只能修改一条记录，请只勾选一条");
            return;
        }
        showEditDialog(getMainStage(), selected.get(0));
    }

    private List<BloodSugarRecord> getCheckedRecords() {
        return table.getItems().stream()
                .filter(r -> {
                    BooleanProperty prop = rowSelected.get(r.getId());
                    return prop != null && prop.get();
                })
                .collect(Collectors.toList());
    }

    // ===================== 日历入口与数据刷新 =====================

    /** 日历入口：弹出月份日历，查看某天数据、补填三餐时间与血糖 */
    void showCalendar(Stage stage) {
        new CalendarDialog(stage, this).show();
    }

    void refreshAll() {
        try {
            refreshDates();
            refreshTableAndChart();
        } catch (Exception e) {
            setStatus("刷新失败：" + e.getMessage());
        }
    }

    private void refreshDates() {
        try {
            String current = dateFilterCombo.getValue();
            dateFilterCombo.getItems().clear();
            dateFilterCombo.getItems().add("全部");
            dateFilterCombo.getItems().addAll(service.getRecordDates());
            if (current != null) {
                dateFilterCombo.setValue(current);
            } else {
                dateFilterCombo.setValue("全部");
            }
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
            setStatus("加载失败：" + e.getMessage());
        }
    }

    // ===================== 趋势曲线渲染 =====================

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
                    ? r.getRecordTime().format(DialogFormats.CHART_FMT) : "";
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
            colorSeries(sugarSeries, DialogSupport.COLOR_BLUE);
            // 标点按正常/异常染色 + 悬停提示
            styleSugarPoints(sugarSeries, labelToRecord);
        });
    }

    private void colorSeries(XYChart.Series<String, Number> series, String color) {
        // 数据点 node 在图表渲染后才创建：监听 nodeProperty，创建后立即应用样式，避免样式丢失
        for (XYChart.Data<String, Number> data : series.getData()) {
            DialogSupport.applyWhenNodeReady(data, node -> node.setStyle("-fx-background-color: " + color + ", white; "
                    + "-fx-background-radius: 8; -fx-background-insets: 0, 3; "
                    + "-fx-padding: 6;  -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 4, 0, 1, 1);"));
        }
        // 系列线颜色：渲染后 lookup 设置
        DialogSupport.applyWhenSeriesNodeReady(series, node -> {
            Node line = node.lookup(".chart-series-line");
            if (line != null) {
                line.setStyle("-fx-stroke: " + color + "; -fx-stroke-width: 2;");
            }
        });
    }

    /** 血糖标点：统一蓝色空心圆（外圈蓝色+白色中心），并绑定悬停提示 */
    private void styleSugarPoints(XYChart.Series<String, Number> series,
                                  Map<String, BloodSugarRecord> labelToRecord) {
        for (XYChart.Data<String, Number> data : series.getData()) {
            BloodSugarRecord r = labelToRecord.get(data.getXValue());
            if (r == null) {
                continue;
            }

            double sugar = r.getBloodSugar();
            String period = r.getMealPeriod();
            double[] range = PeriodClassifier.getNormalRange(period);
            String tipText = buildChartTooltip(r, sugar, period, range);

            // 数据点 node 在图表渲染后才创建：监听 nodeProperty，创建后立即绑定样式与 Tooltip
            DialogSupport.applyWhenNodeReady(data, node -> {
                // 标点着色：高于正常上限红色 / 低于正常下限绿色 / 正常范围内蓝色（保留空心圆样式）
                String pointColor;
                if (sugar > range[1]) {
                    pointColor = "#FF5252";          // 高于正常上限 → 红
                } else if (sugar < range[0]) {
                    pointColor = DialogSupport.COLOR_NORMAL; // 低于正常下限 → 绿
                } else {
                    pointColor = DialogSupport.COLOR_BLUE;   // 正常范围内 → 蓝
                }
                node.setStyle("-fx-background-color: " + pointColor + ", white; "
                        + "-fx-background-radius: 50%; -fx-background-insets: 0, 2; "
                        + "-fx-padding: 6;  -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 4, 0, 1, 1);");

                // 鼠标悬停自动弹出提示：测量时间 / 数值 / 正常区间 / 建议
                // 改用显式事件绑定（不依赖 TooltipBehavior）：进入/移动即显示，离开即隐藏
                Tooltip tooltip = new Tooltip(tipText);
                tooltip.setStyle("-fx-background-color: #FFFDF5; -fx-background-radius: 10; "
                        + "-fx-border-color: " + DialogSupport.COLOR_BORDER + "; -fx-border-radius: 10; "
                        + "-fx-text-fill: " + DialogSupport.COLOR_TEXT + "; -fx-font-size: 13px; -fx-padding: 8 12 8 12;");
                tooltip.setAutoHide(true);
                node.setPickOnBounds(true);
                node.setOnMouseEntered(e -> tooltip.show(node, e.getScreenX(), e.getScreenY() + 18));
                node.setOnMouseMoved(e -> tooltip.show(node, e.getScreenX(), e.getScreenY() + 18));
                node.setOnMouseExited(e -> tooltip.hide());
            });
        }
    }

    /** 构建悬停提示文本 */
    private String buildChartTooltip(BloodSugarRecord r, double sugar, String period, double[] range) {
        String time = r.getRecordTime() != null
                ? r.getRecordTime().format(DialogFormats.FULL_FMT) : "-";
        String periodSafe = (period == null || period.isEmpty()) ? "空腹" : period;
        boolean normal = PeriodClassifier.isNormal(periodSafe, sugar);
        String status = normal ? "正常" : (sugar < range[0] ? "偏低" : "偏高");
        return String.format("时间：%s\n数值：%.1f mmol/L\n餐别：%s\n正常区间：%.1f ~ %.1f mmol/L\n状态：%s\n建议：%s",
                time, sugar, periodSafe, range[0], range[1], status,
                HealthAdvice.generate(sugar, periodSafe));
    }

    // ===================== 数据位置设置与迁移 =====================

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
        hint.setTextFill(Color.web(DialogSupport.COLOR_TEXT));
        hint.setStyle("-fx-font-size: 12px;");

        VBox currentBox = new VBox(4, currentTitle, currentField);
        VBox targetBox = new VBox(4, targetTitle, pathField);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("数据位置设置");
        dialog.setHeaderText("选择数据存放位置");
        DialogPane pane = dialog.getDialogPane();
        DialogSupport.styleDialogPane(pane);
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
            DialogSupport.showAlert(owner, "请填写或选择要存放数据的文件夹路径。");
            return;
        }
        Path target;
        try {
            target = Path.of(newText).toAbsolutePath().normalize();
        } catch (Exception ex) {
            DialogSupport.showAlert(owner, "路径无效：" + newText);
            return;
        }
        if (Files.exists(target) && !Files.isDirectory(target)) {
            DialogSupport.showAlert(owner, "目标不是一个文件夹，请重新选择。");
            return;
        }
        if (target.equals(current)) {
            DialogSupport.showAlert(owner, "该目录已经是当前数据目录，无需修改。");
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
            DialogSupport.styleDialogPane(info.getDialogPane());
            info.showAndWait();
        } catch (Exception ex) {
            DialogSupport.showAlert(owner, "设置数据位置失败：" + ex.getMessage());
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
            DialogSupport.styleDialogPane(confirm.getDialogPane());
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
                    + LocalDateTime.now().format(DialogFormats.FILE_STAMP_FMT));
            Files.createDirectories(backupDir);
            try {
                for (String name : DATA_FILE_NAMES) {
                    Path src = targetRoot.resolve(name);
                    if (Files.exists(src)) {
                        Files.move(src, backupDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (IOException ex) {
                for (String name : DATA_FILE_NAMES) {
                    Path backed = backupDir.resolve(name);
                    if (Files.exists(backed)) {
                        Files.move(backed, targetRoot.resolve(name), StandardCopyOption.REPLACE_EXISTING);
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
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
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
                    Files.move(backup, f, StandardCopyOption.REPLACE_EXISTING);
                    continue;
                }
            }
            Files.deleteIfExists(f);
        }
    }
}
