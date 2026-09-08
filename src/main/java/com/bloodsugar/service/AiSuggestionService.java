package com.bloodsugar.service;

import com.bloodsugar.config.AiConfig;
import com.bloodsugar.model.BloodSugarRecord;
import com.bloodsugar.util.PeriodClassifier;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 智能建议服务：把近期血糖记录整理成文本，调用 OpenAI 兼容接口生成控糖建议。
 * 使用 JDK 内置 HttpClient 发请求，JSON 用轻量库 Gson 构造与解析。
 * 智谱 GLM 与 DeepSeek 都是 OpenAI 兼容格式，只差 baseUrl / model / apiKey，逻辑共用。
 */
public class AiSuggestionService {

    /** 业务异常，message 都是可直接展示给用户的友好中文 */
    public static class AiException extends Exception {
        public AiException(String message) {
            super(message);
        }

        public AiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_RECORDS = 30;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /**
     * 生成 AI 控糖建议。
     *
     * @param records  全部血糖记录（内部取最近 30 条）
     * @param config   配置对象
     * @param provider 当前模型 provider id（AiConfig.PROVIDER_GLM / DEEPSEEK / QWEN / KIMI / CUSTOM 等）
     * @return 模型返回的建议文本
     */
    public String generateSuggestion(List<BloodSugarRecord> records, AiConfig config, String provider)
            throws AiException {
        String apiKey = config.getApiKey(provider);
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiException("尚未配置 " + AiConfig.displayName(provider)
                    + " 的 API Key，请先点击「设置 API Key」填写");
        }
        String baseUrl = config.getBaseUrl(provider);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AiException("模型接口地址未配置，请在设置中填写");
        }
        String model = config.getModel(provider);
        if (model == null || model.isBlank()) {
            throw new AiException("模型名称未配置，请在设置中填写");
        }

        String dataText = buildDataText(records);
        if (dataText.isEmpty()) {
            throw new AiException("暂无可用的血糖记录，请先添加记录再生成建议");
        }

        String requestBody = buildRequestBody(model, dataText);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new AiException("接口地址格式不正确，请在设置中检查", e);
        }

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new AiException("无法连接模型服务，请检查网络或接口地址", e);
        } catch (HttpTimeoutException e) {
            throw new AiException("请求超时，请检查网络后稍后重试", e);
        } catch (IOException e) {
            throw new AiException("网络请求失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("请求被中断，请重试", e);
        }

        if (response.statusCode() >= 400) {
            throw new AiException("模型接口返回错误（HTTP " + response.statusCode() + "）："
                    + extractError(response.body()));
        }

        String content = parseContent(response.body());
        if (content == null || content.isBlank()) {
            throw new AiException("模型返回内容为空，请稍后重试");
        }
        return content.trim();
    }

    /** 把最近 N 条血糖记录 + 简单统计整理成给模型看的文本 */
    private String buildDataText(List<BloodSugarRecord> records) {
        if (records == null || records.isEmpty()) {
            return "";
        }
        // 记录本身按时间倒序，取最近的再转升序，方便模型按时间线看
        List<BloodSugarRecord> recent = records.stream()
                .filter(r -> r.getRecordTime() != null)
                .sorted(Comparator.comparing(BloodSugarRecord::getRecordTime).reversed())
                .limit(MAX_RECORDS)
                .sorted(Comparator.comparing(BloodSugarRecord::getRecordTime))
                .collect(Collectors.toList());

        double avg = recent.stream().mapToDouble(BloodSugarRecord::getBloodSugar).average().orElse(0);
        double max = recent.stream().mapToDouble(BloodSugarRecord::getBloodSugar).max().orElse(0);
        double min = recent.stream().mapToDouble(BloodSugarRecord::getBloodSugar).min().orElse(0);
        long normalCount = recent.stream()
                .filter(r -> PeriodClassifier.isNormal(r.getMealPeriod(), r.getBloodSugar())).count();

        double fastingAvg = recent.stream()
                .filter(r -> "空腹".equals(r.getMealPeriod()))
                .mapToDouble(BloodSugarRecord::getBloodSugar).average().orElse(Double.NaN);
        double post2hAvg = recent.stream()
                .filter(r -> "餐后2h".equals(r.getMealPeriod()))
                .mapToDouble(BloodSugarRecord::getBloodSugar).average().orElse(Double.NaN);

        StringBuilder sb = new StringBuilder();
        sb.append("最近 ").append(recent.size()).append(" 条血糖记录（正常参考区间：空腹 3.9-6.1，餐后1h 3.9-8.9，餐后2h 3.9-7.8 mmol/L）：\n");
        for (BloodSugarRecord r : recent) {
            sb.append(String.format("- %s | %.1f mmol/L | 时段:%s | 餐别:%s",
                    r.getRecordTime().format(DT_FMT),
                    r.getBloodSugar(),
                    r.getMealPeriod() != null ? r.getMealPeriod() : "未知",
                    r.getMealType() != null ? r.getMealType() : "无"));
            if (r.getNote() != null && !r.getNote().isBlank()) {
                sb.append(" | 备注:").append(r.getNote());
            }
            sb.append('\n');
        }
        sb.append(String.format("\n简单统计：共 %d 条，平均值 %.1f，最高 %.1f，最低 %.1f，正常率 %d/%d (%.0f%%)",
                recent.size(), avg, max, min, normalCount, recent.size(), 100.0 * normalCount / recent.size()));
        if (!Double.isNaN(fastingAvg)) {
            sb.append(String.format("；空腹平均 %.1f", fastingAvg));
        }
        if (!Double.isNaN(post2hAvg)) {
            sb.append(String.format("；餐后2h平均 %.1f", post2hAvg));
        }
        sb.append("。");

        // ---- 多维健康数据上下文（近 30 天，仅统计有值的维度）----
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        List<BloodSugarRecord> healthRecent = records.stream()
                .filter(r -> r.getRecordTime() != null && !r.getRecordTime().isBefore(cutoff))
                .collect(Collectors.toList());

        double insulinAvg = avgPositive(healthRecent, BloodSugarRecord::getInsulin);
        double carbsAvg = avgPositive(healthRecent, BloodSugarRecord::getCarbs);
        double activityAvg = avgPositive(healthRecent, BloodSugarRecord::getActivity);
        double pulseAvg = avgPositive(healthRecent, BloodSugarRecord::getPulse);
        BloodSugarRecord lastWithWeight = healthRecent.stream()
                .filter(r -> r.getWeight() > 0)
                .max(Comparator.comparing(BloodSugarRecord::getRecordTime)).orElse(null);
        BloodSugarRecord lastWithBp = healthRecent.stream()
                .filter(r -> r.getBloodPressure() != null && !r.getBloodPressure().isBlank())
                .max(Comparator.comparing(BloodSugarRecord::getRecordTime)).orElse(null);

        List<String> healthParts = new java.util.ArrayList<>();
        if (insulinAvg > 0) healthParts.add(String.format("胰岛素平均 %.1f U/次", insulinAvg));
        if (carbsAvg > 0) healthParts.add(String.format("碳水平均 %.0f g/次", carbsAvg));
        if (activityAvg > 0) healthParts.add(String.format("运动平均 %.0f 分钟/次", activityAvg));
        if (pulseAvg > 0) healthParts.add(String.format("脉搏平均 %.0f 次/分", pulseAvg));
        if (lastWithWeight != null) healthParts.add(String.format("最新体重 %.1f kg", lastWithWeight.getWeight()));
        if (lastWithBp != null) healthParts.add("最新血压 " + lastWithBp.getBloodPressure());
        if (!healthParts.isEmpty()) {
            sb.append("\n\n近 30 天健康数据：").append(String.join("，", healthParts)).append("。");
        }
        return sb.toString();
    }

    private double avgPositive(List<BloodSugarRecord> records, java.util.function.ToDoubleFunction<BloodSugarRecord> fn) {
        return records.stream().mapToDouble(fn).filter(v -> v > 0).average().orElse(0);
    }

    /** 用 Gson 构造 OpenAI 兼容请求体 */
    private String buildRequestBody(String model, String dataText) {
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content",
                "你是专业的糖尿病管理助手。请根据用户提供的血糖记录，给出个性化控糖建议。"
                        + "要求：分条列出，语气温和务实，先总结趋势和问题，再给可执行的饮食、运动、监测建议，"
                        + "最后提醒建议仅供参考、不能替代医生诊断。控制在 400 字以内。");

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", dataText + "\n\n请给出控糖建议。");

        JsonArray messages = new JsonArray();
        messages.add(system);
        messages.add(user);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("temperature", 0.7);
        return new Gson().toJson(body);
    }

    /** 解析 OpenAI 兼容响应，取 choices[0].message.content */
    private String parseContent(String body) throws AiException {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (message != null && message.has("content")) {
                    return message.get("content").getAsString();
                }
            }
            return null;
        } catch (Exception e) {
            throw new AiException("无法解析模型返回内容，请稍后重试", e);
        }
    }

    /** 从错误响应里尽量抠出 error.message 给用户看 */
    private String extractError(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("error")) {
                JsonObject err = root.getAsJsonObject("error");
                if (err.has("message")) {
                    return err.get("message").getAsString();
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，直接截断显示原文
        }
        if (body == null || body.isBlank()) {
            return "无详细信息";
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}
