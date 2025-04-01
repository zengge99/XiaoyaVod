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
    private static final int MAX_LINES_IN_MEMORY = 10000;
    private static final Map<String, LocalIndexService> instances = new HashMap<>();
    private List<String> inputList;
    private HashMap<String, List<String>> queryCache = new HashMap<>();
    private static HashMap<String, List<String>> inputCache = new HashMap<>();
    private boolean slimed = false;
    private Map<String, List<Integer>> invertedIndex;
    private String startPath;

    public static LocalIndexService get(String url) {
        return get(url, "");
    }

    public static LocalIndexService get(Drive drive) {
        return get(drive.getName() + "/" + drive.getServer(), drive.getPath());
    }

    public static LocalIndexService get(String url, String startPath) {
        Logger.log("get() called for url: " + url);
        long startTime = System.currentTimeMillis();
        try {
            String realUrl = url;
            if (!url.startsWith("http")) {
                realUrl = url.substring(url.indexOf("/") + 1);
            }
            if (isOnline(realUrl)) {
                Logger.log("Creating new online instance");
                return new LocalIndexService(realUrl, startPath);
            }
            if (!instances.containsKey(url)) {
                Logger.log("Creating and caching new instance");
                instances.put(url, new LocalIndexService(realUrl, startPath));
            } else {
                Logger.log("Using cached instance");
            }
            return instances.get(url);
        } catch (Throwable e) {
            Logger.log("get() error: " + e.toString());
            return null;
        } finally {
            Logger.log("get() completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    public List<String> quickSearch(String keyword) {
        Logger.log("quickSearch for keyword: " + keyword);
        long startTime = System.currentTimeMillis();
        try {
            if (invertedIndex == null) {
                throw new IllegalStateException("Inverted index not built. Call slim() first.");
            }
            List<Integer> lineNumbers = invertedIndex.get(keyword);
            lineNumbers = lineNumbers != null ? lineNumbers : new ArrayList<>();
            List<String> result = new ArrayList<>();
            for (int lineNumber : lineNumbers) {
                result.add(inputList.get(lineNumber));
            }
            return result;
        } catch (Throwable e) {
            Logger.log("quickSearch() error: " + e.toString());
            return new ArrayList<>();
        } finally {
            Logger.log("quickSearch completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    public List<String> query(LinkedHashMap<String, String> queryParams) {
        Logger.log("query() called with params: " + queryParams);
        long startTime = System.currentTimeMillis();
        try {
            List<String> currentInputList = inputList;
            if (queryParams.containsKey("random")) {
                queryParams.remove("random");
            }
            String cacheKey = generateCacheKey(queryParams);
            if (queryCache.get(cacheKey) != null) {
                Logger.log("Query cache hit for key: " + cacheKey);
                currentInputList = queryCache.get(cacheKey);
                return currentInputList;
            }
            Logger.log("Query cache miss for key: " + cacheKey);
            
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                String method = entry.getKey();
                String param = entry.getValue();
                List<String> tempOutputList;
                
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
                currentInputList = tempOutputList;
            }
            queryCache.put(cacheKey, currentInputList);
            return currentInputList;
        } catch (Throwable e) {
            Logger.log("query() error: " + e.toString());
            return new ArrayList<>();
        } finally {
            Logger.log("query() completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private LocalIndexService(String url, String startPath) {
        Logger.log("LocalIndexService constructor start - url:" + url);
        long startTime = System.currentTimeMillis();
        this.startPath = startPath;
        try {
            if (isOnline(url)) {
                Logger.log("Online mode detected");
                inputList = creatHugeList();
                
                Logger.log("Starting HTTP request");
                long httpStart = System.currentTimeMillis();
                Document doc = Jsoup.parse(OkHttp.string(url));
                Logger.log("HTTP request completed in " + (System.currentTimeMillis() - httpStart) + "ms");
                
                Logger.log("Parsing HTML with " + doc.select("ul > a").size() + " elements");
                long parseStart = System.currentTimeMillis();
                for (Element a : doc.select("ul > a")) {
                    String line = a.text();
                    if (!line.contains("/")) continue;
                    inputList.add(a.text());
                }
                Logger.log("HTML parsed in " + (System.currentTimeMillis() - parseStart) + "ms");
                
                Logger.log("Starting slim operation");
                slim(startPath);
            } else {
                Logger.log("Offline mode detected");
                long downloadStart = System.currentTimeMillis();
                String filePath = IndexDownloader.downlodadAndUnzip(url);
                Logger.log("File downloaded in " + (System.currentTimeMillis() - downloadStart) + "ms, path: " + filePath);
                
                Logger.log("Initializing file list");
                long initListStart = System.currentTimeMillis();
                inputList = inputCache.get(filePath);
                if (inputList == null) {
                    Logger.log("Cache miss, initializing new list");
                    inputList = creatHugeList(filePath);
                    inputCache.put(filePath, inputList);
                    Logger.log("List initialized with size: " + inputList.size());
                } else {
                    Logger.log("Cache hit, using existing list size: " + inputList.size());
                }
                Logger.log("List init took " + (System.currentTimeMillis() - initListStart) + "ms");
                
                Logger.log("Starting slim operation");
                slim(startPath);
            }
        } catch (Throwable e) {
            Logger.log("Constructor error: " + e.toString());
            throw new RuntimeException("初始化失败", e);
        } finally {
            Logger.log("Constructor completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private List<String> creatHugeList() {
        Logger.log("Creating empty FileBasedList");
        return new FileBasedList<String>(String.class);
    }

    private List<String> creatHugeList(String filePath) {
        Logger.log("Creating FileBasedList from: " + filePath);
        return new FileBasedList<String>(filePath, String.class);
    }

    private static boolean isOnline(String path) {
        boolean result = path.contains("/sou?");
        Logger.log("isOnline check for " + path + ": " + result);
        return result;
    }

    private List<String> externalSort(List<String> inputSortList, String order) throws IOException {
        Logger.log("externalSort start with order: " + order);
        long startTime = System.currentTimeMillis();
        try {
            List<List<String>> sortedChunks = sortInChunks(inputSortList, order);
            return mergeSortedChunks(sortedChunks, order);
        } catch (Throwable e) {
            Logger.log("externalSort() error: " + e.toString());
            return new ArrayList<>();
        } finally {
            Logger.log("externalSort completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private List<List<String>> sortInChunks(List<String> inputSortList, String order) throws IOException {
        Logger.log("sortInChunks start");
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
        } catch (Throwable e) {
            Logger.log("sortInChunks() error: " + e.toString());
            return new ArrayList<>();
        } finally {
            Logger.log("sortInChunks completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private List<String> sortAndWriteChunk(List<String> chunk, String order) throws IOException {
        Logger.log("sortAndWriteChunk start, chunk size: " + chunk.size());
        long startTime = System.currentTimeMillis();
        try {
            chunk.sort(createComparator(order));
            List<String> tempList = creatHugeList();
            for (String line : chunk) {
                tempList.add(line);
            }
            return tempList;
        } catch (Throwable e) {
            Logger.log("sortAndWriteChunk() error: " + e.toString());
            return new ArrayList<>();
        } finally {
            Logger.log("sortAndWriteChunk completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private List<String> mergeSortedChunks(List<List<String>> sortedChunks, String order) throws IOException {
        Logger.log("mergeSortedChunks start with " + sortedChunks.size() + " chunks");
        long startTime = System.currentTimeMillis();
        try {
            PriorityQueue<ListReader> minHeap = new PriorityQueue<>(
                Comparator.comparing(lr -> lr.currentLine, createComparator(order)));
            
            for (List<String> trunk : sortedChunks) {
                ListReader reader = new ListReader(trunk);
                if (reader.readLine()) {
                    minHeap.add(reader);
                }
            }
            
            List<String> outputSortList = creatHugeList();
            while (!minHeap.isEmpty()) {
                ListReader reader = minHeap.poll();
                outputSortList.add(reader.currentLine);
                if (reader.readLine()) {
                    minHeap.add(reader);
                }
            }
            return outputSortList;
        } catch (Throwable e) {
            Logger.log("mergeSortedChunks() error: " + e.toString());
            return new ArrayList<>();
        } finally {
            Logger.log("mergeSortedChunks completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private Comparator<String> createComparator(String order) {
        Logger.log("createComparator with order: " + order);
        return (o1, o2) -> {
            double value1 = parseFieldAsDouble(o1.split("#"), 3);
            double value2 = parseFieldAsDouble(o2.split("#"), 3);
            if (order.equals("2")) {
                return Double.compare(value1, value2);
            } else {
                return Double.compare(value2, value1);
            }
        };
    }

    private double parseFieldAsDouble(String[] fields, int index) {
        if (fields == null || fields.length <= index) {
            return 0.0;
        }
        String field = fields[index];
        if (field == null || field.trim().isEmpty()) {
            return 0.0;
        }
        try {
            double value = Double.parseDouble(field);
            if (index == 3 && value > 10) {
                value = 0;
            }
            return value;
        } catch (NumberFormatException e) {
            return 0.0;
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

    private List<String> slim(String path) {
        Logger.log("slim() called for path: " + path);
        long startTime = System.currentTimeMillis();
        try {
            if (slimed) {
                Logger.log("Already slimed, returning");
                return inputList;
            }
            if (path.startsWith("/")) {
                path = path.substring(1);
                Logger.log("Adjusted path to: " + path);
            }
            
            Logger.log("Starting filterByPath on list size: " + inputList.size());
            List<String> outputSortList = filterByPath(inputList, path);
            Logger.log("filterByPath result size: " + outputSortList.size());
            
            Logger.log("Building inverted index");
            inputList = outputSortList;
            buildInvertedIndex();
            slimed = true;
            
            return inputList;
        } catch (Throwable e) {
            Logger.log("slim() error: " + e.toString());
            return new ArrayList<>();
        } finally {
            Logger.log("slim() completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private void buildInvertedIndex() {
        Logger.log("buildInvertedIndex start");
        long startTime = System.currentTimeMillis();
        try {
            invertedIndex = new HashMap<String, List<Integer>>(); // 显式指定泛型
            int i = 0;
            for (String line : inputList) {
                String[] fields = line.split("#");
                if (fields.length >= 2) {
                    String keyword = fields[1].trim();
                    
                    // 完全兼容 Android 4.4 的实现
                    if (!invertedIndex.containsKey(keyword)) {
                        invertedIndex.put(keyword, new ArrayList<Integer>());
                    }
                    invertedIndex.get(keyword).add(i);
                }
                i++;
            }
            Logger.log("Inverted index built with " + invertedIndex.size() + " keywords");
        } catch (Throwable e) {
            Logger.log("buildInvertedIndex error: " + e.toString());
        } finally {
            Logger.log("buildInvertedIndex completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private String generateCacheKey(LinkedHashMap<String, String> queryParams) {
        Logger.log("generateCacheKey start");
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
            Logger.log("generateCacheKey completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }


    private List<String> filterByPath(List<String> inputSortList, String fieldValue) throws IOException {
        Logger.log("filterByPath with value: " + fieldValue);
        long startTime = System.currentTimeMillis();
        try {
            if (fieldValue.startsWith("/")) {
                fieldValue = fieldValue.substring(1);
                Logger.log("Adjusted fieldValue to: " + fieldValue);
            }
            if (fieldValue.isEmpty()) {
                return inputSortList;
            }
            Logger.log("Filtering list size: " + inputSortList.size());
            List<String> outputSortList = creatHugeList();
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
            Logger.log("Filter results - with pic: " + outputSortList.size() + ", no pic: " + noPicList.size());
            outputSortList.addAll(noPicList);
            return outputSortList;
        } catch (Throwable e) {
            Logger.log("filterByPath() error: " + e.toString());
            return new ArrayList<>();
        } finally {
            Logger.log("filterByPath completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private List<String> filterByDouban(List<String> inputSortList, String fieldValue) throws IOException {
        Logger.log("filterByDouban with value: " + fieldValue);
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
        } catch (Throwable e) {
            Logger.log("filterByDouban() error: " + e.toString());
            return new ArrayList<>();
        } finally {
            Logger.log("filterByDouban completed in " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private List<String> sortByDouban(List<String> inputSortList, String order) throws IOException {
        long startTime = System.currentTimeMillis();
        try {
        Logger.log("sortByDouban with order: " + order);
        return externalSort(inputSortList, order);
        } catch (Throwable e) {
            Logger.log("sortByDouban() error: " + e.toString());
            return new ArrayList<>();
        } finally {
            Logger.log("sortByDouban耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    public static void test() {
        Logger.log("test() started");
        LocalIndexService service = LocalIndexService.get("http://xxx:5678/");
        LinkedHashMap<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("subpath", "每日更新");
        queryParams.put("doubansort", "desc");
        List<String> result = service.query(queryParams);
        Logger.log("test() result: " + result.get(0));
    }
}
