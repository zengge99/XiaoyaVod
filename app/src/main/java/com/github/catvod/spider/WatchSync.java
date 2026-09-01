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
 * - 反射访问蜂蜜影视(com.fongmi.android.tv.*)的本地观看记录，编译期无依赖，失败静默降级。
 * - 单文件 watch.txt 存放所有用户的记录，用 user 字段区分（各设备通过 extend 配置 username）。
 * - 只同步"当前小雅源(cid)"的记录；其它源的记录不出本机。
 * - 触发：
 *     1) FileObserver 监视 tv / tv-wal 数据库文件变化 -> 事件驱动推送(5s 防抖)
 *     2) 每 30s 定时拉取合并
 *     3) init() 后立即 pull 一次（首屏拿历史）
 * - 合并：按 (user, vodName) 匹配，createTime 大者胜(LWW)，canSave 进度保护，定向写回小雅源 cid。
 */
public class WatchSync {

    private static final String HISTORY_CLS = "com.fongmi.android.tv.bean.History";
    private static final String DBD_CLS = "com.fongmi.android.tv.db.AppDatabase";
    private static final String DAO_CLS = "com.fongmi.android.tv.db.dao.HistoryDao";
    private static final String VODCFG_CLS = "com.fongmi.android.tv.api.config.VodConfig";

    private static final long PUSH_DEBOUNCE_MS = 5000;
    private static final long PULL_PERIOD_SEC = 30;

    private final Context context;
    private final Drive drive;
    private final String username;
    private final String syncPath;
    private final int xiaoyaCid;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong lastPushEvent = new AtomicLong(0);

    private HandlerThread watchThread;
    private FileObserver observerMain;

    // 反射缓存
    private Object dao;
    private Method historyGet;        // History.get(int) -> List
    private Method historyObjectFrom; // History.objectFrom(String) -> History
    private Method histCid;           // History.cid(int) -> rebuild key
    private Method histGetVodName;
    private Method histGetCreateTime;
    private Method histGetPosition;
    private Method histGetDuration;
    private Method histCanSave;
    private Method histSave;
    private Method findByNameDao;     // HistoryDao.findByName(int,String) -> List

    private WatchSync(Context context, Drive drive, String username, String syncPath, int xiaoyaCid) {
        this.context = context;
        this.drive = drive;
        this.username = username == null ? "" : username;
        this.syncPath = syncPath;
        this.xiaoyaCid = xiaoyaCid;
    }

    /** 从 AListSh.init 调用。defaultDrive 即数组中被选中的元素。未启用 / 反射失败时返回 null（静默关闭）。 */
    public static WatchSync start(Context context, Drive drive) {
        try {
            if (drive == null || !drive.syncWatch() || drive.getSyncPath().isEmpty()) return null;
            int cid = readCid();
            WatchSync ws = new WatchSync(context, drive, drive.getUsername(), drive.getSyncPath(), cid);
            ws.initReflection();
            ws.startWatching();
            ws.schedule();
            ws.pull();       // 启动兜底：立即拉一次
            ws.push();       // 启动时先把现有记录推上去
            return ws;
        } catch (Throwable t) {
            Logger.log("WatchSync start failed: " + t);
            return null;
        }
    }

    // ---------------- 配置 ----------------

    private static int readCid() throws Exception {
        Class<?> vc = Class.forName(VODCFG_CLS);
        Method getCid = vc.getMethod("getCid");
        return ((Number) getCid.invoke(null)).intValue();
    }

    private void initReflection() throws Exception {
        Class<?> appDb = Class.forName(DBD_CLS);
        Method dbGet = appDb.getMethod("get");
        Object db = dbGet.invoke(null);
        Method getHistoryDao = null;
        for (Method m : appDb.getMethods()) {
            if (m.getName().equals("getHistoryDao") && m.getParameterCount() == 0) {
                getHistoryDao = m;
                break;
            }
        }
        if (getHistoryDao != null) dao = getHistoryDao.invoke(db);
        Class<?> hist = Class.forName(HISTORY_CLS);
        historyGet = hist.getMethod("get", int.class);
        historyObjectFrom = hist.getMethod("objectFrom", String.class);
        histCid = hist.getMethod("cid", int.class);
        histGetVodName = hist.getMethod("getVodName");
        histGetCreateTime = hist.getMethod("getCreateTime");
        histGetPosition = hist.getMethod("getPosition");
        histGetDuration = hist.getMethod("getDuration");
        histCanSave = hist.getMethod("canSave");
        histSave = hist.getMethod("save");
        for (Method m : dao.getClass().getMethods()) {
            if (m.getName().equals("findByName")
                && m.getParameterCount() == 2
                && m.getParameterTypes()[0] == int.class
                && m.getParameterTypes()[1] == String.class) {
                findByNameDao = m;
                break;
            }
        }
    }

    // ---------------- 触发 ----------------

    private void startWatching() {
        try {
            String dir = context.getDatabasePath("tv").getParent(); // .../databases
            watchThread = new HandlerThread("watch-sync");
            watchThread.start();
            new Handler(watchThread.getLooper()).post(() -> {
                try {
                    observerMain = new FileObserver(dir, FileObserver.MODIFY | FileObserver.CLOSE_WRITE | FileObserver.CREATE) {
                        @Override public void onEvent(int event, String path) {
                            // 只关心 tv / tv-wal 两个文件的变化（覆盖首次创建和 WAL 写入）
                            if (path != null && (path.equals("tv") || path.equals("tv-wal"))) onDbChanged();
                        }
                    };
                    observerMain.startWatching();
                } catch (Throwable t) {
                    Logger.log("WatchSync observer err: " + t);
                }
            });
        } catch (Throwable t) {
            Logger.log("WatchSync watch err: " + t);
        }
    }

    /** DB 文件变化 -> push，带 5s 防抖。 */
    private void onDbChanged() {
        long now = System.currentTimeMillis();
        long last = lastPushEvent.get();
        if (now - last < PUSH_DEBOUNCE_MS) return;
        lastPushEvent.set(now);
        scheduler.execute(this::push);
    }

    private void schedule() {
        scheduler.scheduleWithFixedDelay(this::pull, PULL_PERIOD_SEC, PULL_PERIOD_SEC, TimeUnit.SECONDS);
    }

    // ---------------- 推送 ----------------

    /** 读取小雅源记录 -> 覆盖写服务器 watch.txt。 */
    private void push() {
        try {
            List<?> local = localHistory();
            String json = pack(local);
            writeRemote(json);
            Logger.log("WatchSync pushed " + local.size() + " records");
        } catch (Throwable t) {
            Logger.log("WatchSync push err: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private List<?> localHistory() throws Exception {
        Object list = historyGet.invoke(null, xiaoyaCid);
        return list == null ? new ArrayList<>() : (List<?>) list;
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
            if (raw == null || raw.trim().isEmpty()) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject wrap = arr.optJSONObject(i);
                if (wrap == null) continue;
                if (!username.equals(wrap.optString("user"))) continue; // 只合并自己的
                JSONObject rec = wrap.optJSONObject("history");
                if (rec == null) continue;
                merge(rec.toString());
            }
        } catch (Throwable t) {
            Logger.log("WatchSync pull err: " + t);
        }
    }

    /** 按 (user, vodName) + createTime LWW + canSave 进度保护合并入库。 */
    private void merge(String historyJson) throws Exception {
        Object hist = historyObjectFrom.invoke(null, historyJson);
        if (hist == null) return;
        String vodName = (String) histGetVodName.invoke(hist);
        Object local = findExisting(vodName);
        long remoteTime = ((Number) histGetCreateTime.invoke(hist)).longValue();
        if (local != null) {
            long localTime = ((Number) histGetCreateTime.invoke(local)).longValue();
            if (remoteTime <= localTime) return;            // LWW：本地不旧，保留本地
            boolean localCanSave = ((Boolean) histCanSave.invoke(local));
            boolean remoteCanSave = ((Boolean) histCanSave.invoke(hist));
            if (localCanSave && !remoteCanSave) return;     // 进度保护：勿用无进度记录覆盖有进度的
        }
        histCid.invoke(hist, xiaoyaCid);                    // 定向写回小雅源
        histSave.invoke(hist);
    }

    private Object findExisting(String vodName) throws Exception {
        if (findByNameDao == null) return null;
        Object list = findByNameDao.invoke(dao, xiaoyaCid, vodName);
        if (list == null) return null;
        List<?> items = (List<?>) list;
        Object best = null;
        long max = -1;
        for (Object it : items) {
            long t = ((Number) histGetCreateTime.invoke(it)).longValue();
            if (t > max) { max = t; best = it; }
        }
        return best;
    }

    // ---------------- 服务器文件读写（走 exec，base64 避免转义问题） ----------------

    private String readRemote() {
        try {
            String out = drive.exec("cat " + syncPath);
            return out == null ? "" : out.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private void writeRemote(String json) {
        try {
            String b64 = android.util.Base64.encodeToString(json.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            drive.exec("printf '%s' '" + b64 + "' | base64 -d > " + syncPath + ".tmp && mv " + syncPath + ".tmp " + syncPath);
        } catch (Throwable t) {
            Logger.log("WatchSync write err: " + t);
        }
    }
}