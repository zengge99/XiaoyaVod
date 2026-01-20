package com.github.catvod.net;

import com.github.catvod.crawler.Spider;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class DoubanHtmlFetcher {

    private static final String TAG = "DoubanCrawler";
    
    // 严格伪装 Header
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";
    private static final String ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8";
    private static final String ACCEPT_LANG = "zh-CN,zh;q=0.9,en;q=0.8";

    // 静态变量缓存 Cookie，避免重复计算 PoW
    private static String cachedBid = "";
    private static String cachedDbsawcv1 = "";

    /**
     * 获取 OkHttpClient 实例 (禁用自动重定向)
     */
    private static OkHttpClient getClient() {
        OkHttpClient baseClient;
        try {
            baseClient = Objects.requireNonNull(Spider.client());
        } catch (Throwable e) {
            // 如果 Spider.client() 不可用，则创建一个基础 client
            baseClient = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
        }
        // 关键：必须禁用 followRedirects 才能手动处理 302 验证逻辑
        return baseClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    /**
     * 统一应用严格的请求头
     */
    private static void applyStrictHeaders(Request.Builder builder, String referer, String cookie) {
        builder.addHeader("User-Agent", UA);
        builder.addHeader("Accept", ACCEPT);
        builder.addHeader("Accept-Language", ACCEPT_LANG);
        builder.addHeader("Connection", "keep-alive");
        builder.addHeader("Upgrade-Insecure-Requests", "1");
        if (referer != null && !referer.isEmpty()) {
            builder.addHeader("Referer", referer);
        }
        if (cookie != null && !cookie.isEmpty()) {
            builder.addHeader("Cookie", cookie);
        }
    }

    /**
     * 主入口：获取豆瓣网页 HTML
     */
    public static String getDoubanHtml(String targetUrl) {
        OkHttpClient client = getClient();
        String currentCookie = buildCookieString(cachedBid, cachedDbsawcv1);

        try {
            // 步骤 1：尝试直接访问 (带着缓存的 Cookie)
            Request.Builder builder1 = new Request.Builder().url(targetUrl);
            applyStrictHeaders(builder1, "https://www.douban.com/", currentCookie);

            try (Response res1 = client.newCall(builder1.build()).execute()) {
                // 如果返回 200，说明 Cookie 有效或当前 IP 没被拦截
                if (res1.code() == 200) {
                    return decodeResponse(res1);
                }

                // 如果返回 302，说明触发了豆瓣的安全验证 (PoW)
                if (res1.code() == 302) {
                    return handleVerification(client, targetUrl, res1);
                }
            }
        } catch (Exception e) {}
        return "";
    }

    /**
     * 处理完整的 PoW 验证逻辑
     */
    private static String handleVerification(OkHttpClient client, String targetUrl, Response res1) throws Exception {
        String redirectUrl = res1.header("Location");
        // 提取 302 响应中的新 bid (如果有)
        String newBid = extractCookie(res1, "bid");
        if (!newBid.isEmpty()) cachedBid = newBid;

        if (redirectUrl == null) return "";

        // 步骤 2：访问验证中转页，获取 tok 和 cha
        Request.Builder builder2 = new Request.Builder().url(redirectUrl);
        applyStrictHeaders(builder2, "https://movie.douban.com/", cachedBid);

        String verifyHtml;
        try (Response res2 = client.newCall(builder2.build()).execute()) {
            verifyHtml = decodeResponse(res2);
        }

        String tok = findByRegex(verifyHtml, "name=\"tok\"\\s+value=\"([^\"]+)\"");
        String cha = findByRegex(verifyHtml, "name=\"cha\"\\s+value=\"([^\"]+)\"");

        if (tok == null || cha == null) {
            return "";
        }

        // 步骤 3：计算 PoW (计算量为难度 4)
        int sol = solvePoW(cha);

        // 步骤 4：POST 提交 PoW 结果
        FormBody formBody = new FormBody.Builder()
                .add("tok", tok)
                .add("cha", cha)
                .add("sol", String.valueOf(sol))
                .build();

        Request.Builder builder3 = new Request.Builder()
                .url("https://sec.douban.com/c")
                .post(formBody);
        applyStrictHeaders(builder3, redirectUrl, cachedBid);
        builder3.addHeader("Origin", "https://sec.douban.com");

        try (Response res3 = client.newCall(builder3.build()).execute()) {
            // 获取验证成功后的核心 Cookie: dbsawcv1
            String dbsawcv1 = extractCookie(res3, "dbsawcv1");
            if (!dbsawcv1.isEmpty()) {
                cachedDbsawcv1 = dbsawcv1;
            }

            // 步骤 5：携带所有新生成的 Cookie 最终请求目标页面
            String finalCookie = buildCookieString(cachedBid, cachedDbsawcv1);
            Request.Builder finalBuilder = new Request.Builder().url(targetUrl);
            applyStrictHeaders(finalBuilder, "https://sec.douban.com/", finalCookie);

            try (Response finalRes = client.newCall(finalBuilder.build()).execute()) {
                return decodeResponse(finalRes);
            }
        }
    }

    /**
     * 计算 Proof of Work
     */
    private static int solvePoW(String cha) throws Exception {
        int nonce = 0;
        String target = "0000"; // 难度 4，即前导 4 个 0
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        
        while (nonce < 2000000) {
            nonce++;
            String input = cha + nonce;
            md.update(input.getBytes(Charset.forName("UTF-8")));
            byte[] digest = md.digest();
            
            // 优化：只转换前两个字节来判断前导 0
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2; i++) {
                String hex = Integer.toHexString(0xFF & digest[i]);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }

            if (sb.toString().startsWith(target)) {
                return nonce;
            }
        }
        return 0;
    }

    /**
     * 解析响应体，处理 GZIP
     */
    private static String decodeResponse(Response response) throws Exception {
        if (response == null || response.body() == null) return "";
        
        InputStream is = response.body().byteStream();
        String contentEncoding = response.header("Content-Encoding");
        
        if (contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip")) {
            is = new GZIPInputStream(is);
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        is.close();
        return new String(bos.toByteArray(), Charset.forName("UTF-8"));
    }

    private static String extractCookie(Response response, String name) {
        List<String> cookies = response.headers("Set-Cookie");
        for (String c : cookies) {
            if (c.contains(name + "=")) {
                return c.split(";")[0];
            }
        }
        return "";
    }

    private static String findByRegex(String text, String regex) {
        if (text == null) return null;
        Matcher m = Pattern.compile(regex).matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String buildCookieString(String bid, String dbsa) {
        StringBuilder sb = new StringBuilder();
        if (bid != null && !bid.isEmpty()) sb.append(bid);
        if (dbsa != null && !dbsa.isEmpty()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(dbsa);
        }
        return sb.toString();
    }
}