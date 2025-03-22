package com.github.catvod.bean.alist;

import java.io.*;
import java.util.*;
import com.github.catvod.spider.Logger;
import com.github.catvod.utils.Path;

public class LocalIndexService {

    private static final int MAX_LINES_IN_MEMORY = 5000; // 每个块的最大行数
    private final String inputFilePath; // 输入文件路径
    private final String outputDirPath; // 输出目录路径
    private String outputFilePath; // 输出文件路径

    /**
     * 构造函数
     *
     * @param inputFilePath 输入文件路径
     * @param outputDirPath 输出目录路径
     */
    public LocalIndexService(String inputFilePath, String outputDirPath) {
        this.inputFilePath = inputFilePath;
        this.outputDirPath = outputDirPath;
        this.outputFilePath = generateRandomOutputFilePath(); // 自动生成随机输出文件路径
    }

    /**
     * 生成随机的输出文件路径
     */
    private String generateRandomOutputFilePath() {
        String randomFileName = "output_" + UUID.randomUUID().toString() + ".txt";
        return outputDirPath + File.separator + randomFileName;
    }

    /**
     * 外部排序的主方法
     *
     * @param sortFieldIndex 排序字段的索引
     * @param order         排序顺序（"asc" 或 "desc"）
     * @throws IOException 如果文件读写失败
     */
    public void externalSort(int sortFieldIndex, String order) throws IOException {
        List<File> sortedChunks = sortInChunks(sortFieldIndex, order);
        mergeSortedChunks(sortedChunks, order);
    }

    /**
     * 将文件分块排序，返回排序后的临时文件列表
     */
    private List<File> sortInChunks(int sortFieldIndex, String order) throws IOException {
        List<File> sortedChunks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFilePath))) {
            List<String[]> chunk = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("#");
                chunk.add(fields);
                if (chunk.size() >= MAX_LINES_IN_MEMORY) {
                    sortedChunks.add(sortAndWriteChunk(chunk, sortFieldIndex, order));
                    chunk.clear();
                }
            }
            if (!chunk.isEmpty()) {
                sortedChunks.add(sortAndWriteChunk(chunk, sortFieldIndex, order));
            }
        }
        return sortedChunks;
    }

    /**
     * 对内存中的块进行排序并写入临时文件
     */
    private File sortAndWriteChunk(List<String[]> chunk, int sortFieldIndex, String order) throws IOException {
        chunk.sort((o1, o2) -> {
            double value1 = parseFieldAsDouble(o1, sortFieldIndex);
            double value2 = parseFieldAsDouble(o2, sortFieldIndex);
            if (order.equals("asc")) {
                return Double.compare(value1, value2); // 升序
            } else {
                return Double.compare(value2, value1); // 降序
            }
        });
        File tempFile = File.createTempFile("sortedChunk", ".txt");
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
     */
    private void mergeSortedChunks(List<File> sortedChunks, String order) throws IOException {
        PriorityQueue<BufferedLineReader> minHeap = new PriorityQueue<>(
            (br1, br2) -> {
                double value1 = parseFieldAsDouble(br1.currentFields, 3);
                double value2 = parseFieldAsDouble(br2.currentFields, 3);
                if (order.equals("asc")) {
                    return Double.compare(value1, value2); // 升序
                } else {
                    return Double.compare(value2, value1); // 降序
                }
            }
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

    /**
     * 测试方法
     */
    public static void test() {
        try {
            String path = Path.root().getPath() + "/TV/";
            LocalIndexService service = new LocalIndexService(path + "index.all.txt", path);
            service.externalSort(3, "desc"); // 按第4个字段降序排序
            String line = service.getLine(99); // 获取第100行
            Logger.log(line);
        } catch (IOException e) {
            Logger.log(e);
        }
    }
}