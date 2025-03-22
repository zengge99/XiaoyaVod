package com.github.catvod.bean.alist;

import java.io.*;
import java.util.*;
import com.github.catvod.spider.Logger;

public class ExternalSort {

    private static final int MAX_LINES_IN_MEMORY = 5000; // 每个块的最大行数

    /**
     * 外部排序的主方法
     *
     * @param inputFilePath  输入文件路径
     * @param outputFilePath 输出文件路径
     * @param sortFieldIndex 排序字段的索引
     * @throws IOException 如果文件读写失败
     */
    public static void externalSort(String inputFilePath, String outputFilePath, String order) throws IOException {
        List<File> sortedChunks = sortInChunks(inputFilePath, order);
        mergeSortedChunks(sortedChunks, outputFilePath, order);
    }

    /**
     * 将文件分块排序，返回排序后的临时文件列表
     */
    private static List<File> sortInChunks(String inputFilePath, String order) throws IOException {
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
    private static File sortAndWriteChunk(List<String[]> chunk, String order) throws IOException {
        chunk.sort((o1, o2) -> {
            double value1 = parseFieldAsDouble(o1, 3); // 解析 o1 的第 4 个字段（索引 3）
            double value2 = parseFieldAsDouble(o2, 3); // 解析 o2 的第 4 个字段（索引 3）
            if (order.equals("asc")) {
                return Double.compare(value1, value2);
            } else {
                return Double.compare(value2, value1);
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
    private static void mergeSortedChunks(List<File> sortedChunks, String outputFilePath, int sortFieldIndex) throws IOException {
        PriorityQueue<BufferedLineReader> minHeap = new PriorityQueue<>(Comparator.comparing(br -> br.currentFields[sortFieldIndex]));
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
     * @param filePath 文件路径
     * @param lineNum  行号（从0开始）
     * @return 指定行的内容
     * @throws IOException 如果文件读写失败
     */
    public static String getLine(String filePath, int lineNum) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
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
            String path = com.github.catvod.utils.Path.root().getPath() + "/TV/";
            externalSort(path + "/index.all.txt", path + "/output.txt", 0); // 字段索引从0开始
            String line = getLine(path + "/output.txt", 99); // 第100行的索引是99
            Logger.log(line);
        } catch (IOException e) {
            Logger.log(e);
        }
    }
}