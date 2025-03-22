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

    private String inputFilePath; // 输入文件路径
    private String cacheDirPath; // 缓存目录路径
    private String outputFilePath; // 输出文件路径
    private HashMap<String, String> queryCache = new HashMap<>();

    private List<Long> lineIndex; // 存储每行的起始位置
    private Map<String, List<Integer>> invertedIndex; // 倒排索引：关键字 -> 行号列表
    private RandomAccessFile randomAccessFile; // 用于随机访问文件

    /**
     * 私有构造函数
     *
     * @param name 实例唯一名字
     */
    private LocalIndexService(String name) {
        String sanitizedName = sanitizeName(name); // 处理特殊字符
        this.inputFilePath = com.github.catvod.utils.Path.root().getPath() + "/TV/index.all.txt"; // 输入文件路径
        this.cacheDirPath = BASE_DIR + "cache/" + sanitizedName; // 缓存目录路径
        Logger.log("Input file path: " + inputFilePath);
        Logger.log("Cache directory path: " + cacheDirPath);
        createCacheDir(); // 创建缓存目录
        Logger.log("Output file path: " + outputFilePath);
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
     * 删除整个 /TV/index/ 目录及其内容
     */
    public static void deleteAllIndex() {
        File baseDir = new File(BASE_DIR);
        if (baseDir.exists()) {
            deleteDirectory(baseDir);
            Logger.log("Deleted base directory: " + BASE_DIR);
        } else {
            Logger.log("Base directory does not exist: " + BASE_DIR);
        }
    }

    /**
     * 删除目录及其内容
     *
     * @param directory 目录
     */
    private static void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    if (!file.delete()) {
                        Logger.log("Failed to delete file: " + file.getAbsolutePath());
                    }
                }
            }
        }
        if (!directory.delete()) {
            Logger.log("Failed to delete directory: " + directory.getAbsolutePath());
        }
    }

    /**
     * 处理 name 中的特殊字符（如 : 和 /），转换为 _
     */
    private static String sanitizeName(String name) {
        return name.replaceAll("[:/]", "_");
    }

    /**
     * 创建缓存目录
     */
    private void createCacheDir() {
        File cacheDir = new File(cacheDirPath);
        Logger.log("Attempting to create cache directory: " + cacheDirPath);
        if (!cacheDir.exists()) {
            if (cacheDir.mkdirs()) {
                Logger.log("Cache directory created successfully: " + cacheDirPath);
            } else {
                Logger.log("Failed to create cache directory: " + cacheDirPath);
            }
        } else {
            Logger.log("Cache directory already exists: " + cacheDirPath);
        }
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

    /**
     * 外部排序的主方法
     *
     * @param inputFile  输入文件路径
     * @param outputFile 输出文件路径
     * @param order      排序顺序（"asc" 或 "desc"")
     * @return 合并后的临时文件路径
     * @throws IOException 如果文件读写失败
     */
    public String externalSort(String inputFile, String outputFile, String order) throws IOException {
        List<File> sortedChunks = sortInChunks(inputFile, order);
        return mergeSortedChunks(sortedChunks, outputFile, order);
    }

    /**
     * 将文件分块排序，返回排序后的临时文件列表
     *
     * @param inputFile 输入文件路径
     * @param order     排序顺序
     * @return 排序后的临时文件列表
     * @throws IOException 如果文件读写失败
     */
    private List<File> sortInChunks(String inputFile, String order) throws IOException {
        List<File> sortedChunks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile), "UTF-8"))) {
            List<String[]> chunk = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
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
        }
        return sortedChunks;
    }

    /**
     * 对内存中的块进行排序并写入临时文件
     *
     * @param chunk 内存中的数据块
     * @param order 排序顺序
     * @return 临时文件
     * @throws IOException 如果文件读写失败
     */
    private File sortAndWriteChunk(List<String[]> chunk, String order) throws IOException {
        chunk.sort(createComparator(order));
        File tempFile = File.createTempFile("sortedChunk", ".txt", new File(cacheDirPath));
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tempFile), "UTF-8"))) {
            for (String[] fields : chunk) {
                writer.write(String.join("#", fields));
                writer.newLine();
            }
        }
        return tempFile;
    }

    /**
     * 合并所有排序后的临时文件
     *
     * @param sortedChunks 排序后的临时文件列表
     * @param outputFile   输出文件路径
     * @param order        排序顺序
     * @return 合并后的文件路径
     * @throws IOException 如果文件读写失败
     */
    private String mergeSortedChunks(List<File> sortedChunks, String outputFile, String order) throws IOException {
        PriorityQueue<BufferedLineReader> minHeap = new PriorityQueue<>(
            Comparator.comparing(br -> br.currentFields, createComparator(order))
        );
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile), "UTF-8"))) {
            // 初始化堆
            for (File file : sortedChunks) {
                BufferedLineReader reader = new BufferedLineReader(file);
                if (reader.readLine()) {
                    minHeap.add(reader);
                }
            }
            // 多路归并
            while (!minHeap.isEmpty()) {
                BufferedLineReader reader = minHeap.poll();
                writer.write(String.join("#", reader.currentFields));
                writer.newLine();
                if (reader.readLine()) {
                    minHeap.add(reader);
                }
            }
        }
        // 删除临时文件
        for (File file : sortedChunks) {
            file.delete();
        }
        return outputFile; // 返回合并后的文件路径
    }

    /**
     * 按字段排序（使用外部排序）
     *
     * @param inputFile  输入文件路径
     * @param outputFile 输出文件路径
     * @param order      排序顺序（"asc" 或 "desc"")
     * @throws IOException 如果文件读写失败
     */
    private void sortByDouban(String inputFile, String outputFile, String order) throws IOException {
        externalSort(inputFile, outputFile, order);
        Logger.log("Sorted by field: " + order);
    }

    /**
     * 分页获取文件内容
     *
     * @param pageNum 页码，从 1 开始
     * @return 该页的内容列表
     */
    public List<String> page(int pageNum) {
        List<String> pageContent = new ArrayList<>();
        int linesPerPage = 72;
        int startLine = (pageNum - 1) * linesPerPage;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(outputFilePath), "UTF-8"))) {
            String line;
            int currentLine = 0;

            // 跳过前面的行
            while (currentLine < startLine && (line = reader.readLine()) != null) {
                currentLine++;
            }

            // 读取当前页的内容
            while (currentLine < startLine + linesPerPage && (line = reader.readLine()) != null) {
                pageContent.add(line);
                currentLine++;
            }
        } catch (IOException e) {
            Logger.log("Error reading file: " + e.getMessage());
        }

        return pageContent;
    }

    /**
     * 构建行索引和倒排索引
     *
     * @throws IOException 如果文件读取失败
     */
    public void buildIndex() throws IOException {
        lineIndex = new ArrayList<>();
        invertedIndex = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(outputFilePath), "UTF-8"))) {
            String line;
            int lineNum = 0;
            long position = 0;
            while ((line = reader.readLine()) != null) {
                // 记录每行的起始位置
                lineIndex.add(position);
                position += line.getBytes("UTF-8").length + System.lineSeparator().getBytes("UTF-8").length; // 加上换行符的长度

                // 构建倒排索引
                String[] fields = line.split("#");
                if (fields.length > 1) {
                    String keyword = fields[1]; // 以第二个字段为关键字
                    invertedIndex.computeIfAbsent(keyword, k -> new ArrayList<>()).add(lineNum);
                }

                lineNum++;
            }
        }
        // 初始化 RandomAccessFile
        randomAccessFile = new RandomAccessFile(outputFilePath, "r");
    }

    /**
     * 获取指定行的内容
     *
     * @param lineNum 行号，从 0 开始
     * @return 该行的内容
     * @throws IOException 如果文件读取失败
     */
    public String getLine(int lineNum) throws IOException {
        if (lineIndex == null || randomAccessFile == null) {
            throw new IllegalStateException("Index not built. Call buildIndex() first.");
        }
        if (lineNum < 0 || lineNum >= lineIndex.size()) {
            throw new IllegalArgumentException("Line number out of range.");
        }

        try (
            RandomAccessFile randomAccessFile = new RandomAccessFile(outputFilePath, "r");
            InputStreamReader reader = new InputStreamReader(new FileInputStream(randomAccessFile.getFD()), "UTF-8");
            BufferedReader bufferedReader = new BufferedReader(reader)
        ) {
            long position = lineIndex.get(lineNum); // 获取指定行的起始位置
            randomAccessFile.seek(position); // 跳转到指定位置

            String line = bufferedReader.readLine(); // 读取该行内容
            if (line != null) {
                return line;
            } else {
                throw new IllegalStateException("Failed to read the specified line");
            }
        }
    }

    /**
     * 快速搜索关键字对应的行内容列表
     *
     * @param keyword 关键字
     * @return 关键字对应的行内容列表
     * @throws IOException 如果文件读取失败
     */
    public List<String> quickSearch(String keyword) throws IOException {
        if (invertedIndex == null) {
            throw new IllegalStateException("Index not built. Call buildIndex() first.");
        }
        List<String> result = new ArrayList<>();
        List<Integer> lineNumbers = invertedIndex.get(keyword);
        if (lineNumbers != null) {
            for (int lineNum : lineNumbers) {
                result.add(getLine(lineNum));
            }
        }
        return result;
    }

    /**
     * 关闭 RandomAccessFile
     *
     * @throws IOException 如果关闭失败
     */
    public void close() throws IOException {
        if (randomAccessFile != null) {
            randomAccessFile.close();
        }
    }

    /**
     * 查询方法
     *
     * @param queryParams 查询参数，key 是查询方法，value 是查询参数
     * @return 最终结果文件名
     * @throws IOException 如果文件读写失败
     */
    public String query(LinkedHashMap<String, String> queryParams) throws IOException {
        String currentInputFile = inputFilePath;
        try {
            String cacheKey = generateCacheKey(queryParams);
            if (queryCache.get(cacheKey) != null) {
                Logger.log("Cache hit for query: " + cacheKey);
                currentInputFile = queryCache.get(cacheKey);
                return currentInputFile;
            }
            Logger.log("Cache miss for query: " + cacheKey);

            // 依次处理查询方法
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                String method = entry.getKey();
                String param = entry.getValue();

                // 生成临时文件路径
                String tempOutputFile = cacheDirPath + File.separator + UUID.randomUUID() + ".txt";

                boolean reserveFile = false;
                // 执行查询方法
                switch (method) {
                    case "subpath":
                        filterByPath(currentInputFile, tempOutputFile, param);
                        break;
                    case "save":
                        this.inputFilePath = currentInputFile;
                        tempOutputFile = currentInputFile;
                        reserveFile = true;
                        break;
                    case "doubansort":
                        sortByDouban(currentInputFile, tempOutputFile, param);
                        break;
                    case "limit":
                        limitRows(currentInputFile, tempOutputFile, Integer.parseInt(param));
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown query method: " + method);
                }

                if (!reserveFile && !currentInputFile.equals(this.inputFilePath)) {
                    new File(currentInputFile).delete();
                }
                
                // 更新当前输入文件
                currentInputFile = tempOutputFile;
            }

            queryCache.put(cacheKey, currentInputFile);

            return currentInputFile;

        } finally {
            this.outputFilePath = currentInputFile;
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
    private void filterByPath(String inputFile, String outputFile, String fieldValue) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile), "UTF-8"));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("#");
                if (fields.length > 0 && fields[0].startsWith(fieldValue)) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
        Logger.log("Filtered by field: " + fieldValue);
    }

    /**
     * 限制行数
     *
     * @param inputFile  输入文件路径
     * @param outputFile 输出文件路径
     * @param limit      行数限制
     * @throws IOException 如果文件读写失败
     */
    private void limitRows(String inputFile, String outputFile, int limit) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile), "UTF-8"));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile), "UTF-8"))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < limit) {
                writer.write(line);
                writer.newLine();
                count++;
            }
        }
        Logger.log("Limited rows: " + limit);
    }

    /**
     * 用于读取文件行的辅助类
     */
    private static class BufferedLineReader {
        private final BufferedReader reader;
        private String[] currentFields;

        public BufferedLineReader(File file) throws FileNotFoundException, UnsupportedEncodingException {
            this.reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        }

        public boolean readLine() throws IOException {
            String line = reader.readLine();
            if (line != null) {
                currentFields = line.split("#");
                return true;
            } else {
                reader.close();
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
            String resultFile = service.query(queryParams);
            Logger.log("Query result file1: " + resultFile);

            // 第二次查询
            queryParams = new LinkedHashMap<>();
            queryParams.put("doubansort", "desc");     // 按字段降序排序
            queryParams.put("limit", "100");    // 限制 100 行
            resultFile = service.query(queryParams);
            Logger.log("Query result file2: " + resultFile);

            // 测试分页
            Logger.log("Page 1: " + service.page(1));

            // 构建索引并测试快速搜索
            service.buildIndex();
            Logger.log("Quick search for keyword: " + service.quickSearch("exampleKeyword"));

            // 关闭资源
            service.close();

        } catch (IOException e) {
            Logger.log(e);
        }
    }
}
