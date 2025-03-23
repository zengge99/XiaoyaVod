package com.github.catvod.bean.alist;

import java.io.*;
import java.util.*;
import com.github.catvod.spider.Logger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class LocalIndexService {

    private static final int MAX_LINES_IN_MEMORY = 5000; // 每个块的最大行数
    private static final Map<String, LocalIndexService> instances = new HashMap<>(); // 实例缓存
    private static final String BASE_DIR = com.github.catvod.utils.Path.root().getPath() + "/TV/index/"; // 基础目录

    private List<String> inputList;
    private List<String> outputList;
    private HashMap<String, List<String>> queryCache = new HashMap<>();

    private List<Long> lineIndex; // 存储每行的起始位置
    private Map<String, List<Integer>> invertedIndex; // 倒排索引：关键字 -> 行号列表
    private RandomAccessFile randomAccessFile; // 用于随机访问文件

    /**
     * 私有构造函数
     *
     * @param name 实例唯一名字
     */
    private LocalIndexService(String name) {
        inputList = new FileBasedList<String>(com.github.catvod.utils.Path.root().getPath() + "/TV/index.all.txt", String.class);
        outputList = new FileBasedList<String>(String.class);
    }

    /**
     * 获取实例（单例模式）
     *
     * @param name 实例唯一名字
     * @return LocalIndexService 实例
     */
    public static LocalIndexService get(String name) {
        Logger.log("Getting instance for name: " + name);
        if (!instances.containsKey(name)) {
            Logger.log("Creating new instance for name: " + name);
            instances.put(name, new LocalIndexService(name));
        }
        Logger.log("Instance retrieved successfully for name: " + name);
        return instances.get(name);
    }

    /**
     * 创建一个比较器，根据指定排序顺序进行比较
     *
     * @param order 排序顺序（"asc" 或 "desc"）
     * @return 比较器
     */
    private Comparator<String[]> createComparator(String order) {
        return (o1, o2) -> {
            double value1 = parseFieldAsDouble(o1, 3); // 固定为第4个字段
            double value2 = parseFieldAsDouble(o2, 3); // 固定为第4个字段
            if (order.equals("asc")) {
                return Double.compare(value1, value2); // 升序
            } else {
                return Double.compare(value2, value1); // 降序
            }
        };
    }

    /**
     * 解析字段为 double，如果字段不足、为空或非 double，则返回 0
     */
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

    public List<String> externalSort(List<String> inputSortList, List<String> outputSortList, String order) throws IOException {
        List<List<String>> sortedChunks = sortInChunks(inputSortList, order);
        return mergeSortedChunks(sortedChunks, outputSortList, order);
    }

    private List<List<String>> sortInChunks(List<String> inputSortList, String order) throws IOException {
        List<List<String>> sortedChunks = new ArrayList<>();
        List<String[]> chunk = new ArrayList<>();
        for (String line : inputSortList) {
            String[] fields = line.split("#");
            chunk.add(fields);
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

    private List<String> sortAndWriteChunk(List<String[]> chunk, String order) throws IOException {
        chunk.sort(createComparator(order));
        List<String> tempList = new FileBasedList<String>(String.class);
        for (String[] fields : chunk) {
            tempList.add(String.join("#", fields));
        }
        return tempList;
    }

    private List<String> mergeSortedChunks(List<List<String>> sortedChunks, List<String> outputSortList, String order) throws IOException {
        PriorityQueue<ListReader> minHeap = new PriorityQueue<>(
            Comparator.comparing(lr -> lr.currentFields, createComparator(order))
        );
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
                outputSortList.add(String.join("#", reader.currentFields));
                if (reader.readLine()) {
                    minHeap.add(reader);
                }
            }

        return outputSortList; // 返回合并后的文件路径
    }

    private void sortByDouban(List<String> inputSortList, List<String> outputSortList, String order) throws IOException {
        externalSort(inputSortList, outputSortList, order);
        Logger.log("Sorted by field: " + order);
    }

    public List<String> page(int pageNum) {
        int linesPerPage = 72;
        int startLine = (pageNum - 1) * linesPerPage;
        int endLine = Math.min(startLine + 72, outputList.size());
        return outputList.subList(startLine, endLine);
    }

    public String getLine(int lineNum) throws IOException {
        return outputList.get(lineNum);
    }

    /**
     * 查询方法
     *
     * @param queryParams 查询参数，key 是查询方法，value 是查询参数
     * @return 最终结果文件名
     * @throws IOException 如果文件读写失败
     */
    public List<String> query(LinkedHashMap<String, String> queryParams) throws IOException {
        List<String> currentInputList = inputList;
        try {
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
                    case "save":
                        this.inputList = currentInputList;
                        tempOutputList = currentInputList;
                        break;
                    case "doubansort":
                        sortByDouban(currentInputList, tempOutputList, param);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown query method: " + method);
                }
                
                // 更新当前输入文件
                currentInputList = tempOutputList;
            }

            queryCache.put(cacheKey, currentInputList);

            return currentInputList;

        } finally {
            this.outputList = currentInputList;
        }
    }

    /**
     * 生成缓存键（MD5 哈希）
     *
     * @param queryParams 查询参数
     * @return 缓存键
     */
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

    /**
     * 按字段过滤
     *
     * @param inputFile  输入文件路径
     * @param outputFile 输出文件路径
     * @param fieldValue 字段值
     * @throws IOException 如果文件读写失败
     */
    private void filterByPath(List<String> inputSortList, List<String> outputSortList, String fieldValue) throws IOException {
        for (String line : inputSortList) {
            String[] fields = line.split("#");
            if (fields.length > 0 && fields[0].startsWith(fieldValue)) {
                outputSortList.add(line);
            }
        }
        Logger.log("Filtered by field: " + fieldValue);
    }

    /**
     * 用于读取文件行的辅助类
     */
    private static class ListReader {
        private final List<String> list;
        private String[] currentFields;
        private int currentLine = 0;

        public ListReader(List<String> list) throws FileNotFoundException, UnsupportedEncodingException {
            this.list = list;
        }

        public boolean readLine() throws IOException {
            if (currentLine < list.size()) 
            {
                String line = list.get(currentLine++);
                currentFields = line.split("#");
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * 测试方法
     */
    public static void test() {
        try {
            LocalIndexService service = LocalIndexService.get("example:test/1");

            // 第一次查询
            LinkedHashMap<String, String> queryParams = new LinkedHashMap<>();
            queryParams.put("subpath", "每日更新");     // 按字段降序排序
            queryParams.put("save", ""); 
            List<String> result = service.query(queryParams);
            Logger.log("Query result1: " + result.get(0));

            // 第二次查询
            queryParams = new LinkedHashMap<>();
            queryParams.put("doubansort", "desc");     // 按字段降序排序
            result = service.query(queryParams);
            Logger.log("Query result2: " + result.get(0));

            // 测试分页
            Logger.log("Page 1: ");
            Logger.log(service.page(1));

        } catch (IOException e) {
            Logger.log(e);
        }
    }
}
