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
import com.github.catvod.bean.alist.XiaoyaLocalIndex;
import com.github.catvod.bean.alist.Item;
import com.github.catvod.bean.alist.Sorter;
import com.github.catvod.bean.alist.VodSorter;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import com.github.catvod.utils.Notify;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import com.github.catvod.bean.alist.LoginDlg;
import android.widget.Toast;

public class AList extends Spider {

    private List<Drive> drives;
    private Drive defaultDrive;
    private String vodPic;
    private String ext;
    private String xiaoyaAlistToken;
    private Map<String, Vod> vodMap = new HashMap<>();
    // private Map<String, List<Vod>> driveVodsMap = new HashMap<>();
    private volatile List<Vod> vodCache;
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
                values.add(new Filter.Value(item.getName(), drive.getName() + drive.getPath() + "/" + item.getName()));
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
        Logger.log("token:" + xiaoyaAlistToken);
        return xiaoyaAlistToken;
    }

    private void fetchRule() {
        if (drives != null && !drives.isEmpty())
            return;
        if (ext.startsWith("http"))
            ext = OkHttp.string(ext);
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
        if (retry && (response.contains("Guest user is disabled") || response.contains("token is invalidated")
                || response.contains("without permission")) && login(drive))
            return post(drive, url, param, false);
        return response;
    }

    @Override
    public void init(Context context, String extend) {
        try {
            ext = extend;
            fetchRule();
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
            // List<Job> jobs = new ArrayList<>();
            // jobs.add(new Job(defaultDrive.check(), "~daily:1000"));
            // for (Future<List<Vod>> future : executor.invokeAll(jobs, 15, TimeUnit.SECONDS))
            //     list.addAll(future.get());
            list = (new Job(defaultDrive.check(), "~daily:1000")).call();
        }

        Drive tmpDrive = defaultDrive;
        String result = Result.string(classes, list, filters);
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
            }
            XiaoyaLocalIndex.downlodadAndUnzip(tmpDrive);
        });
        thread.start();

        Logger.log(result);
        return result;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        Logger.log(tid);
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
        Logger.log(id);
        if (id.endsWith("~soulist") || id.endsWith("~playlist")) {
            return listDetailContent(ids);
        }
        if (id.endsWith("~soufile")) {
            return fileDetailContent(ids);
        }
        return defaultDetailContent(ids);
    }

    @Override
    public String searchContent(String keyword, boolean quick) throws Exception {
        fetchRule();
        Logger.log(keyword);
        Logger.log(quick);
        List<Vod> list = new ArrayList<>();
        List<Job> jobs = new ArrayList<>();
        for (Drive drive : drives) {
            if (drive.search()) {
                if (quick) {
                    jobs.add(new Job(drive.check(), "~quick:" + keyword));
                } else {
                    jobs.add(new Job(drive.check(), "~search:" + keyword));
                }
            }
        }
        for (Future<List<Vod>> future : executor.invokeAll(jobs, 15, TimeUnit.SECONDS))
            list.addAll(future.get());
        Logger.log(Result.string(list));
        return Result.get().vod(list).page().string();
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
        // String result =
        // Result.get().url(url).header(getPlayHeader(url)).subs(getSubs(ids)).string();
        Logger.log(result);
        return result;
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
        if (id.endsWith("~soulist")) {
            walkFolder(drive, path, from, url, true);
        } else {
            walkFolder(drive, path, from, url, false);
        }
        Vod vod = vodMap.get(id);
        if (vod == null && id.endsWith("~soulist")) {
            String keyword = path.substring(path.indexOf("/") + 1);
            // List<Job> jobs = new ArrayList<>();
            // jobs.add(new Job(drive.check(), "~search:" + keyword));
            // for (Future<List<Vod>> future : executor.invokeAll(jobs, 15, TimeUnit.SECONDS))
            //     future.get();
            (new Job(drive.check(), "~search:" + keyword)).call();
            vod = vodMap.get(id);
        }
        if (vod == null) {
            vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(vodPic);
            vod.setVodDrive(drive.getName());
        }
        vod.setVodPlayFrom(from.toString());
        vod.setVodPlayUrl(url.toString());
        if (id.endsWith("~soulist") && vod.doubanInfo.getYear().isEmpty() && !vod.doubanInfo.getId().isEmpty()) {
            DoubanParser.getDoubanInfo(vod.doubanInfo.getId(), vod.doubanInfo);
            vod.setVodContent(vod.doubanInfo.getPlot() + "\r\n\r\n文件路径: " + path.substring(path.indexOf("/") + 1));
            vod.setVodActor(vod.doubanInfo.getActors());
            vod.setVodDirector(vod.doubanInfo.getDirector());
            vod.setVodArea(vod.doubanInfo.getRegion());
            vod.setVodYear(vod.doubanInfo.getYear());
            vod.setVodRemarks(vod.doubanInfo.getRating());
            vod.setTypeName(vod.doubanInfo.getType());
        }
        Logger.log(Result.string(vod));
        return Result.string(vod);
    }

    private String fileDetailContent(List<String> ids) throws Exception {
        fetchRule();
        String id = ids.get(0);
        String key = id.contains("/") ? id.substring(0, id.indexOf("/")) : id;
        String path = id.substring(0, id.lastIndexOf("/"));
        String name = path.substring(path.lastIndexOf("/") + 1);
        Drive drive = getDrive(key);
        Vod vod = vodMap.get(id);
        if (vod == null && id.endsWith("~soufile")) {
            String keyword = path.substring(path.indexOf("/") + 1);
            /*
            List<Job> jobs = new ArrayList<>();  
            jobs.add(new Job(drive.check(), keyword));
            for (Future<List<Vod>> future : executor.invokeAll(jobs, 15, TimeUnit.SECONDS))
                future.get();
            */
            (new Job(drive.check(), keyword)).call();
            vod = vodMap.get(id);
        }
        if (vod == null) {
            vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(name);
            vod.setVodPic(vodPic);
            vod.setVodDrive(drive.getName());
        }
        vod.setVodPlayFrom(drive.getName());
        vod.setVodPlayUrl(name + "$" + path);
        if (id.endsWith("~soufile") && vod.doubanInfo.getYear().isEmpty() && !vod.doubanInfo.getId().isEmpty()) {
            DoubanParser.getDoubanInfo(vod.doubanInfo.getId(), vod.doubanInfo);
            vod.setVodContent(vod.doubanInfo.getPlot() + "\r\n\r\n文件路径: " + path.substring(path.indexOf("/") + 1));
            vod.setVodActor(vod.doubanInfo.getActors());
            vod.setVodDirector(vod.doubanInfo.getDirector());
            vod.setVodArea(vod.doubanInfo.getRegion());
            vod.setVodYear(vod.doubanInfo.getYear());
            vod.setVodRemarks(vod.doubanInfo.getRating());
            vod.setTypeName(vod.doubanInfo.getType());
        }
        Logger.log(Result.string(vod));
        return Result.string(vod);
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
        for (Item item : items)
            if (item.isMedia(drive.isNew())) {
                playUrls.add(item.getName() + "$" + item.getVodId(path) + findSubs(path, items));
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
        Logger.log(tid);
        fetchRule();
        String key = tid.contains("/") ? tid.substring(0, tid.indexOf("/")) : tid;
        Drive drive = getDrive(key);
        //List<Vod> list = driveVodsMap.get(drive.getName());
        List<Vod> list = vodCache;
        if(list != null && !pg.equals("1")) {
            return Result.get().vod(list).page(pg, true).string();
        }

        list = new ArrayList<>();
        //List<Job> jobs = new ArrayList<>();

        if (drive.getName().equals("每日更新")) {
            // jobs.add(new Job(drive.check(), "~daily:100000"));
            // for (Future<List<Vod>> future : executor.invokeAll(jobs, 15, TimeUnit.SECONDS))
            //     list.addAll(future.get());  
            list = (new Job(drive.check(), "~daily:100000")).call();
        } else {
            //if (XiaoyaLocalIndex.isBusy) {
                //Notify.show("本地索引正在构建，请等待30秒再试");
            //} else {
                // jobs.add(new Job(drive.check(), drive.getPath()));
                // for (Future<List<Vod>> future : executor.invokeAll(jobs, 15, TimeUnit.SECONDS))
                //     list.addAll(future.get());
                list = (new Job(drive.check(), drive.getPath())).call();
            //}
        }

        if (filter) {
            list = VodSorter.sortVods(list, extend);
        }

        Logger.log(Result.string(list));
        //driveVodsMap.put(drive.getName(), list);
        vodCache = list;
        return Result.get().vod(list).page(pg, true).string();
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

        Logger.log(Result.get().vod(list).page().string());
        return Result.get().vod(list).page().string();
    }

    private boolean login(Drive drive) {
        try {
            JSONObject params = new JSONObject();
            String userName = LoginDlg.showLoginDlg("用户名(留空默认guest)");
            String password = LoginDlg.showLoginDlg("密码(留空默认guest_Api789，\"alist-\"打头会被识别为alist token)");
            Logger.log("用户名:" + userName + "密码:" + password);
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
        Logger.log(item);
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
                    if (iterator.next().ignore(drive.isNew()))
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

    class Job implements Callable<List<Vod>> {

        private final Drive drive;
        private final String keyword;

        public Job(Drive drive, String keyword) {
            this.drive = drive;
            this.keyword = keyword;
        }

        @Override
        public List<Vod> call() {
            return xiaoya();
        }

        private List<Vod> xiaoya() {
            Logger.log("xiaoya:" + keyword + "drive:" + drive.getName());
            long startTime = System.currentTimeMillis();
            long duration = 0;
            List<Vod> list = new ArrayList<>();
            String shortKeyword = keyword;
            if (keyword.contains(":")) {
                shortKeyword = keyword.split(":")[1];
            }
            shortKeyword = shortKeyword.length() < 30 ? shortKeyword : shortKeyword.substring(0, 30);
            Document doc;
            List<Vod> vods = new ArrayList<>();
            if (keyword.startsWith("~daily:")) {
                List<String> lines = new ArrayList<>();
                doc = Jsoup.parse(OkHttp.string(drive.dailySearchApi(shortKeyword)));
                for (Element a : doc.select("ul > a")) {
                    String line = a.text();
                    if (!line.contains("/"))
                        continue;
                    lines.add(a.text());
                }
                vods = XiaoyaLocalIndex.toVods(drive, lines);
                for (Vod vod : vods) {
                    //vod.setVodDrive(drive.getName());
                    vodMap.put(vod.getVodId(), vod);
                }
                return vods;
            } else if (keyword.startsWith("~search:")) {
                List<String> lines = new ArrayList<>();
                doc = Jsoup.parse(OkHttp.string(drive.searchApi(shortKeyword)));
                for (Element a : doc.select("ul > a")) {
                    String line = a.text();
                    if (!line.contains("/"))
                        continue;
                    lines.add(a.text());
                }
                vods = XiaoyaLocalIndex.toVods(drive, lines);
                for (Vod vod : vods) {
                    //vod.setVodDrive(drive.getName());
                    vodMap.put(vod.getVodId(), vod);
                }
                return vods;
            } else if (keyword.startsWith("~quick:")) {
                XiaoyaLocalIndex.downlodadAndUnzip(drive);
                //startTime = System.currentTimeMillis();
                vods = XiaoyaLocalIndex.quickSearch(drive, shortKeyword);
                //duration = System.currentTimeMillis() - startTime;
                for (Vod vod : vods) {
                    //vod.setVodDrive(drive.getName());
                    vodMap.put(vod.getVodId(), vod);
                }
                Logger.log("快速搜索耗时：" + duration);
                return vods;
            } else {
                vods = XiaoyaLocalIndex.downlodadAndUnzip(drive);
                if (vods.size() == 0) {
                    List<String> lines = new ArrayList<>();
                    doc = Jsoup.parse(OkHttp.string(drive.searchApi(shortKeyword)));
                    for (Element a : doc.select("ul > a")) {
                        String line = a.text();
                        if (!line.contains("/"))
                            continue;
                        lines.add(a.text());
                    }
                    vods = XiaoyaLocalIndex.toVods(drive, lines);
                }
            }

            List<Vod> filteredVods = new ArrayList<>();
            startTime = System.currentTimeMillis();
            for (Vod vod : vods) {
                if (!vod.getVodIdWithoutDrivePrefix().startsWith(drive.getPath())) {
                    continue;
                }

                //vod.setVodDrive(drive.getName());
                filteredVods.add(vod);
                vodMap.put(vod.getVodId(), vod);
            }
            duration = System.currentTimeMillis() - startTime;
            Logger.log("转换Vod耗时：" + duration);

            return filteredVods;
        }
    }
}
