package com.github.catvod.spider;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Date;
import com.github.catvod.utils.Path;
import com.github.catvod.bean.alist.Drive;

public class FanConfigManager {
    private final File mLocalFile;
    private long mLastModifyTime;
    private Thread mWorkThread;
    private boolean isRunning = false;
    private int mCheckCount1 = 0; 
    private int mCheckCount2 = 0; 
    private long mDeltaTime = 0;
    private Drive mServer;

    public FanConfigManager(Drive server) {
        this.mLocalFile = new File(Path.files() + "/tvfan/Cloud-drive.txt");
        mServer = server;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        downloadFile();
        syncServerTime();
        initFileTime();
        mWorkThread = new Thread(new WorkRunnable());
        mWorkThread.start();
    }

    private void initFileTime() {
        mLastModifyTime = mLocalFile.exists() ? mLocalFile.lastModified() : 0;
    }

    private void modifyLocalFileTime(long time) {
        File file = new File(mLocalFile);
        file.setLastModified(time * 1000);
    }

    private void modifyServerFileTime(long time) {
        mServer.exec("touch -d '$(date -d @" + String.valueOf(time) + ")' /Cloud-drive.txt");
    }

    private long getServerFileTime() {
        try {
            return Long.valueOf(mServer.exec("stat -c %Y /Cloud-drive.txt"));
        } catch (Exception e) {
            return 0;
        }
    }

    private void syncServerTime() {
        try {
            long localTime = (new Date().getTime()) / 1000;
            long severTime = Long.valueOf(mServer.exec("date +%s"));
            mDeltaTime = localTime - severTime;
        } catch (Exception e) {
            mDeltaTime = 0;
        }
    }

    private class WorkRunnable implements Runnable {
        @Override
        public void run() {
            while (isRunning) {
                try {
                    uploadFile();

                    mCheckCount1++;
                    if (mCheckCount1 >= 20) {
                        downloadFile();
                        mCheckCount1 = 0;
                    }

                    mCheckCount2++;
                    if (mCheckCount2 >= 200) {
                        syncServerTime();
                        mCheckCount2 = 0;
                    }

                    Thread.sleep(3 * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // 下载文件(用户实现)
    private void downloadFile() {
        new Thread(() -> {
            try {
                // TODO: 实现下载逻辑，保存到mLocalFile
                mLastModifyTime = mLocalFile.lastModified();
            } catch (Exception e) {
                // 异常处理(可选)
            }
        }).start();
    }

    // 检查文件变化并触发上传
    private void uploadFile() {
        if (!mLocalFile.exists()) return;

        long currentTime = mLocalFile.lastModified();
        if (currentTime != mLastModifyTime) {
            // 简单防抖
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (mLocalFile.lastModified() == currentTime) {
                doUploadFile();
                mLastModifyTime = currentTime;
            }
        }
    }

    // 实际上传文件(用户实现)
    private void doUploadFile() {
        new Thread(() -> {
            try {
                if (!mLocalFile.exists()) return;

                // 读取文件内容
                byte[] content = readFileContent();
                // TODO: 实现上传逻辑(使用content)

            } catch (Exception e) {
                // 异常处理(可选)
            }
        }).start();
    }

    // 读取文件内容
    private byte[] readFileContent() throws IOException {
        try (FileInputStream fis = new FileInputStream(mLocalFile)) {
            byte[] buffer = new byte[fis.available()];
            fis.read(buffer);
            return buffer;
        }
    }

    public void stop() {
        isRunning = false;
        if (mWorkThread != null) {
            mWorkThread.interrupt();
        }
    }
}