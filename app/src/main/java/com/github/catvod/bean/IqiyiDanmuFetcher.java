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
import com.github.catvod.spider.Logger;
import com.github.catvod.utils.Path;
import java.io.File;

public class IqiyiDanmuFetcher {

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
            // Step 1: Get episode URL
            String episodeUrl = getEpisodeUrl(title, episode);
            if (episodeUrl == null) {
                throw new RuntimeException("No matching episode found");
            }

            // Step 2: Fetch danmaku data
            List<List<Object>> danmakuData = fetchDanmaku(episodeUrl);
            if (danmakuData == null) {
                throw new RuntimeException("Failed to fetch danmaku");
            }

            // Step 3: Convert to Bilibili XML format
            return convertToBilibiliXML(danmakuData);
        } catch (Exception e) {
            Logger.log(e);
            return "";
        }
    }

    private static String getEpisodeUrl(String title, int episode) throws IOException {
        String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.toString());
        String episodeUrl = "https://search.video.iqiyi.com/o?if=html5&pageNum=1&pos=1&pageSize=24&site=iqiyi&key=" + encodedTitle;
        String jsonResponse = sendGetRequest(episodeUrl);

        Gson gson = new Gson();
        JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);
        JsonArray docInfos = response.getAsJsonObject("data").getAsJsonArray("docinfos");

        for (var item : docInfos) {
            JsonObject docData = item.getAsJsonObject();
            JsonObject albumDocInfo = docData.getAsJsonObject("albumDocInfo"); // 深入 albumDocInfo
            String albumTitle = albumDocInfo.get("albumTitle").getAsString();
            if (albumTitle.equals(title)) {
                JsonArray videoInfos = albumDocInfo.getAsJsonArray("videoinfos"); // 注意字段名是 videoinfos
                for (var videoItem : videoInfos) {
                    JsonObject episodeData = videoItem.getAsJsonObject();
                    int itemNumber = episodeData.get("itemNumber").getAsInt();
                    if (itemNumber == episode) {
                        return episodeData.get("itemLink").getAsString(); // 返回剧集URL
                    }
                }
            }
        }
        return null;
    }

    private static List<List<Object>> fetchDanmaku(String episodeUrl) {
        try {
            String danmakuUrl = "https://dmku.hls.one?ac=dm&url=" + episodeUrl;
            String jsonResponse = sendGetRequest(danmakuUrl);
            Gson gson = new Gson();
            JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);
            JsonArray danmuku = response.getAsJsonArray("danmuku");
            return gson.fromJson(danmuku, List.class);
        } catch (Exception e) {
            Logger.log(e);
        } 

        try {
            String danmakuUrl = "https://dmku.thefilehosting.com?ac=dm&url=" + episodeUrl;
            String jsonResponse = sendGetRequest(danmakuUrl);
            Gson gson = new Gson();
            JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);
            JsonArray danmuku = response.getAsJsonArray("danmuku");
            return gson.fromJson(danmuku, List.class);
        } catch (Exception e) {
            Logger.log(e);
        } 

        return null;
    }

    /**
     * 将优酷的 mode 转换为 Bilibili 的 mode
     *
     * @param youkuMode 优酷的 mode（如 "top", "right", "bottom"）
     * @return Bilibili 的 mode（如 "5", "1", "4"）
     */
    private static String convertMode(String youkuMode) {
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

    private static String convertToBilibiliXML(List<List<Object>> danmakuData) {
        StringBuilder xmlBuilder = new StringBuilder();
        xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xmlBuilder.append("<i>\n");

        //处理dmku.thefilehosting.com的8字段格式
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

        //处理dmku.hls.one的5字段格式
        for (List<Object> danmaku : danmakuData) {
            if (danmaku.size() != 5) {
                break;
            }
            // 解析字段，确保类型正确
            double time = ((Number) danmaku.get(0)).doubleValue(); // 时间
            String youkuMode = danmaku.get(1).toString(); // 优酷的 mode
            String color = danmaku.get(2).toString(); // 颜色（如 "#FFFFFF"）
            String text = escapeXml(danmaku.get(4).toString()); // 弹幕文本
            String fontSize = danmaku.get(3).toString().replace("px", ""); // 字体大小（如 "24px"）

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
    private static String escapeXml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }

    private static String sendGetRequest(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    public static void test() {
        String xml = IqiyiDanmuFetcher.getBilibiliDanmakuXML("北上广不相信眼泪", 1, 2015);
        Logger.log(xml);
    }
}
