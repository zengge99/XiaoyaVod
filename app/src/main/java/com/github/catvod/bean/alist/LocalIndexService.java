package com.github.catvod.bean.alist;

import java.io.*;
import java.util.*;
import com.github.catvod.spider.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
        this.inputFilePath = BASE_DIR + sanitizedName + "/index.all.txt"; // 输入文件路径
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
     * 外部排序的主方法
     *
     * @param order 排序顺序（"asc" 或 "desc"）
     * @return 合并后的临时文件路径
     * @throws IOException 如果文件读写失败
     */
    public String externalSort(String order) throws IOException {
        List<File> sortedChunks = sortInChunks(order);
        return mergeSortedChunks(sortedChunks, order);
    }

    /**
     * 将文件分块排序，返回排序后的临时文件列表
     */
    private List<File> sortInChunks(String order) throws IOException {
        List<File> sortedChunks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFilePath))) {
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
     * 合并所有排序后的临时文件
     *
     * @param sortedChunks 排序后的临时文件列表
     * @param order 排序顺序（"asc" 或 "desc"）
     * @return 合并后的临时文件路径
     * @throws IOException 如果文件读写失败
     */
    private String mergeSortedChunks(List<File> sortedChunks, String order) throws IOException {
        PriorityQueue<BufferedLineReader> minHeap = new PriorityQueue<>(
            Comparator.comparing(br -> br.currentFields, createComparator(order))
        );
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath))) {
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
        return outputFilePath; // 返回合并后的文件路径
    }

    /**
     * 读取指定行
     *
     * @param lineNum 行号（从0开始）
     * @return 指定行的内容
     * @throws IOException 如果文件读写失败
     */
    public String getLine(int lineNum) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(outputFilePath))) {
            String line;
            int currentLine = 0;
            while ((line = reader.readLine()) != null) {
                if (currentLine == lineNum) {
                    return line;
                }
                currentLine++;
            }
        }
        throw new IllegalArgumentException("Line number out of bounds");
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

    public static void test() {
        try {
            // 获取实例
            LocalIndexService service = LocalIndexService.get("example:test/1");
            Logger.log("初始化成功");
            
            // 将 ./TV/ + "/index.all.txt" 拷贝到 BASE_DIR + sanitizedName + "/index.all.txt"
            String sanitizedName = sanitizeName("example:test/1");
            Logger.log("初始化成功1");
            Path sourcePath = Path.of(com.github.catvod.utils.Path.root().getPath(), "TV", "index.all.txt");
            Logger.log("初始化成功2");
            Path destPath = Path.of(BASE_DIR, sanitizedName, "index.all.txt");
            Logger.log("初始化成功3");

            // 确保目标目录存在
            Path parentDir = destPath.getParent();
            if (parentDir != null) {
                Logger.log("parentDir正常");
                try {
                    Files.createDirectories(parentDir);
                    Logger.log("创建目录成功");
                    Logger.log("Created parent directory: " + parentDir);
                } catch (IOException e) {
                    Logger.log("创建目录失败");
                    Logger.log("Failed to create parent directory: " + parentDir);
                    Logger.log(e);
                    return; // 或抛出明确的异常
                }
            } else {
                Logger.log("父目录错误");
                Logger.log("Destination path has no parent directory: " + destPath);
                return; // 或抛出明确的异常
            }

            // 检查源文件是否存在
            if (!Files.exists(sourcePath)) {
                Logger.log("源文件不存在");
                Logger.log("Source file does not exist: " + sourcePath);
                return; // 或抛出明确的异常
            }

            // 执行文件拷贝（高效方式）
            try {
                Logger.log("拷贝文件");
                Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
                Logger.log("Copied file from " + sourcePath + " to " + destPath);
            } catch (IOException e) {
                Logger.log("拷贝文件失败");
                Logger.log("Failed to copy file from " + sourcePath + " to " + destPath);
                Logger.log(e);
                return; // 或抛出明确的异常
            }
            
            // 按第4个字段降序排序，并获取合并后的文件路径
            String mergedFilePath = service.externalSort("desc");
            Logger.log("Merged file path: " + mergedFilePath);
            
            // 获取第100行
            String line = service.getLine(99);
            Logger.log(line);
        } catch (IOException e) {
            Logger.log(e);
        } finally {
            // 删除整个 /TV/index/ 目录
            //LocalIndexService.deleteAllIndex();
        }
    }
}