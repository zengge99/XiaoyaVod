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

public class IqiyiDanmuFetcher extends DanmuFetcher {

    private static IqiyiDanmuFetcher thisObject = new IqiyiDanmuFetcher();

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
            String episodeUrl = thisObject.getEpisodeUrl(title, episode);
            if (episodeUrl == null) {
                throw new RuntimeException("No matching episode found");
            }

            // Step 2: Fetch danmaku data
            List<List<Object>> danmakuData = thisObject.fetchDanmaku(episodeUrl);
            if (danmakuData == null) {
                throw new RuntimeException("Failed to fetch danmaku");
            }

            // Step 3: Convert to Bilibili XML format
            return thisObject.convertToBilibiliXML(danmakuData);
        } catch (Exception e) {
            Logger.log(e);
            return "";
        }
    }

    private String getEpisodeUrl(String title, int episode) throws IOException {
        String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.toString());
        String episodeUrl = "https://search.video.iqiyi.com/o?if=html5&pageNum=1&pos=1&pageSize=24&site=iqiyi&key=" + encodedTitle;
        String jsonResponse = sendGetRequest(episodeUrl);

        Gson gson = new Gson();
        JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);
        JsonArray docInfos = response.getAsJsonObject("data").getAsJsonArray("docinfos");

        for (JsonElement item : docInfos) {
            JsonObject docData = item.getAsJsonObject();
            JsonObject albumDocInfo = docData.getAsJsonObject("albumDocInfo"); // 深入 albumDocInfo
            String albumTitle = albumDocInfo.get("albumTitle").getAsString();
            if (albumTitle.equals(title)) {
                JsonArray videoInfos = albumDocInfo.getAsJsonArray("videoinfos"); // 注意字段名是 videoinfos
                for (JsonElement videoItem : videoInfos) {
                    JsonObject episodeData = videoItem.getAsJsonObject();
                    int itemNumber = episodeData.get("itemNumber").getAsInt();
                    if (itemNumber == episode) {
                        return episodeData.get("itemLink").getAsString().split("\\?")[0]; // 返回剧集URL
                    }
                }
            }
        }
        return null;
    }

    public static void test() {
        String xml = IqiyiDanmuFetcher.getBilibiliDanmakuXML("北上广不相信眼泪", 1, 2015);
        Logger.log(xml);
    }
}
