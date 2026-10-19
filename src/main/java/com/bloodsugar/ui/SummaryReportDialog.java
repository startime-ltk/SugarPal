package com.bloodsugar.ui;

import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.service.BloodSugarService;
import com.bloodsugar.util.PeriodClassifier;

import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * 血糖总结报告对话框：基础统计（均值 / 最高 / 最低 / 正常率）、
 * 分时段统计、近 90 天估算 HbA1c、近 30 天健康数据汇总，
 * 以及糖尿病 / 胰岛素抵抗两项自动风险评估。
 */
public final class SummaryReportDialog {

    private final Stage owner;
    private final List<BloodSugarRecord> records;
    private final BloodSugarService service;

    public SummaryReportDialog(Stage owner, List<BloodSugarRecord> records, BloodSugarService service) {
        this.owner = owner;
        this.records = records;
        this.service = service;
    }

    /** 打开对话框（模态） */
    public void show() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("血糖总结报告");
        alert.setHeaderText("统计数据与风险评估");

        TextArea textArea = new TextArea(buildReportText());
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setFont(Font.font("Microsoft YaHei", 13));
        textArea.setPrefSize(520, 440);
        textArea.setStyle("-fx-control-inner-background: #FDFEFB; -fx-background-radius: 14; "
                + "-fx-border-radius: 14; -fx-border-color: " + DialogSupport.COLOR_BORDER + ";");
        alert.getDialogPane().setContent(textArea);
        DialogSupport.styleDialogPane(alert.getDialogPane());
        alert.showAndWait();
    }

    // ---------- 报告文本组装 ----------

    private String buildReportText() {
        int total = records.size();

        // 基础统计
        DoubleSummaryStatistics stats = records.stream()
                .mapToDouble(BloodSugarRecord::getBloodSugar).summaryStatistics();
        long normalCount = records.stream()
                .filter(r -> PeriodClassifier.isNormal(r.getMealPeriod(), r.getBloodSugar())).count();
        int highCount = (int) (total - normalCount);

        // 按空腹/餐后分组
        List<BloodSugarRecord> fasting = groupByPeriod("空腹");
        List<BloodSugarRecord> post1h = groupByPeriod("餐后1h");
        List<BloodSugarRecord> post2h = groupByPeriod("餐后2h");
        List<BloodSugarRecord> post3h = groupByPeriod("餐后3h");

        // 糖尿病风险评估
        String diabetesRisk = assessDiabetesRisk(fasting, post2h, post1h);
        String insulinRisk = assessInsulinResistanceRisk(fasting, post1h, post2h);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("记录总数：%d 条\n", total));
        sb.append(String.format("测量时间范围：%s  ~  %s\n\n",
                records.get(0).getRecordTime().format(DialogFormats.CHART_FMT),
                records.get(records.size() - 1).getRecordTime().format(DialogFormats.CHART_FMT)));

        sb.append("── 血糖统计 ──\n");
        sb.append(String.format("  平均值：%.1f mmol/L\n", stats.getAverage()));
        sb.append(String.format("  最高值：%.1f mmol/L\n", stats.getMax()));
        sb.append(String.format("  最低值：%.1f mmol/L\n", stats.getMin()));
        sb.append(String.format("  正常率：%d/%d (%.0f%%)\n", normalCount, total, 100.0 * normalCount / total));
        sb.append(String.format("  偏高次数：%d 次\n\n", highCount));

        appendGroupStats(sb, "空腹血糖", fasting);
        appendGroupStats(sb, "餐后1h", post1h);
        appendGroupStats(sb, "餐后2h", post2h);
        appendGroupStats(sb, "餐后3h", post3h);

        appendHbA1cSection(sb);
        appendHealthSummarySection(sb);

        sb.append("── 风险评估 ──\n");
        sb.append(diabetesRisk).append("\n");
        sb.append(insulinRisk).append("\n\n");
        sb.append("⚠ 以上为程序自动分析，仅供参考，不能替代医生诊断。如有疑虑请及时就医。");
        return sb.toString();
    }

    private List<BloodSugarRecord> groupByPeriod(String period) {
        return records.stream()
                .filter(r -> period.equals(r.getMealPeriod()))
                .sorted(Comparator.comparing(BloodSugarRecord::getRecordTime))
                .collect(Collectors.toList());
    }

    private void appendGroupStats(StringBuilder sb, String title, List<BloodSugarRecord> group) {
        if (group.isEmpty()) {
            return;
        }
        DoubleSummaryStatistics s = group.stream().mapToDouble(BloodSugarRecord::getBloodSugar).summaryStatistics();
        sb.append(String.format("── %s（%d 次）──\n", title, group.size()));
        sb.append(String.format("  平均：%.1f  最高：%.1f  最低：%.1f\n\n", s.getAverage(), s.getMax(), s.getMin()));
    }

    private void appendHbA1cSection(StringBuilder sb) {
        sb.append("── 估算 HbA1c（近 90 天）──\n");
        try {
            Double hba1c = service.getHbA1cEstimate();
            Double recentAvg = service.getRecentAvgBloodSugar();
            long recentCount = service.getRecentRecordCount();
            if (hba1c == null || recentAvg == null || recentCount == 0) {
                sb.append("  近 90 天暂无数据，无法估算。\n\n");
                return;
            }
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
        } catch (SQLException ex) {
            sb.append("  估算 HbA1c 失败：" + ex.getMessage() + "\n\n");
        }
    }

    private void appendHealthSummarySection(StringBuilder sb) {
        sb.append("── 健康数据汇总（近 30 天）──\n");
        try {
            sb.append(buildHealthSummaryText(service.getAllRecords()));
        } catch (SQLException ex) {
            sb.append("  读取健康数据失败：" + ex.getMessage() + "\n\n");
        }
    }

    /** 近 30 天健康数据汇总文本：均值 / 最新值 / 趋势，各维度有数据才展示 */
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
        if (insulinAvg > 0) {
            sb.append(String.format("  胰岛素平均：%.1f U/次\n", insulinAvg));
        }
        double carbsAvg = avgPositive(r30, BloodSugarRecord::getCarbs);
        if (carbsAvg > 0) {
            sb.append(String.format("  碳水平均：%.0f g/次\n", carbsAvg));
        }
        double activityAvg = avgPositive(r30, BloodSugarRecord::getActivity);
        if (activityAvg > 0) {
            sb.append(String.format("  运动平均：%.0f 分钟/次\n", activityAvg));
        }
        double pulseAvg = avgPositive(r30, BloodSugarRecord::getPulse);
        if (pulseAvg > 0) {
            sb.append(String.format("  脉搏平均：%.0f 次/分\n", pulseAvg));
        }

        BloodSugarRecord last = r30.get(r30.size() - 1);
        if (last.getWeight() > 0) {
            sb.append(String.format("  最新体重：%.1f kg\n", last.getWeight()));
        }
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
    private double avgPositive(List<BloodSugarRecord> records, ToDoubleFunction<BloodSugarRecord> fn) {
        return records.stream().mapToDouble(fn).filter(v -> v > 0).average().orElse(0);
    }

    // ---------- 风险评估 ----------

    private String assessDiabetesRisk(List<BloodSugarRecord> fasting, List<BloodSugarRecord> post2h,
                                      List<BloodSugarRecord> post1h) {
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
        boolean randomHigh = records.stream().anyMatch(r -> r.getBloodSugar() >= 11.1
                && !"空腹".equals(r.getMealPeriod()));
        if (randomHigh) {
            risk.append("  ⚠ 存在随机血糖≥11.1 的记录（非空腹），也需警惕。\n");
        }

        return "【糖尿病风险评估】\n" + risk;
    }

    private String assessInsulinResistanceRisk(List<BloodSugarRecord> fasting, List<BloodSugarRecord> post1h,
                                               List<BloodSugarRecord> post2h) {
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
        double maxSugar = records.stream().mapToDouble(BloodSugarRecord::getBloodSugar).max().orElse(0);
        double minSugar = records.stream().mapToDouble(BloodSugarRecord::getBloodSugar).min().orElse(0);
        double range = maxSugar - minSugar;
        boolean wideFluctuation = range > 5.0;
        risk.append(String.format("  血糖波动幅度：%.1f (%.1f ~ %.1f)%s\n", range, minSugar, maxSugar,
                wideFluctuation ? " ← 波动偏大" : ""));

        // 综合判断
        int score = 0;
        if (fastingNormal && post1hHigh) {
            score += 2; // 空腹正常+餐后飙升
        }
        if (wideFluctuation) {
            score += 1;
        }
        double avgBloodSugar = records.stream().mapToDouble(BloodSugarRecord::getBloodSugar).average().orElse(0);
        if (avgBloodSugar > 7.0) {
            score += 1;
        }

        if (score >= 2) {
            risk.append("  【高风险】空腹正常但餐后明显升高 + 波动大，符合胰岛素抵抗典型特征。建议就医查空腹胰岛素、HOMA-IR 指数。\n");
        } else if (score == 1) {
            risk.append("  【中风险】部分指标提示可能存在胰岛素抵抗倾向，建议关注并定期监测。\n");
        } else {
            risk.append("  【低风险】当前数据未显示明显的胰岛素抵抗特征。\n");
        }

        return "【胰岛素抵抗评估】\n" + risk;
    }
}
