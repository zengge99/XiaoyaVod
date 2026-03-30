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
import com.google.gson.JsonParser;
import com.github.catvod.spider.Logger;
import com.github.catvod.utils.Path;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Random;

public class LogvarDanmuFetcher extends DanmuFetcher {

    private static LogvarDanmuFetcher INSTANCE = new LogvarDanmuFetcher();

    @Override
    protected String getBilibiliDanmakuXML(String title, int episode, int year) {
        for (int i = 0; i < 2; i++) {
            String danmu = _getBilibiliDanmakuXML(title, episode, year);
            if (danmu != null && !danmu.isEmpty()) {
                break;
            }
        }
        return danmu;
    }

    @Override
    protected int getPriority() {
        return 0;
    }

    private String _getBilibiliDanmakuXML(String title, int episode, int year) {
        try {
            if (danmuApi == null || danmuApi.isEmpty() || !danmuApi.startsWith("http")) {
                return "";
            }
            int ms = new Random().nextInt(20001) + 1000;
            Thread.sleep(ms);
            String fileNameJson = String.format("{fileName: \"%s.%s.S01E%02d.mp4\" }", title, year, episode);
            String jsonResponse = sendPostRequest(danmuApi + "/api/v2/match", JsonParser.parseString(fileNameJson).getAsJsonObject());
            JsonObject root = JsonParser.parseString(jsonResponse).getAsJsonObject();
            JsonArray matches = root.getAsJsonArray("matches");
            String episodeId = "";
            if (matches != null && matches.size() > 0) {
                JsonObject firstMatch = matches.get(0).getAsJsonObject();
                if (firstMatch.has("episodeId")) {
                    episodeId = firstMatch.get("episodeId").getAsString();
                }
            }
            if (episodeId.isEmpty()) {
                return "";
            }
            Logger.log("LogvarDanmuFetcher.getBilibiliDanmakuXML匹配到episodeId： " + episodeId);
            String xmlResponse = sendGetRequest(danmuApi + "/api/v2/comment/" + episodeId + "?format=xml");
            xmlResponse = fixDanmuPosition(xmlResponse);
            if (xmlResponse != null && xmlResponse.startsWith("<?xml") && xmlResponse.contains("<d p=")) {
                String[] lines = xmlResponse.split("</d>", 11);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lines.length && i < 10; i++) {
                    sb.append(lines[i]).append("</d>\n");
                }
                Logger.log("获取到弹幕 (前10行): \n" + sb.toString());
                return xmlResponse;
            }
        } catch (Exception e) {
            Logger.log("LogvarDanmuFetcher.getBilibiliDanmakuXML" + e);
        }
        return "";
    }

    //有些弹幕是居中悬停的，非常讨厌，修改成滚动。
    private static String fixDanmuPosition(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String regex = "(<d\\s+p\\s*=\\s*\"[^\",]+),[^\",]+(,|\\s*\")";
        String replacement = "$1,1$2";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(input);
        return matcher.replaceAll(replacement);
    }
}
