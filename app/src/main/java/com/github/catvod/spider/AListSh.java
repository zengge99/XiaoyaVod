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

public class AListSh extends AList {

    @Override
    public void init(Context context, String extend) {
        try {
            ext = extend;
            fetchRule();
        } catch (Exception e) {
            Logger.log(e.getMessage());
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
            List<String> lines = Arrays.asList(defaultDrive.exec("{ cat index.daily.txt;echo ''; } | tac").split("\n"));
            list = toVods(defaultDrive, lines);
        }

        String result = Result.string(classes, list, filters);
        return result;
    }

    @Override
    protected synchronized String xiaoyaCategoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        Logger.log(tid);
        String result = "";
        fetchRule();
        String key = tid.contains("/") ? tid.substring(0, tid.indexOf("/")) : tid;
        Drive drive = getDrive(key);
        HashMap<String, String> fl = extend;
        drive.fl = fl;
        String cmd;
        if (drive.getName().equals("每日更新")) {
            cmd = String.format("{ cat index.daily.txt;echo ''; } | tac", drive.getPath());
        } else {
            cmd = String.format("{ cat index.video.txt index.115.txt;echo ''; }", drive.getPath());
        }
        String subpath = fl.get("subpath");
        if (subpath != null && !subpath.equals("~all")) {
            cmd +=  String.format(" | grep '^[.]%s'", subpath);
        } else {
            cmd +=  String.format(" | grep '^[.]%s'", drive.getPath());
        }
        String douban = fl.get("douban");
        if (douban != null && !douban.equals("0")) {
            cmd +=  String.format(" | awk -F '#' '$4 >= %s'", douban);
        }
        int total = Integer.parseInt(drive.exec(cmd + " | grep -n '' | tail -n 1 | cut -d ':' -f 1").split("\n")[0]);

        cmd = String.format("{ %s | grep http; %s | grep -v http; }", cmd, cmd);

        String doubansort = fl.get("doubansort");
        if (doubansort != null && doubansort.equals("1")) {
            cmd +=  String.format(" | awk -F '#' '{print $4,$0}' | sort -r | cut -d ' ' -f 2-");
        }
        if (doubansort != null && doubansort.equals("2")) {
            cmd +=  String.format(" | awk -F '#' '{print $4,$0}' | sort | cut -d ' ' -f 2-");
        }

        // int limit = 72;
        // int count = (total + limit - 1) / limit;
        // int pageNum = Integer.parseInt(pg);
        // int startLine = (pageNum - 1) * limit + 1;
        // cmd +=  String.format(" | tail -n +%d | head -n %d", startLine, limit);
        // List<String> lines = Arrays.asList(drive.exec(cmd).split("\n"));

        Pager pager = new Pager(drive, cmd, total, 200, false);
        List<String> lines = pager.page(Integer.parseInt(pg));

        List<Vod> list = toVods(drive, lines);
        result = Result.get().vod(list).page(Integer.parseInt(pg), total, 72, count).string();
        return result;
    }
}
