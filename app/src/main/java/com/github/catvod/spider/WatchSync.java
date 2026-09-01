package com.github.catvod.spider;

import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;

import com.github.catvod.bean.alist.Drive;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 观看记录多端同步（方向 A：复用 alist 服务器文件作为共享记录仓）。
 *
 * - 只反射访问蜂蜜影视(com.fongmi.android.tv)中被 R8 keep 的 bean.History 类，
 *   编译期无依赖，失败静默降级（不影响播放）。
 * - 单文件 watch.txt 存放所有用户的记录，用 user 字段区分；每条带 kind：
 *     history   = 正常观看记录
 *     tombstone = 删除墓碑（user + name + 删除时间），用于全网传播删除
 * - 触发：
 *     1) FileObserver 监视 tv / tv-wal 数据库文件变化 -> 事件驱动推送(5s 防抖)
 *     2) 每 30s 定时拉取合并
 *     3) start() 后立即 pull 一次
 * - 删除语义（tombstone）：
 *     本机维护 lastPushed(上次推送的名称集合)。push 时若某名称上次推送过、当前本地没有了，
 *     判定为"我删了它" -> 生成墓碑。远端 history 只有"存在墓碑"才删除，绝不因"本地暂无"而删，
 *     从而避免乙设备 push 误删甲设备刚新增的记录。
 * - 删除赢：任一设备删除某片 -> 墓碑传播 -> 全网删除该片。
 * - 墓碑保留 60 天，过期在 merge 时自动清理。
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

    private HandlerThread watchThread;
    private FileObserver observerMain;

    // 反射缓存（全部来自被 keep 的 bean.History）
    private Method historyGet;        // History.get() -> List（当前源记录）
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
    // 本机上次推送过的名称集合（判定删除用）
    private final Set<String> lastPushed = new HashSet<>();

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
            ws.loadState();
            ws.initReflection();
            ws.startWatching();
            ws.schedule();
            ws.pull();       // 启动兜底：读一次远端 + 合并本地 + 对账
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
            com.github.catvod.utils.Path.write(stateFile(), o.toString().getBytes("UTF-8"));
            Logger.log("WatchSync > 保存本机状态: lastPushed=" + lastPushed.size() + " tombs=" + localTombs.size());
        } catch (Throwable t) {
            Logger.log("WatchSync > saveState err: " + t);
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
        try {
            histCanSave = hist.getMethod("canSave");
        } catch (Throwable t) {
            histCanSave = null; // canSave 缺失：改用 getPosition/getDuration 自实现进度判断，不阻断启动
            Logger.log("WatchSync > WARN: 未找到 History.canSave(), 改用 getPosition/getDuration: " + t);
        }
        try {
            histGetPosition = hist.getMethod("getPosition");
            histGetDuration = hist.getMethod("getDuration");
        } catch (Throwable t) {
            histGetPosition = null;
            histGetDuration = null;
            Logger.log("WatchSync > WARN: 亦未找到 getPosition/getDuration, 进度保护不可用: " + t);
        }
        try {
            histDelete = hist.getMethod("delete");
        } catch (Throwable t) {
            histDelete = null;
            Logger.log("WatchSync > WARN: 未找到 History.delete(): " + t);
        }
        Logger.log("WatchSync > 反射初始化完成: get/sync/findByName/delete 均可调用");
    }

    /**
     * 解析蜂蜜影视宿主真实的 History 类（换皮可能改包名）。
     */
    private Class<?> resolveHistoryClass() throws Exception {
        List<String> candidates = new ArrayList<>();
        String suffix = "bean.History";
        try {
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
        candidates.add(HISTORY_CLS);
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
            String dir = context.getDatabasePath("tv").getParent();
            Logger.log("WatchSync > 开始监视数据库目录: " + dir);
            watchThread = new HandlerThread("watch-sync");
            watchThread.start();
            new Handler(watchThread.getLooper()).post(() -> {
                try {
                    observerMain = new FileObserver(dir, FileObserver.MODIFY | FileObserver.CLOSE_WRITE | FileObserver.CREATE) {
                        @Override public void onEvent(int event, String path) {
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

    // ---------------- 工具 ----------------

    private List<?> localHistory() throws Exception {
        Object list = historyGet.invoke(null);
        List<?> r = list == null ? new ArrayList<>() : (List<?>) list;
        return r;
    }

    private String vodNameOf(Object o) {
        try {
            Object n = histGetVodName.invoke(o);
            return n == null ? "" : n.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 解析远端 raw 中属于本用户的墓碑集合 name->time（含过期清理）。 */
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
                    if (now - t > TOMBSTONE_TTL_MS) continue; // 过期墓碑忽略
                    tombs.put(item.optString("name", ""), t);
                }
            }
        } catch (Throwable t) {
            Logger.log("WatchSync > parseTombstones err: " + t);
        }
        return tombs;
    }

    /** 远端墓碑导致的删除：删本地同名记录，并同步到本机墓碑。 */
    private void applyRemoteTombstones(Map<String, Long> remoteTombs) {
        if (histDelete == null) return;
        for (Map.Entry<String, Long> e : remoteTombs.entrySet()) {
            String name = e.getKey();
            localTombs.put(name, Math.max(localTombs.getOrDefault(name, 0L), e.getValue()));
            try {
                Object locals = historyFindByName.invoke(null, name);
                if (locals == null) continue;
                for (Object it : (List<?>) locals) {
                    histDelete.invoke(it);
                    Logger.log("WatchSync > 远端墓碑，删除本地记录: " + name);
                }
            } catch (Throwable t) {
                Logger.log("WatchSync > applyRemoteTombstones err (" + name + "): " + t);
            }
        }
    }

    // ---------------- 推送 ----------------

    /** 事件驱动：先判定本机删除生成墓碑，再 merge-write 远端。 */
    private void push() {
        try {
            List<?> local = localHistory();
            Set<String> current = new HashSet<>();
            for (Object o : local) current.add(vodNameOf(o));

            // 判定删除：本机已知(曾见过)、当前本地没有、且无墓碑 -> 生成本机墓碑
            // lastPushed 作为“本机已知全集”（只增不减），确保任何本机曾拥有的记录被删时都能命中生成墓碑
            long now = System.currentTimeMillis();
            for (String name : lastPushed) {
                if (!current.contains(name) && !localTombs.containsKey(name)) {
                    localTombs.put(name, now);
                    Logger.log("WatchSync > 检测到本机删除，生成墓碑: " + name);
                }
            }
            // 只增不减累积已知集合，不清空（否则会漏掉“曾有过但现在没有”的名字，导致复活）
            lastPushed.addAll(current);

            String raw = readRemote();
            String json = merge(local, current, raw);
            writeRemote(json);
            saveState();
            Logger.log("WatchSync > push 完成（merge-write），本机=" + local.size() + " 条，json长度=" + json.length());
        } catch (Throwable t) {
            Logger.log("WatchSync > push err: " + t);
        }
    }

    /**
     * merge-write：保留远端他人 + 未删除的历史；只有墓碑才删除历史；追加本机记录与墓碑。
     * 远端 history 绝不因"本机暂无"而删除（防乙设备 push 误删甲新记录）。
     */
    private String merge(List<?> local, Set<String> localNames, String raw) throws Exception {
        long now = System.currentTimeMillis();
        JSONArray merged = new JSONArray();
        // 墓碑：key = user + "\u0000" + name，value = time(取最新)，只输出每个墓碑最新一条，防无限复制
        Map<String, Long> tombMap = new LinkedHashMap<>();
        // 删除判断用：name -> time（仅本机用户相关）
        Map<String, Long> allTombs = new LinkedHashMap<>();
        allTombs.putAll(localTombs);
        for (Map.Entry<String, Long> e : localTombs.entrySet())
            tombMap.put(username + "\u0000" + e.getKey(), Math.max(tombMap.getOrDefault(username + "\u0000" + e.getKey(), 0L), e.getValue()));
        Set<String> seenHistory = new HashSet<>();

        // 先收远端：历史保留除非被墓碑删除；墓碑收进 tombMap 去重（含过期清理）
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
                        if (username.equals(tu)) {
                            allTombs.put(tn, Math.max(allTombs.getOrDefault(tn, 0L), tt));
                        }
                        String tk = tu + "\u0000" + tn;
                        tombMap.put(tk, Math.max(tombMap.getOrDefault(tk, 0L), tt));
                        continue; // 不直接原样输出，统一在末尾去重输出
                    }
                    // history
                    String u = item.optString("user");
                    String n = nameFromHistory(item.optJSONObject("history"));
                    if (n.isEmpty()) continue;
                    if (allTombs.containsKey(n)) continue; // 已被墓碑删除
                    if (username.equals(u)) {
                        // 本机用户历史：本机当前已有则用本机版本(下方 local 追加)，跳过远端防重复；
                        // 本机还没有(如甲设备刚加、本机未拉到)则保留远端，防止乙设备 push 误删甲新记录
                        if (localNames.contains(n)) continue;
                    }
                    // 其他用户 / 本机尚未拥有的历史：原样保留
                    if (!seenHistory.contains(u + "\u0000" + n)) {
                        merged.put(item);
                        seenHistory.add(u + "\u0000" + n);
                    }
                }
            } catch (Throwable t) {
                Logger.log("WatchSync > merge: 远端解析失败，按空仓处理: " + t);
            }
        }

        // 本机历史（未被墓碑删除的）
        for (Object o : local) {
            String n = vodNameOf(o);
            if (n.isEmpty() || allTombs.containsKey(n)) continue;
            JSONObject wrap = new JSONObject();
            wrap.put("kind", "history");
            wrap.put("user", username);
            wrap.put("history", new JSONObject(o.toString()));
            if (!seenHistory.contains(username + "\u0000" + n)) {
                merged.put(wrap);
                seenHistory.add(username + "\u0000" + n);
            }
        }

        // 输出墓碑：每个 (user,name) 只保留最新一条（含过期清理）
        for (Map.Entry<String, Long> e : tombMap.entrySet()) {
            if (now - e.getValue() > TOMBSTONE_TTL_MS) continue;
            String tk = e.getKey();
            int sep = tk.indexOf("\u0000");
            if (sep <= 0) continue;
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
        try {
            return h == null ? "" : h.optString("vodName", h.optString("vod_name", ""));
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------- 拉取 ----------------

    private void pull() {
        try {
            String raw = readRemote();
            if (raw == null) { Logger.log("WatchSync > pull: 远端读取失败"); return; } // 仅读取失败才放弃
            if (raw.trim().isEmpty()) { // 远端为空：按空仓处理，继续走 reconcile 首推本机记录
                raw = "[]";
                Logger.log("WatchSync > pull: 远端为空，按空仓处理，将首推本机记录");
            }
            Map<String, Long> remoteTombs = parseTombstones(raw);
            if (!remoteTombs.isEmpty()) applyRemoteTombstones(remoteTombs);

            // 收集本用户未被墓碑删除的历史，历史里已删除的不拉回
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
                if (remoteTombs.containsKey(n)) continue; // 墓碑：不拉回
                if (!canSafeMerge(rec)) continue;
                mine.add(historyObjectFrom.invoke(null, rec.toString()));
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
        for (Object o : mine) s.add(vodNameOf(o));
        return s;
    }

    /** 对账：以本机当前状态生成预期内容 vs 远端，不一致才写（并触发删除墓碑传播）。 */
    private void reconcile(String raw) {
        try {
            List<?> local = localHistory();
            Set<String> names = new HashSet<>();
            for (Object o : local) names.add(vodNameOf(o));
            String merged = merge(local, names, raw);
            if (merged.equals(raw)) {
                Logger.log("WatchSync > 对账：服务器与本地一致，无需 push");
            } else {
                Logger.log("WatchSync > 对账：服务器与本地不一致，补一次 merge-write push");
                writeRemote(merged);
                saveState();
            }
        } catch (Throwable t) {
            Logger.log("WatchSync > 对账 err: " + t);
        }
    }

    /** 等价 History.canSave(): position>=0 && duration>0。优先用 canSave()，缺失时用 getPosition/getDuration 自算；取不到则放行(宽松)。 */
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

    // ---------------- 服务器文件读写（走 exec，base64 避免转义） ----------------

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