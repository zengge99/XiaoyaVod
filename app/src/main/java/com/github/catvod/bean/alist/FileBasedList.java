package com.github.catvod.bean.alist;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import com.github.catvod.spider.Logger;
import java.nio.file.Files;
import com.github.catvod.spider.Logger;

public class FileBasedList<T> implements List<T> {
    private final File file;
    private final Gson gson;
    private final Class<T> type;
    private int size;
    private final List<Long> linePositions;
    private final List<T> buffer;
    private static final int BUFFER_SIZE = 5000;
    private final Map<Integer, T> cache;
    private int lastAccessedIndex = -1;
    private RandomAccessFile lastAccessedFile;
    private BufferedReader lastAccessedReader;

    // 带文件路径的构造函数
    public FileBasedList(String filePath, Class<T> type) {
        this.file = new File(filePath);
        this.gson = new Gson();
        this.type = type;
        this.linePositions = new ArrayList<>(BUFFER_SIZE);
        this.buffer = new ArrayList<>(BUFFER_SIZE);
        this.cache = new LinkedHashMap<Integer, T>(100, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, T> eldest) {
                return size() > 100; // LRU cache with max size of 100
            }
        };

        ensureFileExists();
        initializeLinePositions();
    }

    // 不带文件路径的构造函数，自动生成随机文件名
    public FileBasedList(Class<T> type) {
        this(generateRandomFileName(), type);
    }

    // 生成随机文件名
    private static String generateRandomFileName() {
        return getCacheDirPath() + UUID.randomUUID().toString() + ".list";
    }

    // 获取缓存目录路径
    private static String getCacheDirPath() {
        return com.github.catvod.utils.Path.cache() + "/TV/list/";
    }

    // 清空缓存目录
    public static void clearCacheDirectory() {
        Logger.log("clearCacheDirectory1");
        String cacheDirPath = getCacheDirPath();
        Logger.log("clearCacheDirectory2");
        Logger.log(cacheDirPath);
        File cacheDir = new File(cacheDirPath);
        Logger.log("clearCacheDirectory3");
        if (!cacheDir.exists()) {
            Logger.log("clearCacheDirectory4");
            return;
        }
        Logger.log("clearCacheDirectory5");
        if (!cacheDir.isDirectory()) {
            Logger.log("clearCacheDirectory6");
            throw new RuntimeException("Cache directory path is not a directory: " + cacheDirPath);
        }
        Logger.log("clearCacheDirectory7");
        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(".list"));
        Logger.log(files);
        Logger.log("clearCacheDirectory8");
        if (files == null) {
            Logger.log("clearCacheDirectory9");
            return;
        }
        Logger.log("clearCacheDirectory10");
        for (File file : files) {
            try {
                Files.delete(file.toPath());
            } catch (IOException e) {
                Logger.log(e);
            }
        }
        Logger.log("clearCacheDirectory11");
    }

    // 确保文件存在
    private void ensureFileExists() {
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean dirsCreated = parentDir.mkdirs();
            if (!dirsCreated) {
                throw new RuntimeException("Failed to create parent directories: " + parentDir.getAbsolutePath());
            }
        }

        if (!file.exists()) {
            try {
                file.createNewFile();
                this.size = 0;
            } catch (IOException e) {
                throw new RuntimeException("Failed to create file: " + file.getAbsolutePath(), e);
            }
        }
    }

    // 初始化行位置
    private void initializeLinePositions() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            long position = 0;
            String line;
            int lineSeparatorLength = System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
            while ((line = reader.readLine()) != null) {
                linePositions.add(position);
                position += line.getBytes(StandardCharsets.UTF_8).length + lineSeparatorLength;
            }
            this.size = linePositions.size();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize line positions", e);
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean contains(Object o) {
        for (T item : this) {
            if (Objects.equals(item, o)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Iterator<T> iterator() {
        try {
            flushBuffer();
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            return new Iterator<T>() {
                private String nextLine = reader.readLine();

                @Override
                public boolean hasNext() {
                    return nextLine != null;
                }

                @Override
                public T next() {
                    try {
                        T item = parseLine(nextLine);
                        nextLine = reader.readLine();
                        return item;
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read next line", e);
                    }
                }
            };
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize iterator", e);
        }
    }

    @Override
    public Object[] toArray() {
        List<T> list = new ArrayList<>();
        for (T item : this) {
            list.add(item);
        }
        return list.toArray();
    }

    @Override
    public <T1> T1[] toArray(T1[] a) {
        List<T> list = new ArrayList<>();
        for (T item : this) {
            list.add(item);
        }
        return list.toArray(a);
    }

    @Override
    public boolean add(T t) {
        buffer.add(t);
        if (buffer.size() >= BUFFER_SIZE) {
            flushBuffer();
        }
        size++;
        return true;
    }

    /**
     * 将缓存中的数据批量写入文件
     */
    private void flushBuffer() {
        if (buffer.size() == 0) {
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            long currentPosition = file.length(); // 获取当前文件长度作为初始位置

            for (T item : buffer) {
                linePositions.add(currentPosition); // 记录新行的起始位置
                String line;
                if (type == String.class) {
                    line = (String) item + "\n"; // 直接写入字符串
                } else {
                    line = gson.toJson(item) + "\n"; // 序列化为 JSON 字符串
                }
                writer.write(line);
                currentPosition += line.getBytes(StandardCharsets.UTF_8).length; // 更新当前位置
            }

            writer.flush(); // 最终确保所有缓冲的数据都已写入文件
            buffer.clear(); // 清空缓存
        } catch (IOException e) {
            throw new RuntimeException("Failed to write to file", e);
        }
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException("Remove operation is not supported.");
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (!contains(o)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        if (c instanceof FileBasedList) {
            return mergeFileBasedList((FileBasedList<? extends T>) c);
        } else {
            for (T item : c) {
                add(item);
            }
            return true;
        }
    }

    // 合并两个 FileBasedList 的文件内容
    private boolean mergeFileBasedList(FileBasedList<? extends T> other) {
        flushBuffer();
        if (other != this) {
            other.flushBuffer();
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(other.file), StandardCharsets.UTF_8))) {
            long currentPosition = file.length();
            String line;
            while ((line = reader.readLine()) != null) {
                linePositions.add(currentPosition);
                writer.write(line);
                writer.newLine();
                currentPosition += line.getBytes(StandardCharsets.UTF_8).length + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
            }
            writer.flush();
            size += other.size();
            return true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to merge FileBasedList files", e);
        }
    }

    @Override
    public boolean addAll(int index, Collection<? extends T> c) {
        throw new UnsupportedOperationException("AddAll at index is not supported.");
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException("RemoveAll operation is not supported.");
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("RetainAll operation is not supported.");
    }

    @Override
    public void clear() {
        flushBuffer();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("");
            size = 0;
            linePositions.clear();
            buffer.clear();
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear file", e);
        }
    }

    @Override
    public T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index " + index + " is out of bounds");
        }

        // 检查缓存
        if (cache.containsKey(index)) {
            return cache.get(index);
        }

        flushBuffer();
        try {
            // 如果是顺序访问（当前行号的下一个），则直接读取下一行
            if (index == lastAccessedIndex + 1 && lastAccessedReader != null) {
                String line = lastAccessedReader.readLine();
                if (line != null) {
                    T item = parseLine(line);
                    cache.put(index, item);
                    lastAccessedIndex = index;
                    return item;
                }
            }

            // 否则，重新定位文件指针并初始化 BufferedReader
            if (lastAccessedFile == null) {
                lastAccessedFile = new RandomAccessFile(file, "r");
            }

            long position = linePositions.get(index);
            lastAccessedFile.seek(position);
            lastAccessedReader = new BufferedReader(new InputStreamReader(new FileInputStream(lastAccessedFile.getFD()), StandardCharsets.UTF_8));

            String line = lastAccessedReader.readLine();
            if (line != null) {
                T item = parseLine(line);
                cache.put(index, item);
                lastAccessedIndex = index;
                return item;
            }
            throw new IllegalStateException("Failed to read the specified line");
        } catch (IOException e) {
            throw new RuntimeException("Failed to read from file", e);
        }
    }

    // 解析一行数据
    private T parseLine(String line) {
        return type == String.class ? (T) line : gson.fromJson(line, type);
    }

    @Override
    public T set(int index, T element) {
        throw new UnsupportedOperationException("Set operation is not supported.");
    }

    @Override
    public void add(int index, T element) {
        throw new UnsupportedOperationException("Add at index is not supported.");
    }

    @Override
    public T remove(int index) {
        throw new UnsupportedOperationException("Remove at index is not supported.");
    }

    @Override
    public int indexOf(Object o) {
        int index = 0;
        for (T item : this) {
            if (Objects.equals(item, o)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        int lastIndex = -1;
        int index = 0;
        for (T item : this) {
            if (Objects.equals(item, o)) {
                lastIndex = index;
            }
            index++;
        }
        return lastIndex;
    }

    @Override
    public ListIterator<T> listIterator() {
        throw new UnsupportedOperationException("ListIterator is not supported.");
    }

    @Override
    public ListIterator<T> listIterator(int index) {
        throw new UnsupportedOperationException("ListIterator at index is not supported.");
    }

    @Override
    public List<T> subList(int fromIndex, int toIndex) {
        List<T> subList = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; i++) {
            subList.add(get(i));
        }
        return subList;
    }

    public Stream<T> stream() {
        Spliterator<T> spliterator = Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED);
        return StreamSupport.stream(spliterator, false);
    }

    public static class IndexedItem<T> {
        private final T item;
        private final int lineNumber;

        public IndexedItem(T item, int lineNumber) {
            this.item = item;
            this.lineNumber = lineNumber;
        }

        public T getItem() {
            return item;
        }

        public int getLineNumber() {
            return lineNumber;
        }
    }

    public Stream<IndexedItem<T>> indexedStream() {
        try {
            flushBuffer();
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            Spliterator<IndexedItem<T>> spliterator = Spliterators.spliteratorUnknownSize(new Iterator<IndexedItem<T>>() {
                private String nextLine = reader.readLine();
                private int currentLineNumber = 0;

                @Override
                public boolean hasNext() {
                    return nextLine != null;
                }

                @Override
                public IndexedItem<T> next() {
                    try {
                        T item = parseLine(nextLine);
                        IndexedItem<T> indexedItem = new IndexedItem<>(item, currentLineNumber);
                        nextLine = reader.readLine();
                        currentLineNumber++;
                        return indexedItem;
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read next line", e);
                    }
                }
            }, Spliterator.ORDERED);
            return StreamSupport.stream(spliterator, false);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize indexed stream", e);
        }
    }

    public static void test() {
        long startTime, endTime;
        try {
            startTime = System.currentTimeMillis();
            List<String> list = new FileBasedList<>(com.github.catvod.utils.Path.root().getPath() + "/TV/index.all.txt", String.class);
            endTime = System.currentTimeMillis();
            Logger.log("初始化 FileBasedList 耗时: " + (endTime - startTime) + " 毫秒");

            startTime = System.currentTimeMillis();
            String item = list.get(1000);
            endTime = System.currentTimeMillis();
            Logger.log(item);
            Logger.log("获取第 1000 个元素耗时: " + (endTime - startTime) + " 毫秒");

            List<String> filteredList = new FileBasedList<>(String.class);
            startTime = System.currentTimeMillis();
            for (String s : list) {
                if (s.startsWith("电影")) {
                    filteredList.add(s);
                }
            }
            endTime = System.currentTimeMillis();
            Logger.log("过滤列表耗时: " + (endTime - startTime) + " 毫秒");
        } catch (Exception e) {
            Logger.log(e);
        }
    }
}
