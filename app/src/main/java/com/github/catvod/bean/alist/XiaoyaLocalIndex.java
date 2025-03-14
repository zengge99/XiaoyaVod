package com.github.catvod.bean.alist;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import com.github.catvod.bean.Vod;
import com.github.catvod.bean.alist.Drive;
import com.github.catvod.bean.alist.Item;
import com.github.catvod.spider.Logger;
import com.github.catvod.utils.Image;
import com.github.catvod.utils.Util;
import android.text.TextUtils;

import android.os.Debug;

public class XiaoyaLocalIndex {
    private static Map<String, List<Vod>> cacheMap = new HashMap<>();
    private static Map<String, Map<String, List<Integer>>> invertedIndexMap = new HashMap<>();
    private volatile boolean isBusy = false;

    public static synchronized List<Vod> downlodadAndUnzip(Drive drive) {
        isBusy = true;

        Logger.log("本地索引前的内存：" + Debug.getNativeHeapAllocatedSize());

        String server = drive.getServer();
        List<Vod> vods = cacheMap.get(server);
        if (vods != null) {
            for (Vod vod : vods) {
                vod.setVodDrive(drive.getName());
            }
            isBusy = false;
            return vods;
        }

        try {
            String fileUrl = server + "/tvbox/data";
            String saveDir = com.github.catvod.utils.Path.cache().getPath() + "/TV/index/"
                    + server.split("//")[1].replace(":", "_port");
            Logger.log(saveDir);

            // 0. 清空目录
            deleteFiles(saveDir, null); // 删除 saveDir 中的所有文件

            // 1. 确保目录存在
            createDirectoryIfNotExists(saveDir);

            // 2. 下载文件
            downloadFile(fileUrl + "/index.video.tgz", saveDir + "/index.video.tgz");
            downloadFile(fileUrl + "/index.115.tgz", saveDir + "/index.115.tgz");

            // 3. 解压文件
            unzipFile(saveDir + "/index.video.tgz", saveDir);
            unzipFile(saveDir + "/index.115.tgz", saveDir);

            mergeFiles(saveDir, saveDir + "/index.all.txt");

            // 4. 删除指定文件
            deleteFilesExclude(saveDir, "index.all.txt");
            deleteFiles(saveDir, "*.tgz");

            long startMemory = Debug.getNativeHeapAllocatedSize();
            List<String> lines = Files.readAllLines(Paths.get(saveDir + "/index.all.txt"));
            // List<String> lines = new LazyFileList(saveDir + "/index.all.txt");
            Logger.log("索引列表消耗内存：" + (Debug.getNativeHeapAllocatedSize() - startMemory));

            vods = toVods(drive, lines);

            // 构建倒排索引，用于快速查找
            Map<String, List<Integer>> invertedIndex = new HashMap<>();
            for (int i = 0; i < vods.size(); i++) {
                String word = vods.get(i).getVodName();
                invertedIndex.computeIfAbsent(word, k -> new ArrayList<>()).add(i);
            }

            invertedIndexMap.put(server, invertedIndex);
            cacheMap.put(server, vods);

        } catch (IOException e) {
            isBusy = false;
        }

        Logger.log("本地索引后的内存：" + Debug.getNativeHeapAllocatedSize());
        isBusy = false;
        return vods;
    }

    public static List<Vod> quickSearch(Drive drive, String keyword) {

        String server = drive.getServer();
        downlodadAndUnzip(drive);
        List<Integer> lineNumbers = invertedIndexMap.get(server).get(keyword);
        if (lineNumbers == null) {
            return new ArrayList<>();
        }
        List<Vod> vods = new ArrayList<>();
        for (Integer i : lineNumbers) {
            vods.add(cacheMap.get(server).get(i));
        }
        return vods;
    }

    /**
     * 确保目录存在，如果不存在则创建
     *
     * @param dirPath 目录路径
     */
    private static void createDirectoryIfNotExists(String dirPath) throws IOException {
        Path path = Paths.get(dirPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    /**
     * 下载文件
     *
     * @param fileUrl  文件URL
     * @param savePath 保存路径
     */
    private static void downloadFile(String fileUrl, String savePath) throws IOException {
        URL url = new URL(fileUrl);
        try (InputStream in = new BufferedInputStream(url.openStream());
                FileOutputStream out = new FileOutputStream(savePath)) {
            byte[] buffer = new byte[8 * 1024]; // 分块读取，每次读取 8KB
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            throw new IOException("下载文件失败: " + fileUrl, e);
        }
    }

    public static List<Vod> toVods(Drive drive, List<String> lines) {
        List<Vod> list = new ArrayList<>();
        List<Vod> noPicList = new ArrayList<>();
        for (String line : lines) {
            String[] splits = line.split("#");
            int index = splits[0].lastIndexOf("/");
            boolean file = Util.isMedia(splits[0]);
            if (splits[0].endsWith("/")) {
                file = false;
                splits[0] = splits[0].substring(0, index);
                index = splits[0].lastIndexOf("/");
            }
            Item item = new Item();
            item.setType(0); // 海报模式总是认为是文件模式，直接点击播放
            item.doubanInfo.setId(splits.length >= 3 ? splits[2] : "");
            item.doubanInfo.setRating(splits.length >= 4 ? splits[3] : "");
            item.setThumb(splits.length >= 5 ? splits[4] : "");
            item.setPath("/" + splits[0].substring(0, index));
            String fileName = splits[0].substring(index + 1);
            item.setName(fileName);
            item.doubanInfo.setName(splits.length >= 2 ? splits[1] : fileName);
            Vod vod = item.getVod(drive.getName(), drive.getVodPic());
            vod.setVodRemarks(item.doubanInfo.getRating());
            vod.setVodName(item.doubanInfo.getName());
            vod.doubanInfo = item.doubanInfo;
            if (!file) {
                vod.setVodId(vod.getVodId() + "/~soulist");
            } else {
                vod.setVodId(vod.getVodId() + "/~soufile");
            }
            if (TextUtils.isEmpty(item.getThumb())) {
                noPicList.add(vod);
            } else {
                list.add(vod);
            }
        }
        list.addAll(noPicList);
        return list;
    }

    /**
     * 解压文件（支持 ZIP 和 TGZ 格式）
     *
     * @param filePath   压缩文件路径
     * @param extractDir 解压目录
     */
    private static void unzipFile(String filePath, String extractDir) throws IOException {
        createDirectoryIfNotExists(extractDir); // 确保解压目录存在

        if (filePath.toLowerCase().endsWith(".zip")) {
            // 处理 ZIP 文件
            try (ZipFile zipFile = new ZipFile(filePath)) {
                var entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    Path entryPath = Paths.get(extractDir, entry.getName());
                    if (entry.isDirectory()) {
                        createDirectoryIfNotExists(entryPath.toString());
                    } else {
                        try (InputStream is = zipFile.getInputStream(entry)) {
                            Files.copy(is, entryPath);
                        }
                    }
                }
            }
        } else if (filePath.toLowerCase().endsWith(".tgz") || filePath.toLowerCase().endsWith(".tar.gz")) {
            // 处理 TGZ 文件
            try (InputStream fi = new FileInputStream(filePath);
                    InputStream gi = new GzipCompressorInputStream(fi);
                    TarArchiveInputStream ti = new TarArchiveInputStream(gi)) {
                TarArchiveEntry entry;
                while ((entry = ti.getNextTarEntry()) != null) {
                    Path entryPath = Paths.get(extractDir, entry.getName());
                    if (entry.isDirectory()) {
                        createDirectoryIfNotExists(entryPath.toString());
                    } else {
                        try (OutputStream os = Files.newOutputStream(entryPath)) {
                            byte[] buffer = new byte[1024 * 1024]; // 分块读取，每次读取 1MB
                            int bytesRead;
                            while ((bytesRead = ti.read(buffer)) != -1) {
                                os.write(buffer, 0, bytesRead);
                            }
                        }
                    }
                }
            }
        } else {
            throw new IOException("不支持的文件格式: " + filePath);
        }
    }

    /**
     * 删除目录中除了指定文件以外的所有文件
     *
     * @param dirPath      目录路径
     * @param excludeFiles 需要排除的文件名（不删除这些文件）
     */
    private static void deleteFilesExclude(String dirPath, String... excludeFiles) throws IOException {
        Path path = Paths.get(dirPath);

        // 如果目录不存在，直接返回
        if (!Files.exists(path)) {
            return;
        }

        // 如果路径不是目录，抛出异常
        if (!Files.isDirectory(path)) {
            throw new IOException("路径不是目录: " + dirPath);
        }

        try (var stream = Files.newDirectoryStream(path)) {
            for (Path file : stream) {
                // 检查是否需要排除该文件
                boolean shouldExclude = false;
                for (String excludeFile : excludeFiles) {
                    if (file.getFileName().toString().equals(excludeFile)) {
                        shouldExclude = true;
                        break;
                    }
                }

                if (!shouldExclude) {
                    if (Files.isDirectory(file)) {
                        // 如果是目录，递归删除
                        deleteFilesExclude(file.toString(), excludeFiles);
                        Files.delete(file); // 删除空目录
                    } else {
                        Files.delete(file);
                    }
                }
            }
        } catch (IOException e) {
            throw new IOException("删除文件失败: " + dirPath, e);
        }
    }

    /**
     * 删除指定文件
     *
     * @param dirPath 目录路径
     * @param pattern 文件名模式（支持通配符 *，如果为 null 则删除所有文件）
     */
    private static void deleteFiles(String dirPath, String pattern) throws IOException {
        Path path = Paths.get(dirPath);

        // 如果目录不存在，直接返回
        if (!Files.exists(path)) {
            return;
        }

        // 如果路径不是目录，抛出异常
        if (!Files.isDirectory(path)) {
            throw new IOException("路径不是目录: " + dirPath);
        }

        try (var stream = pattern == null ? Files.newDirectoryStream(path) : Files.newDirectoryStream(path, pattern)) {
            for (Path file : stream) {
                if (Files.isDirectory(file)) {
                    // 如果是目录，递归删除
                    deleteFiles(file.toString(), null);
                    Files.delete(file); // 删除空目录
                } else {
                    Files.delete(file);
                }
            }
        } catch (IOException e) {
            throw new IOException("删除文件失败: " + dirPath, e);
        }
    }

    /**
     * 合并文件
     *
     * @param extractDir 解压目录
     * @param outputFile 合并后的文件路径
     */
    private static void mergeFiles(String extractDir, String outputFile) throws IOException {
        Path dirPath = Paths.get(extractDir);
        Path outputFilePath = Paths.get(outputFile);

        try (BufferedWriter writer = Files.newBufferedWriter(outputFilePath)) {
            try (var stream = Files.newDirectoryStream(dirPath, "*.txt")) {
                for (Path file : stream) {
                    // 跳过 outputFile
                    if (file.equals(outputFilePath)) {
                        continue;
                    }

                    try (BufferedReader reader = Files.newBufferedReader(file)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("./")) {
                                line = line.substring(2);
                            }
                            if (!line.contains("/"))
                                continue;
                            writer.write(line);
                            writer.newLine();
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IOException("合并文件失败: " + extractDir, e);
        }
    }
}
