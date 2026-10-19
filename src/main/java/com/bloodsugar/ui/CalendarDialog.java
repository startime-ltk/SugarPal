package com.bloodsugar.ui;

import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.service.BloodSugarService;
import com.bloodsugar.util.PeriodClassifier;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 月份日历对话框：GridPane 7 列月份网格，可切换上/下月；
 * 有血糖记录的业务日黑体（白底加粗），无记录白体（糖果绿底白字）；
 * 点击日期查看当天血糖记录与三餐时间，支持补填三餐时间与血糖记录。
 */
public class CalendarDialog {

    // 糖果风格配色
    private static final String COLOR_TITLE = "#6B4226";
    private static final String COLOR_TEXT = "#4A4A4A";
    private static final String COLOR_MUTED = "#9A9488";
    private static final String COLOR_DAY_BG = "#FFF6E9";
    private static final String COLOR_DAY_BORDER = "#F5D9B0";
    private static final String COLOR_HAS_RECORD = "#FFFFFF";          // 有记录：白底
    private static final String COLOR_NO_RECORD = "linear-gradient(to bottom, #5ED48B, #2FB963)"; // 无记录：糖果绿底
    private static final String COLOR_SELECT_BORDER = "#FF8FB3";
    private static final String COLOR_BTN = "#F5A623";
    private static final String COLOR_BTN_DARK = "#C87F0A";
    private static final String COLOR_BTN_HOVER = "#FFD36E";
    private static final String COLOR_TODAY_DOT = "#FF5252";
    private static final String COLOR_NORMAL = "#2FC86B";
    private static final String COLOR_ABNORMAL = "#FF8A65";

    private final Stage owner;
    private final MainUI mainUI;
    private final BloodSugarService service = new BloodSugarService();

    private YearMonth currentMonth = YearMonth.now();
    private LocalDate selectedDate = LocalDate.now();
    private Set<LocalDate> recordDates = new HashSet<>();

    private Label monthLabel;
    private Label dayTitleLabel;
    private VBox detailBox;
    private GridPane grid;
    private final Map<LocalDate, StackPane> dayCells = new HashMap<>();

    public CalendarDialog(Stage owner, MainUI mainUI) {
        this.owner = owner;
        this.mainUI = mainUI;
    }

    /** 打开日历对话框 */
    public void show() {
        reloadRecordDates();

        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("糖伴SugarPal - 日历");
        dialog.setHeaderText("黑体=有血糖记录（白底加粗），白体=无记录（糖果绿底） ｜ 点击日期查看当天数据");

        VBox content = buildContent();
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.getDialogPane().setPrefSize(780, 780);
        DialogSupport.styleDialogPane(dialog.getDialogPane());
        dialog.showAndWait();
    }

    // ---------- 界面构建 ----------

    private VBox buildContent() {
        VBox root = new VBox(12);
        root.setPadding(new Insets(16, 20, 20, 20));
        root.setStyle("-fx-background-color: linear-gradient(to bottom right, #FFF6E9, #FFF0F6); -fx-background-radius: 16;");

        // 顶部月份切换栏
        root.getChildren().add(buildMonthBar());

        // 星期表头 + 日期网格
        grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(4));
        buildWeekHeader();
        root.getChildren().add(grid);

        // 选中日期标题
        dayTitleLabel = new Label();
        dayTitleLabel.setFont(Font.font("YouYuan", FontWeight.BOLD, 16));
        dayTitleLabel.setTextFill(Color.web(COLOR_TITLE));
        root.getChildren().add(dayTitleLabel);

        // 当天详情（滚动区）
        detailBox = new VBox(6);
        detailBox.setPadding(new Insets(10, 12, 10, 12));
        detailBox.setStyle("-fx-background-color: rgba(255,255,255,0.75); -fx-background-radius: 12;");
        ScrollPane scroll = new ScrollPane(detailBox);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(200);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        root.getChildren().add(scroll);

        // 底部操作按钮
        root.getChildren().add(buildActionBar());

        rebuildGrid();
        showDayDetail(selectedDate);
        return root;
    }

    private HBox buildMonthBar() {
        monthLabel = new Label();
        monthLabel.setFont(Font.font("YouYuan", FontWeight.BOLD, 18));
        monthLabel.setTextFill(Color.web(COLOR_TITLE));

        Button prevBtn = candyButton("◀ 上月", 13);
        prevBtn.setOnAction(e -> shiftMonth(-1));
        Button nextBtn = candyButton("下月 ▶", 13);
        nextBtn.setOnAction(e -> shiftMonth(1));
        Button todayBtn = candyButton("回到今天", 13);
        todayBtn.setOnAction(e -> {
            currentMonth = YearMonth.now();
            selectedDate = LocalDate.now();
            reloadRecordDates();
            rebuildGrid();
            showDayDetail(selectedDate);
        });

        Region spacer = DialogSupport.hGrowSpacer(10);
        HBox bar = new HBox(10, prevBtn, monthLabel, nextBtn, spacer, todayBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void buildWeekHeader() {
        String[] headers = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < 7; i++) {
            Label lb = new Label(headers[i]);
            lb.setFont(Font.font("YouYuan", FontWeight.BOLD, 13));
            lb.setTextFill(Color.web(COLOR_TITLE));
            lb.setAlignment(Pos.CENTER);
            lb.setMaxWidth(Double.MAX_VALUE);
            grid.add(lb, i, 0);
        }
    }

    private HBox buildActionBar() {
        Button mealBtn = candyButton("补填三餐时间", 14);
        mealBtn.setOnAction(e -> showAddMealTimesDialog());
        Button sugarBtn = candyButton("补填血糖记录", 14);
        sugarBtn.setOnAction(e -> showAddSugarDialog());
        HBox bar = new HBox(12, mealBtn, sugarBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    // ---------- 网格渲染 ----------

    private void rebuildGrid() {
        // 清掉日期格（保留第 0 行星期表头）
        grid.getChildren().removeIf(n -> {
            Integer row = GridPane.getRowIndex(n);
            return row != null && row > 0;
        });
        dayCells.clear();
        monthLabel.setText(currentMonth.format(DialogFormats.MONTH_FMT));

        LocalDate first = currentMonth.atDay(1);
        int leading = first.getDayOfWeek().getValue() - 1; // 周一起始：周一=0
        int daysInMonth = currentMonth.lengthOfMonth();
        LocalDate today = LocalDate.now();

        int row = 1;
        for (int c = 0; c < leading; c++) {
            grid.add(emptyCell(), c, row);
        }
        for (int d = 1; d <= daysInMonth; d++) {
            LocalDate date = currentMonth.atDay(d);
            int col = (leading + d - 1) % 7;
            if (col == 0 && d > 1) {
                row++;
            }
            StackPane cell = buildDayCell(date, today);
            grid.add(cell, col, row);
            dayCells.put(date, cell);
        }
    }

    private StackPane emptyCell() {
        StackPane empty = new StackPane();
        empty.setMinSize(88, 62);
        empty.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        GridPane.setHgrow(empty, Priority.ALWAYS);
        GridPane.setVgrow(empty, Priority.ALWAYS);
        return empty;
    }

    private StackPane buildDayCell(LocalDate date, LocalDate today) {
        boolean hasRecord = recordDates.contains(date);
        StackPane cell = new StackPane();
        cell.setMinSize(88, 62);
        cell.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        GridPane.setHgrow(cell, Priority.ALWAYS);
        GridPane.setVgrow(cell, Priority.ALWAYS);
        cell.setAlignment(Pos.CENTER);
        cell.setStyle(buildCellStyle(date, hasRecord));

        Label num = new Label(String.valueOf(date.getDayOfMonth()));
        if (hasRecord) {
            // 有记录：黑体加粗深色
            num.setFont(Font.font("YouYuan", FontWeight.BOLD, 16));
            num.setTextFill(Color.web("#202020"));
        } else {
            // 无记录：白体（糖果绿底衬托）
            num.setFont(Font.font("YouYuan", FontWeight.NORMAL, 14));
            num.setTextFill(Color.WHITE);
        }

        if (date.equals(today)) {
            Circle dot = new Circle(4, Color.web(COLOR_TODAY_DOT));
            StackPane.setAlignment(dot, Pos.TOP_RIGHT);
            StackPane.setMargin(dot, new Insets(7, 10, 0, 0));
            cell.getChildren().addAll(num, dot);
        } else {
            cell.getChildren().add(num);
        }

        Tooltip tip = new Tooltip(date.format(DialogFormats.DATE_FMT) + "（" + (hasRecord ? "有记录·黑体" : "无记录·白体") + "）");
        Tooltip.install(cell, tip);

        cell.setCursor(Cursor.HAND);
        cell.setOnMouseEntered(e -> cell.setOpacity(0.85));
        cell.setOnMouseExited(e -> cell.setOpacity(1.0));
        cell.setOnMouseClicked(e -> selectDate(date));
        return cell;
    }

    private String buildCellStyle(LocalDate date, boolean hasRecord) {
        StringBuilder sb = new StringBuilder();
        if (hasRecord) {
            sb.append("-fx-background-color: ").append(COLOR_HAS_RECORD).append("; ");
        } else {
            sb.append("-fx-background-color: ").append(COLOR_NO_RECORD).append("; ");
        }
        sb.append("-fx-background-radius: 14; -fx-border-radius: 14; ");
        if (date.equals(selectedDate)) {
            sb.append("-fx-border-color: ").append(COLOR_SELECT_BORDER).append("; -fx-border-width: 3; ");
            sb.append("-fx-effect: dropshadow(gaussian, rgba(255,143,179,0.55), 10, 0, 0, 2); ");
        } else {
            sb.append("-fx-border-color: ").append(hasRecord ? COLOR_DAY_BORDER : "transparent")
                    .append("; -fx-border-width: 1; ");
        }
        return sb.toString();
    }

    private void shiftMonth(int delta) {
        currentMonth = currentMonth.plusMonths(delta);
        reloadRecordDates();
        rebuildGrid();
        if (!YearMonth.from(selectedDate).equals(currentMonth)) {
            selectedDate = currentMonth.atDay(Math.min(selectedDate.getDayOfMonth(), currentMonth.lengthOfMonth()));
            showDayDetail(selectedDate);
        }
    }

    // ---------- 选中与详情 ----------

    private void selectDate(LocalDate date) {
        selectedDate = date;
        // 重绘所有格子的选中态
        for (Map.Entry<LocalDate, StackPane> entry : dayCells.entrySet()) {
            boolean has = recordDates.contains(entry.getKey());
            entry.getValue().setStyle(buildCellStyle(entry.getKey(), has));
        }
        showDayDetail(date);
    }

    private void showDayDetail(LocalDate date) {
        dayTitleLabel.setText(date.format(DialogFormats.DAY_FMT));
        detailBox.getChildren().clear();
        try {
            // 当天血糖记录（按凌晨4点业务日边界）
            List<BloodSugarRecord> records = service.getRecordsByBusinessDate(date);
            // 当天已保存的三餐时间
            Map<String, LocalDateTime> meals = service.getMealTimesByBusinessDate(date);

            detailBox.getChildren().add(sectionTitle("三餐时间"));
            if (meals.isEmpty()) {
                detailBox.getChildren().add(detailLabel("  未补填，可通过下方按钮补填", COLOR_MUTED, 12, false));
            } else {
                String mealLine = meals.entrySet().stream()
                        .sorted(Map.Entry.comparingByValue())
                        .map(e -> e.getKey() + " " + e.getValue().format(DialogFormats.TIME_FMT))
                        .collect(Collectors.joining("    "));
                detailBox.getChildren().add(detailLabel("  " + mealLine, COLOR_TEXT, 12, false));
            }

            detailBox.getChildren().add(sectionTitle("血糖记录（" + records.size() + " 条）"));
            if (records.isEmpty()) {
                detailBox.getChildren().add(detailLabel("  当天暂无血糖记录", COLOR_MUTED, 12, false));
            } else {
                for (BloodSugarRecord r : records) {
                    String time = r.getRecordTime() != null
                            ? r.getRecordTime().format(DialogFormats.TIME_FMT) : "--";
                    String period = r.getMealPeriod() != null ? r.getMealPeriod() : "空腹";
                    String type = r.getMealType() != null && !r.getMealType().isEmpty() ? r.getMealType() : "-";
                    String note = r.getNote() != null && !r.getNote().isEmpty() ? "  备注：" + r.getNote() : "";
                    boolean normal = PeriodClassifier.isNormal(period, r.getBloodSugar());
                    String color = normal ? COLOR_NORMAL : COLOR_ABNORMAL;
                    String line = String.format("  %s    %.1f mmol/L   %s   %s%s",
                            time, r.getBloodSugar(), period, type, note);
                    detailBox.getChildren().add(detailLabel(line, color, 12, normal));
                }
            }
        } catch (SQLException ex) {
            detailBox.getChildren().add(detailLabel("加载失败：" + ex.getMessage(), "#FF5252", 12, false));
        }
    }

    // ---------- 补填 ----------

    /** 补填三餐时间：按选中业务日保存（凌晨 0~4 点也归到该业务日） */
    private void showAddMealTimesDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("补填三餐时间 - " + selectedDate.format(DialogFormats.DATE_FMT));
        dialog.setHeaderText("为 " + selectedDate.format(DialogFormats.DAY_FMT) + " 补填用餐时间（HH:mm，留空不修改）");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(20));

        String[] names = {"早餐", "午餐", "晚餐", "加餐"};
        Map<String, TextField> fields = new LinkedHashMap<>();
        Map<String, LocalDateTime> saved = new HashMap<>();
        try {
            saved = service.getMealTimesByBusinessDate(selectedDate);
        } catch (SQLException ignored) {
            // 保持空
        }

        for (int i = 0; i < names.length; i++) {
            Label lb = new Label(names[i] + ":");
            lb.setFont(Font.font("YouYuan", FontWeight.BOLD, 13));
            lb.setTextFill(Color.web(COLOR_TITLE));
            TextField tf = new TextField();
            LocalDateTime st = saved.get(names[i]);
            if (st != null) {
                tf.setText(st.format(DialogFormats.TIME_FMT));
            }
            tf.setPromptText("HH:mm");
            tf.setPrefWidth(140);
            tf.setTextFormatter(new TextFormatter<>(change -> {
                if (change.getText().contains(".")) {
                    change.setText(change.getText().replace('.', ':'));
                }
                return change;
            }));
            tf.setStyle("-fx-background-radius: 8; -fx-border-radius: 8; "
                    + "-fx-border-color: " + COLOR_DAY_BORDER + "; -fx-background-color: #FFFFFF;");
            grid.add(lb, 0, i);
            grid.add(tf, 1, i);
            fields.put(names[i], tf);
        }
        Label hint = new Label("时间格式 HH:mm（支持 . 自动转 :），留空表示不保存该餐别");
        hint.setFont(Font.font("YouYuan", 11));
        hint.setTextFill(Color.web(COLOR_MUTED));
        grid.add(hint, 0, names.length, 2, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        DialogSupport.styleDialogPane(dialog.getDialogPane());
        dialog.setResultConverter(btn -> {
            if (btn != ButtonType.OK) {
                return null;
            }
            int savedCount = 0;
            for (Map.Entry<String, TextField> entry : fields.entrySet()) {
                String text = entry.getValue().getText().trim();
                if (text.isEmpty()) {
                    continue;
                }
                try {
                    LocalTime t = LocalTime.parse(text.replace('.', ':'));
                    service.saveMealTimeForDate(selectedDate, entry.getKey(),
                            LocalDateTime.of(selectedDate, t));
                    savedCount++;
                } catch (Exception ex) {
                    DialogSupport.showAlert(owner, "【" + entry.getKey() + "】时间格式不正确：" + ex.getMessage());
                    return null;
                }
            }
            if (savedCount > 0) {
                showDayDetail(selectedDate);
                mainUI.refreshAll();
            }
            return btn;
        });
        dialog.showAndWait();
    }

    /** 补填血糖记录：复用主界面添加记录对话框，日期预选为选中日期 */
    private void showAddSugarDialog() {
        mainUI.showAddDialog(owner, selectedDate);
        // 返回后刷新日历记录状态与当天详情
        reloadRecordDates();
        rebuildGrid();
        showDayDetail(selectedDate);
    }

    // ---------- 工具 ----------

    private void reloadRecordDates() {
        try {
            recordDates = service.getRecordBusinessDates();
        } catch (SQLException ex) {
            recordDates = new HashSet<>();
        }
    }

    /** 糖果按钮：渐变底 + 悬停变亮（原 navButton / actionButton 两处重复实现合并，仅字号不同） */
    private Button candyButton(String text, double size) {
        Button b = new Button(text);
        b.setFont(Font.font("YouYuan", FontWeight.BOLD, size));
        b.setTextFill(Color.WHITE);
        String normal = "-fx-background-color: linear-gradient(to bottom, #FFD36E, " + COLOR_BTN + "); "
                + "-fx-background-radius: 12; -fx-border-radius: 12; "
                + "-fx-border-color: " + COLOR_BTN_DARK + "; -fx-border-width: 1; "
                + "-fx-cursor: hand;";
        String hover = "-fx-background-color: linear-gradient(to bottom, #FFE9A8, " + COLOR_BTN_HOVER + "); "
                + "-fx-background-radius: 12; -fx-border-radius: 12; "
                + "-fx-border-color: " + COLOR_BTN_DARK + "; -fx-border-width: 1; -fx-cursor: hand;";
        b.setStyle(normal);
        b.setOnMouseEntered(e -> b.setStyle(hover));
        b.setOnMouseExited(e -> b.setStyle(normal));
        return b;
    }

    private Label sectionTitle(String text) {
        Label lb = new Label(text);
        lb.setFont(Font.font("YouYuan", FontWeight.BOLD, 13));
        lb.setTextFill(Color.web(COLOR_TITLE));
        lb.setPadding(new Insets(4, 0, 0, 0));
        return lb;
    }

    private Label detailLabel(String text, String color, int size, boolean bold) {
        Label lb = new Label(text);
        FontWeight weight = bold ? FontWeight.BOLD : FontWeight.NORMAL;
        lb.setFont(Font.font("YouYuan", weight, size));
        lb.setTextFill(Color.web(color));
        return lb;
    }
}
