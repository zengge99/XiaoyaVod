package com.github.catvod.net;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import com.github.catvod.crawler.Spider;

public class DoubanHtmlFetcher {

    private static final String TAG = "DoubanCrawler";
    
    // 严格按照你要求的 Header 定义
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";
    private static final String ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8";
    private static final String ACCEPT_LANG = "zh-CN,zh;q=0.9,en;q=0.8";

    /**
     * 为 Request.Builder 应用严格的伪装头
     */
    private static void applyStrictHeaders(Request.Builder builder, String referer, String cookie) {
        builder.addHeader("User-Agent", UA);
        builder.addHeader("Accept", ACCEPT);
        builder.addHeader("Accept-Language", ACCEPT_LANG);
        builder.addHeader("Connection", "keep-alive");
        builder.addHeader("Upgrade-Insecure-Requests", "1");
        if (referer != null) {
            builder.addHeader("Referer", referer);
        }
        if (cookie != null && !cookie.isEmpty()) {
            builder.addHeader("Cookie", cookie);
        }
    }

    private static OkHttpClient client() {
        try {
            return Objects.requireNonNull(Spider.client());
        } catch (Throwable e) {
            return build();
        }
    }

    /**
     * 主函数：获取豆瓣网页 HTML (安卓 5.0 兼容版)
     */
    public static String getDoubanHtml(String targetUrl) {
        
        OkHttpClient client = client();

        try {
            // --- [1/4] 初始请求目标页面 ---
            Request.Builder builder1 = new Request.Builder().url(targetUrl);
            applyStrictHeaders(builder1, null, null);

            try (Response res1 = client.newCall(builder1.build()).execute()) {
                // 如果 IP 干净直接返回 200
                if (res1.code() == 200) {
                    return decodeResponse(res1);
                }

                // 如果触发 302 重定向到验证页
                if (res1.code() == 302) {
                    String redirectUrl = res1.header("Location");
                    String bid = extractCookie(res1, "bid");

                    if (redirectUrl == null) return "";

                    // --- [2/4] 访问验证页面获取 tok 和 cha ---
                    Request.Builder builder2 = new Request.Builder().url(redirectUrl);
                    applyStrictHeaders(builder2, "https://movie.douban.com/", bid);

                    String verifyHtml = decodeResponse(client.newCall(builder2.build()).execute());
                    String tok = findByRegex(verifyHtml, "name=\"tok\"\\s+value=\"([^\"]+)\"");
                    String cha = findByRegex(verifyHtml, "name=\"cha\"\\s+value=\"([^\"]+)\"");

                    if (tok == null || cha == null) {
                        return "";
                    }

                    // --- [3/4] 计算 PoW (在安卓端可能耗时 0.1-1秒) ---
                    int sol = solvePoW(cha);

                    // --- [4/4] 提交验证并获取 dbsawcv1 ---
                    FormBody formBody = new FormBody.Builder()
                            .add("tok", tok)
                            .add("cha", cha)
                            .add("sol", String.valueOf(sol))
                            .build();

                    Request.Builder builder3 = new Request.Builder()
                            .url("https://sec.douban.com/c")
                            .post(formBody);
                    // 提交验证时 Origin 和 Referer 也很重要
                    applyStrictHeaders(builder3, redirectUrl, bid);
                    builder3.addHeader("Origin", "https://sec.douban.com");

                    try (Response res3 = client.newCall(builder3.build()).execute()) {
                        String dbsawcv1 = extractCookie(res3, "dbsawcv1");
                        
                        // --- [最终步] 携带所有 Cookie 重新请求目标页面 ---
                        String finalCookies = bid + (dbsawcv1.isEmpty() ? "" : "; " + dbsawcv1);
                        Request.Builder finalBuilder = new Request.Builder().url(targetUrl);
                        applyStrictHeaders(finalBuilder, "https://sec.douban.com/", finalCookies);

                        try (Response finalRes = client.newCall(finalBuilder.build()).execute()) {
                            return decodeResponse(finalRes);
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        return "";
    }

    /**
     * SHA-512 PoW 计算逻辑
     */
    private static int solvePoW(String cha) throws Exception {
        int nonce = 0;
        String target = "0000"; // 难度 4
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        while (true) {
            nonce++;
            String input = cha + nonce;
            md.update(input.getBytes(Charset.forName("UTF-8")));
            byte[] digest = md.digest();
            
            // 转换为十六进制并检查前导零
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2; i++) { // 只需要检查前两个字节(4位16进制)
                String hex = Integer.toHexString(0xFF & digest[i]);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }

            if (sb.toString().startsWith(target)) {
                return nonce;
            }
            if (nonce > 1000000) return 0; // 安全阈值
        }
    }

    /**
     * 响应流解析 (支持 GZIP，兼容安卓 5.0)
     */
    private static String decodeResponse(Response response) throws Exception {
        if (response == null || response.body() == null) return "";
        
        InputStream is = response.body().byteStream();
        String contentEncoding = response.header("Content-Encoding");
        if (contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip")) {
            is = new GZIPInputStream(is);
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024 * 4];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        is.close();
        return new String(bos.toByteArray(), Charset.forName("UTF-8"));
    }

    private static String extractCookie(Response response, String cookieName) {
        List<String> cookies = response.headers("Set-Cookie");
        for (String c : cookies) {
            if (c.startsWith(cookieName)) {
                return c.split(";")[0];
            }
        }
        return "";
    }

    private static String findByRegex(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }
}