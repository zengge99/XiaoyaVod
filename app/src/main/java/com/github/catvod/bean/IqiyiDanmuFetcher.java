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
            String episodeUrl = thisObject.getEpisodeUrl(title, episode, year);
            if (episodeUrl == null) {
                throw new RuntimeException("No matching episode found");
            }

            return thisObject.getDanmakutXml(episodeUrl);
        } catch (Exception e) {
            Logger.log("IqiyiDanmuFetcher.getBilibiliDanmakuXML" + e);
            return "";
        }
    }

    private String getEpisodeUrl(String title, int episode, int year) throws IOException {
        String encodedTitle = URLEncoder.encode(title, "UTF-8");
        String episodeUrl = "https://search.video.iqiyi.com/o?if=html5&pageNum=1&pos=1&pageSize=24&site=iqiyi&key=" + encodedTitle;
        String jsonResponse = sendGetRequest(episodeUrl);

        Gson gson = new Gson();
        JsonObject response = gson.fromJson(jsonResponse, JsonObject.class);
        JsonArray docInfos = response.getAsJsonObject("data").getAsJsonArray("docinfos");

        for (JsonElement item : docInfos) {
            JsonObject docData = item.getAsJsonObject();
            JsonObject albumDocInfo = docData.getAsJsonObject("albumDocInfo"); // 深入 albumDocInfo
            String albumTitle = albumDocInfo.get("albumTitle").getAsString();
            String releaseDate = albumDocInfo.get("releaseDate").getAsString();
            if (releaseDate != null && !releaseDate.isEmpty()) {
                if (releaseDate.contains(String.valueOf(year))) {
                    continue;
                }
            }
            if (albumTitle.equals(title)) {
                JsonArray videoInfos = albumDocInfo.getAsJsonArray("videoinfos"); // 注意字段名是 videoinfos
                for (JsonElement videoItem : videoInfos) {
                    JsonObject episodeData = videoItem.getAsJsonObject();
                    int itemNumber = episodeData.get("itemNumber").getAsInt();
                    if (itemNumber == episode) {
                        Logger.log("Found url at iqiyi: " + episodeData.get("itemLink").getAsString().split("\\?")[0]);
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
