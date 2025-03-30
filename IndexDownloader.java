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
import java.util.Enumeration;
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
import com.github.catvod.utils.Notify;

import android.os.Debug;

public class IndexDownloader {
    private static Map<String, String> cacheMap = new HashMap<>();

    public static synchronized String downlodadAndUnzip(String url) {
        String filePath = cacheMap.get(url);
        if (filePath != null) {
            return filePath;
        }

        try {
            String fileUrl = url + "/tvbox/data";
            String saveDir = getCacheDirPath()
                     + url.replace(":", "_").replace("/", "_");

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

            filePath = saveDir + "/index.all.txt";
            cacheMap.put(url, filePath);
            
        } catch (IOException e) {
        }

        return filePath;
    }
    
    private static String getCacheDirPath() {
        return com.github.catvod.utils.Path.cache().getPath() + "/TV/index/";
    }

    public static void clearCacheDirectory() {
        String cacheDirPath = getCacheDirPath();
        File cacheDir = new File(cacheDirPath);

        // 如果缓存目录不存在，直接返回
        if (!cacheDir.exists()) {
            return;
        }

        // 如果路径不是一个目录，直接返回
        if (!cacheDir.isDirectory()) {
            return;
        }

        // 获取缓存目录中的所有文件
        File[] files = cacheDir.listFiles();

        // 如果文件数组为空，直接返回
        if (files == null) {
            return;
        }

        // 遍历并删除文件，静默处理所有错误
        for (File file : files) {
            try {
                if (file.isDirectory()) {
                    // 如果是目录，递归删除
                    deleteFiles(file.getAbsolutePath(), null);
                }
                Files.delete(file.toPath());
            } catch (IOException e) {
                // 静默处理错误，可以记录日志（可选）
                Logger.log("Failed to delete file: " + file.getAbsolutePath() + ", error: " + e.getMessage());
            }
        }
    }

    private static void createDirectoryIfNotExists(String dirPath) throws IOException {
        Path path = Paths.get(dirPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

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

    private static void unzipFile(String filePath, String extractDir) throws IOException {
        createDirectoryIfNotExists(extractDir); // 确保解压目录存在

        if (filePath.toLowerCase().endsWith(".zip")) {
            // 处理 ZIP 文件
            try (ZipFile zipFile = new ZipFile(filePath)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
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

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
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

        try (DirectoryStream<Path> stream = pattern == null ? Files.newDirectoryStream(path) : Files.newDirectoryStream(path, pattern)) {
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

    private static void mergeFiles(String extractDir, String outputFile) throws IOException {
        Path dirPath = Paths.get(extractDir);
        Path outputFilePath = Paths.get(outputFile);

        // 定义文件读取顺序
        List<String> fileOrder = List.of("index.video.txt", "index.115.txt");

        try (BufferedWriter writer = Files.newBufferedWriter(outputFilePath)) {
            // 按照指定顺序读取文件
            for (String fileName : fileOrder) {
                Path filePath = dirPath.resolve(fileName);
                if (Files.exists(filePath)) {
                    try (BufferedReader reader = Files.newBufferedReader(filePath)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("./")) {
                                line = line.substring(2);
                            }
                            if (!line.contains("/")) continue;
                            writer.write(line);
                            writer.newLine();
                        }
                    }
                }
            }

            // 读取剩余的其他 .txt 文件（如果有）
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath, "*.txt")) {
                for (Path file : stream) {
                    // 跳过 outputFile 和已经处理的文件
                    if (file.equals(outputFilePath) || fileOrder.contains(file.getFileName().toString())) {
                        continue;
                    }

                    try (BufferedReader reader = Files.newBufferedReader(file)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("./")) {
                                line = line.substring(2);
                            }
                            if (!line.contains("/")) continue;
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
