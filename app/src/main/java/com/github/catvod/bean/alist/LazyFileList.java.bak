package com.github.catvod.bean.alist;

import java.io.*;
import java.util.AbstractList;
import java.util.Iterator;
import com.github.catvod.spider.*;

public class LazyFileList extends AbstractList<String> {
    private final String filePath;
    private int size = -1;
    private BufferedReader reader; // 维护一个 BufferedReader 实例
    private int currentIndex = -1; // 记录当前读取的行号
    private String currentLine = null;

    public LazyFileList(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public String get(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("索引不能为负数: " + index);
        }
        try {
            if (reader == null) {
                // 第一次调用时初始化 BufferedReader
                reader = new BufferedReader(new FileReader(filePath));
                currentIndex = -1;
            }
            // 如果请求的行号小于当前行号，需要重置读取器
            if (index < currentIndex) {
                reader.close();
                reader = new BufferedReader(new FileReader(filePath));
                currentIndex = -1;
            }
            // 从当前行号继续读取，直到目标行
            while (currentIndex < index) {
                currentLine = reader.readLine();
                if (currentLine == null) {
                    throw new IndexOutOfBoundsException("文件行数不足: " + index);
                }
                currentIndex++;
            }
            return currentLine; // 返回目标行的内容
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + filePath, e);
        }
    }

    @Override
    public Iterator<String> iterator() {
        try {
            // 每次迭代时重新打开文件
            BufferedReader iteratorReader = new BufferedReader(new FileReader(filePath));
            return new Iterator<String>() {
                private String nextLine;

                @Override
                public boolean hasNext() {
                    try {
                        nextLine = iteratorReader.readLine();
                        return nextLine != null;
                    } catch (IOException e) {
                        throw new RuntimeException("读取文件失败", e);
                    }
                }

                @Override
                public String next() {
                    return nextLine;
                }
            };
        } catch (IOException e) {
            throw new RuntimeException("无法打开文件: " + filePath, e);
        }
    }

    @Override
    public int size() {
        if (size != -1) {
            return size;
        }
        try (BufferedReader sizeReader = new BufferedReader(new FileReader(filePath))) {
            int count = 0;
            while (sizeReader.readLine() != null) {
                count++;
            }
            size = count;
            return size;
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + filePath, e);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (reader != null) {
            reader.close(); // 确保资源被释放
        }
        super.finalize();
    }
}
