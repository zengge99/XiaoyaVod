package com.github.catvod.bean.alist;

import java.util.*;
import java.util.stream.Collectors;
import android.text.TextUtils;
import com.github.catvod.bean.Vod;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;

import com.github.catvod.spider.Logger;


public class VodSorter {

    public static class FilterParams {
        public String douban;
        public String doubansort;
        public String random;
    }

    public static List<Vod> sortVods(List<Vod> vods, HashMap<String, String> fl) {
        List<Vod> filteredVods = vods;
        long startTime = System.currentTimeMillis();
        long duration = 0;
    
        String subpath = fl.get("subpath");
        if (subpath != null && !subpath.endsWith("~all")) {
            Logger.log("subpath:" + subpath);
            filteredVods = filteredVods.stream()
                .filter(vod -> vod.getVodId().startsWith(subpath))
                .collect(Collectors.toList());
        }
        duration = System.currentTimeMillis() - startTime;
        Logger.log("过滤分类耗时：" + duration);
        
        startTime = System.currentTimeMillis();
        // 解析豆瓣评分阈值（从 HashMap 获取）
        double doubanThreshold = parseDoubleSafe(fl.getOrDefault("douban", "0"));
        // 1. 过滤评分达标的视频
        filteredVods = filteredVods.stream()
            .filter(vod -> parseDoubleSafe(vod.doubanInfo.getRating()) >= doubanThreshold)
            .collect(Collectors.toList());

        duration = System.currentTimeMillis() - startTime;
        Logger.log("过滤豆瓣评分耗时：" + duration);

        startTime = System.currentTimeMillis();
        // 2. 排序处理（从 HashMap 获取排序类型）
        String sortType = fl.getOrDefault("doubansort", "0");
        if (!"0".equals(sortType)) {
            Comparator<Vod> comparator = Comparator.comparingDouble(
                v -> parseDoubleSafe(v.doubanInfo.getRating())
            );
            
            switch (sortType) {
                case "1":
                    filteredVods.sort(comparator.reversed()); // 降序
                    break;
                case "2":
                    filteredVods.sort(comparator); // 升序
                    break;
            }
        }
        duration = System.currentTimeMillis() - startTime;
        Logger.log("豆瓣评分排序耗时：" + duration);

        startTime = System.currentTimeMillis();
        // 3. 随机筛选（从 HashMap 获取随机数量）
        int randomCount = parseIntSafe(fl.getOrDefault("random", "0"));
        if (randomCount > 0) {
            boolean keepOrder = "1".equals(sortType) || "2".equals(sortType);
            filteredVods = getRandomElements(filteredVods, randomCount, keepOrder);
        }
        duration = System.currentTimeMillis() - startTime;
        Logger.log("随机筛选耗时：" + duration);
        
        return filteredVods;
    }

    private static double parseDoubleSafe(String s) {
        try {
            return (s != null && !s.isEmpty()) ? Double.parseDouble(s) : 0.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return (s != null && !s.isEmpty()) ? Integer.parseInt(s) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    private static List<Vod> getRandomElements(List<Vod> source, int count, boolean keepOrder) {
        if (source.size() <= count) {
            List<Vod> result = new ArrayList<>(source);
            if (!keepOrder) Collections.shuffle(result);
            return result;
        }
    
        // 使用随机索引挑选元素
        Random random = new Random();
        Set<Integer> selectedIndexes = new HashSet<>();
        while (selectedIndexes.size() < count) {
            int index = random.nextInt(source.size());
            selectedIndexes.add(index);
        }
        
        // 根据索引挑选元素
        List<Vod> randomSelection = new ArrayList<>();
        for (int index : selectedIndexes) {
            randomSelection.add(source.get(index));
        }
        
        // 如果需要保持原始顺序，按索引排序
        if (keepOrder) {
            randomSelection.sort(Comparator.comparingInt(source::indexOf));
        }
        
        return randomSelection;
    }
    
}
