package com.github.catvod.bean;

import com.github.catvod.spider.Logger;
import com.github.catvod.utils.Path;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DanmuFetcher {
    private static final DanmuFetcher INSTANCE = new DanmuFetcher();
    private static volatile String recent;
    private static final String DANMU_ROOT = Path.cache() + "/TV/danmu";
    private static final Gson GSON = new Gson();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(); // 使用线程池代替 new Thread
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d{1,4}");
    private static final int TIMEOUT = 20000;
    public static String danmuApi;

    public static void pushDanmu(String title, int episode, int year) {
        String key = title + episode + year;
        recent = key;
        String fileName = generateMd5(key) + ".txt";
        String danmuPath = DANMU_ROOT + "/" + fileName;

        clearOldDanmu();

        // 1. 快速从本地缓存推送
        EXECUTOR.execute(() -> {
            try {
                Thread.sleep(100);
                File danmuFile = new File(danmuPath);
                if (danmuFile.exists() && danmuFile.length() > 0) {
                    String danmuProxyPath = "http://127.0.0.1:9978/proxy?do=fs&file=" + danmuPath;
                    String actionUrl = "http://127.0.0.1:9978/action?do=refresh&type=danmaku&path=" + URLEncoder.encode(danmuProxyPath, "UTF-8");
                    INSTANCE.sendGetRequest(actionUrl);
                }
            } catch (Exception e) {
                Logger.log("pushDanmu Cache: " + e.getMessage());
            }
        });

        // 2. 后台获取最新并重新推送
        pushDanmuBg(title, episode, year);

        // 3. 预加载下一集
        pushDanmuBg(title, episode + 1, year);
    }

    public static String getBilibiliDanmakuXML(String title, int episode, int year) {
        try {
            String showId = INSTANCE.searchShowId(title, year);
            if (showId == null) return "";

            String episodeUrl = INSTANCE.getEpisodeUrl(showId, episode);
            if (episodeUrl == null) return "";

            return INSTANCE.getDanmakutXml(episodeUrl);
        } catch (Exception e) {
            Logger.log("getBilibiliDanmakuXML: " + e.getMessage());
            return "";
        }
    }

    protected String getDanmakutXml(String episodeUrl) {
        String xml = getDanmakutXmlFromLogvar(episodeUrl);
        if (xml == null || xml.isEmpty()) {
            xml = getDanmakutXmlFromChenxi(episodeUrl);
        }
        return xml;
    }

    private String getDanmakutXmlFromLogvar(String episodeUrl) {
        if (danmuApi != null && !danmuApi.isEmpty()) {
            String apiUrl = danmuApi + "/api/v2/comment?&format=xml&&url=" + episodeUrl;
            try {
                String rawResponse = sendGetRequest(apiUrl);
                if (rawResponse != null && rawResponse.startsWith("<?xml") && rawResponse.contains("<d p=")) {
                    return rawResponse;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private String getDanmakutXmlFromChenxi(String episodeUrl) {
        List<List<Object>> danmakuData = fetchDanmaku(episodeUrl);
        return (danmakuData != null) ? convertToBilibiliXML(danmakuData) : "";
    }

    private String searchShowId(String title, int year) throws IOException {
        String encodedTitle = URLEncoder.encode(title, "UTF-8");
        String searchUrl = "https://search.youku.com/api/search?pg=1&keyword=" + encodedTitle;
        String jsonResponse = sendGetRequest(searchUrl);

        JsonObject response = GSON.fromJson(jsonResponse, JsonObject.class);
        JsonArray pageComponentList = response.getAsJsonArray("pageComponentList");
        if (pageComponentList == null) return null;

        String yearStr = String.valueOf(year);
        for (JsonElement item : pageComponentList) {
            JsonObject commonData = item.getAsJsonObject().getAsJsonObject("commonData");
            if (commonData != null && commonData.has("feature") && commonData.get("feature").getAsString().contains(yearStr)) {
                Logger.log("Found showid at youku: " + commonData.get("showId").getAsString());
                return commonData.get("showId").getAsString();
            }
        }
        return null;
    }

    private String getEpisodeUrl(String showId, int episode) throws IOException {
        String episodeUrl = "https://search.youku.com/api/search?appScene=show_episode&showIds=" + showId;
        String jsonResponse = sendGetRequest(episodeUrl);

        JsonObject response = GSON.fromJson(jsonResponse, JsonObject.class);
        JsonArray serisesList = response.getAsJsonArray("serisesList");
        if (serisesList == null) return null;

        for (JsonElement item : serisesList) {
            JsonObject series = item.getAsJsonObject();
            String showVideoStage = series.has("showVideoStage") ? series.get("showVideoStage").getAsString() : "";
            String displayName = series.has("displayName") ? series.get("displayName").getAsString() : "";

            if (extractNumber(showVideoStage) == episode || extractNumber(displayName) == episode) {
                if (series.has("url")) {
                    String url = series.get("url").getAsString();
                    if (url != null && !url.isEmpty()) {
                        Logger.log("Found url at youku: " + url.split("\\?")[0]);
                        return url.split("\\?")[0];
                    }
                }
                if (series.has("videoId")) {
                    String videoId = series.get("videoId").getAsString();
                    if (videoId != null && !videoId.isEmpty()) {
                        Logger.log("Found url at youku: " + "https://v.youku.com/v_show/id_" + videoId + ".html");
                        return "https://v.youku.com/v_show/id_" + videoId + ".html";
                    }
                }
            }
        }
        return null;
    }

    private int extractNumber(String input) {
        if (input == null) return -1;
        Matcher matcher = NUMBER_PATTERN.matcher(input);
        return matcher.find() ? Integer.parseInt(matcher.group()) : -1;
    }

    private List<List<Object>> fetchDanmaku(String episodeUrl) {
        List<String> apiEndpoints = Arrays.asList(
            "https://dmku.hls.one?ac=dm&url=",
            "https://api.danmu.icu/?ac=dm&url="
        );
        for (String endpoint : apiEndpoints) {
            try {
                List<List<Object>> data = fetchDanmakuFromUrl(endpoint + episodeUrl);
                if (data != null && !data.isEmpty()) return data;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private List<List<Object>> fetchDanmakuFromUrl(String danmakuUrl) {
        try {
            String rawResponse = sendGetRequest(danmakuUrl);
            JsonObject response = GSON.fromJson(rawResponse, JsonObject.class);
            int num = 0;
            if (response.has("danum")) num = response.get("danum").getAsInt();
            else if (response.has("danmu")) num = response.get("danmu").getAsInt();

            if (num <= 5) return null;
            JsonArray danmuku = response.getAsJsonArray("danmuku");
            return GSON.fromJson(danmuku, List.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String convertMode(String youkuMode) {
        if ("top".equals(youkuMode)) return "5";
        if ("bottom".equals(youkuMode)) return "4";
        return "1";
    }

    private String convertToBilibiliXML(List<List<Object>> danmakuData) {
        StringBuilder xmlBuilder = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<i>\n");
        for (List<Object> danmaku : danmakuData) {
            int size = danmaku.size();
            if (size != 5 && size != 8) continue;

            double time = ((Number) danmaku.get(0)).doubleValue();
            String mode = convertMode(danmaku.get(1).toString());
            String text = escapeXml(danmaku.get(4).toString());
            String fontSize = (size == 8) ? danmaku.get(7).toString().replace("px", "") : "24";
            
            // 颜色统一处理为十进制白色(16777215)或从数据解析
            int colorDecimal = 16777215; 

            String attrs = String.format("%.5f,%s,%s,%d,0,0,0,0,0", time, mode, fontSize, colorDecimal);
            xmlBuilder.append("  <d p=\"").append(attrs).append("\">").append(text).append("</d>\n");
        }
        return xmlBuilder.append("</i>").toString();
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                   .replace("\"", "&quot;").replace("'", "&apos;");
    }

    protected String sendGetRequest(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String generateMd5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "fakemd5";
        }
    }

    private static String getAllDanmakuXML(String title, int episode, int year) {
        String danmu = getBilibiliDanmakuXML(title, episode, year);
        if (danmu.isEmpty()) danmu = KanDanmuFetcher.getBilibiliDanmakuXML(title, episode, year);
        if (danmu.isEmpty()) danmu = IqiyiDanmuFetcher.getBilibiliDanmakuXML(title, episode, year);
        return danmu;
    }

    private static void pushDanmuBg(String title, int episode, int year) {
        String key = title + episode + year;
        String danmuPath = DANMU_ROOT + "/" + generateMd5(key) + ".txt";
        
        EXECUTOR.execute(() -> {
            try {
                Thread.sleep(100);
                String danmu = getAllDanmakuXML(title, episode, year);
                
                if (danmu.isEmpty()) {
                    // 如果是当前播放集或上一集且没获取到，1分钟后重试
                    String current = title + episode + year;
                    String prev = title + (episode - 1) + year;
                    if (recent.equals(current) || recent.equals(prev)) {
                        Thread.sleep(60000);
                        pushDanmuBg(title, episode, year);
                    }
                    return;
                }

                File file = new File(danmuPath);
                Path.write(file, danmu.getBytes(StandardCharsets.UTF_8));

                if (recent.equals(key)) {
                    String proxy = "http://127.0.0.1:9978/proxy?do=fs&file=" + danmuPath;
                    String action = "http://127.0.0.1:9978/action?do=refresh&type=danmaku&path=" + URLEncoder.encode(proxy, "UTF-8");
                    INSTANCE.sendGetRequest(action);
                }
            } catch (Exception e) {
                Logger.log("pushDanmuBg Error: " + e.getMessage());
            }
        });
    }

    private static void clearOldDanmu() {
        File directory = new File(DANMU_ROOT);
        if (!directory.exists() || !directory.isDirectory()) return;
        
        long cutoff = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000);
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".txt"));
        if (files != null) {
            for (File file : files) {
                if (file.lastModified() < cutoff) file.delete();
            }
        }
    }

    public static void test() {
        Logger.log(getBilibiliDanmakuXML("北上", 1, 2025));
    }
}

