package com.bloodsugar.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * PC 端一键更新服务（参考安卓端 UpdateChecker/UpdateDownloader 逻辑）。
 *
 * <p>检查更新：GET 更新源（默认 GitHub Releases latest API），解析 tag_name 与
 * assets 中 .exe 安装包的 browser_download_url；支持国内加速镜像前缀
 * （默认 https://ghproxy.net/）——下载时先走镜像，失败回退直连。
 * 安装：静默运行安装程序（/VERYSILENT /SUPPRESSMSGBOXES /NORESTART）。
 */
public class UpdateService {

    /** 更新检查结果 */
    public static class UpdateInfo {
        public final String latestVersion;   // 例如 1.3.1
        public final String downloadUrl;     // 安装包直链
        public final String fileName;        // 安装包文件名
        public final String notes;           // 发布说明（可为空）

        public UpdateInfo(String latestVersion, String downloadUrl, String fileName, String notes) {
            this.latestVersion = latestVersion;
            this.downloadUrl = downloadUrl;
            this.fileName = fileName;
            this.notes = notes;
        }
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /**
     * 检查更新源，返回最新版信息。
     *
     * @param sourceUrl 更新源地址（GitHub Releases latest API）
     * @param currentVersion 当前应用版本号（用于比较）
     * @return 有新版时返回 UpdateInfo；已是最新时返回 null
     * @throws UpdateException 更新源不可达或 JSON 格式不匹配等具体原因
     */
    public UpdateInfo checkForUpdate(String sourceUrl, String currentVersion) throws UpdateException {
        String body = httpGet(sourceUrl);
        if (body == null || body.isBlank()) {
            throw new UpdateException("更新源返回内容为空");
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            throw new UpdateException("更新源 JSON 格式不匹配（无法解析）");
        }
        if (root.has("message") && root.has("documentation_url")) {
            throw new UpdateException("更新源返回错误：" + root.get("message").getAsString());
        }

        JsonElement tagEl = root.get("tag_name");
        if (tagEl == null || tagEl.isJsonNull()) {
            throw new UpdateException("更新源 JSON 缺少 tag_name 字段");
        }
        String tag = tagEl.getAsString().trim();
        String version = normalizeVersion(tag);
        if (version.isEmpty()) {
            throw new UpdateException("更新源版本号格式不合法：" + tag);
        }

        // 从 assets 中找 .exe 安装包
        String downloadUrl = null;
        String fileName = null;
        JsonElement assetsEl = root.get("assets");
        if (assetsEl != null && assetsEl.isJsonArray()) {
            JsonArray assets = assetsEl.getAsJsonArray();
            for (JsonElement el : assets) {
                if (el.isJsonNull() || !el.isJsonObject()) {
                    continue;
                }
                JsonObject asset = el.getAsJsonObject();
                JsonElement nameEl = asset.get("name");
                if (nameEl == null || nameEl.isJsonNull()) {
                    continue;
                }
                String name = nameEl.getAsString();
                if (name.endsWith(".exe") && (name.contains("安装包") || name.contains("SugarPal"))) {
                    JsonElement urlEl = asset.get("browser_download_url");
                    if (urlEl != null && !urlEl.isJsonNull()) {
                        downloadUrl = urlEl.getAsString();
                        fileName = name;
                        break;
                    }
                }
            }
        }
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new UpdateException("更新源 JSON 中未找到 .exe 安装包（browser_download_url）");
        }

        String notes = null;
        JsonElement bodyEl = root.get("body");
        if (bodyEl != null && !bodyEl.isJsonNull()) {
            String b = bodyEl.getAsString().trim();
            if (!b.isEmpty()) {
                notes = b;
            }
        }

        // 与当前版本比较
        if (compareVersions(version, normalizeVersion(currentVersion)) <= 0) {
            return null; // 已是最新
        }
        return new UpdateInfo(version, downloadUrl, fileName, notes);
    }

    /**
     * 下载安装包到指定目录。优先走镜像前缀，失败回退直连。
     *
     * @param update 更新信息
     * @param targetDir 保存目录（自动创建）
     * @param mirrorPrefix 镜像前缀（如 https://ghproxy.net/；空串则直连）
     * @return 下载到本地的安装包路径
     */
    public Path download(UpdateInfo update, Path targetDir, String mirrorPrefix) throws UpdateException {
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            throw new UpdateException("无法创建下载目录：" + e.getMessage(), e);
        }
        String fileName = (update.fileName == null || update.fileName.isBlank())
                ? "sugarpal-installer.exe"
                : sanitizeFileName(update.fileName);
        Path target = targetDir.resolve(fileName);

        List<String> attempts = new ArrayList<>();
        String mirror = mirrorPrefix == null ? "" : mirrorPrefix.trim();
        if (!mirror.isEmpty()) {
            if (!mirror.endsWith("/")) {
                mirror = mirror + "/";
            }
            attempts.add(mirror + update.downloadUrl);
        }
        attempts.add(update.downloadUrl);

        IOException lastIo = null;
        for (String url : attempts) {
            try {
                downloadTo(url, target);
                return target;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpdateException("下载被中断，请重试", e);
            } catch (IOException e) {
                lastIo = e;
                // 镜像失败则尝试下一个（直连）；HttpTimeoutException / ConnectException 均为 IOException 子类
            }
        }
        throw new UpdateException("下载安装包失败（镜像与直连均不可达）："
                + (lastIo == null ? "未知错误" : lastIo.getMessage()), lastIo);
    }

    /**
     * 静默运行安装程序并等待其启动完成。
     *
     * @param installer 安装包路径
     */
    public void runInstaller(Path installer) throws UpdateException {
        List<String> cmd = new ArrayList<>();
        cmd.add(installer.toString());
        cmd.add("/VERYSILENT");
        cmd.add("/SUPPRESSMSGBOXES");
        cmd.add("/NORESTART");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.start();
        } catch (IOException e) {
            throw new UpdateException("无法启动安装程序：" + e.getMessage(), e);
        }
    }

    private void downloadTo(String url, Path target) throws IOException, InterruptedException,
            HttpTimeoutException, ConnectException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "SugarPal-Desktop-Updater")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw e;
        } catch (ConnectException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        try (InputStream in = response.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String httpGet(String url) throws UpdateException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "SugarPal-Desktop-Updater")
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new UpdateException("更新源地址格式不正确，请在关于对话框中检查", e);
        }

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new UpdateException("无法连接更新源，请检查网络或更新源地址", e);
        } catch (HttpTimeoutException e) {
            throw new UpdateException("请求更新源超时，请检查网络后稍后重试", e);
        } catch (IOException e) {
            throw new UpdateException("请求更新源失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("请求被中断，请重试", e);
        }

        if (response.statusCode() != 200) {
            throw new UpdateException("更新源返回 HTTP " + response.statusCode() + "（"
                    + (response.body() == null ? "" : response.body().trim()) + "）");
        }
        return response.body();
    }

    /** 将 tag（如 v1.3.1 / 1.3.1）规范化为 1.3.1；无法识别返回空串 */
    static String normalizeVersion(String tag) {
        if (tag == null) {
            return "";
        }
        String v = tag.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        if (v.isEmpty() || !v.matches("[0-9]+(\\.[0-9]+)*")) {
            return "";
        }
        return v;
    }

    /** 比较版本号：a > b 返回正数，a == b 返回 0；段数不足补 0 */
    static int compareVersions(String a, String b) {
        String[] pa = normalizeVersion(a).isEmpty() ? new String[]{"0"} : normalizeVersion(a).split("\\.");
        String[] pb = normalizeVersion(b).isEmpty() ? new String[]{"0"} : normalizeVersion(b).split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = i < pa.length ? Integer.parseInt(pa[i]) : 0;
            int nb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        return 0;
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** 更新服务异常：携带面向用户的具体原因 */
    public static class UpdateException extends Exception {
        public UpdateException(String message) {
            super(message);
        }

        public UpdateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
