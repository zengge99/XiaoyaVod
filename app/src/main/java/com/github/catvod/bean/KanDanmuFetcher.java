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

public class KanDanmuFetcher extends DanmuFetcher {

    private static KanDanmuFetcher INSTANCE = new KanDanmuFetcher();

    @Override
    protected String getBilibiliDanmakuXML(String title, int episode, int year) {
        try {
            String showId = searchEnId(title, year);
            if (showId == null) {
                throw new RuntimeException("No matching show found");
            }

            String episodeUrl = getEpisodeUrl(showId, episode);
            if (episodeUrl == null) {
                throw new RuntimeException("No matching episode found");
            }

            return getDanmakutXml(episodeUrl);
        } catch (Exception e) {
            Logger.log("getBilibiliDanmakuXML" + e);
            return "";
        }
    }

    public static KanDanmuFetcher get() {
        return INSTANCE;
    }

    private String searchEnId(String title, int year) throws IOException {
        // URL 编码影片名
        String encodedTitle = URLEncoder.encode(title, "UTF-8");
        // 使用步骤1的API搜索节目ID
        String searchUrl = "https://api.so.360kan.com/index?force_v=1&from=&pageno=1&v_ap=1&tab=all&kw=" + encodedTitle;
        String jsonResponse = sendGetRequest(searchUrl);

        Gson gson = new Gson();
        JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);

        // 解析 JSON 结构
        JsonObject data = response.getAsJsonObject("data");
        JsonObject longData = data.getAsJsonObject("longData");
        JsonArray rows = longData.getAsJsonArray("rows");

        for (JsonElement item : rows) {
            JsonObject show = item.getAsJsonObject();
            String showYear = show.get("year").getAsString();
            if (showYear.equals(String.valueOf(year))) {
                return show.get("en_id").getAsString(); // 返回 en_id
            }
        }
        return null;
    }

    private String getEpisodeUrl(String enId, int episode) throws IOException {
        // 使用步骤2的API获取剧集URL，en_id 作为参数
        String episodeUrl = "https://api.web.360kan.com/v1/detail?cat=2&id=" + enId;
        String jsonResponse = sendGetRequest(episodeUrl);

        Gson gson = new Gson();
        JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);
        JsonObject allepidetail = response.getAsJsonObject("data").getAsJsonObject("allepidetail");

        // 遍历 allepidetail 的所有字段
        for (String key : allepidetail.keySet()) {
            JsonArray episodes = allepidetail.getAsJsonArray(key); // 获取当前字段的 JsonArray
            for (JsonElement item : episodes) {
                JsonObject episodeData = item.getAsJsonObject();
                int playlinkNum = episodeData.get("playlink_num").getAsInt();
                if (playlinkNum == episode) {
                    return episodeData.get("url").getAsString().split("\\?")[0]; // 返回剧集URL
                }
            }
        }

        // 如果未找到匹配的剧集，返回 null
        return null;
    }
}
