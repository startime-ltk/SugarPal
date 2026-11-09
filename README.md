# 糖伴 SugarPal（Sugar Pal Blood Sugar Tracker）

基于 JavaFX 的桌面血糖管理工具：记录血糖、趋势图表、自动餐别识别、AGP 图谱、AI 建议、Excel / PDF 报告导出，以及完整的数据备份与恢复。
数据全部保存在本机，**不上传任何服务器**。

> **当前版本 v1.3.9**（2026-11-06）。
> 本仓库 `master` 为当前开发分支；`main` 是 「v1.2.0 代码整理版」，仅做代码结构整理，功能与数据格式同 v1.1.0。**功能说明与运行版本请以 `master` 为准。**

## 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| JDK | 21 (Eclipse Adoptium) | 运行时 |
| JavaFX | 21 | 界面（纯 Java 代码构建，未使用 FXML） |
| H2 Database | 2.2.224 | 嵌入式数据库，零安装 |
| HikariCP | 5.1.0 | 连接池 |
| Gson | 2.10.1 | AI / 更新检查的 JSON 解析 |
| Apache POI | 5.2.5 | Excel 报告导出 |
| OpenPDF | 1.3.40 | PDF 报告导出 |
| Maven | 3.9+ | 构建（Shade + Assembly + jpackage） |

## 功能

- **血糖记录**：添加 / 修改 / 删除血糖测量数据（日期、时间、血糖值、备注）
- **餐别自动识别**：根据「今日用餐时间」面板推算精确餐别描述（如"早餐后 2小时35分钟""空腹""睡前"）
- **趋势曲线**：血糖折线图，正常值绿色、偏高值红色，含正常区间上下限
- **时间段分类**：自动区分 空腹 / 餐后1h / 餐后2h / 餐后3h，用于区间判定
- **业务日边界**：凌晨 4 点前的记录归入前一天（熬夜后测的血糖不会被算错日子）
- **日历视图**：按月查看记录分布，支持补填三餐时间
- **健康数据**：记录胰岛素、碳水、活动量、体重、脉搏、血压，并生成多维趋势图
- **异常报警**：可配置低 / 高阈值（默认 3.9 / 13.9 mmol/L），超出范围时提示
- **AI 智能建议**：接入智谱 GLM / DeepSeek / 通义千问 / Kimi / 豆包 及自定义服务，按记录给出饮食运动建议
- **总结报告**：平均值 / 最高 / 最低 / 正常率，糖尿病风险与胰岛素抵抗评估
- **AGP 报告**：动态血糖图谱（AGP）标准图，含 24 小时分位曲线、中位数带与目标范围，指标含 TIR / TAR / TBR / SD / CV / 估算 HbA1c
- **分享摘要 / 导出报告**：一键导出 Excel（5 张表）或 PDF 报告，方便带给医生看
- **CSV 导入**：批量导入历史记录，缺失餐别自动推断
- **数据位置**：可在「关于」中查看并更改数据存放目录，改目录时自动备份旧数据
- **数据备份 / 恢复**：每天首次启动自动留一份快照（保留最近 7 天）；也可手动导出 `.zip` 到 U 盘 / 网盘。恢复时先对比"备份记录数 vs 当前记录数"并警告，恢复前自动另存当前数据，任何一次恢复都能从清单里再退回去
- **一键更新**：检查新版本并静默覆盖安装；更新源多源回退，系统代理握手失败时自动绕行直连
- **本地日志**：运行期异常写入 `%USERPROFILE%\.bloodsugar\logs\sugarpal.log`

## 数据与隐私

- 数据存放于 `%USERPROFILE%\.bloodsugar\`（可在「关于 → 数据位置」中更改）
- 本地 H2 文件数据库，**不联网上报**，断网可正常使用
- 只有「AI 智能建议」在你主动点击时才会调用你所配置的模型服务；不配置则该功能不可用
- API Key 以 Windows DPAPI 加密后存储在本机配置文件中

## 环境要求

- JDK 21
- Maven 3.9+
- Windows / macOS / Linux

## 快速开始

```bash
# 克隆项目（注意切换到 master 分支）
git clone https://github.com/startime-ltk/SugarPal.git
cd SugarPal
git checkout master

# 编译并打包（fat JAR 位于 target/SugarPal.jar）
mvn clean package

# 以开发模式运行
mvn javafx:run

# Windows 下也可以双击 启动血糖系统.bat（需先编译）
```

## 打包成安装包

打包脚本：`打包脚本/build.py`（在项目外层目录，与源码同级），版本号按实际发布填写。

```bash
python ..\打包脚本\build.py 1.3.9
```

产物：

- `成品安装包\_v<版本>\糖伴SugarPal\` —— 免安装版，直接运行 `糖伴SugarPal.exe`
- `SugarPal-Setup-v<版本>.exe` —— Inno Setup 安装包，支持**选择安装位置**，默认装到非系统盘（如 `F:\Programs\糖伴SugarPal`）

> ⚠️ **打包时 `--add-modules` 必须包含 `jdk.crypto.ec`**
> jpackage 自动生成的精简 Java 运行时只有 17 个模块，缺少 `jdk.crypto.ec`（提供 ECDSA 验签）。
> 而 GitHub / jsDelivr 的证书链上一级是 EC 根证书 `USERTrust ECC Certification Authority`，
> 缺该模块时 TLS 握手会直接失败，表现为「检查更新」永远报 `handshake_failure`，
> 且**与代理、网络无关**。该模块请勿删除。

## 项目结构

```
sugarpal/
├── pom.xml
├── 启动血糖系统.bat                     # Windows 开发用启动脚本
├── 打包命令-jpackage.ps1
├── update/latest.json                   # 备用版本清单（多源回退用）
├── src/main/
│   ├── assembly/assembly.xml            # 分发包配置
│   ├── java/com/bloodsugar/
│   │   ├── BloodSugarApp.java           # 应用入口
│   │   ├── config/                      # 数据库连接、数据目录、AI/报警/更新配置
│   │   ├── dao/BloodSugarDAO.java       # 数据访问层
│   │   ├── model/                       # 血糖记录、餐别区间、AGP 报告模型
│   │   ├── service/                     # 业务逻辑、导入导出、AI、报警、备份、更新检查
│   │   ├── ui/                          # 主界面 MainUI、日历、关于对话框
│   │   └── util/                        # 时段分类与正常区间、日志
│   ├── resources/                       # 图标、版本属性
│   └── scripts/                         # 分发包内的启动脚本
└── .vscode/                             # VS Code 运行配置
```

## 数据库

使用 H2 嵌入式数据库，首次运行自动创建（`DatabaseConfig`）。数据文件路径：

```
%USERPROFILE%\.bloodsugar\data.mv.db
```

表 `blood_sugar_records`：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INT AUTO_INCREMENT | 主键 |
| record_time | TIMESTAMP | 测量时间 |
| blood_sugar | DOUBLE | 血糖值 (mmol/L) |
| meal_time | TIMESTAMP | 用餐时间 |
| meal_period | VARCHAR(20) | 时段（空腹/餐后1h/2h/3h） |
| meal_type | VARCHAR(20) | 餐别描述（自动识别） |
| note | VARCHAR(200) | 备注 |
| insulin | DOUBLE | 胰岛素剂量 |
| carbs | DOUBLE | 碳水摄入 |
| activity | DOUBLE | 运动量 |
| weight | DOUBLE | 体重 |
| pulse | DOUBLE | 脉搏 |
| blood_pressure | VARCHAR(20) | 血压（如 "120/80"） |
| created_at | TIMESTAMP | 创建时间 |

表 `meal_times`：同一业务日（凌晨 4 点边界）同一餐别只保留一条，重复保存覆盖旧值。

老版本数据库升级时，会自动用 `ALTER TABLE ADD COLUMN` 补齐新增字段，**不丢已有数据**。

## 正常血糖范围

区间定义见 `util/PeriodClassifier.java`：

| 时段 | 正常下限 | 正常上限 |
|------|---------|---------|
| 空腹（含餐后超 3 小时） | 3.9 | 6.1 |
| 餐后1h | 3.9 | 8.9 |
| 餐后2h | 3.9 | 7.8 |
| 餐后3h | 3.9 | 7.8 |

单位均为 mmol/L。

## 使用说明

1. 启动后，在左侧「今日用餐时间」面板输入当天的早餐 / 午餐 / 晚餐 / 加餐时间，点击保存
2. 点击「+ 添加记录」，输入测量日期、时间和血糖值
3. 餐别会根据用餐时间自动计算（如早餐 7:00，9:35 测量 → "早餐后 2小时35分钟"）
4. 右侧趋势图直观展示血糖变化，绿色点在正常范围内，红色点偏高
5. 顶部「数据报告」分组内可生成总结 / AGP 报告 / 分享摘要 / 导出报告
6. 「关于」中可检查更新、修改数据存放位置、配置 AI 模型与报警阈值，以及**备份数据 / 从备份恢复**

## 许可

源码以 **MIT** 许可发布。

> 依赖 OpenPDF（AGPL-3.0）用于 PDF 导出。若后续需要闭源或商业分发，
> 请将 PDF 生成替换为其他许可更宽松的库（如 Apache PDFBox）。
