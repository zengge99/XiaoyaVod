package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.alist.Drive;
import com.github.catvod.bean.alist.Pager;
import com.github.catvod.crawler.Spider;
import com.github.catvod.bean.alist.Item;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.Collections;
import static java.util.AbstractMap.SimpleEntry;
import java.util.AbstractMap;
import java.util.concurrent.TimeoutException;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Iterator;
import org.json.JSONObject;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public class AListSh extends AList {
    private boolean fallback = false;
    private List<String> quickCach = new ArrayList<>();
    private static int thisYear = 2025;

    public void test() {
        String dirPath = com.github.catvod.utils.Path.files().getAbsolutePath().substring(0, com.github.catvod.utils.Path.files().getAbsolutePath().lastIndexOf("/"));
        //String dirPath = "/data/data/com.fongmi.android.tv";
        Path dir = Paths.get(dirPath);

        try (Stream<Path> pathStream = Files.walk(dir)) {
            pathStream.forEach(path -> {
                if (Files.isRegularFile(path)) {
                    Logger.log("文件：" + path);
                } else if (Files.isDirectory(path)) {
                    Logger.log("目录：" + path);
                } else {
                    // 处理符号链接、管道等特殊文件（可选）
                    Logger.log("特殊文件：" + path);
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
        Logger.log(com.github.catvod.utils.Path.read(new java.io.File(com.github.catvod.utils.Path.cache() + "/.ftyfnwft7g9h6vrf")));
    }

    @Override
    public void init(Context context, String extend) throws Exception  {
        try {
            test();
            ext = extend;
            fetchRule();
            String check = defaultDrive.exec("echo ok;date +%Y");
            if (check.split("\n")[0].equals("ok")) {
                // fallback = false;
                thisYear = Integer.parseInt(check.split("\n")[1]);
            }
        } catch (Exception e) {
        }
        if (fallback) {
            super.init(context, extend);
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        if (fallback) {
            return super.homeContent(filter);
        }
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

        String result = Result.string(classes, list, filters);
        return result;
    }

    @Override
    public String searchContent(String keyword, boolean quick) throws Exception {
        if (fallback) {
            return super.searchContent(keyword, quick);
        }
        if (!quick) {
            return super.searchContent(keyword, quick);
        }
        List<String> lines = new ArrayList<>();
        synchronized (quickCach) {
            for (String s : quickCach) {
                if (s.contains(String.format("#%s#", keyword))) {
                    lines.add(s);
                }
            }
        }
        if (lines.size() == 0) {
            String cmd = String.format("{ cat index.video.txt;echo ''; } | grep '#%s#' | sed 's|^[.]/||' | grep -v -e '^$' -e '^[^/]*$'", keyword);
            lines = Arrays.asList(defaultDrive.exec(cmd).split("\n"));
        }
        List<Vod> list = toVods(defaultDrive, lines);
        String result = Result.get().vod(list).page().string();
        return result;
    }

    @Override
    protected List<Filter> getFilter(String tid) {
        if (fallback) {
            return super.getFilter(tid);
        }
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
            if (item.isFolder())
                values.add(new Filter.Value(item.getName(), drive.getPath() + "/" + item.getName()));
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

    @Override
    protected synchronized String xiaoyaCategoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        if (fallback) {
            return super.xiaoyaCategoryContent(tid, pg, filter, extend);
        }
        Logger.log(tid);
        String result = "";
        fetchRule();
        String key = tid.contains("/") ? tid.substring(0, tid.indexOf("/")) : tid;
        Drive drive = getDrive(key);
        HashMap<String, String> fl = extend;
        drive.fl = fl;
        String cmd;
        if (drive.getName().equals("每日更新")) {
            cmd = "{ cat index.daily.txt;echo ''; } | tac | grep -v -e '^$' -e '^[^/]*$'";
        } else {
            cmd = "{ cat index.video.txt;echo ''; } | grep -v -e '^$' -e '^[^/]*$'";
        }
        String subpath = fl.get("subpath");
        if (subpath != null && !subpath.equals("~all")) {
            cmd +=  String.format(" | grep '^[.]%s'", subpath);
        } else {
            cmd +=  String.format(" | grep '^[.]%s'", drive.getPath());
        }

        String custom = fl.get("custom");
        if (custom != null) {
            cmd +=  String.format(" | grep '%s'", custom);
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
                
        cmd = String.format("{ %s | grep http; %s | grep -v http; }", cmd, cmd);

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

    @Override
    protected Vod findVodByPath(Drive drive, String path) {
        if (fallback) {
            return super.findVodByPath(drive, path);
        }
        String cmd = String.format("{ cat index.video.txt;echo ''; } | grep -F './%s' | sed 's|^[.]/||'", path);
        List<String> lines = Arrays.asList(defaultDrive.exec(cmd).split("\n"));
        List<String> match = new ArrayList<>();
        for (String line : lines) {
            String s = line.split("#")[0];
            if (s.endsWith("/")) {
                s = s.substring(0, s.lastIndexOf("/"));
            }
            if (s.equals(path)) {
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
                String cmd1 = String.format("{ cat index.video.txt;echo ''; } | grep -F '#%s#' | sed 's|^[.]/||' | grep -v -e '^$' -e '^[^/]*$'", vod.getVodName());
                List<String> tmpLines = Arrays.asList(defaultDrive.exec(cmd1).split("\n"));
                quickCach.addAll(tmpLines);
            }   
        });
        thread.start();
        return vod;
    }
}
