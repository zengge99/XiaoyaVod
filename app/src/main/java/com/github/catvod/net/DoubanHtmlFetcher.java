package com.github.catvod.net;

import android.util.Log;
import com.github.catvod.crawler.Spider;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
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
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";
    private static final String ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8";
    private static final String ACCEPT_LANG = "zh-CN,zh;q=0.9,en;q=0.8";

    private static void applyStrictHeaders(Request.Builder builder, String referer, String cookie) {
        builder.addHeader("User-Agent", UA);
        builder.addHeader("Accept", ACCEPT);
        builder.addHeader("Accept-Language", ACCEPT_LANG);
        builder.addHeader("Connection", "keep-alive");
        builder.addHeader("Upgrade-Insecure-Requests", "1");
        if (referer != null) builder.addHeader("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) builder.addHeader("Cookie", cookie);
    }

    private static OkHttpClient client() {
        OkHttpClient baseClient;
        try {
            baseClient = Objects.requireNonNull(Spider.client());
        } catch (Throwable e) {
            baseClient = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
        }
        
        return baseClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    public static String getDoubanHtml(String targetUrl) {
        OkHttpClient client = client(); // 每次调用获取一个禁用重定向的实例

        try {
            // --- [1/4] 初始请求 ---
            Request.Builder builder1 = new Request.Builder().url(targetUrl);
            applyStrictHeaders(builder1, null, null);

            try (Response res1 = client.newCall(builder1.build()).execute()) {
                // 如果直接成功 (部分 IP 干净的情况)
                if (res1.code() == 200) {
                    return decodeResponse(res1);
                }

                // 如果触发验证 (302)
                if (res1.code() == 302) {
                    String redirectUrl = res1.header("Location");
                    String bid = extractCookie(res1, "bid");

                    if (redirectUrl == null) return "";

                    // --- [2/4] 获取验证参数 ---
                    Request.Builder builder2 = new Request.Builder().url(redirectUrl);
                    applyStrictHeaders(builder2, "https://movie.douban.com/", bid);

                    String verifyHtml;
                    try (Response res2 = client.newCall(builder2.build()).execute()) {
                        verifyHtml = decodeResponse(res2);
                    }
                    
                    String tok = findByRegex(verifyHtml, "name=\"tok\"\\s+value=\"([^\"]+)\"");
                    String cha = findByRegex(verifyHtml, "name=\"cha\"\\s+value=\"([^\"]+)\"");

                    if (tok == null || cha == null) return "";

                    // --- [3/4] 计算 PoW ---
                    int sol = solvePoW(cha);

                    // --- [4/4] 提交验证 ---
                    FormBody formBody = new FormBody.Builder()
                            .add("tok", tok)
                            .add("cha", cha)
                            .add("sol", String.valueOf(sol))
                            .build();

                    Request.Builder builder3 = new Request.Builder()
                            .url("https://sec.douban.com/c")
                            .post(formBody);
                    applyStrictHeaders(builder3, redirectUrl, bid);
                    builder3.addHeader("Origin", "https://sec.douban.com");

                    try (Response res3 = client.newCall(builder3.build()).execute()) {
                        String dbsawcv1 = extractCookie(res3, "dbsawcv1");
                        
                        // --- [最终步] 重新请求目标页面 ---
                        String finalCookies = (bid.isEmpty() ? "" : bid + "; ") + dbsawcv1;
                        Request.Builder finalBuilder = new Request.Builder().url(targetUrl);
                        applyStrictHeaders(finalBuilder, "https://sec.douban.com/", finalCookies);

                        try (Response finalRes = client.newCall(finalBuilder.build()).execute()) {
                            // 这里注意：如果验证通过，finalRes.code() 应该是 200
                            return decodeResponse(finalRes);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fetch Error: " + e.getMessage());
        }
        return "";
    }

    private static int solvePoW(String cha) throws Exception {
        int nonce = 0;
        String target = "0000";
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        while (nonce < 1500000) { // 增加上限防止死循环
            nonce++;
            String input = cha + nonce;
            md.update(input.getBytes(Charset.forName("UTF-8")));
            byte[] digest = md.digest();
            
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
            if (c.contains(cookieName + "=")) {
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
}