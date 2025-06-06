package com.github.catvod.bean.alist;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import android.net.Uri;
import android.text.TextUtils;

import com.github.catvod.utils.Path;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.github.catvod.bean.Class;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Image;
import com.github.catvod.utils.Util;
import com.github.catvod.spider.Logger;

public class Drive {

    @SerializedName("drives")
    private List<Drive> drives;
    @SerializedName("params")
    private JSONObject params;
    @SerializedName("filters")
    private JSONObject filters;
    @SerializedName("login")
    private Login login;
    @SerializedName("vodPic")
    private String vodPic;
    @SerializedName("name")
    private String name;
    @SerializedName("server")
    private String server;
    @SerializedName("version")
    private int version;
    @SerializedName("startPage")
    private String path;
    @SerializedName("token")
    private String token;
    @SerializedName("search")
    private Boolean search;
    @SerializedName("hidden")
    private Boolean hidden;
    @SerializedName("noPoster")
    private Boolean noPoster;
    @SerializedName("pathByApi")
    private Boolean pathByApi;
    public HashMap<String, String> fl;

    private static class SignCache {
        String sign;
        Instant expirationTime;
    }
    
    private SignCache cache = null;
    private static final long CACHE_DURATION_SECONDS = 5;
    
    public String getSign() {
        if (cache != null && Instant.now().isBefore(cache.expirationTime)) {
            return cache.sign;
        }
        
        String newSign = this.exec("cat md5");

        if (newSign == null) {
            return "";
        }
        
        SignCache newCache = new SignCache();
        newCache.sign = newSign;
        newCache.expirationTime = Instant.now().plusSeconds(CACHE_DURATION_SECONDS);
        this.cache = newCache;
        
        return newSign;
    }
    

    public static Drive objectFrom(String str) {
        try {
            JSONObject json = new JSONObject(str);
            Gson gson = new Gson();
            Drive drive = gson.fromJson(str, Drive.class);
    
            if (json.has("drives")) {
                JSONArray drivesArray = json.getJSONArray("drives");
                for (int i = 0; i < drivesArray.length(); i++) {
                    JSONObject driveJson = drivesArray.getJSONObject(i);
                    if (driveJson.has("params")) {
                        if (drive.getDrives() != null && i < drive.getDrives().size()) {
                            drive.getDrives().get(i).params = driveJson.getJSONObject("params");
                        }
                    }
                    if (driveJson.has("filters")) {
                        if (drive.getDrives() != null && i < drive.getDrives().size()) {
                            drive.getDrives().get(i).filters = driveJson.getJSONObject("filters");
                        }
                    }
                }
            }
            return drive;
        } catch (JSONException e) {
            throw new JsonParseException("Failed to parse JSON: " + e.getMessage());
        }
    }

    public String exec(String cmd) {
        try {
            return OkHttp.post(getServer() + "/soutv", cmd, getHeader()).getBody();
        } catch (Exception e) {
            return "";
        }
    }

    public List<Drive> getDrives() {
        return drives == null ? new ArrayList<>() : drives;
    }

    public JSONObject getParams() {
        return params == null ? new JSONObject() : params;
    }

    public JSONObject getFilters() {
        return filters == null ? new JSONObject() : filters;
    }

    public JSONObject getParamByPath(String path) {
        //Logger.log("getParamByPath:" + path);
        if (params != null) {
            Logger.log(params);
            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = params.keys();
            while (iterator.hasNext()) {
                keys.add(iterator.next());
            }
            keys.sort(Comparator.comparingInt(String::length).reversed());
            for (String key : keys) {
                if (!path.startsWith(key)) {
                    continue;
                }
                try {
                    Object param = params.get(key);
                    Logger.log(param);
                    if (param instanceof JSONObject) {
                        return (JSONObject) param;
                    }
                } catch (Exception e) {
                    continue;
                }
            }
        }
        return new JSONObject();
    }

    public Login getLogin() {
        return login;
    }

    public Drive(String name) {
        this.name = name;
    }

    public String getVodPic() {
        return TextUtils.isEmpty(vodPic) ? Image.FOLDER : vodPic;
    }

    public String getPlaylistPic() {
        return Image.PLAYLIST;
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? "" : name;
    }

    public String getServer() {
        String r = TextUtils.isEmpty(server) ? "" : server;
        if (r.endsWith("/")) {
            r = r.substring(0, r.lastIndexOf("/"));
        }
        return r;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getPath() {
        return TextUtils.isEmpty(path) ? "" : path;
    }

    public void setPath(String path) {
        this.path = TextUtils.isEmpty(path) ? "" : path;
    }

    public String getToken() {
        token = TextUtils.isEmpty(token) ? "" : token;
        // if (token.isEmpty()) {
        //     String tokenPath = Path.files() + "/" + getServer().replace("://", "_").replace(":", "_") + ".token";
        //     File tokenFile = new File(tokenPath);
        //     token = Path.read(tokenFile);
        // }
        return token;
    }

    public void setToken(String token) {
        this.token = token;
        // if (token.isEmpty())
        //     return;
        
        // String tokenPath = Path.files() + "/" + getServer().replace("://", "_").replace(":", "_") + ".token";
        // File tokenFile = new File(tokenPath);
        // Path.write(tokenFile, token.getBytes());
    }

    public Boolean search() {
        return search != null && search;
    }

    public Boolean hidden() {
        return hidden != null && hidden;
    }

    public Boolean noPoster() {
        return noPoster != null && noPoster;
    }

    public Boolean pathByApi() {
        return pathByApi != null && pathByApi;
    }

    public boolean isNew() {
        return getVersion() == 3;
    }

    public Class toType() {
        if (this.noPoster()) {
            return new Class(getName(), getName(), "1");
        } else {
            return new Class(getName(), getName(), "2");
        }
    }

    public String getHost() {
        return getServer().replace(getPath(), "");
    }

    public String settingsApi() {
        return getHost() + "/api/public/settings";
    }

    public String loginApi() {
        return getHost() + "/api/auth/login";
    }

    public String listApi() {
        return getHost() + (isNew() ? "/api/fs/list" : "/api/public/path");
    }

    public String getApi() {
        return getHost() + (isNew() ? "/api/fs/get" : "/api/public/path");
    }

    public String searchApi() {
        return getHost() + (isNew() ? "/api/fs/search" : "/api/public/search");
    }

    public String searchApi(String param) {
        return getHost() + "/sou?box=" + param + "&url=&type=video";
    }

    public String dailySearchApi(String num) {
        return getHost() + "/sou?type=daily&filter=last&num=" + num;
    }

    public Drive check() {
        if (path == null)
            setPath(Uri.parse(getServer()).getPath());
        if (version == 0)
            //setVersion(OkHttp.string(settingsApi()).contains("v2.") ? 2 : 3);
            setVersion(3);
        return this;
    }

    public HashMap<String, String> getHeader() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Util.CHROME);
        if (!getToken().isEmpty())
            headers.put("Authorization", token);
        return headers;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof Drive))
            return false;
        Drive it = (Drive) obj;
        return getName().equals(it.getName());
    }
}
