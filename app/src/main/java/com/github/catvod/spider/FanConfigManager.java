import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class FanConfigManager {
    private final File mLocalFile;
    private long mLastModifyTime;
    private Thread mWorkThread;
    private boolean isRunning = false;
    private int mCheckCount = 0; // 监控计数，控制下载频率

    public FanConfigManager(String filePath) {
        this.mLocalFile = new File(filePath);
        initFileTime();
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;

        // 首次下载
        downloadFile();
        // 启动工作线程（合并监控和下载逻辑）
        mWorkThread = new Thread(new WorkRunnable());
        mWorkThread.start();
    }

    private void initFileTime() {
        mLastModifyTime = mLocalFile.exists() ? mLocalFile.lastModified() : 0;
    }

    // 合并后的工作线程逻辑
    private class WorkRunnable implements Runnable {
        @Override
        public void run() {
            while (isRunning) {
                try {
                    // 1. 先执行监控逻辑（检查文件变化并上传）
                    uploadFile();

                    // 2. 每20次监控执行一次下载（3秒×20=60秒=1分钟）
                    mCheckCount++;
                    if (mCheckCount >= 20) {
                        downloadFile();
                        mCheckCount = 0; // 重置计数
                    }

                    // 间隔3秒
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