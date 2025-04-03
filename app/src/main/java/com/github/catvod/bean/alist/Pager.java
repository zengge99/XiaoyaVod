package com.github.catvod.bean.alist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import com.github.catvod.bean.alist.Drive;
import java.util.Arrays;

public class Pager {
    private List<String> inputList;
    private List<Integer> randomIndices;
    private static final int PAGE_SIZE = 72; 
    private String cmd;
    private Drive drive;
    public int limit = 0;
    public int total = 0;
    public int count = 0;

    public Pager(List<String> inputList, int randomOutputSize, boolean isKeepOrder) {

        if (inputList == null || inputList.isEmpty()) {
            this.inputList = new ArrayList<>();
            this.randomIndices = new ArrayList<>();
            return;
        }

        this.inputList = inputList;
        limit = PAGE_SIZE;
        total = this.inputList.size();
        count = (total + limit - 1) / limit;

        // 如果 randomOutputSize 为 0，直接使用整个输入列表的索引
        if (randomOutputSize == 0) {
            this.randomIndices = new ArrayList<>();
            for (int i = 0; i < inputList.size(); i++) {
                randomIndices.add(i);
            }
            return;
        }

        // 如果随机结果个数大于列表大小，则使用整个列表的索引
        if (randomOutputSize > inputList.size()) {
            randomOutputSize = inputList.size();
        }

        // 根据模式处理索引
        if (isKeepOrder) {
            // 保序模式：随机选择 randomOutputSize 个索引，但保持相对顺序
            this.randomIndices = randomlySelectIndicesWithOrder(inputList.size(), randomOutputSize);
        } else {
            // 乱序模式：随机选择 randomOutputSize 个索引，顺序随机
            this.randomIndices = randomlySelectIndices(inputList.size(), randomOutputSize);
        }
    }

     public Pager(Drive drive, String cmd, int total, int randomOutputSize, boolean isKeepOrder) {
        this.cmd = cmd;
        this.total = total;
        this.drive = drive;
        limit = PAGE_SIZE;
        count = (total + limit - 1) / limit;

        // 如果 randomOutputSize 为 0，直接使用整个输入列表的索引
        if (randomOutputSize == 0) {
            this.randomIndices = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                randomIndices.add(i);
            }
            return;
        }

        // 如果随机结果个数大于列表大小，则使用整个列表的索引
        if (randomOutputSize > total) {
            randomOutputSize = total;
        }

        // 根据模式处理索引
        if (isKeepOrder) {
            // 保序模式：随机选择 randomOutputSize 个索引，但保持相对顺序
            this.randomIndices = randomlySelectIndicesWithOrder(total, randomOutputSize);
        } else {
            // 乱序模式：随机选择 randomOutputSize 个索引，顺序随机
            this.randomIndices = randomlySelectIndices(total, randomOutputSize);
        }
    }

    private List<Integer> randomlySelectIndices(int totalSize, int randomOutputSize) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < totalSize; i++) {
            indices.add(i);
        }
        Collections.shuffle(indices);
        return indices.subList(0, randomOutputSize);
    }

    private List<Integer> randomlySelectIndicesWithOrder(int totalSize, int randomOutputSize) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < totalSize; i++) {
            indices.add(i);
        }
        Collections.shuffle(indices);
        List<Integer> selectedIndices = indices.subList(0, randomOutputSize);
        Collections.sort(selectedIndices); // 按原始顺序排序
        return selectedIndices;
    }

    public List<String> page(int pageNum) {
        if (pageNum < 1 || randomIndices.isEmpty()) {
            return new ArrayList<>();
        }
        int startIndex = (pageNum - 1) * PAGE_SIZE;
        if (startIndex >= randomIndices.size()) {
            return new ArrayList<>();
        }
        int endIndex = Math.min(startIndex + PAGE_SIZE, randomIndices.size());
        List<String> pageContent;
        if (cmd == null || cmd.isEmpty()) {
            pageContent = new ArrayList<>();
            for (int i = startIndex; i < endIndex; i++) {
                int index = randomIndices.get(i);
                pageContent.add(inputList.get(index));
            }
            return pageContent;
        } else {
            cmd += " | grep -n ''";
            String lineString = String.format("%d", randomIndices.get(startIndex) + 1);
            for (int i = startIndex + 1; i < endIndex; i++) {
                lineString = String.format("%s|%d", lineString, randomIndices.get(i) + 1);
            }
            lineString = String.format("(%s)", lineString);
            cmd += String.format(" | grep '^%s:'", lineString);
            List<String> tmpList = Arrays.asList(drive.exec(cmd).split("\n"));
            List<String> resultList = new ArrayList<>();
            for (int i = startIndex; i < endIndex; i++) {
                String prefix = String.format("%i:", randomIndices.get(i) + 1);
                for (String s : tmpList) {
                    if (s.startsWith(prefix)) {
                        s = s.split(":")[1];
                        if (s.startsWith("./")) {
                            s = s.subList(2);
                        }
                        resultList.add(s);
                        break;
                    }
                }
            }
            return resultList;
        }
    }
}