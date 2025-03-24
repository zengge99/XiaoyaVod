package com.github.catvod.bean.alist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class Pager {
    private List<String> inputList; // 存储输入的列表
    private List<Integer> randomIndices; // 存储随机选择的索引
    private static final int PAGE_SIZE = 72; // 每页固定大小为 72
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
        List<String> pageContent = new ArrayList<>();
        for (int i = startIndex; i < endIndex; i++) {
            int index = randomIndices.get(i);
            pageContent.add(inputList.get(index));
        }

        return pageContent;
    }
}