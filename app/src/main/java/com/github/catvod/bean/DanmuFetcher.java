package com.github.catvod.bean;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.github.catvod.spider.Logger;
import com.github.catvod.utils.Path;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.security.MessageDigest;

public class DanmuFetcher {
    private static DanmuFetcher thisObject = new DanmuFetcher();
    private static volatile String recent;

    public static void pushDanmu(String title, int episode, int year) {
        String danmuPath = Path.cache() + String.format("/TV/danmu/%s.txt", generateMd5(title + String.valueOf(episode) + String.valueOf(year)));
        recent = danmuPath;
        //从缓存文件快速推弹幕
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(100);
                File danmuFile = new File(danmuPath);
                if (!Path.read(danmuFile).isEmpty()) {
                    thisObject.sendGetRequest("http://127.0.0.1:9978/action?do=refresh&type=danmaku&path=" + "file://" + danmuPath);
                }
            } catch (Exception e) {
                Logger.log("pushDanmu" + e);
            }
        });
        thread.start();

        //后台线程从网络获取最新弹幕并重新推送
        pushDanmuBg(title, episode, year);

        //加速获取下一集弹幕
        pushDanmuBg(title, episode + 1, year);
    }

    /**
     * 获取 Bilibili 弹幕格式的 XML
     *
     * @param title   影片名
     * @param episode 集数
     * @param year    年份
     * @return Bilibili 弹幕格式的 XML 字符串
     * @throws IOException 如果请求失败
     */
    public static String getBilibiliDanmakuXML(String title, int episode, int year) {
        try {
            // Step 1: Get showId
            String showId = thisObject.searchShowId(title, year);
            if (showId == null) {
                throw new RuntimeException("No matching show found");
            }

            // Step 2: Get episode URL
            String episodeUrl = thisObject.getEpisodeUrl(showId, episode);
            if (episodeUrl == null) {
                throw new RuntimeException("No matching episode found");
            }

            // Step 3: Fetch danmaku data
            List<List<Object>> danmakuData = thisObject.fetchDanmaku(episodeUrl);
            if (danmakuData == null) {
                throw new RuntimeException("Failed to fetch danmaku");
            }

            // Step 4: Convert to Bilibili XML format
            return thisObject.convertToBilibiliXML(danmakuData);
        } catch (Exception e) {
            Logger.log("getBilibiliDanmakuXML" + e);
            return "";
        }
    }

    private String searchShowId(String title, int year) throws IOException {
        // URL 编码影片名
        String encodedTitle = URLEncoder.encode(title, "UTF-8");
        String searchUrl = "https://search.youku.com/api/search?pg=1&keyword=" + encodedTitle;
        String jsonResponse = sendGetRequest(searchUrl);

        Gson gson = new Gson();
        JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);
        JsonArray pageComponentList = response.getAsJsonArray("pageComponentList");

        for (JsonElement item : pageComponentList) {
            JsonObject commonData = item.getAsJsonObject().get("commonData").getAsJsonObject();
            String feature = commonData.get("feature").getAsString();
            if (feature.contains(String.valueOf(year))) {
                return commonData.get("showId").getAsString();
            }
        }
        return null;
    }

    private String getEpisodeUrl(String showId, int episode) throws IOException {
        String episodeUrl = "https://search.youku.com/api/search?appScene=show_episode&showIds=" + showId;
        String jsonResponse = sendGetRequest(episodeUrl);

        Gson gson = new Gson();
        JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);
        JsonArray serisesList = response.getAsJsonArray("serisesList");

        for (JsonElement item : serisesList) {
            JsonObject series = item.getAsJsonObject();
            String showVideoStage = series.get("showVideoStage").getAsString();
            String displayName = series.get("displayName").getAsString();
            Logger.log("showVideoStage:" + showVideoStage + "displayName:" + displayName + "episode:"
                    + String.valueOf(episode));
            if (extractNumber(showVideoStage) == episode || extractNumber(displayName) == episode) {
                if (series.has("url")) {
                    try {
                        String url = series.get("url").getAsString();
                        // 确保 URL 不为空，并且是合法的链接
                        if (url != null && !url.isEmpty()) {
                            // 去除 URL 中的查询参数部分
                            String baseUrl = url.split("\\?")[0];
                            Logger.log("Extracted URL: " + baseUrl);
                            return baseUrl;
                        }
                    } catch (Exception e) {
                        Logger.log("Failed to parse URL: " + e.getMessage());
                    }
                }

                if (series.has("videoId")) {
                    try {
                        String videoId = series.get("videoId").getAsString();
                        // 确保 videoId 不为空
                        if (videoId != null && !videoId.isEmpty()) {
                            String generatedUrl = String.format("https://v.youku.com/v_show/id_%s.html", videoId);
                            Logger.log("Generated URL from videoId: " + generatedUrl);
                            return generatedUrl;
                        }
                    } catch (Exception e) {
                        Logger.log("Failed to parse videoId: " + e.getMessage());
                    }
                }
                Logger.log("Neither 'url' nor 'videoId' is available.");
            }
        }
        return null;
    }

    protected int extractNumber(String input) {
        // 定义正则表达式，匹配1到4位数字，包括以0开头的数字
        String regex = "\\d{1,4}";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(input);

        // 如果找到匹配的数字，将其转换为整数
        if (matcher.find()) {
            String numberStr = matcher.group();
            return Integer.parseInt(numberStr);
        }

        // 如果没有找到匹配的数字，返回-1或其他默认值
        return -1;
    }

    protected List<List<Object>> fetchDanmaku(String episodeUrl) {
        // 尝试从第一个 URL 获取弹幕数据
        List<List<Object>> danmakuData = fetchDanmakuFromUrl("https://dmku.thefilehosting.com?ac=dm&url=" + episodeUrl);
        if (danmakuData != null) {
            return danmakuData;
        }

        // 如果第一个 URL 失败，尝试从第二个 URL 获取弹幕数据
        return fetchDanmakuFromUrl("https://dmku.hls.one?ac=dm&url=" + episodeUrl);
    }

    private List<List<Object>> fetchDanmakuFromUrl(String danmakuUrl) {
        try {
            String jsonResponse = sendGetRequest(danmakuUrl);
            Gson gson = new Gson();
            JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);

            // 检查 danmu 字段的值
            int num = 1; // 默认值
            if (response.has("danum")) {
                num = response.get("danum").getAsInt();
            }

            // 如果 danmu 的值为 0，直接返回 null
            if (num == 0) {
                return null;
            }

            // 解析 danmuku 数组
            JsonArray danmuku = response.getAsJsonArray("danmuku");
            return gson.fromJson(danmuku, List.class);
        } catch (Exception e) {
            Logger.log("fetchDanmakuFromUrl" + e);
            return null;
        }
    }

    /**
     * 将优酷的 mode 转换为 Bilibili 的 mode
     *
     * @param youkuMode 优酷的 mode（如 "top", "right", "bottom"）
     * @return Bilibili 的 mode（如 "5", "1", "4"）
     */
    protected String convertMode(String youkuMode) {
        switch (youkuMode) {
            case "top":
                return "5"; // 顶部弹幕
            case "right":
                return "1"; // 滚动弹幕
            case "bottom":
                return "4"; // 底部弹幕
            default:
                return "1"; // 默认滚动弹幕
        }
    }

    protected String convertToBilibiliXML(List<List<Object>> danmakuData) {
        StringBuilder xmlBuilder = new StringBuilder();
        xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xmlBuilder.append("<i>\n");

        // 处理dmku.thefilehosting.com的8字段格式
        for (List<Object> danmaku : danmakuData) {
            if (danmaku.size() != 8) {
                break;
            }
            // 解析字段，确保类型正确
            double time = ((Number) danmaku.get(0)).doubleValue(); // 时间
            String youkuMode = danmaku.get(1).toString(); // 优酷的 mode
            String color = danmaku.get(2).toString(); // 颜色（如 "#FFFFFF"）
            String text = escapeXml(danmaku.get(4).toString()); // 弹幕文本
            String fontSize = danmaku.get(7).toString().replace("px", ""); // 字体大小（如 "24px"）

            // 将颜色转换为十进制，去掉 # 号
            int colorDecimal = Integer.parseInt(color.replace("#", ""), 16);

            // 转换 mode
            String bilibiliMode = convertMode(youkuMode);

            // Bilibili 弹幕格式：时间,模式,字体大小,颜色,时间戳,弹幕池,用户Hash,弹幕ID
            String attrs = String.format("%.5f,%s,%s,%d,0,0,0,0,0", time, bilibiliMode, fontSize, colorDecimal);
            xmlBuilder.append(String.format("  <d p=\"%s\">%s</d>\n", attrs, text));
        }

        // 处理dmku.hls.one的5字段格式
        for (List<Object> danmaku : danmakuData) {
            if (danmaku.size() != 5) {
                break;
            }
            // 解析字段，确保类型正确
            double time = ((Number) danmaku.get(0)).doubleValue(); // 时间
            String youkuMode = danmaku.get(1).toString(); // 优酷的 mode
            String color = "#FFFFFF"; // 颜色（如 "#FFFFFF"）
            String text = escapeXml(danmaku.get(4).toString()); // 弹幕文本
            String fontSize = "24"; // 字体大小（如 "24px"）

            // 将颜色转换为十进制，去掉 # 号
            int colorDecimal = Integer.parseInt(color.replace("#", ""), 16);

            // 转换 mode
            String bilibiliMode = convertMode(youkuMode);

            // Bilibili 弹幕格式：时间,模式,字体大小,颜色,时间戳,弹幕池,用户Hash,弹幕ID
            String attrs = String.format("%.5f,%s,%s,%d,0,0,0,0,0", time, bilibiliMode, fontSize, colorDecimal);
            xmlBuilder.append(String.format("  <d p=\"%s\">%s</d>\n", attrs, text));
        }

        xmlBuilder.append("</i>");
        return xmlBuilder.toString();
    }

    /**
     * 转义 XML 特殊字符
     *
     * @param text 输入的文本
     * @return 转义后的文本
     */
    protected String escapeXml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    protected String sendGetRequest(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000); 
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    protected static String generateMd5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 algorithm not found", e);
        }
    }

    protected static String getAllDanmakuXML(String title, int episode, int year) {
            String danmu = DanmuFetcher.getBilibiliDanmakuXML(title, episode, year);
            if (danmu.isEmpty()) {
                danmu = KanDanmuFetcher.getBilibiliDanmakuXML(title, episode, year);
            }
            if (danmu.isEmpty()) {
                danmu = IqiyiDanmuFetcher.getBilibiliDanmakuXML(title, episode, year);
            }
            return danmu;
    }

    private static void pushDanmuBg(String title, int episode, int year) {
        String danmuPath = Path.cache() + String.format("/TV/danmu/%s.txt", generateMd5(title + String.valueOf(episode) + String.valueOf(year)));
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(100);
                String danmu = DanmuFetcher.getAllDanmakuXML(title, episode, year);
                if (danmu.isEmpty() && recent.equals(danmuPath)) {
                    pushDanmuBg(title, episode, year);
                    Thread.sleep(60000);
                    return;
                }
                File danmuFile = new File(danmuPath);
                Path.write(danmuFile, danmu.getBytes());
                if (recent.equals(danmuPath)) {
                    thisObject.sendGetRequest("http://127.0.0.1:9978/action?do=refresh&type=danmaku&path=" + "file://" + danmuPath);
                }
            } catch (Exception e) {
                Logger.log("pushDanmuBg" + e);
            }
        });
        thread.start();
    }

    public static void test() {
        String xml = DanmuFetcher.getBilibiliDanmakuXML("北上", 1, 2025);
        Logger.log(xml);
    }
}
