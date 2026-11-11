/*
 * Copyright (c) 2026 startime-ltk
 * SPDX-License-Identifier: MIT
 */
package com.bloodsugar.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PC 端一键更新服务（参考安卓端 UpdateChecker/UpdateDownloader 逻辑）。
 *
 * <p>检查更新：GET 更新源（默认 GitHub Releases latest API），解析 tag_name 与
 * assets 中 .exe 安装包的 browser_download_url。
 *
 * <p>下载采用三通道自动回退，任一成功即止：
 * <ol>
 *   <li>资产 API 通道（{@code api.github.com/.../releases/assets/<id>} +
 *       {@code Accept: application/octet-stream}）——不经过常被阻断的 {@code github.com}；</li>
 *   <li>国内加速镜像前缀 + 直链；</li>
 *   <li>直链（{@code browser_download_url}，302 到 release-assets，需跟随重定向）。</li>
 * </ol>
 * 下载过程支持断点续传（HTTP Range），中断后从断点继续而非从头再来；
 * 完成后按服务器给出的 content-length 校验完整性，避免把半截文件当安装包启动。
 *
 * <p>安装：静默运行安装程序（/VERYSILENT /SUPPRESSMSGBOXES /NORESTART）。
 */
public class UpdateService {

    /** 更新检查结果 */
    public static class UpdateInfo {
        public final String latestVersion;   // 例如 1.3.1
        public final String downloadUrl;     // 安装包直链（browser_download_url）
        public final String fileName;        // 安装包文件名
        public final String notes;           // 发布说明（可为空）
        /**
         * 资产的 API 地址（assets[].url）。带 Accept: application/octet-stream 请求它，
         * GitHub 会 302 到 release-assets.githubusercontent.com 的签名地址。
         * 这条通道不经过被阻断的 github.com，是「下载不通」时的关键回退路径。
         */
        public final String assetApiUrl;

        public UpdateInfo(String latestVersion, String downloadUrl, String fileName, String notes) {
            this(latestVersion, downloadUrl, fileName, notes, null);
        }

        public UpdateInfo(String latestVersion, String downloadUrl, String fileName, String notes,
                          String assetApiUrl) {
            this.latestVersion = latestVersion;
            this.downloadUrl = downloadUrl;
            this.fileName = fileName;
            this.notes = notes;
            this.assetApiUrl = assetApiUrl;
        }
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 解析资产真实地址时的超时。这一步只发一次 HEAD/GET 拿 302，不应久等。
     */
    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 真实下载用的超时。安装包约 50 MB，国内到 release-assets 的带宽可能只有几十 KB/s，
     * 因此单次请求给足 30 分钟，并配合断点续传（Range）在中断后续传而不是从头再来。
     */
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(30);

    /**
     * 必须显式跟随重定向：GitHub 的下载地址会先 302 到 release-assets.githubusercontent.com，
     * 而它的一级域名 github.com 在国内常被阻断。HttpClient 默认 NEVER 不跟随，
     * 会把 302 当失败直接抛「HTTP 302」，导致明明能下的包也下不下来。
     */
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    /**
     * 绕过系统代理的直连客户端。用于：当系统代理（如 Clash 等）对 HTTPS 做 TLS 中间人拦截、
     * 而自带 Java 不信任其证书导致 handshake_failure 时，自动改用直连重试。
     * 同样必须跟随重定向。
     */
    private final HttpClient directClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .proxy(ProxySelector.of(null))
            .followRedirects(HttpClient.Redirect.ALWAYS)
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
        String assetApiUrl = null;
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
                        // 同时记下 API 资产地址：静态清单里通常没有这个字段，
                        // 只有走 Releases API 时才有，有则多一条不经过 github.com 的下载通道
                        JsonElement apiEl = asset.get("url");
                        if (apiEl != null && !apiEl.isJsonNull()) {
                            assetApiUrl = apiEl.getAsString();
                        }
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
        return new UpdateInfo(version, downloadUrl, fileName, notes, assetApiUrl);
    }

    /**
     * 备用更新源：不依赖单一域名的可达性，按「实测可达性」排序。
     *
     * <p>顺序即尝试顺序，主源失败后再依次回退到这里。
     *
     * <p>为什么不只有静态清单：{@code raw.githubusercontent.com} 在国内经常整段不可达，
     * {@code cdn.jsdelivr.net} 也可能被 TLS 阻断，而 {@code api.github.com} 通常是通的
     * （它和网页/下载所在的 {@code github.com} 是不同域名、不同线路）。
     * 因此把 Releases API 放在首位，静态清单作为兜底。
     */
    public static final List<String> FALLBACK_SOURCES = List.of(
            "https://api.github.com/repos/startime-ltk/SugarPal/releases/latest",
            "https://cdn.jsdelivr.net/gh/startime-ltk/SugarPal@master/update/latest.json",
            "https://raw.githubusercontent.com/startime-ltk/SugarPal/master/update/latest.json"
    );

    /**
     * 组合候选更新源：用户配置的源优先，随后补齐备用源（去重）。
     */
    public static List<String> candidateSources(String userSource) {
        List<String> list = new ArrayList<>();
        if (userSource != null && !userSource.isBlank()) {
            list.add(userSource.trim());
        }
        for (String s : FALLBACK_SOURCES) {
            if (!list.contains(s)) {
                list.add(s);
            }
        }
        return list;
    }

    /**
     * 多源检查更新：按候选顺序逐个尝试，任一源成功即返回结果；
     * 全部失败时抛出携带各源失败原因的异常。
     */
    public UpdateInfo checkForUpdate(List<String> sources, String currentVersion) throws UpdateException {
        StringBuilder errors = new StringBuilder();
        for (String src : sources) {
            try {
                UpdateInfo info = checkForUpdate(src, currentVersion);
                return info;
            } catch (UpdateException e) {
                errors.append("• ").append(src).append(" → ").append(e.getMessage()).append("\n");
            }
        }
        throw new UpdateException("所有更新源均暂不可用，请稍后重试：\n" + errors);
    }

    /**
     * 无进展判定阈值：连续这么多秒没有读到任何新字节，就认为这条连接已卡死。
     *
     * <p>必须自己做这个判定 —— {@code HttpRequest.timeout()} 只覆盖到响应头返回为止，
     * body 边读边传阶段一旦对端把连接晾住，{@code InputStream.read()} 会永久阻塞，
     * 请求级超时不会触发。实测 GitHub 的 release-assets 线路会出现「连上了、下了一点、
     * 然后彻底不动」的情况，没有这层判定就会永久挂起（界面一直停在「正在下载」）。
     */
    private static final long STALL_SECONDS = 45;

    /** 单条通道内允许的无进展重试次数（每次从断点续传，不是从头开始） */
    private static final int MAX_STALL_RETRIES = 6;

    /** 期望的安装包总长度（响应头 content-length）；-1 表示未知。每次 download() 前重置 */
    private long expectedSizeCache = -1L;

    /**
     * 下载安装包到指定目录（单镜像重载，兼容旧调用）。
     *
     * @param update 更新信息
     * @param targetDir 保存目录（自动创建）
     * @param mirrorPrefix 单个镜像前缀；空串则跳过镜像
     * @return 下载到本地的安装包路径
     */
    public Path download(UpdateInfo update, Path targetDir, String mirrorPrefix) throws UpdateException {
        return download(update, targetDir,
                (mirrorPrefix == null || mirrorPrefix.isBlank())
                        ? List.of()
                        : List.of(mirrorPrefix));
    }

    /**
     * 下载安装包到指定目录。
     *
     * <p>按顺序尝试多条通道，任一成功即返回：
     * <ol>
     *   <li><b>API 资产通道</b>：带 {@code Accept: application/octet-stream} 请求
     *       {@code assets[].url}，由 GitHub 302 到 {@code release-assets.githubusercontent.com}。
     *       这条链路只用 {@code api.github.com}，不经过常被阻断的 {@code github.com}。</li>
     *   <li><b>各镜像前缀</b>：如 {@code https://ghproxy.net/} + 直链，按列表顺序逐个试。</li>
     *   <li><b>直连</b>：{@code browser_download_url}（会 302 到 release-assets，需跟随重定向）。</li>
     * </ol>
     *
     * <p>下载支持断点续传：中途中断后用 {@code Range} 从已下载字节处续传，
     * 不会每次都从头再来（安装包约 50 MB，国内带宽下从头重试几乎不可能成功）。
     *
     * @param update 更新信息
     * @param targetDir 保存目录（自动创建）
     * @param mirrorPrefixes 镜像前缀列表；空列表表示跳过镜像
     * @return 下载到本地的安装包路径
     */
    public Path download(UpdateInfo update, Path targetDir, List<String> mirrorPrefixes)
            throws UpdateException {
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            throw new UpdateException("无法创建下载目录：" + e.getMessage(), e);
        }
        String fileName = (update.fileName == null || update.fileName.isBlank())
                ? "sugarpal-installer.exe"
                : sanitizeFileName(update.fileName);
        Path target = targetDir.resolve(fileName);
        Path partial = targetDir.resolve(fileName + ".part");

        // 组装候选地址：API 资产通道优先，其次各镜像，最后直链
        Set<String> attempts = new LinkedHashSet<>();
        String assetApi = update.assetApiUrl;
        if (assetApi == null || assetApi.isBlank()) {
            // 静态清单（update/latest.json）没有 assets[].url，这里按直链反推资产 API 地址：
            // .../releases/download/<tag>/<file>  →  需查 API 才能拿到 asset id，
            // 故退化为「用 tag 查 Releases API」再取 url（失败则仅走下面两条通道）
            assetApi = discoverAssetApiUrl(update);
        }
        if (assetApi != null && !assetApi.isBlank()) {
            attempts.add(assetApi);
        }
        if (mirrorPrefixes != null) {
            for (String m0 : mirrorPrefixes) {
                if (m0 == null || m0.isBlank()) {
                    continue;
                }
                String m = m0.trim();
                if (!m.endsWith("/")) {
                    m = m + "/";
                }
                attempts.add(m + update.downloadUrl);
            }
        }
        if (update.downloadUrl != null && !update.downloadUrl.isBlank()) {
            attempts.add(update.downloadUrl);
        }
        if (attempts.isEmpty()) {
            throw new UpdateException("没有可用的下载地址，请检查更新源配置");
        }

        List<String> errors = new ArrayList<>();
        for (String url : attempts) {
            try {
                downloadWithResume(url, partial, isAssetApiUrl(url));
                // 校验完整性后转正，避免把半截文件当安装包启动
                long expected = resolveExpectedSize(url);
                long actual = Files.size(partial);
                if (expected > 0 && actual != expected) {
                    throw new IOException("文件不完整（已下载 " + actual + " 字节，应为 " + expected + " 字节）");
                }
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpdateException("下载被中断，请重试", e);
            } catch (IOException e) {
                errors.add(shortHost(url) + "：" + e.getMessage());
                // 换下一条通道；.part 保留，供后续尝试续传
            }
        }
        throw new UpdateException("下载安装包失败（已尝试 " + attempts.size() + " 条通道均不可达）：\n"
                + String.join("\n", errors));
    }

    /** 是否为 GitHub 资产 API 地址（需要 application/octet-stream 才会 302 到真实文件） */
    private static boolean isAssetApiUrl(String url) {
        return url != null && url.contains("api.github.com/") && url.contains("/releases/assets/");
    }

    private static String shortHost(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 取该 URL 对应的文件总长度（用于完整性校验）。
     * 取不到返回 -1，此时不阻断下载，只跳过校验。
     */
    private long resolveExpectedSize(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(RESOLVE_TIMEOUT)
                    .header("User-Agent", "SugarPal-Desktop-Updater")
                    .header("Accept", isAssetApiUrl(url) ? "application/octet-stream" : "*/*")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> resp = sendQuietly(req, HttpResponse.BodyHandlers.discarding());
            if (resp != null && resp.statusCode() == 200) {
                return resp.headers().firstValueAsLong("content-length").orElse(-1L);
            }
        } catch (Exception ignored) {
            // 某些 CDN 不支持 HEAD，或签名地址拒绝 HEAD —— 不阻断下载
        }
        return -1L;
    }

    /** 无异常抛出的发送：失败返回 null */
    private <T> HttpResponse<T> sendQuietly(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return client.send(request, handler);
        } catch (Exception e) {
            try {
                return directClient.send(request, handler);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /**
     * 带断点续传 + 无进展重试的下载。
     *
     * <p>每轮调 {@link #downloadOnce}；若判定卡死（{@link StallException}）且 .part 变大过，
     * 就从新断点续传再试，最多 {@link #MAX_STALL_RETRIES} 次；完全没进展才判定该通道失败。
     *
     * @param url 下载地址
     * @param partial 临时文件（.part）
     * @param assetApi 是否走资产 API 通道（需 octet-stream 才会 302 到真实文件）
     */
    private void downloadWithResume(String url, Path partial, boolean assetApi)
            throws IOException, InterruptedException {
        expectedSizeCache = -1L;
        int stalled = 0;
        long previous = -1L;
        while (true) {
            long before = Files.exists(partial) ? Files.size(partial) : 0L;
            boolean eof = false;
            try {
                eof = downloadOnce(url, partial, assetApi);
            } catch (StallException e) {
                // 卡死：保留 .part，下一轮用 Range 续传
            }

            long after = Files.exists(partial) ? Files.size(partial) : 0L;

            // 已知总长度时，达到即完成
            if (expectedSizeCache > 0 && after >= expectedSizeCache) {
                return;
            }
            // 未知总长度时，读到 EOF 即完成
            if (eof && expectedSizeCache <= 0) {
                return;
            }
            // 读到 EOF 但长度不足，说明服务端提前收尾，需要续传补齐
            if (eof && expectedSizeCache > 0 && after < expectedSizeCache) {
                // 继续循环续传
            }

            boolean progressed = after > before && after != previous;
            if (!progressed) {
                stalled++;
                if (stalled > MAX_STALL_RETRIES) {
                    throw new IOException("多次重试仍无进展（已下载 " + after + " 字节）");
                }
                Thread.sleep(Math.min(1500L * stalled, 6000L));
            } else {
                stalled = 0;
            }
            previous = after;
        }
    }

    /** 卡死信号：区分「对端晾住」与普通 IO 错误 */
    private static class StallException extends IOException {
        StallException(String msg) {
            super(msg);
        }
    }

    /**
     * 发起一次请求，读到 EOF 或判定卡死为止。
     *
     * @return true 表示读到了 EOF（这一轮把服务端给的数据读完了）
     */
    private boolean downloadOnce(String url, Path partial, boolean assetApi)
            throws IOException, InterruptedException {
        long existing = Files.exists(partial) ? Files.size(partial) : 0L;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", "SugarPal-Desktop-Updater")
                .header("Accept", assetApi ? "application/octet-stream" : "*/*")
                .GET();
        if (existing > 0) {
            builder.header("Range", "bytes=" + existing + "-");
        }
        HttpRequest request = builder.build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (SSLHandshakeException e) {
            // 系统代理 TLS 中间人拦截：绕过代理直连重试
            response = directClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }

        int code = response.statusCode();
        // 206 = 续传成功；200 = 服务器不支持 Range，从头来
        if (code != 200 && code != 206) {
            // 关掉 body 防止连接泄漏
            try (InputStream in = response.body()) {
                // ignore
            } catch (Exception ignored) {
                // ignore
            }
            throw new IOException("HTTP " + code);
        }

        boolean append = (code == 206 && existing > 0);
        long startOffset = append ? existing : 0L;

        // 记录总长度（200 时 content-length 即完整大小；206 时是剩余大小，需加上已有）
        if (expectedSizeCache <= 0) {
            long cl = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (cl > 0) {
                expectedSizeCache = (code == 206 && existing > 0) ? existing + cl : cl;
            }
        }

        // 读线程搬字节，主线程按「最后成功读到数据的时间」判定是否卡死。
        //
        // 关键点：HttpRequest.timeout() 只覆盖到响应头返回为止；body 阶段对端把连接晾住时，
        // HttpResponseInputStream.read() 会一直阻塞在内部队列上，请求超时不会触发。
        // 因此必须自己计时，并在判定卡死后立即关流，让阻塞的 read 抛异常退出，
        // 否则该线程与连接会一直挂着，下一轮续传只会不断堆积。
        InputStream in = response.body();
        RandomAccessFile raf = new RandomAccessFile(partial.toFile(), "rw");
        Thread reader = null;
        final IOException[] readerErr = new IOException[1];
        final long[] written = new long[]{0};
        final long[] lastReadAt = new long[]{System.currentTimeMillis()};
        final boolean[] sawFirstByte = new boolean[]{false};
        try {
            if (append) {
                raf.seek(startOffset);
            } else {
                raf.setLength(0);
                raf.seek(0);
            }

            final InputStream fin = in;
            final RandomAccessFile fraf = raf;
            reader = new Thread(() -> {
                byte[] buf = new byte[64 * 1024];
                try {
                    int n;
                    while ((n = fin.read(buf)) != -1) {
                        synchronized (written) {
                            fraf.write(buf, 0, n);
                            written[0] += n;
                        }
                        // 只要读到数据就刷新计时（含首字节）
                        synchronized (lastReadAt) {
                            lastReadAt[0] = System.currentTimeMillis();
                            sawFirstByte[0] = true;
                        }
                    }
                } catch (IOException e) {
                    readerErr[0] = e;
                }
            }, "sugarpal-update-reader");
            reader.setDaemon(true);
            reader.start();

            while (reader.isAlive()) {
                Thread.sleep(500);
                long last;
                synchronized (lastReadAt) {
                    last = lastReadAt[0];
                }
                if (System.currentTimeMillis() - last > STALL_SECONDS * 1000L) {
                    // 卡死：关流让阻塞的 read 立即返回，再按断点续传重来
                    try {
                        in.close();
                    } catch (Exception ignored) {
                        // ignore
                    }
                    // 给读线程一点时间响应关闭
                    reader.interrupt();
                    reader.join(2000);
                    throw new StallException("连接无响应超过 " + STALL_SECONDS + " 秒，准备断点续传");
                }
            }
            if (readerErr[0] != null) {
                // 读取出错（含被我们主动 close 打断）：交给上层续传
                throw readerErr[0];
            }
            return true;
        } finally {
            if (reader != null) {
                reader.interrupt();
            }
            try {
                in.close();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                raf.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    /**
     * 从直链反查资产的 API 地址。
     *
     * <p>静态清单 {@code update/latest.json} 只给 {@code browser_download_url}，
     * 没有 {@code assets[].url}。这里用其中的 tag 查一次 Releases API（走 {@code api.github.com}，
     * 国内可达），拿到同名资产的 {@code url}，从而得到不经过 {@code github.com} 的下载通道。
     *
     * <p>任何环节失败都返回 null —— 这只是「多一条通道」，不应影响原有的镜像/直连尝试。
     *
     * @param update 更新信息（需含 downloadUrl；fileName 用于匹配资产名）
     * @return 资产 API 地址；取不到返回 null
     */
    private String discoverAssetApiUrl(UpdateInfo update) {
        try {
            String tag = tagFromDownloadUrl(update.downloadUrl);
            if (tag == null) {
                return null;
            }
            String repoApi = repoApiFromDownloadUrl(update.downloadUrl);
            if (repoApi == null) {
                return null;
            }
            String body = httpGetQuietly(repoApi + "/releases/tags/" + tag);
            if (body == null || body.isBlank()) {
                return null;
            }
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement assetsEl = root.get("assets");
            if (assetsEl == null || !assetsEl.isJsonArray()) {
                return null;
            }
            String want = update.fileName;
            String fallback = null;
            for (JsonElement el : assetsEl.getAsJsonArray()) {
                if (el == null || el.isJsonNull() || !el.isJsonObject()) {
                    continue;
                }
                JsonObject asset = el.getAsJsonObject();
                JsonElement nameEl = asset.get("name");
                JsonElement urlEl = asset.get("url");
                if (nameEl == null || nameEl.isJsonNull() || urlEl == null || urlEl.isJsonNull()) {
                    continue;
                }
                String name = nameEl.getAsString();
                if (!name.endsWith(".exe")) {
                    continue;
                }
                String apiUrl = urlEl.getAsString();
                if (want != null && want.equals(name)) {
                    return apiUrl;
                }
                if (fallback == null) {
                    fallback = apiUrl;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 download URL 中解析 tag：.../releases/download/<tag>/<file> */
    private static String tagFromDownloadUrl(String url) {
        if (url == null) {
            return null;
        }
        int i = url.indexOf("/releases/download/");
        if (i < 0) {
            return null;
        }
        String rest = url.substring(i + "/releases/download/".length());
        int slash = rest.indexOf('/');
        return slash > 0 ? rest.substring(0, slash) : null;
    }

    /** 从 download URL 解析仓库 API 前缀：https://api.github.com/repos/<owner>/<repo> */
    private static String repoApiFromDownloadUrl(String url) {
        if (url == null) {
            return null;
        }
        // https://github.com/<owner>/<repo>/releases/download/...
        try {
            URI u = URI.create(url);
            String path = u.getPath();
            if (path == null) {
                return null;
            }
            String[] seg = path.split("/");
            // ["", owner, repo, "releases", "download", tag, file]
            if (seg.length < 3) {
                return null;
            }
            return "https://api.github.com/repos/" + seg[1] + "/" + seg[2];
        } catch (Exception e) {
            return null;
        }
    }

    /** 静默 GET：失败返回 null，不抛异常 */
    private String httpGetQuietly(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(RESOLVE_TIMEOUT)
                    .header("User-Agent", "SugarPal-Desktop-Updater")
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> resp = sendQuietly(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp != null && resp.statusCode() == 200) {
                return resp.body();
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
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

        HttpResponse<String> response = sendWithProxyFallback(request);
        if (response.statusCode() != 200) {
            throw new UpdateException("更新源返回 HTTP " + response.statusCode() + "（"
                    + (response.body() == null ? "" : response.body().trim()) + "）");
        }
        return response.body();
    }

    /**
     * 发送 GET：优先走系统代理（client）；若因 TLS 握手失败（常见于 Clash 等代理的
     * 中间人拦截，Java 不信任其证书），自动改用直连客户端（directClient）重试一次。
     */
    private HttpResponse<String> sendWithProxyFallback(HttpRequest request) throws UpdateException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new UpdateException("请求更新源超时，请检查网络后稍后重试", e);
        } catch (ConnectException e) {
            throw new UpdateException("无法连接更新源，请检查网络或更新源地址", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("请求被中断，请重试", e);
        } catch (SSLHandshakeException e) {
            // 系统代理做 TLS 中间人拦截，Java 不信任其证书；绕过代理直连重试
            try {
                return directClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception e2) {
                throw new UpdateException("请求更新源失败（代理 TLS 拦截，已尝试直连）：" + e2.getMessage(), e2);
            }
        } catch (IOException e) {
            throw new UpdateException("请求更新源失败：" + e.getMessage(), e);
        }
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
