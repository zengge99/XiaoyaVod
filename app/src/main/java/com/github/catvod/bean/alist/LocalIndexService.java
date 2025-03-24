package com.github.catvod.bean.alist;

import java.io.*;
import java.util.*;
import com.github.catvod.spider.Logger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import android.text.TextUtils;
import com.github.catvod.net.OkHttp;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.Jsoup;
import com.github.catvod.bean.alist.Drive;
import com.github.catvod.bean.alist.Item;
import com.github.catvod.bean.Vod;

public class LocalIndexService {

    private static final int MAX_LINES_IN_MEMORY = 5000; // 每个块的最大行数
    private static final Map<String, LocalIndexService> instances = new HashMap<>(); // 实例缓存

    private List<String> inputList;
    private HashMap<String, List<String>> queryCache = new HashMap<>();
    private boolean slimed = false;

    private LocalIndexService(String url) {
        inputList = new FileBasedList<String>(String.class);
        if (isOnline(url)) {
            Document doc = Jsoup.parse(OkHttp.string(url));
            for (Element a : doc.select("ul > a")) {
                String line = a.text();
                if (!line.contains("/"))
                    continue;
                inputList.add(a.text());
            }
        } else {
            inputList = new FileBasedList<String>(IndexDownloader.downlodadAndUnzip(url), String.class);
        }
    }

    private static boolean isOnline(String path) {
        return path.contains("/sou?");
    }

    public static LocalIndexService get(String url) {

        String realUrl = url;
        if (!url.startsWith("http")) {
            realUrl = url.substring(url.indexOf("/") + 1);
        }

        if (isOnline(realUrl)) {
            return new LocalIndexService(realUrl);
        }

        if (!instances.containsKey(url)) {
            instances.put(url, new LocalIndexService(realUrl));
        }

        return instances.get(url);
    }

    public List<String> externalSort(List<String> inputSortList, List<String> outputSortList, String order)
            throws IOException {
        List<List<String>> sortedChunks = sortInChunks(inputSortList, order);
        return mergeSortedChunks(sortedChunks, outputSortList, order);
    }

    private List<List<String>> sortInChunks(List<String> inputSortList, String order) throws IOException {
        List<List<String>> sortedChunks = new ArrayList<>();
        List<String> chunk = new ArrayList<>();
        for (String line : inputSortList) {
            chunk.add(line);
            if (chunk.size() >= MAX_LINES_IN_MEMORY) {
                sortedChunks.add(sortAndWriteChunk(chunk, order));
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            sortedChunks.add(sortAndWriteChunk(chunk, order));
        }
        return sortedChunks;
    }

    private List<String> sortAndWriteChunk(List<String> chunk, String order) throws IOException {
        chunk.sort(createComparator(order));
        List<String> tempList = new FileBasedList<String>(String.class);
        for (String line : chunk) {
            tempList.add(line);
        }
        return tempList;
    }

    private List<String> mergeSortedChunks(List<List<String>> sortedChunks, List<String> outputSortList, String order)
            throws IOException {
        PriorityQueue<ListReader> minHeap = new PriorityQueue<>(
                Comparator.comparing(lr -> lr.currentLine, createComparator(order)));
        // 初始化堆
        for (List<String> trunk : sortedChunks) {
            ListReader reader = new ListReader(trunk);
            if (reader.readLine()) {
                minHeap.add(reader);
            }
        }
        // 多路归并
        while (!minHeap.isEmpty()) {
            ListReader reader = minHeap.poll();
            outputSortList.add(reader.currentLine);
            if (reader.readLine()) {
                minHeap.add(reader);
            }
        }

        return outputSortList; // 返回合并后的文件路径
    }

    private Comparator<String> createComparator(String order) {
        return (o1, o2) -> {
            double value1 = parseFieldAsDouble(o1.split("#"), 3);
            double value2 = parseFieldAsDouble(o2.split("#"), 3);
            if (order.equals("asc")) {
                return Double.compare(value1, value2); // 升序
            } else {
                return Double.compare(value2, value1); // 降序
            }
        };
    }

    private double parseFieldAsDouble(String[] fields, int index) {
        if (fields == null || fields.length <= index) {
            return 0.0; // 字段不足，返回 0
        }
        String field = fields[index];
        if (field == null || field.trim().isEmpty()) {
            return 0.0; // 字段为空，返回 0
        }
        try {
            return Double.parseDouble(field); // 解析为 double
        } catch (NumberFormatException e) {
            return 0.0; // 字段非 double，返回 0
        }
    }

    private double parseStringAsDouble(String input) {
        if (TextUtils.isEmpty(input)) {
            return 0.0;
        }
        try {
            return Double.parseDouble(input);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static class ListReader {
        private final List<String> list;
        private String currentLine;
        private int currentLineIndex = 0;

        public ListReader(List<String> list) throws FileNotFoundException, UnsupportedEncodingException {
            this.list = list;
        }

        public boolean readLine() throws IOException {
            if (currentLineIndex < list.size()) {
                currentLine = list.get(currentLineIndex++);
                return true;
            } else {
                return false;
            }
        }
    }

    public List<String> slim(String path) {
        if (slimed) {
            return inputList;
        }
        try {
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            List<String> outputSortList = new FileBasedList<String>(String.class);
            filterByPath(inputList, outputSortList, path);
            inputList = outputSortList;
            slimed = true;
            return inputList;
        } catch (Exception e) {
            Logger.log(e);
        }
        return new ArrayList<>();
    }

    private void sortByDouban(List<String> inputSortList, List<String> outputSortList, String order)
            throws IOException {
        externalSort(inputSortList, outputSortList, order);
        Logger.log("Sorted by field: " + order);
    }

    public List<String> query(LinkedHashMap<String, String> queryParams) {
        try {
            List<String> currentInputList = inputList;
            if (queryParams.containsKey("random")) {
                queryParams.remove("random");
            }

            String cacheKey = generateCacheKey(queryParams);
            if (queryCache.get(cacheKey) != null) {
                Logger.log("Cache hit for query: " + cacheKey);
                currentInputList = queryCache.get(cacheKey);
                return currentInputList;
            }
            Logger.log("Cache miss for query: " + cacheKey);

            // 依次处理查询方法
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                String method = entry.getKey();
                String param = entry.getValue();

                List<String> tempOutputList = new FileBasedList<String>(String.class);

                // 执行查询方法
                switch (method) {
                    case "subpath":
                        filterByPath(currentInputList, tempOutputList, param);
                        break;
                    case "doubansort":
                        sortByDouban(currentInputList, tempOutputList, param);
                        break;
                    case "douban":
                        filterByDouban(currentInputList, tempOutputList, param);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown query method: " + method);
                }

                // 更新当前输入文件
                currentInputList = tempOutputList;
            }
            queryCache.put(cacheKey, currentInputList);
            return currentInputList;
        } catch (Exception e) {
            Logger.log(e);
            return new ArrayList<>();
        }
    }

    private String generateCacheKey(LinkedHashMap<String, String> queryParams) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            String queryString = queryParams.toString();
            byte[] hashBytes = md.digest(queryString.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not found", e);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 encoding not supported", e);
        }
    }

    private void filterByPath(List<String> inputSortList, List<String> outputSortList, String fieldValue)
            throws IOException {
        List<String> noPicList = new FileBasedList<String>(String.class);
        for (String line : inputSortList) {
            String[] fields = line.split("#");
            if (fields.length > 0 && fields[0].startsWith(fieldValue)) {
                if (fields.length < 5 || fields[4].isEmpty()) {
                    noPicList.add(line);
                } else {
                    outputSortList.add(line);
                } 
            }
        }
        outputSortList.addAll(noPicList);
        Logger.log("Filtered by field: " + fieldValue);
    }

    private void filterByDouban(List<String> inputSortList, List<String> outputSortList, String fieldValue)
            throws IOException {
        double filterValue = parseStringAsDouble(fieldValue);
        for (String line : inputSortList) {
            double actualValue = parseFieldAsDouble(line.split("#"), 3);
            if (actualValue >= filterValue) {
                outputSortList.add(line);
            }
        }
    }

    public static List<Vod> toVods(Drive drive, List<String> lines) {
        List<Vod> list = new ArrayList<>();
        List<Vod> noPicList = new ArrayList<>();
        for (String line : lines) {
            String[] splits = line.split("#");
            int index = splits[0].lastIndexOf("/");
            // boolean file = Util.isMedia(splits[0]);
            if (splits[0].endsWith("/")) {
                // file = false;
                splits[0] = splits[0].substring(0, index);
                index = splits[0].lastIndexOf("/");
            }
            Item item = new Item();
            item.setType(0);
            item.doubanInfo.setId(splits.length >= 3 ? splits[2] : "");
            item.doubanInfo.setRating(splits.length >= 4 ? splits[3] : "");
            item.setThumb(splits.length >= 5 ? splits[4] : "");
            item.setPath("/" + splits[0].substring(0, index));
            String fileName = splits[0].substring(index + 1);
            item.setName(fileName);
            item.doubanInfo.setName(splits.length >= 2 ? splits[1] : fileName);
            Vod vod = item.getVod(drive.getName(), drive.getVodPic());
            vod.setVodRemarks(item.doubanInfo.getRating());
            vod.setVodName(item.doubanInfo.getName());
            vod.doubanInfo = item.doubanInfo;
            vod.setVodId(vod.getVodId() + "/~xiaoya");
            if (TextUtils.isEmpty(item.getThumb())) {
                noPicList.add(vod);
            } else {
                list.add(vod);
            }
        }
        list.addAll(noPicList);
        return list;
    }

    public static void test() {

        // 第一次查询
        LocalIndexService service = LocalIndexService.get("http://zengge99.f3322.org:5678/");
        LinkedHashMap<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("subpath", "每日更新");
        queryParams.put("doubansort", "desc");
        List<String> result = service.query(queryParams);
        Logger.log("Query result1: " + result.get(0));

        // 测试分页
        Pager pagger = new Pager(result, 1000, true);
        Logger.log("Query result2: " + pagger.page(1));

        // service = LocalIndexService.get(
        //         "http://zengge99.1996999.xyz:5678/sou?box=%E6%AF%8F%E6%97%A5%E6%9B%B4%E6%96%B0&url=&type=video");
        // queryParams = new LinkedHashMap<>();
        // queryParams.put("doubansort", "desc");
        // service.slim("每日更新/电视剧/国产剧");
        // result = service.query(queryParams);
        // Logger.log("Query result3: " + result.get(0));
    }
}
