package com.bloodsugar.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.chart.XYChart;
import javafx.stage.Stage;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 对话框公共支持：糖果风配色常量、按钮 / 艺术字 / 对话框样式、提示框、表格列工厂。
 * 各功能对话框（记录、健康、AI、导出、总结、AGP、导入）共用，
 * 避免同一套样式代码在多个类里重复维护。
 */
public final class DialogSupport {

    // ---------- 糖果马卡龙配色 ----------

    public static final String COLOR_BG = "#EAFCF0";          // 主背景 薄荷糖白
    public static final String COLOR_PANEL = "#FFFFFF";       // 面板 纯白
    public static final String COLOR_BORDER = "#B8EED0";      // 边框 亮薄荷绿
    public static final String COLOR_TEXT = "#3A4A42";        // 正文 深灰绿
    public static final String COLOR_TITLE = "#2BB673";       // 标题 糖果绿
    public static final String COLOR_TITLE_DARK = "#12A35C";  // 标题渐变深端
    public static final String COLOR_TITLE_LIGHT = "#7FE8B0"; // 标题渐变浅端
    public static final String COLOR_GREEN = "#3BC96E";       // 主按钮 亮糖绿
    public static final String COLOR_GREEN_LIGHT = "#7DE3A4"; // 主按钮浅端
    public static final String COLOR_GREEN_DARK = "#22A856";  // 主按钮深端
    public static final String COLOR_BLUE = "#3FB8FF";        // 次按钮 天蓝
    public static final String COLOR_BLUE_LIGHT = "#7FD3FF";  // 次按钮浅端
    public static final String COLOR_BLUE_DARK = "#1E9FE8";   // 次按钮深端
    public static final String COLOR_ORANGE = "#FFA45E";      // 蜜桃橙
    public static final String COLOR_ORANGE_LIGHT = "#FFC48C";// 蜜桃橙浅端
    public static final String COLOR_ORANGE_DARK = "#F58B36"; // 蜜桃橙深端
    public static final String COLOR_PINK = "#FF8FB3";        // 樱粉 装饰
    public static final String COLOR_PINK_LIGHT = "#FFC4D9";  // 樱粉浅 装饰
    public static final String COLOR_MEAL_BG = "#FFF6E9";     // 用餐卡片 奶油蜜桃
    public static final String COLOR_MEAL_BG2 = "#FFE3C9";    // 用餐卡片渐变浅端
    public static final String COLOR_MEAL_BORDER = "#FFD2A8"; // 用餐卡片边框
    public static final String COLOR_NORMAL = "#2FC86B";      // 正常血糖 清新亮绿
    public static final String COLOR_HIGH = "#FF8A65";        // 偏高血糖 蜜桃珊瑚
    public static final String COLOR_HEADER = "#D9F9E4";      // 表头 亮薄荷
    public static final String COLOR_STATUS = "#EFFBF2";      // 状态栏 薄荷白

    /** 主标题用的糖果渐变：樱粉→蜜桃橙→亮糖绿 */
    public static final LinearGradient CANDY_GRADIENT = new LinearGradient(0, 0, 1, 1, true,
            CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#FF7EB3")),
            new Stop(0.5, Color.web("#FFA45E")),
            new Stop(1, Color.web("#2FC86B")));

    private DialogSupport() {
    }

    // ---------- 按钮与标签 ----------

    /** 糖果按钮样式：亮渐变、大圆角、底部深色边 */
    public static String buttonStyle(String from, String to, String border) {
        return "-fx-background-color: linear-gradient(to bottom, " + from + ", " + to + "); "
                + "-fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 11 22; "
                + "-fx-background-radius: 20; -fx-border-radius: 20; "
                + "-fx-border-color: " + border + "; -fx-border-width: 0 0 4 0; "
                + "-fx-cursor: hand; -fx-font-weight: bold;";
    }

    /** 糖果按钮：圆角大、带彩色阴影，鼠标放上去变亮 */
    public static Button styledButton(String text, String from, String to, String hoverFrom, String hoverTo,
                                      String border, String shadow) {
        Button btn = new Button(text);
        btn.setStyle(buttonStyle(from, to, border));
        btn.setEffect(new DropShadow(5, 3, 4, Color.web(shadow)));
        btn.setOnMouseEntered(e -> btn.setStyle(buttonStyle(hoverFrom, hoverTo, border)));
        btn.setOnMouseExited(e -> btn.setStyle(buttonStyle(from, to, border)));
        return btn;
    }

    /** 渐变艺术字小标题 */
    public static Label artLabel(String text, double size) {
        Label label = new Label(text);
        label.setFont(Font.font("YouYuan", FontWeight.BOLD, size));
        label.setTextFill(CANDY_GRADIENT);
        label.setEffect(new DropShadow(2, 1, 1, Color.web("#FFD3E2")));
        return label;
    }

    /** 关闭按钮：粉色糖果样式（各对话框统一使用） */
    public static void stylePinkCloseButton(Button b) {
        b.setStyle("-fx-background-color: linear-gradient(to bottom, #FFD3E2, #FFA8C5); "
                + "-fx-text-fill: #8C3B52; -fx-font-size: 13px; -fx-padding: 8 20; "
                + "-fx-background-radius: 18; -fx-border-radius: 18; "
                + "-fx-border-color: #E87FA5; -fx-border-width: 0 0 3 0; "
                + "-fx-cursor: hand; -fx-font-weight: bold;");
        b.setEffect(new DropShadow(4, 2, 3, Color.web("#FFC4D9")));
    }

    /** 对话框关闭按钮：添加 CANCEL_CLOSE 型按钮并统一粉色样式 */
    public static Button addPinkCloseButton(DialogPane pane) {
        ButtonType closeType = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().add(closeType);
        Button closeBtn = (Button) pane.lookupButton(closeType);
        if (closeBtn != null) {
            stylePinkCloseButton(closeBtn);
        }
        return closeBtn;
    }

    // ---------- 对话框样式与提示 ----------

    /** 统一对话框样式：奶油渐变背景、大圆角、OK 绿色 / CANCEL 粉色按钮 */
    public static void styleDialogPane(DialogPane pane) {
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

    /** 警告提示框（统一风格） */
    public static void showAlert(Stage owner, String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        styleDialogPane(alert.getDialogPane());
        alert.showAndWait();
    }

    // ---------- 通用控件工厂 ----------

    /** 多维健康录入框：胰岛素 / 碳水 / 运动 / 体重 / 脉搏 / 血压 共用样式 */
    public static TextField createHealthField(String placeholder) {
        TextField tf = new TextField();
        tf.setPromptText(placeholder);
        tf.setPrefWidth(110);
        tf.setStyle("-fx-background-radius: 8; -fx-border-radius: 8; "
                + "-fx-border-color: #A8D8FF; -fx-background-color: #FFFFFF;");
        return tf;
    }

    /** 「标题 + 内容」竖向卡片，内容区自动撑满 */
    public static VBox titledBox(String title, Node content) {
        Label lb = artLabel(title, 13);
        VBox v = new VBox(6, lb, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        v.setMaxWidth(Double.MAX_VALUE);
        return v;
    }

    /**
     * 字符串列工厂：单元格值由 extractor 从行数据计算。
     * 合并了原 AGP 统计表（strCol）与导入预览表（impCol）两处重复实现。
     */
    public static <T> TableColumn<T, String> textColumn(String title, double width, Function<T, String> extractor) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cd -> new SimpleStringProperty(extractor.apply(cd.getValue())));
        return col;
    }

    /** 统计表格通用样式（AGP 分时段 / 餐别统计表共用） */
    public static <T> TableView<T> statTable() {
        TableView<T> t = new TableView<>();
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        t.setPlaceholder(new Label("暂无数据"));
        t.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-font-size: 12px;");
        return t;
    }

    // ---------- 图表节点监听工具 ----------

    /**
     * data node 尚未创建时挂监听，创建后立即执行 action 并摘除监听。
     * 用于图表渲染完成后再给数据点上色 / 绑提示，避免样式丢失。
     */
    public static void applyWhenNodeReady(XYChart.Data<String, Number> data, Consumer<Node> action) {
        Node node = data.getNode();
        if (node != null) {
            action.accept(node);
            return;
        }
        data.nodeProperty().addListener(new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Node> obs, Node oldNode, Node newNode) {
                if (newNode != null) {
                    action.accept(newNode);
                    data.nodeProperty().removeListener(this);
                }
            }
        });
    }

    /** series node 尚未创建时挂监听，创建后立即执行 action 并摘除监听 */
    public static void applyWhenSeriesNodeReady(XYChart.Series<String, Number> series, Consumer<Node> action) {
        Node node = series.getNode();
        if (node != null) {
            action.accept(node);
            return;
        }
        series.nodeProperty().addListener(new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Node> obs, Node oldNode, Node newNode) {
                if (newNode != null) {
                    action.accept(newNode);
                    series.nodeProperty().removeListener(this);
                }
            }
        });
    }

    /** HBox 伸缩占位（简化 HBox.setHgrow 写法） */
    public static Region hGrowSpacer(double prefWidth) {
        Region spacer = new Region();
        spacer.setPrefWidth(prefWidth);
        spacer.setMinWidth(prefWidth);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
