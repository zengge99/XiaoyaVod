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
import java.nio.file.Paths;
import java.nio.file.Files;

public class LocalIndexService {

    private static final int MAX_LINES_IN_MEMORY = 10000; // 每个块的最大行数
    private static final Map<String, LocalIndexService> instances = new HashMap<>(); // 实例缓存

    private List<String> inputList;
    private HashMap<String, List<String>> queryCache = new HashMap<>();
    private static HashMap<String, List<String>> inputCache = new HashMap<>();
    private boolean slimed = false;
    private Map<String, List<Integer>> invertedIndex; // 倒排索引，保存行号
    private String startPath;

    private LocalIndexService(String url, String startPath) {
        long startTime = System.currentTimeMillis();
        this.startPath = startPath;
        try {
            if (isOnline(url)) {
                inputList = creatHugeList();
                long httpStart = System.currentTimeMillis();
                Document doc = Jsoup.parse(OkHttp.string(url));
                Logger.log("网络请求耗时: " + (System.currentTimeMillis() - httpStart) + "ms");

                long parseStart = System.currentTimeMillis();
                for (Element a : doc.select("ul > a")) {
                    String line = a.text();
                    if (!line.contains("/")) continue;
                    inputList.add(a.text());
                }
                slim(startPath);
                Logger.log("HTML解析耗时: " + (System.currentTimeMillis() - parseStart) + "ms");
            } else {
                long downloadStart = System.currentTimeMillis();
                String filePath = IndexDownloader.downlodadAndUnzip(url); // 下载并解压文件
                Logger.log("文件下载解压耗时: " + (System.currentTimeMillis() - downloadStart) + "ms");

                long initListStart = System.currentTimeMillis();
                inputList = inputCache.get(filePath);
                if (inputList == null) {
                    Logger.log("缓存未命中，初始化 FileBasedList，filePath：" + filePath);
                    inputList = creatHugeList(filePath); // 初始化 FileBasedList
                    inputCache.put(filePath, inputList); // 放入缓存
                    Logger.log("初始化 FileBasedList 耗时: " + (System.currentTimeMillis() - initListStart) + "ms");
                } else {
                    Logger.log("缓存命中，直接使用缓存数据");
                }
                slim(startPath);
            }
        } finally {
            Logger.log("LocalIndexService初始化总耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private List<String> creatHugeList() {
        // return new ArrayList<>();
        return new FileBasedList<String>(String.class);
    }

    private List<String> creatHugeList(String filePath) {
        // try {
        //     return Files.readAllLines(Paths.get(filePath));
        // } catch (Exception e) {
        //     return new ArrayList<>();
        // }
        return new FileBasedList<String>(filePath, String.class);
    }

    private static boolean isOnline(String path) {
        return path.contains("/sou?");
    }

    public static LocalIndexService get(String url) {
        return LocalIndexService.get(url, "");
    }

    public static LocalIndexService get(Drive drive) {
        return LocalIndexService.get(drive.getName() + "/" + drive.getServer(), drive.getPath());
    }

    public static LocalIndexService get(String url, String startPath) {
        long startTime = System.currentTimeMillis();
        try {
            String realUrl = url;
            if (!url.startsWith("http")) {
                realUrl = url.substring(url.indexOf("/") + 1);
            }

            if (isOnline(realUrl)) {
                return new LocalIndexService(realUrl, startPath);
            }

            if (!instances.containsKey(url)) {
                instances.put(url, new LocalIndexService(realUrl, startPath));
            }

            return instances.get(url);
        } finally {
            Logger.log("LocalIndexService.get总耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    public List<String> externalSort(List<String> inputSortList, String order)
            throws IOException {
        long startTime = System.currentTimeMillis();
        try {
            List<List<String>> sortedChunks = sortInChunks(inputSortList, order);
            return mergeSortedChunks(sortedChunks, order);
        } finally {
            Logger.log("externalSort总耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private List<List<String>> sortInChunks(List<String> inputSortList, String order) throws IOException {
        long startTime = System.currentTimeMillis();
        try {
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
        } finally {
            Logger.log("sortInChunks总耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private List<String> sortAndWriteChunk(List<String> chunk, String order) throws IOException {
        long startTime = System.currentTimeMillis();
        try {
            chunk.sort(createComparator(order));
            List<String> tempList = creatHugeList();
            for (String line : chunk) {
                tempList.add(line);
            }
            return tempList;
        } finally {
            Logger.log("sortAndWriteChunk耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private List<String> mergeSortedChunks(List<List<String>> sortedChunks, String order)
            throws IOException {
        long startTime = System.currentTimeMillis();
        try {
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
            List<String> outputSortList = creatHugeList();
            while (!minHeap.isEmpty()) {
                ListReader reader = minHeap.poll();
                outputSortList.add(reader.currentLine);
                if (reader.readLine()) {
                    minHeap.add(reader);
                }
            }

            return outputSortList; // 返回合并后的文件路径
        } finally {
            Logger.log("mergeSortedChunks耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private Comparator<String> createComparator(String order) {
        return (o1, o2) -> {
            double value1 = parseFieldAsDouble(o1.split("#"), 3);
            double value2 = parseFieldAsDouble(o2.split("#"), 3);
            if (order.equals("2")) {
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
            double value = Double.parseDouble(field);
            if (index == 3 && value > 10) {
                value = 0;
            }
            return value; 
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
        long startTime = System.currentTimeMillis();
        try {
            if (slimed) {
                return inputList;
            }
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            long filterStart = System.currentTimeMillis();
            List<String> outputSortList = filterByPath(inputList, path);

            long indexStart = System.currentTimeMillis();
            inputList = outputSortList;

            buildInvertedIndex();
            
            slimed = true;
            return inputList;
        } catch (Exception e) {
            Logger.log(e);
            return new ArrayList<>();
        } finally {
            Logger.log("slim方法总耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    public List<String> quickSearch(String keyword) {
        long startTime = System.currentTimeMillis();
        try {
            if (invertedIndex == null) {
                throw new IllegalStateException("Inverted index not built. Call slim() first.");
            }
            List<Integer> lineNumbers = invertedIndex.getOrDefault(keyword, Collections.emptyList());
            List<String> result = new ArrayList<>();
            for (int lineNumber : lineNumbers) {
                result.add(inputList.get(lineNumber)); // 根据行号获取原始数据
            }
            return result;
        } finally {
            Logger.log("quickSearch耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private void buildInvertedIndex() {
        long startTime = System.currentTimeMillis();
        try {
            invertedIndex = new HashMap<>();
            int i = 0;
            for (String line : inputList) {
                String[] fields = line.split("#");
                if (fields.length >= 2) {
                    String keyword = fields[1].trim(); // 第二个字段作为关键字
                    invertedIndex.computeIfAbsent(keyword, k -> new ArrayList<>()).add(i); // 保存行号
                }
                i++;
            }
        } finally {
            Logger.log("buildInvertedIndex耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private List<String> sortByDouban(List<String> inputSortList, String order)
            throws IOException {
        long startTime = System.currentTimeMillis();
        try {
            Logger.log("Sorted by field: " + order);
            return externalSort(inputSortList, order);
        } finally {
            Logger.log("sortByDouban耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    public List<String> query(LinkedHashMap<String, String> queryParams) {
        long startTime = System.currentTimeMillis();
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

                List<String> tempOutputList;

                // 执行查询方法
                switch (method) {
                    case "subpath":
                        tempOutputList = filterByPath(currentInputList, param);
                        break;
                    case "doubansort":
                        tempOutputList = sortByDouban(currentInputList, param);
                        break;
                    case "douban":
                        tempOutputList = filterByDouban(currentInputList, param);
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
        } finally {
            Logger.log("query方法总耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private String generateCacheKey(LinkedHashMap<String, String> queryParams) {
        long startTime = System.currentTimeMillis();
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
        } finally {
            Logger.log("generateCacheKey耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private List<String> filterByPath(List<String> inputSortList, String fieldValue)
            throws IOException {
        long startTime = System.currentTimeMillis();
        try {
            if (fieldValue.startsWith("/")) {
                fieldValue = fieldValue.substring(1);
            }
            Logger.log("Filtered by field: " + fieldValue);
            List<String> outputSortList;
            if (fieldValue.isEmpty()) {
                outputSortList = inputSortList;
                return outputSortList;
            }
            outputSortList = creatHugeList();
            List<String> noPicList = creatHugeList();
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
            return outputSortList;
        } finally {
            Logger.log("filterByPath耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private List<String> filterByDouban(List<String> inputSortList, String fieldValue)
            throws IOException {
        long startTime = System.currentTimeMillis();
        try {
            double filterValue = parseStringAsDouble(fieldValue);
            List<String> outputSortList = creatHugeList();
            for (String line : inputSortList) {
                double actualValue = parseFieldAsDouble(line.split("#"), 3);
                if (actualValue >= filterValue) {
                    outputSortList.add(line);
                }
            }
            return outputSortList;
        } finally {
            Logger.log("filterByDouban耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    public Vod findVodByPath(Drive drive, String path) {
        long startTime = System.currentTimeMillis();
        try {
            String normalizedPath = normalizePath(path);

            List<String> input = new ArrayList<>();
            for (String line : inputList) {
                String[] splits = line.split("#");
                String normalizedTargetPath = normalizePath(splits[0]);

                if (normalizedTargetPath.equals(normalizedPath)) {
                    input.add(line);
                    break;
                }
            }

            if (input.size() > 0) {
                return toVods(drive, input).get(0);
            }
            return null;
        } finally {
            Logger.log("findVodByPath耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    public static List<Vod> toVods(Drive drive, List<String> lines) {
        long startTime = System.currentTimeMillis();
        try {
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
        } finally {
            Logger.log("toVods耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    public static void test() {

        // 第一次查询
        LocalIndexService service = LocalIndexService.get("http://xxx:5678/");
        LinkedHashMap<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("subpath", "每日更新");
        queryParams.put("doubansort", "desc");
        List<String> result = service.query(queryParams);
        Logger.log("Query result1: " + result.get(0));

        // 测试分页
        Pager pagger = new Pager(result, 1000, true);
        Logger.log("Query result2: " + pagger.page(1));

        // service = LocalIndexService.get(
        //         "http://xxx:5678/sou?box=%E6%AF%8F%E6%97%A5%E6%9B%B4%E6%96%B0&url=&type=video");
        // queryParams = new LinkedHashMap<>();
        // queryParams.put("doubansort", "desc");
        // service.slim("每日更新/电视剧/国产剧");
        // result = service.query(queryParams);
        // Logger.log("Query result3: " + result.get(0));
    }
}
