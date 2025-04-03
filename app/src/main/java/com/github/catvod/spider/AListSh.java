package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.alist.Drive;
import com.github.catvod.bean.alist.Pager;
import com.github.catvod.crawler.Spider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

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
            List<String> lines = Arrays.asList(defaultDrive.exec("{ cat index.daily.txt;echo ''; } | tac |  sed 's|^[.]/||'").split("\n"));
            list = toVods(defaultDrive, lines);
        }

        String result = Result.string(classes, list, filters);
        return result;
    }

    @Override
    public String searchContent(String keyword, boolean quick) throws Exception {
        if (!quick) {
            return base.searchContent(keyword, quick);
        }
        String cmd = String.format("{ cat index.video.txt index.115.txt;echo ''; } | grep '#%s#'", keyword);
        List<String> lines = Arrays.asList(defaultDrive.exec(cmd).split("\n"));
        List<Vod> list = toVods(defaultDrive, lines);
        return list;
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
            cmd = "{ cat index.daily.txt;echo ''; } | tac";
        } else {
            cmd = "{ cat index.video.txt index.115.txt;echo ''; }";
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
            pager = new Pager(drive, cmd, total, randomNum, keepOrder);
            drivePagerMap.put(drive.getName(), pager);
        }
        List<String> lines = pager.page(Integer.parseInt(pg));
        List<Vod> list = toVods(drive, lines);
        result = Result.get().vod(list).page(Integer.parseInt(pg), pager.total, pager.limit, pager.count).string();
        return result;
    }
}
