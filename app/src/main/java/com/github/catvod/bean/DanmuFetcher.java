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

public class DanmuFetcher {

    /**
     * 获取 Bilibili 弹幕格式的 XML
     *
     * @param title   影片名
     * @param episode 集数
     * @param year    年份
     * @return Bilibili 弹幕格式的 XML 字符串
     * @throws IOException 如果请求失败
     */
    public static String getBilibiliDanmakuXML(String title, int episode, int year) throws IOException {
        // Step 1: Get showId
        String showId = searchShowId(title, year);
        if (showId == null) {
            throw new RuntimeException("No matching show found");
        }

        // Step 2: Get episode URL
        String episodeUrl = getEpisodeUrl(showId, episode);
        if (episodeUrl == null) {
            throw new RuntimeException("No matching episode found");
        }

        // Step 3: Fetch danmaku data
        List<List<String>> danmakuData = fetchDanmaku(episodeUrl);
        if (danmakuData == null) {
            throw new RuntimeException("Failed to fetch danmaku");
        }

        // Step 4: Convert to Bilibili XML format
        return convertToBilibiliXML(danmakuData);
    }

    private static String searchShowId(String title, int year) throws IOException {
        // URL 编码影片名
        String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.toString());
        String searchUrl = "https://search.youku.com/api/search?pg=1&keyword=" + encodedTitle;
        String jsonResponse = sendGetRequest(searchUrl);

        Gson gson = new Gson();
        JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);
        JsonArray pageComponentList = response.getAsJsonArray("pageComponentList");

        for (var item : pageComponentList) {
            JsonObject commonData = item.getAsJsonObject().get("commonData").getAsJsonObject();
            String feature = commonData.get("feature").getAsString();
            if (feature.contains(String.valueOf(year))) {
                return commonData.get("showId").getAsString();
            }
        }
        return null;
    }

    private static String getEpisodeUrl(String showId, int episode) throws IOException {
        String episodeUrl = "https://search.youku.com/api/search?appScene=show_episode&showIds=" + showId;
        String jsonResponse = sendGetRequest(episodeUrl);

        Gson gson = new Gson();
        JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);
        JsonArray serisesList = response.getAsJsonArray("serisesList");

        for (var item : serisesList) {
            JsonObject series = item.getAsJsonObject();
            String showVideoStage = series.get("showVideoStage").getAsString();
            if (showVideoStage.equals(String.valueOf(episode))) {
                return series.get("url").getAsString().split("\\?")[0];
            }
        }
        return null;
    }

    private static List<List<String>> fetchDanmaku(String episodeUrl) throws IOException {
        String danmakuUrl = "https://dmku.thefilehosting.com?ac=dm&url=" + episodeUrl;
        String jsonResponse = sendGetRequest(danmakuUrl);

        Gson gson = new Gson();
        JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);
        JsonArray danmuku = response.getAsJsonArray("danmuku");

        return gson.fromJson(danmuku, List.class);
    }

    private static String convertToBilibiliXML(List<List<String>> danmakuData) {
        StringBuilder xmlBuilder = new StringBuilder();
        xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xmlBuilder.append("<i>\n");

        for (List<String> danmaku : danmakuData) {
            double time = Double.parseDouble(danmaku.get(0)); // 时间
            String mode = danmaku.get(1); // 模式（如 "right"）
            String color = danmaku.get(2); // 颜色（如 "#FFFFFF"）
            String text = danmaku.get(4); // 弹幕文本
            String fontSize = danmaku.get(7).replace("px", ""); // 字体大小（如 "24px"）

            // 将颜色转换为十进制，去掉 # 号
            int colorDecimal = Integer.parseInt(color.replace("#", ""), 16);

            // Bilibili 弹幕格式：时间,模式,字体大小,颜色,时间戳,弹幕池,用户Hash,弹幕ID
            String attrs = String.format("%.5f,%s,%s,%d,0,0,0,0", time, mode, fontSize, colorDecimal);
            xmlBuilder.append(String.format("  <d p=\"%s\">%s</d>\n", attrs, text));
        }

        xmlBuilder.append("</i>");
        return xmlBuilder.toString();
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
        try {
            String xml = DanmuFetcher.getBilibiliDanmakuXML("北上", 1, 2025);
            Logger.log(xml);
        } catch (IOException e) {
            Logger.log(e);
        }
    }
}
