package com.github.catvod.spider;

import android.content.Context;
import java.util.HashMap;
import java.util.List;
import com.github.catvod.crawler.Spider;


public class XiaoyaAlist extends Spider {
    private Spider core = null;

    @Override
    public void init(Context context, String extend) {
        try {
            if (core == null) {
                core = new AList();
            }
            core.init(context, extend); 
        } catch (Exception e) {
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        return core.homeContent(filter);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        return core.categoryContent(tid, pg, filter, extend);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        return core.detailContent(ids);
    }

    @Override
    public String searchContent(String keyword, boolean quick) throws Exception {
        return core.searchContent(keyword, quick);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return core.playerContent(flag, id, vipFlags);
    }
}
