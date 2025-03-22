package com.github.catvod.bean.alist;

import com.google.gson.Gson;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.nio.charset.StandardCharsets;

public class FileBasedList<T> implements List<T> {
    private final File file; // 存储数据的文件
    private final Gson gson; // Gson 用于序列化和反序列化
    private final Class<T> type; // 泛型类型
    private int size; // 当前列表的大小
    private final List<Long> linePositions; // 记录每一行的文件位置
    private final List<T> buffer; // 内存缓存
    private static final int BUFFER_SIZE = 1000; // 缓存大小

    public FileBasedList(String filePath, Class<T> type) {
        this.file = new File(filePath);
        this.gson = new Gson();
        this.type = type;
        this.linePositions = new ArrayList<>();
        this.buffer = new ArrayList<>(BUFFER_SIZE);

        // 确保文件的父目录存在，如果不存在则创建所有缺失的父目录
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean dirsCreated = parentDir.mkdirs();
            if (!dirsCreated) {
                throw new RuntimeException("Failed to create parent directories: " + parentDir.getAbsolutePath());
            }
        }

        // 如果文件不存在，则创建
        if (!file.exists()) {
            try {
                file.createNewFile();
                this.size = 0; // 新文件大小为 0
            } catch (IOException e) {
                throw new RuntimeException("Failed to create file: " + filePath, e);
            }
        } else {
            // 如果文件已存在，初始化文件位置和大小
            initializeLinePositions();
        }
    }

    // 不带文件名的构造函数，自动生成随机文件名
    public FileBasedList(Class<T> type) {
        this(generateRandomFileName(), type);
    }

    // 生成随机文件名
    private static String generateRandomFileName() {
        return com.github.catvod.utils.Path.root() + "/TV/list/" + UUID.randomUUID().toString() + ".list";
    }

    /**
     * 初始化文件位置和大小
     */
    private void initializeLinePositions() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            long position = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                linePositions.add(position);
                position += line.getBytes(StandardCharsets.UTF_8).length
                        + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length; // 更新位置
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
                private String nextLine = reader.readLine(); // 读取第一行

                @Override
                public boolean hasNext() {
                    return nextLine != null;
                }

                @Override
                public T next() {
                    try {
                        T item = gson.fromJson(nextLine, type); // 反序列化为对象
                        nextLine = reader.readLine(); // 读取下一行
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
                String json = gson.toJson(item) + "\n";
                writer.write(json);
                currentPosition += json.getBytes(StandardCharsets.UTF_8).length; // 更新当前位置
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
            // 如果传入的集合是 FileBasedList 类型，直接合并文件
            return mergeFileBasedList((FileBasedList<? extends T>) c);
        } else {
            // 否则，按照原来的方式逐条添加
            for (T item : c) {
                add(item);
            }
            return true;
        }
    }
    
    /**
     * 合并两个 FileBasedList 的文件内容
     * @param other 另一个 FileBasedList
     * @return 是否合并成功
     */
    private boolean mergeFileBasedList(FileBasedList<? extends T> other) {
        flushBuffer(); // 确保当前缓存数据写入文件
        if (other != this) {
            other.flushBuffer(); // 确保另一个文件的缓存数据写入文件
        }
    
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(new FileInputStream(other.file), StandardCharsets.UTF_8))) {
    
            long currentPosition = file.length(); // 获取当前文件长度作为初始位置
            String line;
            while ((line = reader.readLine()) != null) {
                linePositions.add(currentPosition); // 记录新行的起始位置
                writer.write(line); // 将另一文件的内容逐行写入当前文件
                writer.newLine(); // 写入换行符
                currentPosition += line.getBytes(StandardCharsets.UTF_8).length + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length; // 更新当前位置
            }
            writer.flush(); // 确保所有数据写入文件
            size += other.size(); // 更新列表大小
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
            writer.write(""); // 清空文件内容
            size = 0; // 大小重置为 0
            linePositions.clear(); // 清空文件位置记录
            buffer.clear(); // 清空缓存
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear file", e);
        }
    }

    @Override
    public T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index " + index + " is out of bounds");
        }
        flushBuffer();
        try (
                RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
                InputStreamReader reader = new InputStreamReader(new FileInputStream(randomAccessFile.getFD()), StandardCharsets.UTF_8);
                BufferedReader bufferedReader = new BufferedReader(reader)
        ) {
            long position = linePositions.get(index); // 获取指定行的起始位置
            randomAccessFile.seek(position); // 跳转到指定位置

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                return gson.fromJson(line, type); // 反序列化为对象
            }

            throw new IllegalStateException("Failed to read the specified line");

        } catch (IOException e) {
            throw new RuntimeException("Failed to read from file", e);
        }
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
        return StreamSupport.stream(spliterator, false); // 不支持并行流
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
            Spliterator<IndexedItem<T>> spliterator = Spliterators
                    .spliteratorUnknownSize(new Iterator<IndexedItem<T>>() {
                        private String nextLine = reader.readLine(); // 读取第一行
                        private int currentLineNumber = 0; // 当前行号

                        @Override
                        public boolean hasNext() {
                            return nextLine != null;
                        }

                        @Override
                        public IndexedItem<T> next() {
                            try {
                                T item = gson.fromJson(nextLine, type); // 反序列化为对象
                                IndexedItem<T> indexedItem = new IndexedItem<>(item, currentLineNumber);
                                nextLine = reader.readLine(); // 读取下一行
                                currentLineNumber++; // 行号增加
                                return indexedItem;
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to read next line", e);
                            }
                        }
                    }, Spliterator.ORDERED);
            return StreamSupport.stream(spliterator, false); // 不支持并行流
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize indexed stream", e);
        }
    }
}
