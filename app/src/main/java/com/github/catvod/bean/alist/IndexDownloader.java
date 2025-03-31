package com.github.catvod.bean.alist;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import com.github.catvod.spider.Logger;

public class IndexDownloader {
    private static Map<String, String> cacheMap = new HashMap<>();

    public static synchronized String downlodadAndUnzip(String url) {
        String filePath = cacheMap.get(url);
        if (filePath != null) {
            return filePath;
        }

        try {
            String fileUrl = url + "/tvbox/data";
            String saveDir = getCacheDirPath() + url.replace(":", "_").replace("/", "_");
            
            // 0. 清空目录
            deleteDirectory(new File(saveDir));
            
            // 1. 确保目录存在
            createDirectory(saveDir);
            
            // 2. 下载文件
            downloadFile(fileUrl + "/index.video.tgz", saveDir + "/index.video.tgz");
            downloadFile(fileUrl + "/index.115.tgz", saveDir + "/index.115.tgz");
            
            // 3. 解压文件
            unzipFile(saveDir + "/index.video.tgz", saveDir);
            unzipFile(saveDir + "/index.115.tgz", saveDir);
            
            // 4. 合并文件
            mergeFiles(saveDir, saveDir + "/index.all.txt");
            
            // 5. 清理文件
            deleteFilesExclude(saveDir, "index.all.txt");
            deleteFilesByPattern(saveDir, "*.tgz");
            
            filePath = saveDir + "/index.all.txt";
            cacheMap.put(url, filePath);
        } catch (IOException e) {
            Logger.log("downlodadAndUnzip error: " + e.getMessage());
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

    private static void createDirectory(String dirPath) throws IOException {
        File dir = new File(dirPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create directory: " + dirPath);
        }
    }

    private static void deleteDirectory(File dir) throws IOException {
        if (!dir.exists()) return;
        
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    if (!file.delete()) {
                        throw new IOException("Failed to delete file: " + file.getAbsolutePath());
                    }
                }
            }
        }
        
        if (!dir.delete()) {
            throw new IOException("Failed to delete directory: " + dir.getAbsolutePath());
        }
    }

    private static void downloadFile(String fileUrl, String savePath) throws IOException {
        try (InputStream in = new BufferedInputStream(new URL(fileUrl).openStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(savePath))) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    private static void unzipFile(String filePath, String extractDir) throws IOException {
        createDirectory(extractDir);
        
        if (filePath.toLowerCase().endsWith(".zip")) {
            // ZIP 文件处理
            try (ZipFile zipFile = new ZipFile(filePath)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    File entryFile = new File(extractDir, entry.getName());
                    
                    if (entry.isDirectory()) {
                        createDirectory(entryFile.getAbsolutePath());
                    } else {
                        createDirectory(entryFile.getParent());
                        try (InputStream is = zipFile.getInputStream(entry);
                             OutputStream os = new FileOutputStream(entryFile)) {
                            
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                os.write(buffer, 0, bytesRead);
                            }
                        }
                    }
                }
            }
        } else if (filePath.toLowerCase().endsWith(".tgz") || filePath.toLowerCase().endsWith(".tar.gz")) {
            // TGZ 文件处理
            try (InputStream fi = new FileInputStream(filePath);
                 InputStream gi = new GzipCompressorInputStream(fi);
                 TarArchiveInputStream ti = new TarArchiveInputStream(gi)) {
                
                TarArchiveEntry entry;
                while ((entry = ti.getNextTarEntry()) != null) {
                    File entryFile = new File(extractDir, entry.getName());
                    
                    if (entry.isDirectory()) {
                        createDirectory(entryFile.getAbsolutePath());
                    } else {
                        createDirectory(entryFile.getParent());
                        try (OutputStream os = new FileOutputStream(entryFile)) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = ti.read(buffer)) != -1) {
                                os.write(buffer, 0, bytesRead);
                            }
                        }
                    }
                }
            }
        } else {
            throw new IOException("Unsupported file format: " + filePath);
        }
    }

    private static void deleteFilesExclude(String dirPath, String... excludeFiles) throws IOException {
        File dir = new File(dirPath);
        if (!dir.exists()) return;
        if (!dir.isDirectory()) throw new IOException("Path is not a directory: " + dirPath);
        
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                boolean shouldExclude = false;
                for (String excludeFile : excludeFiles) {
                    if (file.getName().equals(excludeFile)) {
                        shouldExclude = true;
                        break;
                    }
                }
                
                if (!shouldExclude) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        if (!file.delete()) {
                            throw new IOException("Failed to delete file: " + file.getAbsolutePath());
                        }
                    }
                }
            }
        }
    }

    private static void deleteFilesByPattern(String dirPath, String pattern) throws IOException {
        File dir = new File(dirPath);
        if (!dir.exists()) return;
        if (!dir.isDirectory()) throw new IOException("Path is not a directory: " + dirPath);
        
        final String regex = pattern.replace(".", "\\.").replace("*", ".*");
        File[] files = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.matches(regex);
            }
        });
        
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    if (!file.delete()) {
                        throw new IOException("Failed to delete file: " + file.getAbsolutePath());
                    }
                }
            }
        }
    }

    private static void mergeFiles(String extractDir, String outputFile) throws IOException {
        File dir = new File(extractDir);
        File output = new File(outputFile);
        
        // 定义文件读取顺序
        String[] fileOrder = {"index.video.txt", "index.115.txt"};
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(output))) {
            // 按照指定顺序读取文件
            for (String fileName : fileOrder) {
                File file = new File(dir, fileName);
                if (file.exists()) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
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
            
            // 读取剩余的其他 .txt 文件
            File[] otherFiles = dir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.endsWith(".txt") && 
                           !name.equals("index.all.txt") &&
                           !Arrays.asList(fileOrder).contains(name);
                }
            });
            
            if (otherFiles != null) {
                for (File file : otherFiles) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
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
        }
    }
}
