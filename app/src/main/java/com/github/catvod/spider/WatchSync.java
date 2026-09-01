package com.github.catvod.spider;

import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;

import com.github.catvod.bean.alist.Drive;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 观看记录多端同步（方向 A：复用 alist 服务器文件作为共享记录仓）。
 */
public class WatchSync {

    private static final String HISTORY_CLS = "com.fongmi.android.tv.bean.History";
    private static final String STATE_FILE = "watch_sync_state.json";

    private static final long PUSH_DEBOUNCE_MS = 5000;
    private static final long PULL_PERIOD_SEC = 30;
    private static final long TOMBSTONE_TTL_MS = 60L * 24 * 60 * 60 * 1000; // 60 天

    private final Context context;
    private final Drive drive;
    private final String username;
    private final String syncPath;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong lastPushEvent = new AtomicLong(0);
    // 增加防抖积压标志，防止高频删除吞事件
    private final AtomicBoolean pushPending = new AtomicBoolean(false);

    private HandlerThread watchThread;
    private FileObserver observerMain;

    // 反射缓存
    private Class<?> historyClass;
    private Method historyGet;        // History.get() -> List
    private Method historyFindByName; // History.findByName(String) -> List
    private Method historyObjectFrom; // History.objectFrom(String) -> History
    private Method historySync;       // History.sync(List) -> void
    private Method histGetVodName;
    private Method histCanSave;
    private Method histGetPosition;
    private Method histGetDuration;
    private Method histDelete;        // History.delete() -> void

    // 本机墓碑：name -> 删除时间
    private final Map<String, Long> localTombs = new HashMap<>();
    // 本机上次推送过的名称集合
    private final Set<String> lastPushed = new HashSet<>();

    private WatchSync(Context context, Drive drive, String username, String syncPath) {
        this.context = context;
        this.drive = drive;
        this.username = username == null ? "" : username;
        this.syncPath = syncPath;
    }

    public static WatchSync start(Context context, Drive drive) {
        try {
            if (drive == null) {
                Logger.log("WatchSync > 未启用：defaultDrive 为空");
                return null;
            }
            Logger.log("WatchSync > defaultDrive=" + drive.getName() + " syncWatch=" + drive.syncWatch() + " username=[" + drive.getUsername() + "] syncPath=[" + drive.getSyncPath() + "]");
            if (!drive.syncWatch() || drive.getSyncPath().isEmpty()) {
                Logger.log("WatchSync > 未启用：syncWatch=false 或 syncPath 为空");
                return null;
            }
            WatchSync ws = new WatchSync(context, drive, drive.getUsername(), drive.getSyncPath());
            ws.loadState();
            ws.initReflection();
            ws.startWatching();
            ws.schedule();
            // 启动通过单线程池调度，避免并发竞争
            ws.scheduler.execute(ws::pull);
            Logger.log("WatchSync > 启动完成");
            return ws;
        } catch (Throwable t) {
            Logger.log("WatchSync > start failed: " + t);
            return null;
        }
    }

    // ---------------- 本机状态持久化 ----------------

    private File stateFile() {
        return new File(context.getFilesDir(), STATE_FILE);
    }

    private void loadState() {
        try {
            File f = stateFile();
            if (f.exists()) {
                JSONObject o = new JSONObject(com.github.catvod.utils.Path.read(f));
                if (o.has("lastPushed")) {
                    JSONArray arr = o.getJSONArray("lastPushed");
                    for (int i = 0; i < arr.length(); i++) lastPushed.add(arr.getString(i));
                }
                if (o.has("tombs")) {
                    JSONObject tb = o.getJSONObject("tombs");
                    java.util.Iterator<String> it = tb.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        localTombs.put(k, tb.getLong(k));
                    }
                }
            }
            Logger.log("WatchSync > 加载本机状态: lastPushed=" + lastPushed.size() + " tombs=" + localTombs.size());
        } catch (Throwable t) {
            Logger.log("WatchSync > loadState err: " + t);
        }
    }

    private void saveState() {
        try {
            JSONObject o = new JSONObject();
            JSONArray lp = new JSONArray();
            for (String s : lastPushed) lp.put(s);
            JSONObject tb = new JSONObject();
            for (Map.Entry<String, Long> e : localTombs.entrySet()) tb.put(e.getKey(), e.getValue());
            o.put("lastPushed", lp);
            o.put("tombs", tb);
            com.github.catvod.utils.Path.write(stateFile(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Logger.log("WatchSync > saveState err: " + t);
        }
    }

    private void initReflection() throws Exception {
        historyClass = resolveHistoryClass();
        Logger.log("WatchSync > 解析到 History 类: " + historyClass.getName());
        historyGet = historyClass.getMethod("get");
        historyFindByName = historyClass.getMethod("findByName", String.class);
        historyObjectFrom = historyClass.getMethod("objectFrom", String.class);
        historySync = historyClass.getMethod("sync", List.class);
        histGetVodName = historyClass.getMethod("getVodName");
        try {
            histCanSave = historyClass.getMethod("canSave");
        } catch (Throwable t) {
            histCanSave = null;
        }
        try {
            histGetPosition = historyClass.getMethod("getPosition");
            histGetDuration = historyClass.getMethod("getDuration");
        } catch (Throwable t) {
            histGetPosition = null;
            histGetDuration = null;
        }
        try {
            histDelete = historyClass.getMethod("delete");
        } catch (Throwable t) {
            histDelete = null;
        }
    }

    private Class<?> resolveHistoryClass() throws Exception {
        List<String> candidates = new ArrayList<>();
        String suffix = "bean.History";
        try {
            String appCls = context.getApplicationInfo().className;
            if (appCls != null && appCls.lastIndexOf('.') > 0)
                candidates.add(appCls.substring(0, appCls.lastIndexOf('.') + 1) + suffix);
        } catch (Throwable ignored) {
        }
        try {
            String pkg = context.getPackageName();
            if (pkg != null && !pkg.isEmpty())
                candidates.add(pkg + "." + suffix);
        } catch (Throwable ignored) {
        }
        candidates.add(HISTORY_CLS);
        for (String cand : candidates) {
            try {
                return Class.forName(cand);
            } catch (Throwable ignored) {
            }
        }
        throw new ClassNotFoundException("History 类解析失败，候选: " + candidates);
    }

    // ---------------- 触发 ----------------

    private void startWatching() {
        try {
            File dbFile = context.getDatabasePath("tv");
            File dbDir = dbFile.getParentFile();
            if (dbDir != null && !dbDir.exists()) {
                dbDir.mkdirs();
            }
            if (dbDir == null) return;

            String dirPath = dbDir.getAbsolutePath();
            Logger.log("WatchSync > 开始监视数据库目录: " + dirPath);
            watchThread = new HandlerThread("watch-sync");
            watchThread.start();
            new Handler(watchThread.getLooper()).post(() -> {
                try {
                    observerMain = new FileObserver(dirPath, FileObserver.MODIFY | FileObserver.CLOSE_WRITE | FileObserver.CREATE) {
                        @Override
                        public void onEvent(int event, String path) {
                            if (path != null && (path.equals("tv") || path.equals("tv-wal"))) {
                                onDbChanged();
                            }
                        }
                    };
                    observerMain.startWatching();
                } catch (Throwable t) {
                    Logger.log("WatchSync > observer err: " + t);
                }
            });
        } catch (Throwable t) {
            Logger.log("WatchSync > watch err: " + t);
        }
    }

    private void onDbChanged() {
        long now = System.currentTimeMillis();
        long last = lastPushEvent.get();
        if (now - last < PUSH_DEBOUNCE_MS) {
            // 如果 5 秒内频繁变动，不要直接丢弃，而是延迟调度一次兜底推送
            if (pushPending.compareAndSet(false, true)) {
                scheduler.schedule(() -> {
                    pushPending.set(false);
                    lastPushEvent.set(System.currentTimeMillis());
                    push();
                }, PUSH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            }
            return;
        }
        lastPushEvent.set(now);
        scheduler.execute(this::push);
    }

    private void schedule() {
        scheduler.scheduleWithFixedDelay(this::pull, PULL_PERIOD_SEC, PULL_PERIOD_SEC, TimeUnit.SECONDS);
    }

    // ---------------- 工具 ----------------

    private List<?> localHistory() throws Exception {
        Object list = historyGet.invoke(null);
        return list == null ? new ArrayList<>() : (List<?>) list;
    }

    private String vodNameOf(Object o) {
        try {
            Object n = histGetVodName.invoke(o);
            return n == null ? "" : n.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 将 History 对象安全转为 JSONObject
     */
    private JSONObject historyToJson(Object o) {
        if (o == null) return null;
        try {
            // 优先尝试 toString
            String str = o.toString();
            if (str != null && str.trim().startsWith("{")) {
                return new JSONObject(str);
            }
        } catch (Throwable ignored) {
        }
        // 降级使用反射字段提取
        try {
            JSONObject json = new JSONObject();
            Field[] fields = historyClass.getDeclaredFields();
            for (Field f : fields) {
                f.setAccessible(true);
                Object val = f.get(o);
                if (val != null) json.put(f.getName(), val);
            }
            return json;
        } catch (Throwable t) {
            Logger.log("WatchSync > historyToJson failed: " + t);
            return null;
        }
    }

    private Map<String, Long> parseTombstones(String raw) {
        Map<String, Long> tombs = new HashMap<>();
        long now = System.currentTimeMillis();
        try {
            if (raw != null && !raw.trim().isEmpty()) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item == null) continue;
                    if (!"tombstone".equals(item.optString("kind"))) continue;
                    if (!username.equals(item.optString("user"))) continue;
                    long t = item.optLong("time", 0);
                    if (now - t > TOMBSTONE_TTL_MS) continue;
                    tombs.put(item.optString("name", ""), t);
                }
            }
        } catch (Throwable t) {
            Logger.log("WatchSync > parseTombstones err: " + t);
        }
        return tombs;
    }

    private void applyRemoteTombstones(Map<String, Long> remoteTombs) {
        if (histDelete == null) return;
        for (Map.Entry<String, Long> e : remoteTombs.entrySet()) {
            String name = e.getKey();
            long tombTime = e.getValue();
            try {
                Object locals = historyFindByName.invoke(null, name);
                List<?> list = locals == null ? new ArrayList<>() : (List<?>) locals;
                // 复活判定：本机已重新观看了该记录（其 createTime 晚于墓碑删除时间）→ 撤销墓碑，不再删除
                if (!list.isEmpty() && hasRecordNewerThan(list, tombTime)) {
                    localTombs.remove(name);
                    Logger.log("WatchSync > 本机已重新观看，复活记录（跳过墓碑删除）: " + name);
                    continue;
                }
                // 正常删除：写入本地墓碑表 + 删本地同名记录
                localTombs.put(name, Math.max(localTombs.getOrDefault(name, 0L), tombTime));
                for (Object it : list) {
                    histDelete.invoke(it);
                    Logger.log("WatchSync > 远端墓碑，删除本地记录: " + name);
                }
            } catch (Throwable t) {
                Logger.log("WatchSync > applyRemoteTombstones err (" + name + "): " + t);
            }
        }
    }

    /** 本机记录列表里是否有观看时间晚于墓碑删除时间的记录（即删除后又看了 → 复活）。 */
    private boolean hasRecordNewerThan(List<?> list, long tombTime) {
        try {
            for (Object it : list) {
                long t = historyCreateTime(it);
                if (t > tombTime) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 读取 History 对象的观看创建时间（createTime）；空用户名/异常返回 -1。 */
    private long historyCreateTime(Object o) {
        try {
            JSONObject j = historyToJson(o);
            if (j != null && j.has("createTime")) {
                return j.optLong("createTime", 0);
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    // ---------------- 推送 ----------------

    private void push() {
        try {
            List<?> local = localHistory();
            Set<String> current = new HashSet<>();
            for (Object o : local) {
                String n = vodNameOf(o);
                if (!n.isEmpty()) current.add(n);
            }

            // 复活：本机当前拥有的记录（重新观看/保留），撤销其墓碑，让其正常全网同步
            for (String name : current) {
                if (localTombs.remove(name) != null) {
                    Logger.log("WatchSync > 本机重新观看，复活记录（撤销墓碑）: " + name);
                }
            }

            // 检测删除生成墓碑（只要是在 lastPushed 里存在过，但现在 local 没有了，就是被删除了）
            long now = System.currentTimeMillis();
            for (String name : lastPushed) {
                if (!current.contains(name) && !localTombs.containsKey(name)) {
                    localTombs.put(name, now);
                    Logger.log("WatchSync > 检测到本机删除，生成墓碑: " + name);
                }
            }
            
            lastPushed.clear();
            lastPushed.addAll(current);

            String raw = readRemote();
            String json = merge(local, current, raw);
            writeRemote(json);
            saveState();
        } catch (Throwable t) {
            Logger.log("WatchSync > push err: " + t);
        }
    }

    private String merge(List<?> local, Set<String> localNames, String raw) throws Exception {
        long now = System.currentTimeMillis();
        JSONArray merged = new JSONArray();
        Map<String, Long> tombMap = new LinkedHashMap<>();
        Map<String, Long> allTombs = new LinkedHashMap<>(localTombs);

        for (Map.Entry<String, Long> e : localTombs.entrySet()) {
            tombMap.put(username + "\u0000" + e.getKey(), Math.max(tombMap.getOrDefault(username + "\u0000" + e.getKey(), 0L), e.getValue()));
        }
        Set<String> seenHistory = new HashSet<>();

        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONArray remote = new JSONArray(raw);
                for (int i = 0; i < remote.length(); i++) {
                    JSONObject item = remote.optJSONObject(i);
                    if (item == null) continue;
                    String kind = item.optString("kind");
                    if ("tombstone".equals(kind)) {
                        String tu = item.optString("user");
                        String tn = item.optString("name", "");
                        long tt = item.optLong("time", 0);
                        // 复活：本用户墓碑对应的记录本机正拥有（重新观看）→ 丢弃该墓碑，使其作为 history 正常同步
                        if (tu.equals(username) && localNames.contains(tn)) {
                            continue;
                        }
                        if (username.equals(tu)) {
                            allTombs.put(tn, Math.max(allTombs.getOrDefault(tn, 0L), tt));
                        }
                        String tk = tu + "\u0000" + tn;
                        tombMap.put(tk, Math.max(tombMap.getOrDefault(tk, 0L), tt));
                        continue;
                    }
                    // history
                    String u = item.optString("user");
                    String n = nameFromHistory(item.optJSONObject("history"));
                    if (n.isEmpty()) continue;
                    if (allTombs.containsKey(n)) continue;
                    if (username.equals(u) && localNames.contains(n)) continue;

                    if (!seenHistory.contains(u + "\u0000" + n)) {
                        merged.put(item);
                        seenHistory.add(u + "\u0000" + n);
                    }
                }
            } catch (Throwable t) {
                Logger.log("WatchSync > merge: 远端解析失败，按空仓处理: " + t);
            }
        }

        // 本机历史
        for (Object o : local) {
            String n = vodNameOf(o);
            if (n.isEmpty() || allTombs.containsKey(n)) continue;
            JSONObject histJson = historyToJson(o);
            if (histJson == null) continue;

            JSONObject wrap = new JSONObject();
            wrap.put("kind", "history");
            wrap.put("user", username);
            wrap.put("history", histJson);
            if (!seenHistory.contains(username + "\u0000" + n)) {
                merged.put(wrap);
                seenHistory.add(username + "\u0000" + n);
            }
        }

        // 输出墓碑
        for (Map.Entry<String, Long> e : tombMap.entrySet()) {
            if (now - e.getValue() > TOMBSTONE_TTL_MS) continue;
            String tk = e.getKey();
            int sep = tk.indexOf("\u0000");
            if (sep < 0) continue; // 修复：允许空用户名
            JSONObject tb = new JSONObject();
            tb.put("kind", "tombstone");
            tb.put("user", tk.substring(0, sep));
            tb.put("name", tk.substring(sep + 1));
            tb.put("time", e.getValue());
            merged.put(tb);
        }

        return merged.toString();
    }

    private String nameFromHistory(JSONObject h) {
        if (h == null) return "";
        String n = h.optString("vodName", "");
        if (n.isEmpty()) n = h.optString("vod_name", "");
        return n;
    }

    // ---------------- 拉取 ----------------

    private void pull() {
        try {
            // 在拉取远端之前，主动比对一次本地删除状态，防止 fileObserver 没来得及 push 产生复活
            List<?> localBeforeSync = localHistory();
            Set<String> currentLocalNames = new HashSet<>();
            for (Object o : localBeforeSync) {
                String n = vodNameOf(o);
                if (!n.isEmpty()) currentLocalNames.add(n);
            }
            long now = System.currentTimeMillis();
            for (String name : lastPushed) {
                if (!currentLocalNames.contains(name) && !localTombs.containsKey(name)) {
                    localTombs.put(name, now);
                    Logger.log("WatchSync > pull前防复活对账，检测到本机删除，生成墓碑: " + name);
                }
            }

            String raw = readRemote();
            if (raw == null) {
                Logger.log("WatchSync > pull: 远端读取失败");
                return;
            }
            if (raw.trim().isEmpty()) {
                raw = "[]";
            }
            Map<String, Long> remoteTombs = parseTombstones(raw);
            if (!remoteTombs.isEmpty()) applyRemoteTombstones(remoteTombs);

            List<Object> mine = new ArrayList<>();
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject wrap = arr.optJSONObject(i);
                if (wrap == null) continue;
                if (!"history".equals(wrap.optString("kind"))) continue;
                if (!username.equals(wrap.optString("user"))) continue;
                JSONObject rec = wrap.optJSONObject("history");
                if (rec == null) continue;
                String n = nameFromHistory(rec);
                if (remoteTombs.containsKey(n)) continue;
                
                // 远端有这条记录，但本地刚刚把它删了（localTombs包含它），坚决不拉取，防止复活！
                if (localTombs.containsKey(n)) continue;

                // 本用户未被墓碑删除的 history 一律收进“本机已知全集”，
                // 保证任何本机见过的记录被删时都能命中墓碑判定（不受 canSafeMerge 过滤影响）
                if (!n.isEmpty()) lastPushed.add(n);
                if (!canSafeMerge(rec)) continue;
                Object obj = historyObjectFrom.invoke(null, rec.toString());
                if (obj != null) mine.add(obj);
            }

            Logger.log("WatchSync > pull 完成，远端总数=" + arr.length() + " 属于本用户=" + mine.size());
            if (!mine.isEmpty()) {
                historySync.invoke(null, mine);
                lastPushed.addAll(lastPushedOf(mine));
            }
            reconcile(raw);
        } catch (Throwable t) {
            Logger.log("WatchSync > pull err: " + t);
        }
    }

    private Set<String> lastPushedOf(List<Object> mine) {
        Set<String> s = new HashSet<>();
        for (Object o : mine) {
            String n = vodNameOf(o);
            if (!n.isEmpty()) s.add(n);
        }
        return s;
    }

    private void reconcile(String raw) {
        try {
            List<?> local = localHistory();
            Set<String> names = new HashSet<>();
            for (Object o : local) {
                String n = vodNameOf(o);
                if (!n.isEmpty()) names.add(n);
            }
            // 定时对账兜底墓碑判定：本机已知全集里、当前已无、且无墓碑的记录 → 补生成墓碑，
            // 确保 FileObserver 漏触发时也能在 30s 周期内把删除传播成墓碑，杜绝复活。
            long now = System.currentTimeMillis();
            if (!lastPushed.isEmpty()) {
                for (String name : lastPushed) {
                    if (!names.contains(name) && !localTombs.containsKey(name)) {
                        localTombs.put(name, now);
                        Logger.log("WatchSync > 对账检测到本机删除，补生成墓碑: " + name);
                    }
                }
            }
            String merged = merge(local, names, raw);
            // 语义比较：只有内容真变了才写，避免 JSON 顺序/空列表导致的无意义重写
            if (!merged.equals(raw)) {
                writeRemote(merged);
                saveState();
            }
        } catch (Throwable t) {
            Logger.log("WatchSync > 对账 err: " + t);
        }
    }

    private boolean hasProgress(Object hist) {
        try {
            if (histCanSave != null) return (Boolean) histCanSave.invoke(hist);
            if (histGetPosition != null && histGetDuration != null) {
                long pos = ((Number) histGetPosition.invoke(hist)).longValue();
                long dur = ((Number) histGetDuration.invoke(hist)).longValue();
                return pos >= 0 && dur > 0;
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean canSafeMerge(JSONObject rec) {
        try {
            Object hist = historyObjectFrom.invoke(null, rec.toString());
            if (hist == null) return true;
            boolean remoteCanSave = hasProgress(hist);
            if (remoteCanSave) return true;
            String vodName = (String) histGetVodName.invoke(hist);
            Object locals = historyFindByName.invoke(null, vodName);
            if (locals == null) return true;
            for (Object it : (List<?>) locals) {
                if (hasProgress(it)) {
                    Logger.log("WatchSync > 进度保护：无进度记录不覆盖本地有进度记录 name=" + vodName);
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------------- 服务器文件读写 ----------------

    private String readRemote() {
        try {
            String out = drive.exec("cat \"" + syncPath + "\"");
            if (out == null) {
                Logger.log("WatchSync > readRemote: 远端返回 null");
                return "";
            }
            return out.trim();
        } catch (Throwable t) {
            Logger.log("WatchSync > readRemote err: " + t);
            return null;
        }
    }

    private void writeRemote(String json) {
        try {
            String b64 = android.util.Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            String cmd = "printf '%s' '" + b64 + "' | base64 -d > \"" + syncPath + ".tmp\" && mv \"" + syncPath + ".tmp\" \"" + syncPath + "\"";
            String res = drive.exec(cmd);
            Logger.log("WatchSync > writeRemote: 已写入 " + syncPath + "（json长度=" + json.length() + "，exec返回=[" + res + "]）");
        } catch (Throwable t) {
            Logger.log("WatchSync > write err: " + t);
        }
    }
}
