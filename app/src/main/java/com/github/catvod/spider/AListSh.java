package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.alist.Drive;
import com.github.catvod.bean.alist.Pager;
import com.github.catvod.crawler.Spider;
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

public class AListSh extends AList {
    private boolean fallback = true;
    private List<String> quickCach = new ArrayList<>();

    @Override
    public void init(Context context, String extend) throws Exception  {
        try {
            ext = extend;
            fetchRule();
            if (defaultDrive.exec("echo ok").split("\n")[0].equals("ok")) {
                fallback = false;
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
            String cmd = String.format("{ cat index.video.txt index.115.txt;echo ''; } | grep '#%s#' | sed 's|^[.]/||' | grep -v -e '^$' -e '^[^/]*$'", keyword);
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
            items.add(new Filter("category", "分类", customFilterValues));
        }

        items.addAll(super.getFilter(tid));

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
            cmd = "{ cat index.video.txt index.115.txt;echo '' | grep -v -e '^$' -e '^[^/]*$'; }";
        }
        String subpath = fl.get("subpath");
        if (subpath != null && !subpath.equals("~all")) {
            cmd +=  String.format(" | grep '^[.]%s'", subpath);
        } else {
            cmd +=  String.format(" | grep '^[.]%s'", drive.getPath());
        }

        String category = fl.get("category");
        if (category != null) {
            cmd +=  String.format(" | grep '%s'", category);
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
        String cmd = String.format("{ cat index.video.txt index.115.txt;echo ''; } | grep -F './%s' | sed 's|^[.]/||'", path);
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
                String cmd1 = String.format("{ cat index.video.txt index.115.txt;echo ''; } | grep -F '#%s#' | sed 's|^[.]/||' | grep -v -e '^$' -e '^[^/]*$'", vod.getVodName());
                List<String> tmpLines = Arrays.asList(defaultDrive.exec(cmd1).split("\n"));
                quickCach.addAll(tmpLines);
            }   
        });
        thread.start();
        return vod;
    }
}
