package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.security.MessageDigest;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.DoubanParser;
import com.github.catvod.bean.Sub;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.alist.Drive;
import com.github.catvod.bean.alist.Item;
import com.github.catvod.bean.alist.Sorter;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import com.github.catvod.utils.Image;
import com.github.catvod.bean.alist.Pager;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import com.github.catvod.bean.alist.LoginDlg;
import com.github.catvod.utils.Path;
import java.io.File;
import com.github.catvod.bean.DanmuFetcher;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class AListSh extends Spider {
    private List<String> quickCach = new ArrayList<>();

    private static int thisYear = 2025;

    private List<Drive> drives;

    private Drive defaultDrive;

    private String vodPic;

    private String ext;

    private String xiaoyaAlistToken;

    private Map<String, Vod> vodMap = new HashMap<>();

    private Map<String, List<String>> driveLinesMap = new HashMap<>();

    private Map<String, Pager> drivePagerMap = new HashMap<>();

    private ExecutorService executor = Executors.newCachedThreadPool();

    private String jarVer = "%JARVER%";

    private WatchSync watchSync;

    private Context mContext;

    private String getRootCmd(Drive drive) {
        return drive.getCombinedMode() ? "{ cat index.combined.txt;echo ''; }" : "{ cat index.video.txt;echo ''; }";
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        this.mContext = context;
        try {
            ext = extend;
            fetchRule();
            // 注入 defaultDrive 供 Logger remote log 使用（配置 remoteLog=true 时打远端 log.txt）
            Logger.setDrive(defaultDrive);
            String check = defaultDrive.exec("echo ok;date +%Y");
            if (check.split("\n")[0].equals("ok")) {
                thisYear = Integer.parseInt(check.split("\n")[1]);
            }
        } catch (Exception e) {
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        fetchRule();
        List<Class> classes = new ArrayList<>();
        for (Drive drive : drives)
            if (!drive.hidden())
                classes.add(drive.toType());
        // for (Class item : classes)
        //     filters.put(item.getTypeId(), getFilter(item.getTypeId()));

        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        Map<String, Future<List<Filter>>> futureMap = new HashMap<>();
        for (Class item : classes) {
            final String typeId = item.getTypeId();
            Future<List<Filter>> future = executor.submit(() -> getFilter(typeId));
            futureMap.put(typeId, future);
        }
        for (Map.Entry<String, Future<List<Filter>>> entry : futureMap.entrySet()) {
            try {
                filters.put(entry.getKey(), entry.getValue().get());
            } catch (Exception e) {
            }
        }

        List<Vod> list = new ArrayList<>();
        if (defaultDrive != null) {
            List<String> lines = Arrays.asList(defaultDrive.exec("{ cat index.daily.txt;echo ''; } | tac | sed 's|^[.]/||' | grep -v -e '^$' -e '^[^/]*$' | head -n 500").split("\n"));
            list = toVods(defaultDrive, lines);
        }

        //处理合并列表，iso和非iso分别合并
        String initTest = defaultDrive.exec("[ -f index.combined.txt ] && grep '~~~~~~~~~~' index.video.txt");
        if (initTest.isEmpty()) {
            defaultDrive.exec("cp -f index.video.txt index.combined.txt");
            Thread thread = new Thread(() -> {
                defaultDrive.exec("awk -F'#' '$3==\"\" {print;next} tolower($0)~/iso#/{a[$3]=(a[$3]?$1\"~~~\"a[$3]:$0);next} {b[$3]=(b[$3]?$1\"~~~\"b[$3]:$0)} END{for(i in b)print b[i];for(i in a)print a[i]}' index.combined.txt > index.tmp.txt && mv -f index.tmp.txt index.combined.txt && echo '~~~~~~~~~~'>>index.video.txt"); 
            });
            thread.start();
        }

        String result = Result.string(classes, list, filters);
        return result;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        Logger.log(tid);
        String key = tid.contains("/") ? tid.substring(0, tid.indexOf("/")) : tid;
        Drive drive = getDrive(key);
        drive.fl = extend;
        if (drive.noPoster() && !isCombinedList(tid)) {
            return alistCategoryContent(tid, pg, filter, extend);
        } else {
            return xiaoyaCategoryContent(tid, pg, filter, extend);
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        Logger.log(id);

        //兼容老id格式
        id = id.replace("~soulist", "~xiaoya").replace("~soufile", "~xiaoya");
        ids.set(0, id);

        Boolean isFile = id.endsWith("~playlist") ? false : true;
        //String path = id.substring(id.indexOf("/"));
        if (id.endsWith("~xiaoya")) {
            String path = id.substring(0, id.lastIndexOf("/"));
            Logger.log("path is: " + path);
            if (path.contains(".iso~~~")) {
                isFile = true;
            } else {
                isFile = getList(fixPath(path), false).size() == 0 ? true : false;
                isFile = isFile && Util.isMedia(path);
            }
            Logger.log(isFile);
        }

        if (id.endsWith("~xiaoya") || id.endsWith("~playlist")) {
            if (isFile) {
                return fileDetailContent(ids);
            } else {
                return listDetailContent(ids);
            }
        }

        return defaultDetailContent(ids);
    }

    @Override
    public String searchContent(String keyword, boolean quick) throws Exception {
        if (quick) {
            List<String> lines = new ArrayList<>();
            synchronized (quickCach) {
                for (String s : quickCach) {
                    if (s.contains(String.format("#%s#", keyword))) {
                        lines.add(s);
                    }
                }
            }
            if (lines.size() == 0) {
                String cmd = getRootCmd(defaultDrive) + String.format(" | grep '#%s#' | sed 's|^[.]/||' | grep -v -e '^$' -e '^[^/]*$'", keyword);
                //还原合并列表
                cmd += "|awk -F'#' '{n=split($1,p,\"~~~\"); if(n>1 && tolower($1) !~ /iso$/){r=$0; sub(/^[^#]*#/,\"\",r); for(i=1;i<=n;i++) print p[i]\"#\"r} else {print $0}}'";
                lines = Arrays.asList(defaultDrive.exec(cmd).split("\n"));
            }
            List<Vod> list = toVods(defaultDrive, lines);
            String result = Result.get().vod(list).page().string();
            return result;
        } else {
            List<String> lines = new ArrayList<>();
            keyword = keyword.replace(" ", ".*");
            String cmd = getRootCmd(defaultDrive) + String.format(" | grep -i '%s' | sed 's|^[.]/||' | grep -v -e '^$' -e '^[^/]*$'", keyword);
            String defaultFilter = defaultDrive.defaultFilter();
            if (!defaultFilter.isEmpty()) {
                if (defaultFilter.startsWith("|")) {
                    cmd += defaultFilter;
                } else {
                    cmd += String.format(" | grep '%s'", defaultFilter);
                }
            }
            lines = Arrays.asList(defaultDrive.exec(cmd).split("\n"));
            List<Vod> list = toVods(defaultDrive, lines);
            String result = Result.get().vod(list).page().string();
            Logger.log("searchContent: " + result);
            return result;
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        Logger.log(flag);
        Logger.log(id);
        String[] ids = id.split("~~~"); 
        String key = ids[0].contains("/") ? ids[0].substring(0, ids[0].indexOf("/")) : ids[0];
        Drive drive = getDrive(key);
        String url = getDetail(ids[0]).getUrl();
        String result = Result.get().url(url).header(drive.getHeader()).subs(getSubs(ids)).string();
        if(ids[ids.length - 1].contains("danmu:")) {
            String[] danmuParams = ids[ids.length - 1].replace("danmu:", "").split(",");
            if (danmuParams.length == 3) {
                String danmuName = danmuParams[0];
                String danmuEp = danmuParams[1];
                String danmuYear = danmuParams[2];
                if (!danmuName.isEmpty() && !danmuEp.isEmpty() && !danmuYear.isEmpty()) {
                    DanmuFetcher.get().pushDanmu(danmuName, Integer.parseInt(danmuEp), Integer.parseInt(danmuYear));
                }
            }

        }
        Logger.log(result);
        return result;
    }

    private List<Filter> getFilter(String tid) {
        List<Filter> items = new ArrayList<>();
        Drive drive = getDrive(tid);

        if (drive.noPoster()) {
            items.add(new Filter("order", "排序：", Arrays.asList(
                new Filter.Value("默认排序", "def_def"),
                new Filter.Value("名字降序", "name_desc"),
                new Filter.Value("名字升序", "name_asc"),
                new Filter.Value("时间降序", "date_desc"),
                new Filter.Value("时间升序", "date_asc"))));
            return items;
        }

        List<String> keys = new ArrayList<>();
        JSONObject customFilters = drive.getFilters();
        Iterator<String> iterator = customFilters.keys();
        List<Filter.Value> customFilterValues = new ArrayList<>();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        for (String key : keys) {
            try {
                customFilterValues.add(new Filter.Value(key, customFilters.get(key).toString()));
            } catch (Exception e) {
                customFilterValues.clear();
            }
        }
        if (customFilterValues.size() > 0) {
            items.add(new Filter("custom", "自定义分类", customFilterValues));
        }

        List<Filter.Value> areaFilterValues = new ArrayList<>();
        List<String> areaList = Arrays.asList(
            "全部", "中国", "韩国", "日本", "美国", "欧美", "印度", "泰国");
        for (String s : areaList) {
            String v = s;
            if (s.equals("欧美")) {
                v = "(美国|英国|法国|德国|意大利|西班牙|瑞典|丹麦|爱尔兰|澳大利亚|巴西)";
            }
            areaFilterValues.add(new Filter.Value(s, v));
        }
        items.add(new Filter("area", "地区", areaFilterValues));

        List<Filter.Value> typeFilterValues = new ArrayList<>();
        List<String> typeList = Arrays.asList(
            "全部", "喜剧", "爱情", "动作", "科幻", "动画", "悬疑", "犯罪", "惊悚", 
            "冒险", "音乐", "历史", "奇幻", "恐怖", "战争", "传记", "歌舞", 
            "武侠", "灾难", "西部", "纪录片", "短片", "剧情", "家庭", 
            "儿童", "古装", "运动", "黑色电影");
        for (String s : typeList) {
            typeFilterValues.add(new Filter.Value(s, s));
        }
        items.add(new Filter("type", "类型", typeFilterValues));

        List<Filter.Value> yearFilterValues = new ArrayList<>();
        yearFilterValues.add(new Filter.Value("全部", "全部"));
        for (int i = thisYear; i > thisYear - 10; i--) {
            yearFilterValues.add(new Filter.Value(String.valueOf(i), String.valueOf(i)));
        }
        yearFilterValues.add(new Filter.Value(String.valueOf(thisYear - 10) + "及以前", String.valueOf(thisYear - 10) + "-"));
        items.add(new Filter("year", "年份", yearFilterValues));

        List<Filter.Value> values = new ArrayList<>();
        values.add(new Filter.Value("全部目录", "~all"));
        for (Item item : getList(tid, true)) {
            if (item.isFolder()) {
                String path = drive.getPath();
                String name = item.getName();
                String fullPath = path.endsWith("/") ? path + name : path + "/" + name;
                values.add(new Filter.Value(name, fullPath));
            }
        }
        if (values.size() > 0 && customFilterValues.size() == 0) {
            items.add(new Filter("subpath", "目录", values));
        }

        items.add(new Filter("douban", "豆瓣评分：", Arrays.asList(
                new Filter.Value("全部评分", "0"),
                new Filter.Value("9分以上", "9"),
                new Filter.Value("8分以上", "8"),
                new Filter.Value("7分以上", "7"),
                new Filter.Value("6分以上", "6"),
                new Filter.Value("5分以上", "5"))));

        items.add(new Filter("doubansort", "豆瓣排序：", Arrays.asList(
                new Filter.Value("原始顺序", "0"),
                new Filter.Value("评分\u2B07\uFE0F", "1"),
                new Filter.Value("评分\u2B06\uFE0F", "2"),
                new Filter.Value("年份\u2B07\uFE0F", "3"),
                new Filter.Value("年份\u2B06\uFE0F", "4"))));

        items.add(new Filter("random", "随机显示：", Arrays.asList(
                new Filter.Value("固定显示", "0"),
                new Filter.Value("随机显示️", "9999999"),
                new Filter.Value("随机200个️", "200"),
                new Filter.Value("随机500个️", "500"))));

        return items;
    }

    private void fetchRule() {
        if (drives != null && !drives.isEmpty())
            return;
        if (ext.startsWith("http"))
            ext = OkHttp.string(ext);
        String ext1 = "{\"drives\":" + ext + "}";
        JsonObject jsonObject = JsonParser.parseString(ext1).getAsJsonObject();
        JsonArray drives1 = jsonObject.getAsJsonArray("drives");
        JsonObject globalConfig = null;
        Iterator<JsonElement> iterator = drives1.iterator();
        while (iterator.hasNext()) {
            JsonElement element = iterator.next();
            if (element.isJsonObject()) {
                JsonObject item = element.getAsJsonObject();
                if (item.has("type") && "global".equals(item.get("type").getAsString())) {
                    globalConfig = item;
                    iterator.remove();
                    break;
                }
            }
        }

        if (globalConfig != null) {
            globalConfig.remove("type");
            for (Map.Entry<String, JsonElement> entry : globalConfig.entrySet()) {
                iterator = drives1.iterator();
                while (iterator.hasNext()) {
                    JsonElement element = iterator.next();
                    if (element.isJsonObject()) {
                        JsonObject item = element.getAsJsonObject();
                        if (!item.has(entry.getKey())) {
                            item.add(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
        }

        String result = new Gson().toJson(jsonObject);
        Logger.log(result);
        Drive drive = Drive.objectFrom(result);
        drives = drive.getDrives();
        vodPic = drive.getVodPic();

        List<Drive> searcherDrivers = new ArrayList<>();
        for (Drive d : drives) {
            if (d.search()) {
                searcherDrivers.add(d);
            }
        }
        if (searcherDrivers.size() > 0) {
            defaultDrive = searcherDrivers.get(0);
        } else {
            defaultDrive = drives.get(0);
        }

        DanmuFetcher.get().setDanmuApi(defaultDrive.getDanmuApi());

        //默认驱动要执行exec，需要提前登陆，简单规避
        getList(defaultDrive.getName() + defaultDrive.getPath(), false);

        //将配置中的用户名密码更新到本地文件
        for (Drive d : drives) {
            if (d.getLogin() == null) {
                continue;
            }
            String cUserName = d.getLogin().getUsername();
            String cPassword = d.getLogin().getPassword();
            if (cUserName.isEmpty() || cPassword.isEmpty()) {
                continue;
            }
            String loginPath = Path.files() + "/" + d.getServer().replace("://", "_").replace(":", "_") + ".login";
            File rLoginFile = new File(loginPath);
            File wLoginFile = new File(loginPath);
            String login = Path.read(rLoginFile);
            String fUserName = "";
            String fPassword = "";
            String[] parts = login.split("\n");
            if (parts.length >= 2) {
                fUserName = parts[0];
                fPassword = parts[1];
            } 
            if (!cUserName.equals(fUserName) || !cPassword.equals(fPassword)) {
                Path.write(wLoginFile, (cUserName + "\n" + cPassword).getBytes());
            }
        }
        
        // 观看记录多端同步：FileObserver 监视本地 DB + 30s 拉取 + 启动立即拉一次
        watchSync = WatchSync.start(mContext, defaultDrive);        
    }

    private Drive getDrive(String name) {
        return drives.get(drives.indexOf(new Drive(name))).check();
    }

    private String post(Drive drive, String url, String param) {
        return post(drive, url, param, true);
    }

    private String post(Drive drive, String url, String param, boolean retry) {
        String response = OkHttp.post(url, param, drive.getHeader()).getBody();
        int code = 200;
        try {
            code = new JSONObject(response).getInt("code");
        } catch (Exception e) {
            Logger.log("post" + e);
        }
        if (retry && (code == 401 || code == 403) && login(drive)) {
            return post(drive, url, param, false);
        }
        return response;
    }

    private synchronized boolean login(Drive drive) {
        boolean result = loginByConfig(drive) || loginByFile(drive) || loginByUser(drive);
        if (!result) {
            return false;
        }
        //即便登陆成功也要再次验证，比如guest登陆成功，但是结果还是401
        int code = 200;
        try {
            String path = "/";
            JSONObject params = drive.getParamByPath(path);
            params.put("path", path);
            String response = post(drive, drive.listApi(), params.toString(), false);
            code = new JSONObject(response).getInt("code");
        } catch (Exception e) {
        }
        if (code == 401 || code == 403) {
            String loginPath = Path.files() + "/" + drive.getServer().replace("://", "_").replace(":", "_") + ".login";
            File loginFile = new File(loginPath);
            Path.write(loginFile, "\n\n");
            return false;
        }

        //服务器相同则用户名密码相同，快速复制登陆结果到其它驱动（TBD：可能引入问题）
        if (!drive.getToken().isEmpty()) {
            for (Drive d : drives) {
                if(drive.getServer().equals(d.getServer())) {
                    d.setToken(drive.getToken());
                }
            }
        }
        return true;
    }

    private boolean loginByConfig(Drive drive) {
        try {
            if (drive.getLogin() == null) {
                return false;
            }
            JSONObject params = new JSONObject();
            String userName = drive.getLogin().getUsername();
            String password = drive.getLogin().getPassword();
            Logger.log("用户名:" + userName + "密码:" + password);
            userName = userName.isEmpty() ? "dav" : userName;
            password = password.isEmpty() ? "1234" : password;
            params.put("username", userName);
            params.put("password", password);
            if (password.startsWith("alist-")) {
                drive.setToken(password);
                return true;
            } 
            String response = OkHttp.post(drive.loginApi(), params.toString());
            drive.setToken(new JSONObject(response).getJSONObject("data").getString("token"));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean loginByUser(Drive drive) {
        try {
            JSONObject params = new JSONObject();
            String userName = LoginDlg.showLoginDlg("用户名(留空默认dav)");
            String password = LoginDlg.showLoginDlg("密码(留空默认1234，\"alist-\"打头会被识别为alist token)");
            Logger.log("用户名:" + userName + "密码:" + password);
            userName = userName.isEmpty() ? "dav" : userName;
            password = password.isEmpty() ? "1234" : password;
            String loginPath = Path.files() + "/" + drive.getServer().replace("://", "_").replace(":", "_") + ".login";
            File loginFile = new File(loginPath);
            Path.write(loginFile, (userName + "\n" + password).getBytes());
            params.put("username", userName);
            params.put("password", password);
            if (password.startsWith("alist-")) {
                drive.setToken(password);
                return true;
            } 
            String response = OkHttp.post(drive.loginApi(), params.toString());
            drive.setToken(new JSONObject(response).getJSONObject("data").getString("token"));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean loginByFile(Drive drive) {
        try {
            JSONObject params = new JSONObject();
            String loginPath = Path.files() + "/" + drive.getServer().replace("://", "_").replace(":", "_") + ".login";
            File loginFile = new File(loginPath);
            String login = Path.read(loginFile);
            String userName = "";
            String password = "";
            String[] parts = login.split("\n");
            if (parts.length >= 2) {
                userName = parts[0];
                password = parts[1];
            } 
            Logger.log("用户名:" + userName + "密码:" + password);
            if (userName.isEmpty() || password.isEmpty()) {
                return false;
            }
            params.put("username", userName);
            params.put("password", password);
            if (password.startsWith("alist-")) {
                drive.setToken(password);
                return true;
            } 
            String response = OkHttp.post(drive.loginApi(), params.toString());
            drive.setToken(new JSONObject(response).getJSONObject("data").getString("token"));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private String getSign(Drive drive) {
        try {
            String loginPath = Path.files() + "/" + drive.getServer().replace("://", "_").replace(":", "_") + ".login";
            File loginFile = new File(loginPath);
            String login = Path.read(loginFile) + "\n" + "\n";
            String input = login.split("\n")[1];
            if (input.isEmpty()) {
                return "";
            }
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(input.getBytes());
            byte[] digest = md.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private List<Item> getList(String id, boolean filter) {
        try {
            String key = id.contains("/") ? id.substring(0, id.indexOf("/")) : id;
            String path = id.contains("/") ? id.substring(id.indexOf("/")) : "";
            Drive drive = getDrive(key);
            path = path.startsWith(drive.getPath()) ? path : drive.getPath() + path;
            JSONObject params = drive.getParamByPath(path);
            params.put("path", path);
            String response = post(drive, drive.listApi(), params.toString());
            List<Item> items = Item.arrayFrom(getListJson(drive.isNew(), response));
            Iterator<Item> iterator = items.iterator();
            if (filter)
                while (iterator.hasNext())
                    if (iterator.next().ignore())
                        iterator.remove();
            return items;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String getListJson(boolean isNew, String response) throws Exception {
        if (isNew) {
            return new JSONObject(response).getJSONObject("data").getJSONArray("content").toString();
        } else {
            return new JSONObject(response).getJSONObject("data").getJSONArray("files").toString();
        }
    }

    private String getDetailJson(boolean isNew, String response) throws Exception {
        if (isNew) {
            return new JSONObject(response).getJSONObject("data").toString();
        } else {
            return new JSONObject(response).getJSONObject("data").getJSONArray("files").getJSONObject(0).toString();
        }
    }

    private Item getDetail(String id) {
        String key = id.contains("/") ? id.substring(0, id.indexOf("/")) : id;
        Drive drive = getDrive(key);
        Item item;
        if (drive.pathByApi()) {
            item = getDetailByApi(id);
        } else {
            item = getDetailBy302(id);
        }
        Logger.log(item);
        return item;
    }

    private Item getDetailBy302(String id) {
        try {
            String key = id.contains("/") ? id.substring(0, id.indexOf("/")) : id;
            String path = id.contains("/") ? id.substring(id.indexOf("/")) : "";
            Drive drive = getDrive(key);
            path = path.startsWith(drive.getPath()) ? path : drive.getPath() + path;
            //对路径中#的特殊处理
            path = path.replace("%23", "#");
            Item item = new Item();
            String sign = drive.getSign();
            if (sign.isEmpty()) {
                sign = getSign(drive);
            }
            String url = drive.getServer() + "/d" + URLEncoder.encode(path, "UTF-8").replace("+", "%20").replace("%2F", "/") + "?sign=" + sign;
            Logger.log(url);
            item.setUrl(url);
            return item;
        } catch (Exception e) {
            return new Item();
        }
    }

    private Item getDetailByApi(String id) {
        try {
            String key = id.contains("/") ? id.substring(0, id.indexOf("/")) : id;
            String path = id.contains("/") ? id.substring(id.indexOf("/")) : "";
            Drive drive = getDrive(key);
            path = path.startsWith(drive.getPath()) ? path : drive.getPath() + path;
            JSONObject params = drive.getParamByPath(path);
            params.put("path", path);
            String response = post(drive, drive.getApi(), params.toString());
            return Item.objectFrom(getDetailJson(drive.isNew(), response));
        } catch (Exception e) {
            return new Item();
        }
    }

    private String defaultDetailContent(List<String> ids) throws Exception {
        Logger.log(ids);
        fetchRule();
        String id = ids.get(0);
        String key = id.contains("/") ? id.substring(0, id.indexOf("/")) : id;
        String name = id.substring(id.lastIndexOf("/") + 1);
        Vod vod = new Vod();
        vod.setVodPlayFrom(key);
        vod.setVodId(id);
        //对路径中#的特殊处理
        name = name.replace("#", "%23");
        id = id.replace("#", "%23");
        vod.setVodName(name);
        vod.setVodPic(vodPic);
        vod.setVodPlayUrl(name + "$" + id);
        Logger.log(Result.string(vod));
        return Result.string(vod);
    }

    private String listDetailContent(List<String> ids) throws Exception {
        fetchRule();
        String id = ids.get(0);
        String key = id.contains("/") ? id.substring(0, id.indexOf("/")) : id;
        String path = id.substring(0, id.lastIndexOf("/"));
        String name = path.substring(path.lastIndexOf("/") + 1);
        Drive drive = getDrive(key);
        StringBuilder from = new StringBuilder();
        StringBuilder url = new StringBuilder();
        if (id.endsWith("~xiaoya")) {
            walkFolder(drive, fixPath(path), from, url, true);
        } else {
            walkFolder(drive, path, from, url, false);
        }
        Vod vod = null;
        if (id.endsWith("~xiaoya")) {
            vod = findVodByPath(drive, path.substring(path.indexOf("/") + 1));
        }
        if (vod == null) {
            vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(vodPic);
        }

        vod.setVodPlayFrom(from.toString());

        if (id.endsWith("~xiaoya") && !vod.doubanInfo.getId().isEmpty()) {
            vod.doubanInfo = DoubanParser.getDoubanInfo(vod.doubanInfo.getId(), vod.doubanInfo);
            vod.setVodContent(vod.doubanInfo.getPlot() + "\r\n\r\n文件路径: " + path.substring(path.indexOf("/") + 1));
            vod.setVodActor(vod.doubanInfo.getActors());
            vod.setVodDirector(vod.doubanInfo.getDirector());
            vod.setVodArea(vod.doubanInfo.getRegion());
            vod.setVodYear(vod.doubanInfo.getYear());
            vod.setVodRemarks(vod.doubanInfo.getRating());
            vod.setTypeName(vod.doubanInfo.getType());
        }

        String urlString = url.toString();
        Logger.log("urlString is" + urlString);
        if (id.endsWith("~xiaoya")) {
            urlString = urlString.replace("%NAME%", vod.doubanInfo.getName()).replace("%YEAR%", vod.doubanInfo.getYear());
        } else {
            urlString = urlString.replace("danmu:", "");
        }
        vod.setVodPlayUrl(urlString);

        String result = Result.get().vod(vod).vodDrive(drive.getName()).string();
        Logger.log(result);
        return result;
    }

    private String fileDetailContent(List<String> ids) throws Exception {
        fetchRule();
        String id = ids.get(0);
        String key = id.contains("/") ? id.substring(0, id.indexOf("/")) : id;
        String path = id.substring(0, id.lastIndexOf("/"));
        String name = path.substring(path.lastIndexOf("/") + 1);
        Drive drive = getDrive(key);
        Vod vod = null;
        if (id.endsWith("~xiaoya")) {
            vod = findVodByPath(drive, path.substring(path.indexOf("/") + 1));
        }
        if (vod == null) {
            vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(vodPic);
        }

        //对路径中#的特殊处理
        name = name.replace("#", "%23");
        path = path.replace("#", "%23");
        path = fixPath(path);

        vod.setVodPlayFrom(drive.getName());

        if (id.endsWith("~xiaoya") && !vod.doubanInfo.getId().isEmpty()) {
            vod.doubanInfo = DoubanParser.getDoubanInfo(vod.doubanInfo.getId(), vod.doubanInfo);
            vod.setVodContent(vod.doubanInfo.getPlot().isEmpty() ? "文件路径: \r\n" + path.substring(path.indexOf("/") + 1) : vod.doubanInfo.getPlot() + "\r\n\r\n文件路径: \r\n" + path.substring(path.indexOf("/") + 1));
            vod.setVodActor(vod.doubanInfo.getActors());
            vod.setVodDirector(vod.doubanInfo.getDirector());
            vod.setVodArea(vod.doubanInfo.getRegion());
            vod.setVodYear(vod.doubanInfo.getYear());
            vod.setVodRemarks(vod.doubanInfo.getRating());
            vod.setTypeName(vod.doubanInfo.getType());
        }

        if (id.endsWith("~xiaoya")) {
            if (path.contains("~~~")) {
                String filesPart = path.substring(path.indexOf("/") + 1);
                String[] splits = filesPart.split("~~~");
                List<String> paths = new ArrayList<>();
                List<String> playUrls = new ArrayList<>();
                List<String> displayPaths = new ArrayList<>();

                for (String s : splits) {
                    s = s.replaceAll("^\\./", "");
                    paths.add(s);
                }
                Sorter.sort("asc", paths);

                for (int i = 0; i < paths.size(); i++) {
                    displayPaths.add(String.format("%d: %s", i + 1, paths.get(i)));
                }

                int n = 0;
                for (String s : paths) {
                    String fileName = s.substring(s.lastIndexOf("/") + 1);

                    String fullPathForPlayer = key + "/" + s;

                    String doubanName = vod.doubanInfo.getName();
                    String doubanYear = vod.doubanInfo.getYear();

                    String formattedUrl = String.format("%d: %s$%s~~~danmu:%s,1,%s", 
                                            ++n, fileName, fullPathForPlayer, doubanName, doubanYear);
                    playUrls.add(formattedUrl);
                }

                String displayPlot = vod.doubanInfo.getPlot().isEmpty() ? "文件路径: \r\n" + TextUtils.join("\r\n", displayPaths) : vod.doubanInfo.getPlot() + "\r\n\r\n文件路径: \r\n" + TextUtils.join("\r\n", displayPaths);
                Logger.log("fileDetailContent displayPlot: " + displayPlot);
                vod.setVodContent(displayPlot);
                String fullUrl = TextUtils.join("#", playUrls);
                Logger.log("fileDetailContent Multi-Part Url: " + fullUrl);
                vod.setVodPlayUrl(fullUrl);

            } else {
                vod.setVodPlayUrl(name + "$" + path + String.format("~~~danmu:%s,1,%s", vod.doubanInfo.getName(), vod.doubanInfo.getYear()));
            }
        } else {
            vod.setVodPlayUrl(name + "$" + path);
        }

        String result = Result.get().vod(vod).vodDrive(drive.getName()).string();
        Logger.log("fileDetailContent: " + result);
        return result;
    }

    private void walkFolder(Drive drive, String path, StringBuilder from, StringBuilder url, Boolean recursive)
            throws Exception {
        List<Item> items = getList(path, false);
        String name = path.substring(path.lastIndexOf("/") + 1);

        String order = (drive.fl != null && drive.fl.containsKey("order")) ? drive.fl.get("order") : "";
        if (order.isEmpty()) {
            Sorter.sort("name", "asc", items);
        } else {
            String[] splits = order.split("_");
            Sorter.sort(splits[0], splits[1], items);
        }

        List<String> playUrls = new ArrayList<>();
        Boolean haveFile = false;
        int i = 1;
        for (Item item : items)
            if (item.isMedia()) {
                String displayName = item.getName();
                String playUrl = item.getVodId(path) + findSubs(path, items) + "~~~" + String.format("danmu:%%NAME%%,%d,%%YEAR%%", i++);
                //对路径中#的特殊处理
                displayName = displayName.replace("#", "%23");
                playUrl = playUrl.replace("#", "%23");
                playUrls.add(displayName + "$" + playUrl);
                haveFile = true;
            }
        if (haveFile) {
            url.append("$$$" + TextUtils.join("#", playUrls));
            from.append("$$$" + name);
        }
        if (recursive) {
            for (Item item : items)
                if (item.isFolder()) {
                    walkFolder(drive, item.getVodId(path), from, url, recursive);
                }
        }
        if (url.indexOf("$$$") == 0) {
            url.delete(0, 3);
            from.delete(0, 3);
        }
    }

    private String fixPath(String path) {
        try {
            path = path.replace("+", "%2B");
            return URLDecoder.decode(path, "UTF-8");
        } catch (Exception e) {
            return path;
        }
    }

    private synchronized String xiaoyaCategoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        Logger.log("xiaoyaCategoryContent: " + tid);
        String result = "";
        fetchRule();
        String key = tid.contains("/") ? tid.substring(0, tid.indexOf("/")) : tid;
        Drive drive = getDrive(key);
        HashMap<String, String> fl = extend;
        drive.fl = fl;

        //合并列表
        if (isCombinedList(tid)) {
            int slashIndex = tid.indexOf("/");
            String combinedPaths = (slashIndex != -1) ? tid.substring(slashIndex + 1) : tid;
            String[] splits = combinedPaths.split("~~~");
            List<String> pathList = new ArrayList<>();
            for (String s : splits) {
                pathList.add(s.replace("./", "").replace("/~xiaoya", ""));
            }
            if (pathList.isEmpty()) return Result.get().string();
            Vod baseVod = findVodByPath(drive, pathList.get(0));
            List<Vod> vodList = toVods(drive, pathList);
            if (baseVod !=null) {
                for (Vod v : vodList) {
                    v.doubanInfo = baseVod.doubanInfo;
                    v.setVodPic(baseVod.getVodPic());
                    v.setStyle(Vod.Style.list());
                }
            }
            result = Result.get().vod(vodList).page().string();
            Logger.log("xiaoyaCategoryContent, Combined Result: " + result);
            return result;
        }

        String cmd;
        if (drive.getName().equals("每日更新")) {
            cmd = "{ cat index.daily.txt;echo ''; } | tac | grep -v -e '^$' -e '^[^/]*$'";
        } else {
            cmd = getRootCmd(drive) + " | grep -v -e '^$' -e '^[^/]*$'";
        }
        String subpath = fl.get("subpath");
        if (subpath != null && !subpath.equals("~all")) {
            cmd +=  String.format(" | grep '^[.]%s'", subpath);
        } else {
            cmd +=  String.format(" | grep '^[.]%s'", drive.getPath());
        }

        String defaultFilter = drive.defaultFilter();
        if (!defaultFilter.isEmpty()) {
            if (defaultFilter.startsWith("|")) {
                cmd += defaultFilter;
            } else {
                cmd += String.format(" | grep '%s'", defaultFilter);
            }
        }

        String custom = fl.get("custom");
        if (custom != null) {
            if (custom.startsWith("|")) {
                cmd += custom;
            } else {
                cmd += String.format(" | grep '%s'", custom);
            }
        }

        String type = fl.get("type");
        if (type != null && !type.equals("全部")) {
            cmd +=  String.format(" | grep '%s'", type);
        }

        String area = fl.get("area");
        if (area != null && !area.equals("全部")) {
            cmd +=  String.format(" | grep '%s'", area);
        }

        String year = fl.get("year");
        if (year != null && !year.equals("全部") && !year.contains("-")) {
            cmd +=  String.format(" | grep '#%s#'", year);
        }
        if (year != null && year.contains("-")) {
            cmd +=  String.format(" | awk -F '#' '$6 <= %s'", year.split("-")[0]);
        }

        String douban = fl.get("douban");
        if (douban != null && !douban.equals("0")) {
            cmd +=  String.format(" | awk -F '#' '$4 >= %s'", douban);
        }

        String totalCmd = cmd + " | grep -n '' | tail -n 1 | cut -d ':' -f 1";

        cmd = String.format("{ %s | grep douban; %s | grep -v douban; }", cmd, cmd);

        boolean keepOrder = false;
        String doubansort = fl.get("doubansort");
        if (doubansort != null && doubansort.equals("1")) {
            cmd +=  String.format(" | awk -F '#' '{print $4,$0}' | sort -r | cut -d ' ' -f 2-");
            keepOrder = true;
        }
        if (doubansort != null && doubansort.equals("2")) {
            cmd +=  String.format(" | awk -F '#' '{print $4,$0}' | sort | cut -d ' ' -f 2-");
            keepOrder = true;
        }
        if (doubansort != null && doubansort.equals("3")) {
            cmd +=  String.format(" | awk -F '#' '{n = ($6 ~ /^[0-9]{4}$/ && $6 <= %d) ? $6 : \"0000\"; print n,$0}' | sort -r | cut -d ' ' -f 2-", thisYear);            
            keepOrder = true;
        }
        if (doubansort != null && doubansort.equals("4")) {
            cmd +=  String.format(" | awk -F '#' '{n = ($6 ~ /^[0-9]{4}$/ && $6 <= %d) ? $6 : \"9999\"; print n,$0}' | sort | cut -d ' ' -f 2-", thisYear);
            keepOrder = true;
        }

        int randomNum = 0;
        String random = fl.get("random");
        if (random != null && !random.equals("0")) {
            randomNum = Integer.parseInt(random);
        } else {
            randomNum = 0;
        }
        Pager pager = drivePagerMap.get(drive.getName());
        if (pager == null || pg.equals("1")) {
            int total = Integer.parseInt(drive.exec(totalCmd).split("\n")[0]);
            pager = new Pager(drive, cmd, total, randomNum, keepOrder);
            drivePagerMap.put(drive.getName(), pager);
        }
        List<String> lines = pager.page(Integer.parseInt(pg));
        List<Vod> list = toVods(drive, lines);
        result = Result.get().vod(list).page(Integer.parseInt(pg), pager.total, pager.limit, pager.count).string();
        return result;
    }

    private String alistCategoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        Logger.log(tid);
        fetchRule();
        String order = extend.containsKey("order") ? extend.get("order") : "";
        List<Item> folders = new ArrayList<>();
        List<Item> files = new ArrayList<>();
        List<Vod> list = new ArrayList<>();
        String key = tid.contains("/") ? tid.substring(0, tid.indexOf("/")) : tid;
        Drive drive = getDrive(key);

        for (Item item : getList(tid, true)) {
            if (item.isFolder()) {
                if (!item.getName().equals("元数据")) {
                    folders.add(item);
                }
                if (item.getName().contains("©️")) {
                    Item jarVerItem = item.clone();
                    jarVerItem.setName("©️ " + jarVer);
                    folders.add(jarVerItem);
                }
            } else {
                files.add(item);
            }

        }
        if (!TextUtils.isEmpty(order)) {
            String splits[] = order.split("_");
            Sorter.sort(splits[0], splits[1], folders);
            Sorter.sort(splits[0], splits[1], files);
        }

        Vod playlistVod = null;
        if (files.size() > 0) {
            String remark = String.format("共 %d 集", files.size());
            playlistVod = new Vod(tid + "/~playlist", "播放列表", "", remark, false);
            playlistVod.setVodPic(drive.getPlaylistPic());

            list.add(playlistVod);
        }

        for (Item item : folders) {
            Vod vod = item.getVod(tid, vodPic);
            vod.setVodRemarks(item.getModified().split("T")[0] + "\t文件夹");
            list.add(vod);
        }

        for (Item item : files) {
            Vod vod = item.getVod(tid, vodPic);
            vod.setVodRemarks(item.getModified().split("T")[0] + "\t" + getSize(item.getSize()));
            list.add(vod);
        }

        String result = Result.get().vod(list).page().string();
        //Logger.log(result);
        return result;
    }

    private Vod findVodByPath(Drive drive, String path) {
        String cmd = getRootCmd(defaultDrive) + String.format(" | grep -F '%s' | sed 's|^[.]/||'", path.replace("'", "'\\''"));
        List<String> lines = Arrays.asList(defaultDrive.exec(cmd).split("\n"));
        List<String> match = new ArrayList<>();
        for (String line : lines) {
            String s = line.split("#")[0];
            if (s.endsWith("/")) {
                s = s.substring(0, s.lastIndexOf("/"));
            }
            if (s.contains(path)) {
                match.add(line);
                break;
            }
        }
        if (match.size() == 0) {
            return null;
        }
        Vod vod = toVods(drive, match).get(0);
        Thread thread = new Thread(() -> {
            synchronized (quickCach) {
                for (String l : quickCach) {
                    if (l.contains(String.format("#%s#", vod.getVodName()))) {
                        return;
                    }
                }
                quickCach.clear();
                String cmd1 = getRootCmd(defaultDrive) + String.format(" | grep -F '#%s#' | sed 's|^[.]/||' | grep -v -e '^$' -e '^[^/]*$'", vod.getVodName());
                //还原合并列表
                cmd1 += "|awk -F'#' '{n=split($1,p,\"~~~\"); if(n>1 && tolower($1) !~ /iso$/){r=$0; sub(/^[^#]*#/,\"\",r); for(i=1;i<=n;i++) print p[i]\"#\"r} else {print $0}}'";
                List<String> tmpLines = Arrays.asList(defaultDrive.exec(cmd1).split("\n"));
                quickCach.addAll(tmpLines);
            }   
        });
        thread.start();
        return vod;
    }

    private List<Vod> toVods(Drive drive, List<String> lines) {
        Logger.log("toVods() converting " + lines.size() + " lines");
        long startTime = System.currentTimeMillis();
        try {
            List<Vod> list = new ArrayList<>();
            List<Vod> noPicList = new ArrayList<>();
            for (String line : lines) {
                String[] splits = line.split("#");
                //splits[0] = URLDecoder.decode(splits[0], "UTF-8");
                int index = splits[0].lastIndexOf("/");
                if (splits[0].endsWith("/")) {
                    splits[0] = splits[0].substring(0, index);
                    index = splits[0].lastIndexOf("/");
                }
                Item item = new Item();
                //合并列表
                if (isCombinedList(line)){
                    item.setType(1);
                } else {
                    item.setType(0);
                }
                item.doubanInfo.setId(splits.length >= 3 ? splits[2] : "");
                item.doubanInfo.setRating(splits.length >= 4 ? splits[3] : "");
                item.doubanInfo.setYear(splits.length >= 6 ? splits[5] : "");
                item.doubanInfo.setRegion(splits.length >= 7 ? splits[6] : "");
                item.doubanInfo.setType(splits.length >= 8 ? splits[7] : "");
                item.setThumb(splits.length >= 5 ? splits[4] : "");
                item.setPath("/" + splits[0].substring(0, index));
                String fileName = splits[0].substring(index + 1);
                item.setName(fileName);
                item.doubanInfo.setName(splits.length >= 2 ? splits[1] : fileName);
                Vod vod = item.getVod(drive.getName(), drive.getVodPic());
                vod.setVodRemarks(item.doubanInfo.getRating() + calcFlag(line));
                vod.setVodName(item.doubanInfo.getName());
                vod.setVodYear(item.doubanInfo.getYear());
                vod.doubanInfo = item.doubanInfo;
                vod.setVodId(vod.getVodId() + "/~xiaoya");
                if (TextUtils.isEmpty(item.getThumb())) {
                    vod.setVodPic(Image.XIAOYA);
                    noPicList.add(vod);
                } else {
                    String picHeader = "@Referer=https://api.douban.com/@User-Agent=Mozilla/5.0%20(Windows%20NT%2010.0;%20Win64;%20x64)%20AppleWebKit/537.36%20(KHTML,%20like%20Gecko)%20Chrome/113.0.0.0%20Safari/537.36";
                    vod.setVodPic(vod.getVodPic() + picHeader);
                    list.add(vod);
                }
            }
            list.addAll(noPicList);
            return list;
        } catch (Throwable e) {
            Logger.log("toVods() error: " + e.toString());
            return new ArrayList<>();
        } finally {
            Logger.log("toVods() completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private String calcFlag(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }

        String out = "";
        String lowerLine = line.toLowerCase();
        int index = lowerLine.indexOf('#');
        if (index != -1) {
            lowerLine = lowerLine.substring(0, index);
        }

        if (lowerLine.contains("115")) {
            out += "/115";
        }
        if (lowerLine.contains("套娃")) {
            out += "/套娃";
        }
        if (lowerLine.contains("pikpak")) {
            out += "/pikpak";
        }
        if (lowerLine.contains("夸克")) {
            out += "/夸克";
        }
        if (lowerLine.endsWith(".iso")) {
            out += "/ISO";
        }

        if (!out.isEmpty()) {
            out = out.substring(1); 
            out = " (" + out + ")";
        }
        return out;
    }

    private boolean isCombinedList(String path) {
        return path.contains("~~~") && !path.toLowerCase().contains(".iso");
    }

    private String getSize(long sz) {
        if (sz <= 0) {
            return "";
        }

        String filesize;
        double size;
        if (sz > 1024L * 1024 * 1024 * 1024) {
            size = sz / (1024.0 * 1024 * 1024 * 1024);
            filesize = "TB";
        } else if (sz > 1024L * 1024 * 1024) {
            size = sz / (1024.0 * 1024 * 1024);
            filesize = "GB";
        } else if (sz > 1024L * 1024) {
            size = sz / (1024.0 * 1024);
            filesize = "MB";
        } else if (sz > 1024) {
            size = sz / 1024.0;
            filesize = "KB";
        } else {
            size = sz;
            filesize = "B";
        }

        // 格式化输出，保留两位小数
        return String.format("%.2f %s", size, filesize);
    }

    private String findSubs(String path, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item item : items)
            if (Util.isSub(item.getExt()))
                sb.append("~~~").append(item.getName()).append("@@@").append(item.getExt()).append("@@@")
                        .append(item.getVodId(path));
        return sb.toString();
    }

    private List<Sub> getSubs(String[] ids) {
        List<Sub> allSubs = new ArrayList<>();
        if (ids == null || ids.length == 0) return allSubs;

        String movieId = ids[0];
        Sub bestSub = null;      // 记录当前找到的最优 Sub
        double maxSim = -1.0;    // 记录最高相似度
        boolean bestHasChs = false; // 记录最优项是否包含 CHS

        for (String text : ids) {
            if (text == null || !text.contains("@@@")) continue;

            String[] split = text.split("@@@");
            if (split.length < 3) continue;

            String name = split[0];
            String ext = split[1];
            String url = getDetail(split[2]).getUrl();

            // 1. 计算当前项的相似度和 CHS 状态
            double currentSim = similarity(movieId, url);
            boolean currentHasChs = name.toLowerCase().contains("chs");

            // 2. 创建 Sub 对象并加入临时总表
            Sub currentSub = Sub.create().name(name).ext(ext).url(url);
            allSubs.add(currentSub);

            // 3. 比较并更新“最优项”
            boolean isBetter = false;
            if (bestSub == null) {
                isBetter = true;
            } else {
                if (currentSim > maxSim) {
                    // 相似度更高，直接胜出
                    isBetter = true;
                } else if (currentSim == maxSim) {
                    // 相似度相同，看是否包含 chs
                    if (currentHasChs && !bestHasChs) {
                        isBetter = true;
                    }
                }
            }

            if (isBetter) {
                maxSim = currentSim;
                bestHasChs = currentHasChs;
                bestSub = currentSub;
            }
        }

        // 4. 重新组装结果列表
        List<Sub> result = new ArrayList<>();
        if (bestSub != null) {
            // 先放最优的那个
            result.add(bestSub);

            // 再放其他的，且保持在 ids 中的原始顺序
            for (Sub s : allSubs) {
                if (s != bestSub) { // 通过引用判断，剔除掉那个已经放进首位的最优项
                    result.add(s);
                }
            }
        }

        return result;
    }

    private double similarity(String sourceStr, String targetStr) {
        // 空指针检查
        if (sourceStr == null || targetStr == null) {
            return 0.0;
        }

        int sourceLen = sourceStr.length();
        int targetLen = targetStr.length();

        // 如果其中一个字符串长度为 0，按照原 JS 逻辑返回 0
        if (sourceLen == 0 || targetLen == 0) {
            return 0.0;
        }

        // 定义 DP 数组
        int[][] arr = new int[sourceLen + 1][targetLen + 1];

        // 初始化第一列
        for (int i = 0; i <= sourceLen; i++) {
            arr[i][0] = i;
        }

        // 初始化第一行
        for (int j = 0; j <= targetLen; j++) {
            arr[0][j] = j;
        }

        char sourceChar;
        char targetChar;

        // 填充矩阵
        for (int i = 1; i <= sourceLen; i++) {
            sourceChar = sourceStr.charAt(i - 1);
            for (int j = 1; j <= targetLen; j++) {
                targetChar = targetStr.charAt(j - 1);

                if (sourceChar == targetChar) {
                    arr[i][j] = arr[i - 1][j - 1];
                } else {
                    // Java 的 Math.min 只能比较两个数，所以需要嵌套
                    int min = Math.min(arr[i - 1][j], arr[i][j - 1]);
                    arr[i][j] = Math.min(min, arr[i - 1][j - 1]) + 1;
                }
            }
        }

        // 计算最终相似度
        // 注意：Java 中两个整数相除会丢失精度，所以需要强转为 double
        return 1.0 - (double) arr[sourceLen][targetLen] / Math.max(sourceLen, targetLen);
    }

}
