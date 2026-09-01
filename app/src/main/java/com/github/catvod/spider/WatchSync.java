package com.github.catvod.spider;

import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;

import com.github.catvod.bean.alist.Drive;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 观看记录多端同步（方向 A：复用 alist 服务器文件作为共享记录仓）。
 *
 * - 只反射访问蜂蜜影视(com.fongmi.android.tv)中被 R8 keep 的 bean.History 类，
 *   编译期无依赖，失败静默降级（不影响播放）。
 * - 单文件 watch.txt 存放所有用户的记录，用 user 字段区分（各设备通过 defaultDrive 配置 username）。
 * - 触发：
 *     1) FileObserver 监视 tv / tv-wal 数据库文件变化 -> 事件驱动推送(5s 防抖)
 *     2) 每 30s 定时拉取合并
 *     3) start() 后立即 pull 一次
 * - 合并：复用蜂蜜影视 History.sync()（{@link #syncMerge}）的 LWW / mergeFrom / 定向当前源语义；
 *         并在喂给 sync 前用 History.findByName 做 canSave 进度保护（无进度记录不覆盖有进度的本地）。
 */
public class WatchSync {

    private static final String HISTORY_CLS = "com.fongmi.android.tv.bean.History";

    private static final long PUSH_DEBOUNCE_MS = 5000;
    private static final long PULL_PERIOD_SEC = 30;

    private final Context context;
    private final Drive drive;
    private final String username;
    private final String syncPath;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong lastPushEvent = new AtomicLong(0);

    private HandlerThread watchThread;
    private FileObserver observerMain;

    // 反射缓存（全部来自被 keep 的 bean.History）
    private Method historyGet;        // History.get() -> List（当前源记录）
    private Method historyFindByName; // History.findByName(String) -> List
    private Method historyObjectFrom; // History.objectFrom(String) -> History
    private Method historySync;       // History.sync(List) -> void
    private Method histGetVodName;
    private Method histCanSave;

    private WatchSync(Context context, Drive drive, String username, String syncPath) {
        this.context = context;
        this.drive = drive;
        this.username = username == null ? "" : username;
        this.syncPath = syncPath;
    }

    /** 从 AListSh.init 调用。defaultDrive 即数组中被选中的元素。未启用 / 反射失败时返回 null（静默关闭）。 */
    public static WatchSync start(Context context, Drive drive) {
        try {
            if (drive == null) { Logger.log("WatchSync > 未启用：defaultDrive 为空"); return null; }
            Logger.log("WatchSync > defaultDrive=" + drive.getName() + " syncWatch=" + drive.syncWatch() + " username=[" + drive.getUsername() + "] syncPath=[" + drive.getSyncPath() + "]");
            if (!drive.syncWatch() || drive.getSyncPath().isEmpty()) { Logger.log("WatchSync > 未启用：syncWatch=false 或 syncPath 为空"); return null; }
            WatchSync ws = new WatchSync(context, drive, drive.getUsername(), drive.getSyncPath());
            ws.initReflection();
            ws.startWatching();
            ws.schedule();
            ws.pull();       // 启动兜底：立即拉一次
            ws.push();       // 启动时先把现有记录推上去
            Logger.log("WatchSync > 启动完成");
            return ws;
        } catch (Throwable t) {
            Logger.log("WatchSync > start failed: " + t);
            return null;
        }
    }

    private void initReflection() throws Exception {
        Logger.log("WatchSync > initReflection: 加载 " + HISTORY_CLS);
        Class<?> hist = Class.forName(HISTORY_CLS);
        historyGet = hist.getMethod("get");
        historyFindByName = hist.getMethod("findByName", String.class);
        historyObjectFrom = hist.getMethod("objectFrom", String.class);
        historySync = hist.getMethod("sync", List.class);
        histGetVodName = hist.getMethod("getVodName");
        histCanSave = hist.getMethod("canSave");
        Logger.log("WatchSync > 反射初始化完成: get/sync/findByName 均可调用");
    }

    // ---------------- 触发 ----------------

    private void startWatching() {
        try {
            String dir = context.getDatabasePath("tv").getParent(); // .../databases
            Logger.log("WatchSync > 开始监视数据库目录: " + dir);
            watchThread = new HandlerThread("watch-sync");
            watchThread.start();
            new Handler(watchThread.getLooper()).post(() -> {
                try {
                    observerMain = new FileObserver(dir, FileObserver.MODIFY | FileObserver.CLOSE_WRITE | FileObserver.CREATE) {
                        @Override public void onEvent(int event, String path) {
                            // 只关心 tv / tv-wal 两个文件的变化（覆盖首次创建和 WAL 写入）
                            Logger.log("WatchSync > 文件事件 event=" + event + " path=" + path);
                            if (path != null && (path.equals("tv") || path.equals("tv-wal"))) onDbChanged();
                        }
                    };
                    observerMain.startWatching();
                    Logger.log("WatchSync > FileObserver 开始监视");
                } catch (Throwable t) {
                    Logger.log("WatchSync > observer err: " + t);
                }
            });
        } catch (Throwable t) {
            Logger.log("WatchSync > watch err: " + t);
        }
    }

    /** DB 文件变化 -> push，带 5s 防抖。 */
    private void onDbChanged() {
        long now = System.currentTimeMillis();
        long last = lastPushEvent.get();
        if (now - last < PUSH_DEBOUNCE_MS) { Logger.log("WatchSync > DB变化，5s防抖中，跳过"); return; }
        lastPushEvent.set(now);
        Logger.log("WatchSync > DB变化 -> 调度 push");
        scheduler.execute(this::push);
    }

    private void schedule() {
        Logger.log("WatchSync > 启动定时拉取，周期 " + PULL_PERIOD_SEC + "s");
        scheduler.scheduleWithFixedDelay(this::pull, PULL_PERIOD_SEC, PULL_PERIOD_SEC, TimeUnit.SECONDS);
    }

    // ---------------- 推送 ----------------

    /** 读取当前源(小雅)记录 -> 覆盖写服务器 watch.txt。 */
    private void push() {
        try {
            List<?> local = localHistory();
            String json = pack(local);
            writeRemote(json);
            Logger.log("WatchSync > push 完成，共 " + local.size() + " 条记录，json长度=" + json.length());
        } catch (Throwable t) {
            Logger.log("WatchSync > push err: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private List<?> localHistory() throws Exception {
        Object list = historyGet.invoke(null); // History.get() = 当前源(cid)记录
        List<?> r = list == null ? new ArrayList<>() : (List<?>) list;
        Logger.log("WatchSync > 本地读取(History.get) 条数=" + r.size());
        return r;
    }

    /** 包装成 [{user, history}]，history 为蜂蜜影视 History 的 toString() JSON。 */
    private String pack(List<?> list) throws Exception {
        JSONArray arr = new JSONArray();
        for (Object o : list) {
            JSONObject wrap = new JSONObject();
            wrap.put("user", username);
            wrap.put("history", new JSONObject(o.toString()));
            arr.put(wrap);
        }
        return arr.toString();
    }

    // ---------------- 拉取 ----------------

    private void pull() {
        try {
            String raw = readRemote();
            if (raw == null || raw.trim().isEmpty()) { Logger.log("WatchSync > pull: 远端为空或读取失败"); return; }
            JSONArray arr = new JSONArray(raw);
            List<Object> mine = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject wrap = arr.optJSONObject(i);
                if (wrap == null) continue;
                if (!username.equals(wrap.optString("user"))) continue; // 只合并自己的
                JSONObject rec = wrap.optJSONObject("history");
                if (rec == null) continue;
                if (!canSafeMerge(rec)) continue; // canSave 进度保护
                mine.add(historyObjectFrom.invoke(null, rec.toString()));
            }
            Logger.log("WatchSync > pull 完成，远端总数=" + arr.length() + " 属于本用户=" + mine.size() + " 可合并=" + mine.size());
            if (!mine.isEmpty()) syncMerge(mine);
        } catch (Throwable t) {
            Logger.log("WatchSync > pull err: " + t);
        }
    }

    /** canSave 进度保护：远端若是"纯访问无进度"(!canSave)，且本地存在有进度的记录，则跳过不合并。 */
    private boolean canSafeMerge(JSONObject rec) {
        try {
            Object hist = historyObjectFrom.invoke(null, rec.toString());
            if (hist == null) return true;
            boolean remoteCanSave = (Boolean) histCanSave.invoke(hist);
            if (remoteCanSave) return true;
            String vodName = (String) histGetVodName.invoke(hist);
            Object locals = historyFindByName.invoke(null, vodName); // History.findByName(name)
            if (locals == null) return true;
            for (Object it : (List<?>) locals) {
                if ((Boolean) histCanSave.invoke(it)) {
                    Logger.log("WatchSync > 进度保护：无进度记录不覆盖本地有进度记录 name=" + vodName);
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 复用蜂蜜影视 History.sync(List)：LWW(findByName + createTime) + mergeFrom + cid(当前).save()。 */
    private void syncMerge(List<Object> mine) {
        try {
            historySync.invoke(null, mine);
            Logger.log("WatchSync > 已调用 History.sync() 合并 " + mine.size() + " 条");
        } catch (Throwable t) {
            Logger.log("WatchSync > syncMerge err: " + t);
        }
    }

    // ---------------- 服务器文件读写（走 exec，base64 避免转义问题） ----------------

    private String readRemote() {
        try {
            String out = drive.exec("cat " + syncPath);
            if (out == null) { Logger.log("WatchSync > readRemote: 远端返回 null"); return ""; }
            Logger.log("WatchSync > readRemote: 返回长度=" + out.trim().length());
            return out.trim();
        } catch (Throwable t) {
            Logger.log("WatchSync > readRemote err: " + t);
            return null;
        }
    }

    private void writeRemote(String json) {
        try {
            String b64 = android.util.Base64.encodeToString(json.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            String cmd = "printf '%s' '" + b64 + "' | base64 -d > " + syncPath + ".tmp && mv " + syncPath + ".tmp " + syncPath;
            String res = drive.exec(cmd);
            Logger.log("WatchSync > writeRemote: 已写入 " + syncPath + "（json长度=" + json.length() + "，exec返回=[" + res + "]）");
        } catch (Throwable t) {
            Logger.log("WatchSync > write err: " + t);
        }
    }
}