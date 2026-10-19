# 糖伴SugarPal (Sugar Pal Blood Sugar Tracker)

基于 JavaFX 的桌面血糖管理工具，支持记录血糖、趋势图表、自动餐别识别、糖尿病风险评估。

## 技术栈

| 组件 | 版本 |
|------|------|
| JDK | 21 (Eclipse Adoptium) |
| JavaFX | 21 |
| H2 Database | 2.2.224 (嵌入式，零安装) |
| HikariCP | 5.1.0 |
| Maven | 3.9+ |
| 构建 | Maven Shade + Assembly |

## 功能

- **血糖记录**：添加/修改/删除血糖测量数据（日期、时间、血糖值、备注）
- **餐别自动识别**：根据「今日用餐时间」面板推算精确餐别描述（如"早餐后 2小时35分钟""空腹""睡前"）
- **趋势曲线**：血糖折线图，正常值绿色、偏高值红色，含正常区间上下限
- **时间段分类**：自动区分空腹/餐后1h/2h/3h，用于区间判定
- **日期筛选**：按日期过滤历史记录
- **健康建议**：每次记录后自动给出饮食/运动建议
- **总结报告**：统计平均值/最高/最低/正常率，糖尿病风险与胰岛素抵抗评估
- **本地数据库**：H2 嵌入式数据库，数据存储在 `%USERPROFILE%\.bloodsugar\data`

## 环境要求

- JDK 21
- Maven 3.9+
- Windows / macOS / Linux

## 快速开始

```bash
# 克隆项目
git clone <your-repo-url>
cd sugarpal

# 编译
mvn compile

# 运行
mvn javafx:run

# 打包为可执行 JAR
mvn package
# fat JAR 位于 target/SugarPal.jar
```

## 项目结构

```
sugarpal/
├── pom.xml
├── sql/
│   └── init.sql                        # 数据库建表参考
├── src/
│   ├── main/
│   │   ├── assembly/
│   │   │   └── assembly.xml            # 分发包配置
│   │   ├── java/com/bloodsugar/
│   │   │   ├── BloodSugarApp.java      # 应用入口
│   │   │   ├── config/                 # 配置层
│   │   │   │   ├── AiConfig.java       # AI 服务商配置（Key 本地混淆存储）
│   │   │   │   ├── DatabaseConfig.java # H2 连接池配置
│   │   │   │   └── DataLocation.java   # 数据目录管理与迁移
│   │   │   ├── dao/
│   │   │   │   └── BloodSugarDAO.java  # 数据访问层
│   │   │   ├── model/
│   │   │   │   ├── AgpReport.java      # AGP 报告数据模型
│   │   │   │   ├── BloodSugarRecord.java
│   │   │   │   └── MealPeriod.java
│   │   │   ├── service/                # 业务层
│   │   │   │   ├── AgpPdfExporter.java      # AGP 报告 PDF 导出
│   │   │   │   ├── AgpReportService.java    # AGP 指标计算（TIR/CV/SD/HbA1c）
│   │   │   │   ├── AiSuggestionService.java # AI 建议（OpenAI 兼容接口）
│   │   │   │   ├── BloodSugarService.java
│   │   │   │   ├── CsvImportService.java    # 血糖仪数据批量导入
│   │   │   │   └── ExportService.java       # Excel / PDF 导出
│   │   │   ├── ui/                     # 界面层（MainUI 只保留主界面，
│   │   │   │                            #  各功能对话框拆分为独立类）
│   │   │   │   ├── MainUI.java               # 主界面与趋势曲线
│   │   │   │   ├── CalendarDialog.java       # 月份日历与补填
│   │   │   │   ├── RecordDialog.java         # 血糖记录 新增/修改/补填
│   │   │   │   ├── HealthEntryDialog.java    # 多维健康录入与趋势
│   │   │   │   ├── AiSuggestionDialog.java   # AI 建议与模型设置
│   │   │   │   ├── ExportDialog.java         # 导出报告
│   │   │   │   ├── SummaryReportDialog.java  # 总结报告与风险评估
│   │   │   │   ├── AgpReportDialog.java      # AGP 葡萄糖图谱
│   │   │   │   ├── DataImportDialog.java     # 血糖仪数据批量导入
│   │   │   │   ├── DialogSupport.java        # 对话框公共样式与控件工厂
│   │   │   │   ├── DialogFormats.java        # 日期时间格式常量
│   │   │   │   ├── MealTypeAutoRecognizer.java # 餐别自动识别
│   │   │   │   └── HealthAdvice.java        # 血糖建议文案
│   │   │   └── util/
│   │   │       └── PeriodClassifier.java  # 时段分类与正常区间
│   │   └── resources/
│   │       ├── icon.png
│   │       └── icon.ico
│   └── test/
└── .vscode/                            # VS Code 运行配置
```

## 代码整理说明（v1.2.0 软著申请整理版）

本版本为**软著申请专用整理版**，在不改变任何功能与交互行为的前提下完成代码清理：

- **拆分巨类**：原 `MainUI.java`（约 3000 行）按功能拆分为「主界面 + 9 个功能对话框类」，
  主界面保留主界面组装、数据刷新与图表渲染（约 1200 行）
- **消除重复**：合并 5 处重复的对话框关闭按钮样式、添加/修改对话框重复的健康字段逻辑、
  两处重复的表格列工厂、日历对话框重复的按钮工厂
- **删除死代码**：移除空实现的监听器、定义后从未使用的常量与 import
- **规范风格**：全库取消通配符 import、清除全限定名混用，统一命名与注释
- **提取公共层**：`DialogSupport`（样式/控件工厂）、`DialogFormats`（格式常量）、
  `MealTypeAutoRecognizer`（餐别识别）、`HealthAdvice`（建议文案）供各对话框共用

功能与数据格式与 v1.1.0 完全一致，已通过 `mvn package` 完整构建验证。

## 数据库

使用 H2 嵌入式数据库，首次运行自动创建。数据文件路径：

```
%USERPROFILE%\.bloodsugar\data
```

表结构（`blood_sugar_records`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INT AUTO_INCREMENT | 主键 |
| record_time | DATETIME | 测量时间 |
| blood_sugar | DECIMAL(5,2) | 血糖值 (mmol/L) |
| meal_time | DATETIME | 用餐时间 |
| meal_period | VARCHAR(20) | 时段（空腹/餐后1h/2h/3h） |
| meal_type | VARCHAR(40) | 餐别描述（自动识别） |
| note | VARCHAR(200) | 备注 |
| created_at | TIMESTAMP | 创建时间 |

## 正常血糖范围

| 时段 | 正常下限 | 正常上限 |
|------|---------|---------|
| 空腹 | 3.9 | 6.1 |
| 餐后1h | 3.9 | 11.1 |
| 餐后2h | 3.9 | 7.8 |
| 餐后3h | 3.9 | 7.8 |
| 睡前 | 3.9 | 7.8 |

## 使用说明

1. 启动后，在左侧「今日用餐时间」面板输入当天的早餐/午餐/晚餐/加餐时间，点击保存
2. 点击「+ 添加记录」，输入测量日期、时间和血糖值
3. 餐别会根据用餐时间自动计算（如早餐 7:00，9:35 测量 → "早餐后 2小时35分钟"）
4. 右侧趋势图直观展示血糖变化，绿色点在正常范围内，红色点偏高
5. 点击「生成总结」查看统计报告与风险评估

## 许可

MIT
