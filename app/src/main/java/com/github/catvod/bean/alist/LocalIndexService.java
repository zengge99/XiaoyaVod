package com.github.catvod.bean.alist;

import java.io.*;
import java.util.*;
import com.github.catvod.spider.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class LocalIndexService {

    private static final int MAX_LINES_IN_MEMORY = 5000; // 每个块的最大行数
    private static final Map<String, LocalIndexService> instances = new HashMap<>(); // 实例缓存
    private static final String BASE_DIR = com.github.catvod.utils.Path.root().getPath() + "/TV/index/"; // 基础目录

    private final String inputFilePath; // 输入文件路径
    private final String cacheDirPath; // 缓存目录路径
    private String outputFilePath; // 输出文件路径

    /**
     * 私有构造函数
     *
     * @param name 实例唯一名字
     */
    private LocalIndexService(String name) {
        String sanitizedName = sanitizeName(name); // 处理特殊字符
        //this.inputFilePath = BASE_DIR + sanitizedName + "/index.all.txt"; // 输入文件路径
        this.inputFilePath = com.github.catvod.utils.Path.root().getPath() + "/TV/ + "/index.all.txt"; // 输入文件路径
        this.cacheDirPath = BASE_DIR + "cache/" + sanitizedName; // 缓存目录路径
        Logger.log("Input file path: " + inputFilePath);
        Logger.log("Cache directory path: " + cacheDirPath);
        createCacheDir(); // 创建缓存目录
        this.outputFilePath = generateRandomOutputFilePath(); // 自动生成随机输出文件路径
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
     * 生成随机的输出文件路径
     */
    private String generateRandomOutputFilePath() {
        String randomFileName = "output_" + UUID.randomUUID().toString() + ".txt";
        return cacheDirPath + File.separator + randomFileName;
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
     * @param order      排序顺序（"asc" 或 "desc"）
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
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
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
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
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
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
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
 * @param order      排序顺序（"asc" 或 "desc"）
 * @throws IOException 如果文件读写失败
 */
private void sortByField(String inputFile, String outputFile, String order) throws IOException {
    externalSort(inputFile, outputFile, order);
    Logger.log("Sorted by field: " + order);
}

/**
 * 查询方法
 *
 * @param queryParams 查询参数，key 是查询方法，value 是查询参数
 * @return 最终结果文件名
 * @throws IOException 如果文件读写失败
 */
public String query(HashMap<String, String> queryParams) throws IOException {
    // 生成缓存键
    String cacheKey = generateCacheKey(queryParams);
    String cacheFilePath = cacheDirPath + File.separator + cacheKey + ".txt";

    // 如果缓存文件存在，直接返回
    File cacheFile = new File(cacheFilePath);
    if (cacheFile.exists()) {
        Logger.log("Cache hit for query: " + cacheKey);
        return cacheFilePath;
    }

    Logger.log("Cache miss for query: " + cacheKey);

    // 初始输入文件
    String currentInputFile = inputFilePath;

    // 依次处理查询方法
    for (Map.Entry<String, String> entry : queryParams.entrySet()) {
        String method = entry.getKey();
        String param = entry.getValue();

        // 生成临时文件路径
        String tempOutputFile = cacheDirPath + File.separator + "temp_" + UUID.randomUUID() + ".txt";

        // 执行查询方法
        switch (method) {
            case "filter":
                filterByField(currentInputFile, tempOutputFile, param);
                break;
            case "sort":
                sortByField(currentInputFile, tempOutputFile, param);
                break;
            case "limit":
                limitRows(currentInputFile, tempOutputFile, Integer.parseInt(param));
                break;
            default:
                throw new IllegalArgumentException("Unknown query method: " + method);
        }

        // 更新当前输入文件
        currentInputFile = tempOutputFile;
    }

    // 将最终结果保存到缓存文件
    Files.copy(Path.of(currentInputFile), Path.of(cacheFilePath), StandardCopyOption.REPLACE_EXISTING);
    Logger.log("Query result saved to cache: " + cacheFilePath);

    // 删除临时文件
    new File(currentInputFile).delete();

    return cacheFilePath;
}

/**
 * 生成缓存键（MD5 哈希）
 *
 * @param queryParams 查询参数
 * @return 缓存键
 */
private String generateCacheKey(HashMap<String, String> queryParams) {
    try {
        MessageDigest md = MessageDigest.getInstance("MD5");
        String queryString = queryParams.toString();
        byte[] hashBytes = md.digest(queryString.getBytes());
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    } catch (NoSuchAlgorithmException e) {
        throw new RuntimeException("MD5 algorithm not found", e);
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
private void filterByField(String inputFile, String outputFile, String fieldValue) throws IOException {
    try (BufferedReader reader = new BufferedReader(new FileReader(inputFile));
         BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
        String line;
        while ((line = reader.readLine()) != null) {
            String[] fields = line.split("#");
            if (fields.length > 0 && fields[0].equals(fieldValue)) {
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
    try (BufferedReader reader = new BufferedReader(new FileReader(inputFile));
         BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
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

    public BufferedLineReader(File file) throws FileNotFoundException {
        this.reader = new BufferedReader(new FileReader(file));
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

        // 查询参数
        HashMap<String, String> queryParams = new HashMap<>();
        //queryParams.put("filter", "value1"); // 过滤字段值为 "value1" 的行
        queryParams.put("sort", "desc");     // 按字段降序排序
        queryParams.put("limit", "100");     // 限制 100 行

        // 执行查询
        String resultFile = service.query(queryParams);
        Logger.log("Query result file: " + resultFile);
    } catch (IOException e) {
        Logger.log(e);
    } finally {
        // 删除整个 /TV/index/ 目录
        LocalIndexService.deleteAllIndex();
    }
}
}