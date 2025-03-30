package com.github.catvod.spider;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import java.net.URLEncoder;

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
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import com.github.catvod.utils.Notify;
import com.github.catvod.bean.alist.FileBasedList;
import com.github.catvod.bean.alist.LocalIndexService;
import com.github.catvod.bean.alist.Pager;
import com.github.catvod.bean.alist.IndexDownloader;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.WeakHashMap;
import static java.util.AbstractMap.SimpleEntry;
import java.util.AbstractMap;
import java.util.concurrent.TimeoutException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import com.github.catvod.bean.alist.LoginDlg;
import android.widget.Toast;
import com.github.catvod.utils.Path;
import java.io.File;
import com.github.catvod.bean.DanmuFetcher;

public class AList extends Spider {

    private List<Drive> drives;
    private Drive defaultDrive;
    private String vodPic;
    private String ext;
    private String xiaoyaAlistToken;
    private Map<String, Vod> vodMap = new HashMap<>();
    private Map<String, List<String>> driveLinesMap = new HashMap<>();
    private Map<String, Pager> drivePagerMap = new HashMap<>();
    private ExecutorService executor = Executors.newCachedThreadPool();

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

        List<Filter.Value> values = new ArrayList<>();
        values.add(new Filter.Value("全部分类", "~all"));
        for (Item item : getList(tid, true)) {
            if (item.isFolder())
                values.add(new Filter.Value(item.getName(), drive.getPath() + "/" + item.getName()));
        }
        if (values.size() > 0) {
            items.add(new Filter("subpath", "分类", values));
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
                new Filter.Value("豆瓣评分\u2B07\uFE0F", "1"),
                new Filter.Value("豆瓣评分\u2B06\uFE0F", "2"))));

        items.add(new Filter("random", "随机显示：", Arrays.asList(
                new Filter.Value("固定显示", "0"),
                new Filter.Value("随机显示️", "9999999"),
                new Filter.Value("随机200个️", "200"),
                new Filter.Value("随机500个️", "500"))));

        return items;
    }

    // 临时方案
    private String getXiaoyaAlistToken() {

        if (xiaoyaAlistToken != null) {
            return xiaoyaAlistToken;
        }

        String url = defaultDrive.getServer() + "/tvbox/libs/alist.min.js";

        String regex = "'\\s*Authorization\\s*':\\s*'([^']*)'";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(OkHttp.string(url));

        // 查找并提取目标部分
        if (matcher.find()) {
            String token = matcher.group(1); // 获取捕获组的内容
            xiaoyaAlistToken = token;
        } else {
            xiaoyaAlistToken = "";
        }
        //*Logger.log("token:" + xiaoyaAlistToken);
        return xiaoyaAlistToken;
    }

    private void fetchRule() {
        if (drives != null && !drives.isEmpty())
            return;
        Logger.log("fetchRule");
        if (ext.startsWith("http"))
            ext = OkHttp.string(ext);
        Logger.log(ext);
        String ext1 = "{\"drives\":" + ext + "}";
        Drive drive = Drive.objectFrom(ext1);
        drives = drive.getDrives();
        vodPic = drive.getVodPic();

        List<Drive> searcherDrivers = drives.stream().filter(d -> d.search()).collect(Collectors.toList());
        if (searcherDrivers.size() > 0) {
            defaultDrive = searcherDrivers.get(0);
        }
    }

    private Drive getDrive(String name) {
        return drives.get(drives.indexOf(new Drive(name))).check();
    }

    private String post(Drive drive, String url, String param) {
        return post(drive, url, param, true);
    }

    private String post(Drive drive, String url, String param, boolean retry) {
        String response = OkHttp.post(url, param, drive.getHeader()).getBody();
        SpiderDebug.log(response);
        // if (retry && (response.contains("Guest user is disabled") || response.contains("token is invalidated") || 
        //     response.contains("without permission") || response.contains("token is expired")) && (loginByFile(drive) || loginByUser(drive)))
        //     return post(drive, url, param, false);
        int code = 200;
        try {
            code = new JSONObject(response).getInt("code");
        } catch (Exception e) {
            //*Logger.log(e);
        }
        if (retry && code == 401 && (loginByFile(drive) || loginByUser(drive))) {
            return post(drive, url, param, false);
        }
        return response;
    }

    @Override
    public void init(Context context, String extend) {
        try {
            ext = extend;
            fetchRule();
            FileBasedList.clearCacheDirectory();
            IndexDownloader.clearCacheDirectory();
        } catch (Exception ignored) {
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        fetchRule();
        List<Class> classes = new ArrayList<>();
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        for (Drive drive : drives)
            if (!drive.hidden())
                classes.add(drive.toType());
        for (Class item : classes)
            filters.put(item.getTypeId(), getFilter(item.getTypeId()));

        List<Vod> list = new ArrayList<>();
        if (defaultDrive != null) {
            List<String> lines = (new Job(defaultDrive.check(), "~daily:1000")).call();
            list = LocalIndexService.toVods(defaultDrive, lines);
        }

        String result = Result.string(classes, list, filters);
        //*Logger.log(result);

        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
            }
            Notify.show("开始构建本地索引，需要数秒");
            for (Drive d : drives) {
                if (d.search()) {
                   LocalIndexService.get(d).slim(d.getPath());
                }
            }
            // LocalIndexService.get(defaultDrive);
            Notify.show("构建本地索引完成");
        });
        thread.start();
        
        return result;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        //*Logger.log(tid);
        String key = tid.contains("/") ? tid.substring(0, tid.indexOf("/")) : tid;
        Drive drive = getDrive(key);
        drive.fl = extend;
        if (drive.noPoster()) {
            return alistCategoryContent(tid, pg, filter, extend);
        } else {
            return xiaoyaCategoryContent(tid, pg, filter, extend);
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        //*Logger.log(id);

        //兼容老id格式
        id = id.replace("~soulist", "~xiaoya").replace("~soufile", "~xiaoya");
        ids.set(0, id);
        
        Boolean isFile = id.endsWith("~playlist") ? false : true;
        String path = id.substring(id.indexOf("/"));
        if (id.endsWith("~xiaoya")) {
            //path = path.substring(0, path.lastIndexOf("/"));
            isFile = getList(path, false).size() == 0 ? true : false;
            isFile = isFile && Util.isMedia(path);
        }

        if (id.endsWith("~xiaoya") || id.endsWith("~playlist")) {
            if (isFile) {
                return fileDetailContent(ids);
            } else {
                return listDetailContent(ids);
            }
        }

        // if (id.endsWith("~soulist") || id.endsWith("~playlist")) {
        //     return listDetailContent(ids);
        // }
        // if (id.endsWith("~soufile")) {
        //     return fileDetailContent(ids);
        // }

        return defaultDetailContent(ids);
    }

    @Override
    public String searchContent(String keyword, boolean quick) throws Exception {
        fetchRule();
        //*Logger.log(keyword);
        //*Logger.log(quick);
        List<Vod> list = new ArrayList<>();
        List<AbstractMap.SimpleEntry<Future<List<String>>, String>> futuresWithDrives = new ArrayList<>();

        for (Drive drive : drives) {
            if (drive.search()) {
                Future<List<String>> future;
                if (quick) {
                    future = executor.submit(new Job(drive.check(), "~quick:" + keyword));
                } else {
                    future = executor.submit(new Job(drive.check(), "~search:" + keyword));
                }
                futuresWithDrives.add(new AbstractMap.SimpleEntry<>(future, drive.getName()));
            }
        }

        // 处理每个Future的结果，并为每个Vod设置正确的vodDrive
        for (AbstractMap.SimpleEntry<Future<List<String>>, String> entry : futuresWithDrives) {
            Future<List<String>> future = entry.getKey();
            String driveName = entry.getValue();
            try {
                List<String> tmpLines = future.get(15, TimeUnit.SECONDS);
                Drive tmpDrive = drives.get(drives.indexOf(new Drive(driveName))).check();
                list.addAll(LocalIndexService.toVods(tmpDrive, tmpLines));
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                e.printStackTrace();
            }
        }

        String result = Result.get().vod(list).page().string();
        //*Logger.log(result);
        return result;
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        //*Logger.log(flag);
        //*Logger.log(id);
        String[] ids = id.split("~~~"); 
        String key = ids[0].contains("/") ? ids[0].substring(0, ids[0].indexOf("/")) : ids[0];
        Drive drive = getDrive(key);
        String url = getDetail(ids[0]).getUrl();
        String result = Result.get().url(url).header(drive.getHeader()).subs(getSubs(ids)).string();
        // String result =
        // Result.get().url(url).header(getPlayHeader(url)).subs(getSubs(ids)).string();
        if(ids[ids.length - 1].contains("danmu:")) {
            String[] danmuParams = ids[ids.length - 1].replace("danmu:", "").split(",");
            if (danmuParams.length == 3) {
                String danmuName = danmuParams[0];
                String danmuEp = danmuParams[1];
                String danmuYear = danmuParams[2];
                if (!danmuName.isEmpty() && !danmuEp.isEmpty() && !danmuYear.isEmpty()) {
                    DanmuFetcher.pushDanmu(danmuName, Integer.parseInt(danmuEp), Integer.parseInt(danmuYear));
                }
            }

        }
        //*Logger.log(result);
        return result;
    }

    private String defaultDetailContent(List<String> ids) throws Exception {
        //*Logger.log(ids);
        fetchRule();
        String id = ids.get(0);
        String key = id.contains("/") ? id.substring(0, id.indexOf("/")) : id;
        String name = id.substring(id.lastIndexOf("/") + 1);
        Vod vod = new Vod();
        vod.setVodPlayFrom(key);
        vod.setVodId(id);
        vod.setVodName(name);
        vod.setVodPic(vodPic);
        vod.setVodPlayUrl(name + "$" + id);
        //*Logger.log(Result.string(vod));
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
        //if (id.endsWith("~soulist")) {
        if (id.endsWith("~xiaoya")) {
            walkFolder(drive, path, from, url, true);
        } else {
            walkFolder(drive, path, from, url, false);
        }
        Vod vod = null;
        if (id.endsWith("~xiaoya")) {
            vod = LocalIndexService.get(drive).findVodByPath(drive, path.substring(path.indexOf("/") + 1));
        }
        if (vod == null) {
            vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(vodPic);
        }

        vod.setVodPlayFrom(from.toString());

        //if (id.endsWith("~soulist") && vod.doubanInfo.getYear().isEmpty() && !vod.doubanInfo.getId().isEmpty()) {
        if (id.endsWith("~xiaoya") && vod.doubanInfo.getYear().isEmpty() && !vod.doubanInfo.getId().isEmpty()) {
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
        //*Logger.log("urlString is" + urlString);
        if (id.endsWith("~xiaoya")) {
            urlString = urlString.replace("%NAME%", vod.doubanInfo.getName()).replace("%YEAR%", vod.doubanInfo.getYear());
        } else {
            urlString = urlString.replace("danmu:", "");
        }
        vod.setVodPlayUrl(urlString);

        String result = Result.get().vod(vod).vodDrive(drive.getName()).string();
        //*Logger.log(result);
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
            vod = LocalIndexService.get(drive).findVodByPath(drive, path.substring(path.indexOf("/") + 1));
        }
        if (vod == null) {
            vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(vodPic);
        }
        vod.setVodPlayFrom(drive.getName());
        if (id.endsWith("~xiaoya")) {
            vod.setVodPlayUrl(name + "$" + path + String.format("~~~danmu:%s,1,%s", vod.doubanInfo.getName(), vod.doubanInfo.getYear()));
        } else {
            vod.setVodPlayUrl(name + "$" + path);
        }
        
        if (id.endsWith("~xiaoya") && vod.doubanInfo.getYear().isEmpty() && !vod.doubanInfo.getId().isEmpty()) {
            vod.doubanInfo = DoubanParser.getDoubanInfo(vod.doubanInfo.getId(), vod.doubanInfo);
            vod.setVodContent(vod.doubanInfo.getPlot() + "\r\n\r\n文件路径: " + path.substring(path.indexOf("/") + 1));
            vod.setVodActor(vod.doubanInfo.getActors());
            vod.setVodDirector(vod.doubanInfo.getDirector());
            vod.setVodArea(vod.doubanInfo.getRegion());
            vod.setVodYear(vod.doubanInfo.getYear());
            vod.setVodRemarks(vod.doubanInfo.getRating());
            vod.setTypeName(vod.doubanInfo.getType());
        }
        String result = Result.get().vod(vod).vodDrive(drive.getName()).string();
        //*Logger.log(result);
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
                playUrls.add(item.getName() + "$" + item.getVodId(path) + findSubs(path, items)
                 + "~~~" + String.format("danmu:%%NAME%%,%d,%%YEAR%%", i++));
                //playUrls.add(item.getName() + "$" + item.getVodId(path) + findSubs(path, items));
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

    private static Map<String, String> getPlayHeader(String url) {
        try {
            Uri uri = Uri.parse(url);
            Map<String, String> header = new HashMap<>();
            if (uri.getHost().contains("115.com"))
                header.put("User-Agent", Util.CHROME);
            else if (uri.getHost().contains("baidupcs.com"))
                header.put("User-Agent", "pan.baidu.com");
            return header;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private synchronized String xiaoyaCategoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        //*Logger.log(tid);
        String result = "";
        fetchRule();
        String key = tid.contains("/") ? tid.substring(0, tid.indexOf("/")) : tid;
        Drive drive = getDrive(key);
        HashMap<String, String> fl = extend;
        drive.fl = fl;
        List<String> lines = driveLinesMap.get(drive.getName());
        Pager pager = drivePagerMap.get(drive.getName());;
        if(lines == null || pg.equals("1")) {
            if (drive.getName().equals("每日更新")) {
                lines = (new Job(drive.check(), "~daily:100000")).call();
            } else {
                lines = (new Job(drive.check(), drive.getPath())).call();
            }

            boolean keepOrder = false;
            String doubansort = fl.get("doubansort");
            if (doubansort != null && !doubansort.equals("0")) {
                keepOrder = true;
            }

            String random = fl.get("random");
            if (random != null && !random.equals("0")) {
                pager = new Pager(lines, Integer.parseInt(random), keepOrder);
            } else {
                pager = new Pager(lines, 0, false);
            }

        }

        List<Vod> list = LocalIndexService.toVods(drive, pager.page(Integer.parseInt(pg)));

        driveLinesMap.put(drive.getName(), lines);
        drivePagerMap.put(drive.getName(), pager);
        result = Result.get().vod(list).page(Integer.parseInt(pg), pager.count, pager.limit, pager.count).string();
        //*Logger.log(result);
        return result;
    }

public static List<String> doFilter(LocalIndexService service, HashMap<String, String> fl) {
        LinkedHashMap<String, String> queryParams = new LinkedHashMap<>();

        if (fl == null) {
            fl = new HashMap<>();
        }
        String subpath = fl.get("subpath");
        if (subpath != null && !subpath.endsWith("~all")) {
            queryParams.put("subpath", subpath);
        }

        String douban = fl.get("douban");
        if (douban != null && !douban.equals("0")) {
            queryParams.put("douban", douban);
        }

        String doubansort = fl.get("doubansort");
        if (doubansort != null && !doubansort.equals("0")) {
            queryParams.put("doubansort", doubansort);
        }

        return service.query(queryParams);
    }

    private String alistCategoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        //*Logger.log(tid);
        fetchRule();
        String order = extend.containsKey("order") ? extend.get("order") : "";
        List<Item> folders = new ArrayList<>();
        List<Item> files = new ArrayList<>();
        List<Vod> list = new ArrayList<>();
        String key = tid.contains("/") ? tid.substring(0, tid.indexOf("/")) : tid;
        Drive drive = getDrive(key);

        for (Item item : getList(tid, true)) {
            if (item.isFolder())
                folders.add(item);
            else
                files.add(item);
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
        //*Logger.log(result);
        return result;
    }

    private boolean loginByUser(Drive drive) {
        try {
            JSONObject params = new JSONObject();
            String userName = LoginDlg.showLoginDlg("用户名(留空默认guest)");
            String password = LoginDlg.showLoginDlg("密码(留空默认guest_Api789，\"alist-\"打头会被识别为alist token)");
            //*Logger.log("用户名:" + userName + "密码:" + password);
            userName = userName.isEmpty() ? "guest" : userName;
            password = password.isEmpty() ? "guest_Api789" : password;
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
            String login = Path.read(loginFile) + "\n" + "\n";
            String userName = login.split("\n")[0];
            String password = login.split("\n")[1];
            //*Logger.log("用户名:" + userName + "密码:" + password);
            userName = userName.isEmpty() ? "guest" : userName;
            password = password.isEmpty() ? "guest_Api789" : password;
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

    private Item getDetail(String id) {
        String key = id.contains("/") ? id.substring(0, id.indexOf("/")) : id;
        String path = id.contains("/") ? id.substring(id.indexOf("/")) : "";
        Drive drive = getDrive(key);
        Item item;
        if (drive.pathByApi()) {
            item = getDetailByApi(id);
        } else {
            item = getDetailBy302(id);
        }
        //*Logger.log(item);
        return item;
    }

    private Item getDetailBy302(String id) {
        try {
            String key = id.contains("/") ? id.substring(0, id.indexOf("/")) : id;
            String path = id.contains("/") ? id.substring(id.indexOf("/")) : "";
            Drive drive = getDrive(key);
            path = path.startsWith(drive.getPath()) ? path : drive.getPath() + path;
            Item item = new Item();
            String url = drive.getServer() + "/d" + URLEncoder.encode(path, "UTF-8").replace("+", "%20").replace("%2F", "/");
            //*Logger.log(url);
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

    private String getSearchJson(boolean isNew, String response) throws Exception {
        if (isNew) {
            return new JSONObject(response).getJSONObject("data").getJSONArray("content").toString();
        } else {
            return new JSONObject(response).getJSONArray("data").toString();
        }
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
        List<Sub> sub = new ArrayList<>();
        for (String text : ids) {
            if (!text.contains("@@@"))
                continue;
            String[] split = text.split("@@@");
            String name = split[0];
            String ext = split[1];
            String url = getDetail(split[2]).getUrl();
            sub.add(Sub.create().name(name).ext(ext).url(url));
        }
        return sub;
    }

    class Job implements Callable<List<String>> {

        private final Drive drive;
        private final String keyword;

        public Job(Drive drive, String keyword) {
            this.drive = drive;
            this.keyword = keyword;
        }

        @Override
        public List<String> call() {
            return xiaoya();
        }

        private List<String> xiaoya() {
            //*Logger.log("xiaoya:" + keyword + "drive:" + drive.getName());
            String shortKeyword = keyword;
            if (keyword.contains(":")) {
                shortKeyword = keyword.split(":")[1];
            }
            shortKeyword = shortKeyword.length() < 30 ? shortKeyword : shortKeyword.substring(0, 30);
            if (keyword.startsWith("~daily:")) {
                LocalIndexService service = LocalIndexService.get(drive.getName() + "/"+ drive.dailySearchApi(shortKeyword));
                //service.slim(drive.getPath());
                return doFilter(service, drive.fl);
                //return service.query(new LinkedHashMap<String, String>());
            } else if (keyword.startsWith("~search:")) {
                LocalIndexService service = LocalIndexService.get(drive.getName() + "/"+ drive.searchApi(shortKeyword));
                //service.slim(drive.getPath());
                return service.query(new LinkedHashMap<String, String>());
            } else if (keyword.startsWith("~quick:")) {
                return LocalIndexService.get(drive).quickSearch(shortKeyword);
            } else {
                LocalIndexService service = LocalIndexService.get(drive);
                //service.slim(drive.getPath());
                return doFilter(service, drive.fl);
            }
        }
    }
}
