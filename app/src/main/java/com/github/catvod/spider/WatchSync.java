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
    private static final long LOCK_TIMEOUT_MS = 5000;
    private static final long LOCK_RETRY_MS = 100;

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
            ws.pull();       // 启动兜底：读一次远端 + 合并本地 + 对账（不一致才写），不再单独 push
            Logger.log("WatchSync > 启动完成");
            return ws;
        } catch (Throwable t) {
            Logger.log("WatchSync > start failed: " + t);
            return null;
        }
    }

    private void initReflection() throws Exception {
        Class<?> hist = resolveHistoryClass();
        Logger.log("WatchSync > 解析到 History 类: " + hist.getName());
        historyGet = hist.getMethod("get");
        historyFindByName = hist.getMethod("findByName", String.class);
        historyObjectFrom = hist.getMethod("objectFrom", String.class);
        historySync = hist.getMethod("sync", List.class);
        histGetVodName = hist.getMethod("getVodName");
        histCanSave = hist.getMethod("canSave");
        Logger.log("WatchSync > 反射初始化完成: get/sync/findByName 均可调用");
    }

    /**
     * 解析蜂蜜影视宿主真实的 History 类。
     * 宿主类可能被二次开发改了包名，故按下列顺序探测：
     * 1) 从宿主的 Application 类名推导包名前缀 + bean.History
     * 2) 从 context.getPackageName() 推导 + .bean.History
     * 3) 兜底原始包名 com.fongmi.android.tv.bean.History
     * 全部失败则抛出，由调用方静默降级。
     */
    private Class<?> resolveHistoryClass() throws Exception {
        List<String> candidates = new ArrayList<>();
        String suffix = "bean.History";
        try {
            // 宿主 Application 类（如 com.fongmi.android.tv.App / <换皮包>.App），取包前缀
            String appCls = context.getApplicationInfo().className;
            if (appCls != null && appCls.lastIndexOf('.') > 0)
                candidates.add(appCls.substring(0, appCls.lastIndexOf('.') + 1) + suffix);
        } catch (Throwable t) {
            Logger.log("WatchSync > 解析候选1(Application类)失败: " + t);
        }
        try {
            String pkg = context.getPackageName();
            if (pkg != null && !pkg.isEmpty())
                candidates.add(pkg + "." + suffix);
        } catch (Throwable t) {
            Logger.log("WatchSync > 解析候选2(packageName)失败: " + t);
        }
        candidates.add(HISTORY_CLS); // 兜底原始包名
        for (String cand : candidates) {
            try {
                Class<?> cls = Class.forName(cand);
                Logger.log("WatchSync > 候选命中: " + cand);
                return cls;
            } catch (Throwable t) {
                Logger.log("WatchSync > 候选未命中: " + cand);
            }
        }
        throw new ClassNotFoundException("History 类解析失败，候选: " + candidates);
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

    /** 读取当前源(小雅)记录 -> 持锁 merge-write 写服务器 watch.txt（事件驱动）。锁内重新读远端，避免并发覆盖。 */
    private void push() {
        withLock(() -> {
            try {
                List<?> local = localHistory();
                String raw = readRemote();          // 锁内读最新远端，用于合并
                String json = merge(local, raw);    // merge-write：保留远端别人记录，只刷新自己的部分
                writeRemote(json);
                Logger.log("WatchSync > push 完成（merge-write，保留他人记录），本机=" + local.size() + " 条，json长度=" + json.length());
            } catch (Throwable t) {
                Logger.log("WatchSync > push err: " + t);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private List<?> localHistory() throws Exception {
        Object list = historyGet.invoke(null); // History.get() = 当前源(cid)记录
        List<?> r = list == null ? new ArrayList<>() : (List<?>) list;
        Logger.log("WatchSync > 本地读取(History.get) 条数=" + r.size());
        return r;
    }

    /**
     * merge-write：基于已读的远端 raw(所有用户)，先原样保留 user != 本机 的记录，
     * 再用本机当前记录覆盖 user == 本机 的部分，返回全量合并 JSON。
     * 确保 push 绝不覆盖其他用户的信息。
     */
    private String merge(List<?> local, String raw) throws Exception {
        JSONArray merged = new JSONArray();
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONArray remote = new JSONArray(raw);
                for (int i = 0; i < remote.length(); i++) {
                    JSONObject item = remote.optJSONObject(i);
                    if (item != null && !username.equals(item.optString("user"))) merged.put(item);
                }
            } catch (Throwable t) {
                Logger.log("WatchSync > merge: 远端解析失败，按空仓处理: " + t);
            }
        }
        for (Object o : local) {
            JSONObject wrap = new JSONObject();
            wrap.put("user", username);
            wrap.put("history", new JSONObject(o.toString()));
            merged.put(wrap);
        }
        return merged.toString();
    }

    // ---------------- 拉取 ----------------

    private void pull() {
        try {
            String raw = readRemote();           // 读一次远端，透传给 syncMine/reconcile（避免重复读）
            if (raw == null || raw.trim().isEmpty()) { Logger.log("WatchSync > pull: 远端为空或读取失败"); return; }
            syncMine(raw);                        // 过滤出本机组记录并 History.sync 合并本地
            reconcile(raw);                       // 对账：合并后拉取预期内容 vs 远端，不一致才写
        } catch (Throwable t) {
            Logger.log("WatchSync > pull err: " + t);
        }
    }

    /** 从远端 raw 过滤出 user==本机 的记录，经 canSave 保护后 History.sync 合并进本地 DB。 */
    private void syncMine(String raw) {
        try {
            List<Object> mine = new ArrayList<>();
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject wrap = arr.optJSONObject(i);
                if (wrap == null) continue;
                if (!username.equals(wrap.optString("user"))) continue; // 只合并自己的
                JSONObject rec = wrap.optJSONObject("history");
                if (rec == null) continue;
                if (!canSafeMerge(rec)) continue; // canSave 进度保护
                mine.add(historyObjectFrom.invoke(null, rec.toString()));
            }
            Logger.log("WatchSync > pull 完成，远端总数=" + arr.length() + " 属于本用户=" + mine.size());
            if (!mine.isEmpty()) syncMerge(mine);
        } catch (Throwable t) {
            Logger.log("WatchSync > syncMine err: " + t);
        }
    }

    /**
     * 对账：用已读到的远端 raw + 本地当前记录生成预期 merge 内容，与远端 raw 比较，
     * 不一致则持锁重新读-合并-写（防漏推 + 并发安全）。
     */
    private void reconcile(String raw) {
        try {
            List<?> local = localHistory();
            String merged = merge(local, raw);
            if (merged.equals(raw)) {
                Logger.log("WatchSync > 对账：服务器与本地一致，无需 push");
            } else {
                Logger.log("WatchSync > 对账：服务器与本地不一致，补一次 merge-write push");
                withLock(() -> {
                    try {
                        List<?> l2 = localHistory();
                        String raw2 = readRemote();          // 锁内读最新远端，避免并发覆盖
                        String m2 = merge(l2, raw2);
                        if (!m2.equals(raw2)) writeRemote(m2);
                    } catch (Throwable t) {
                        Logger.log("WatchSync > 对账写入 err: " + t);
                    }
                });
            }
        } catch (Throwable t) {
            Logger.log("WatchSync > 对账 err: " + t);
        }
    }

    /** 用 mkdir 锁目录在服务器端互斥，串行化 push 的读-改-写；超时自动放弃。 */
    private void withLock(Runnable r) {
        if (!acquireLock()) {
            Logger.log("WatchSync > 获取锁超时，本次跳过（不写，防并发覆盖）");
            return;
        }
        try {
            r.run();
        } finally {
            releaseLock();
        }
    }

    private boolean acquireLock() {
        String lock = syncPath + ".lock";
        long deadline = System.currentTimeMillis() + LOCK_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String res = drive.exec("mkdir " + lock + " 2>/dev/null && echo LOCKED");
            if (res != null && res.contains("LOCKED")) return true;
            try {
                Thread.sleep(LOCK_RETRY_MS);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private void releaseLock() {
        try {
            drive.exec("rmdir " + syncPath + ".lock 2>/dev/null");
        } catch (Throwable t) {
            Logger.log("WatchSync > releaseLock err: " + t);
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