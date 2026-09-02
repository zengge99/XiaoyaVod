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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 观看记录多端同步（方向 A：复用 alist 服务器文件作为共享记录仓）。
 *
 * <p><b>同步模型（简化版）</b></p>
 * <ul>
 *   <li>每个用户一个远端文件（如 watch.&lt;username&gt;.txt），用户之间物理隔离。</li>
 *   <li>远端文件是 JSON 数组，元素为一组观看记录：{@code {"history":{...}}}（History 对象的完整 JSON）。</li>
 *   <li><b>本版本只做「记录合并同步」，不做删除传播</b>（已移除墓碑与删除检测逻辑——此前该部分问题较多）。
 *       因此：本机单独删除一条记录只影响本机，不会被同步；push/pull 会以远端+本机合并结果为准把记录保留下来。</li>
 *   <li>本机全集通过 {@code AppDatabase.get().getHistoryDao().findAll()} 反射获取并过滤当前播放源(cid)，
 *       避免 {@code History.get()} 的 LIMIT 60 截断导致把“自然淘汰”误判成删除。</li>
 * </ul>
 *
 * <p><b>合并规则</b></p>
 * <ol>
 *   <li><b>同名记录取新</b>：同一片名存在多个版本（createTime 不同）时，合并按 createTime 较新者胜出，
 *       避免旧记录把新记录（新剧集/新进度）打回。</li>
 *   <li><b>进度保护</b>：拉取时远端“无进度”记录不覆盖本机“有进度”同名记录，避免进度倒退。</li>
 *   <li><b>只在内容真正变化时才写远端</b>，减少 30 秒周期拉取与 FileObserver 之间的写放大抖动。</li>
 * </ol>
 *
 * <p><i>说明：因删除同步被移除，若远端某条记录被任一设备保留，则其它设备即便本地删除，也会在下次 pull 时被重新拉回。</i></p>
 */
public class WatchSync {

    /** 目标主机应用里的 History bean 的默认类名（作为最后兜底候选）。 */
    private static final String HISTORY_CLS = "com.fongmi.android.tv.bean.History";

    /** 本机数据库变动后的推送防抖窗口（毫秒）。 */
    private static final long PUSH_DEBOUNCE_MS = 5000;
    /** 定期拉取远端文件的周期（秒）。 */
    private static final long PULL_PERIOD_SEC = 30;

    private final Context context;
    private final Drive drive;
    private final String username;
    /** 远端同步文件路径（已按用户名隔离）。 */
    private final String syncPath;

    /** 单线程调度器：push/pull/定时对账都在同一线程串行执行，避免并发竞争。 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /** 最近一次触发推送的事件时间，用于防抖。 */
    private final AtomicLong lastPushEvent = new AtomicLong(0);
    /** 防抖积压标志：防抖窗口内再次变更时，延迟补一次兜底推送，避免高频操作吞掉事件。 */
    private final AtomicBoolean pushPending = new AtomicBoolean(false);

    /** 监视数据库目录的线程。 */
    private HandlerThread watchThread;
    /** 主数据库文件观察器（改库就触发 push）。 */
    private FileObserver observerMain;

    // ---------------- 反射缓存 ----------------
    // 由于 WatchSync 是 spider 插件，运行在宿主播放器内，History 等类是宿主应用的，
    // 因此一律通过反射调用，避免直接编译期依赖。
    private Class<?> historyClass;          // com.fongmi.android.tv.bean.History
    private Method historyGet;              // History.get() -> 最近 60 条（仅作兜底）
    private Method historyFindByName;       // History.findByName(String) -> List
    private Method historyObjectFrom;       // History.objectFrom(String) -> History
    private Method historySync;             // History.sync(List) -> void
    private Method histGetVodName;          // History.getVodName()
    private Method histCanSave;             // History.canSave()（可为 null）
    private Method histGetPosition;         // History.getPosition()（可为 null）
    private Method histGetDuration;         // History.getDuration()（可为 null）

    private Class<?> appDbClass;            // AppDatabase
    private Method dbGet;                   // AppDatabase.get()
    private Method dbGetHistoryDao;         // AppDatabase.get().getHistoryDao()
    private Method daoFindAll;              // HistoryDao.findAll() -> 本机全量
    private Method vodGetCid;               // VodConfig.getCid() -> 当前播放源 id（可为 null）

    private WatchSync(Context context, Drive drive, String username, String syncPath) {
        this.context = context;
        this.drive = drive;
        this.username = username == null ? "" : username;
        // 每用户一个远端文件：避免多用户共享单文件互相干扰（如 watch.txt -> watch.<username>.txt）
        this.syncPath = isolatedPath(syncPath, this.username);
    }

    /** 把远端同步文件按用户名隔离：watch.txt -> watch.&lt;username&gt;.txt（已隔离则不重复注入）。 */
    private static String isolatedPath(String syncPath, String username) {
        if (syncPath == null || syncPath.isEmpty() || username == null || username.isEmpty()) {
            return syncPath;
        }
        // 已隔离过（形如 xxx.<user>.yyy）则不再重复
        if (syncPath.matches(".*\\." + java.util.regex.Pattern.quote(username) + "\\.[A-Za-z0-9]+$")) {
            return syncPath;
        }
        int slash = syncPath.lastIndexOf('/');
        String dir = slash >= 0 ? syncPath.substring(0, slash + 1) : "";
        String name = slash >= 0 ? syncPath.substring(slash + 1) : syncPath;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String base = name.substring(0, dot);
            String ext = name.substring(dot);
            return dir + base + "." + username + ext;
        }
        return dir + name + "." + username;
    }

    /**
     * 启动同步。满足条件（drive 非空、syncWatch 开启、syncPath 非空）才初始化：
     * 解析宿主反射 → 启动数据库监视 → 定时拉取 → 立即拉一次。
     *
     * @return 启动成功的实例；条件不满足或初始化失败返回 null（静默降级，不影响播放器）。
     */
    public static WatchSync start(Context context, Drive drive) {
        try {
            if (drive == null) {
                Logger.log("WatchSync > 未启用：defaultDrive 为空");
                return null;
            }
            Logger.log("WatchSync > defaultDrive=" + drive.getName() + " syncWatch=" + drive.syncWatch()
                    + " username=[" + drive.getUsername() + "] syncPath=[" + drive.getSyncPath() + "]");
            if (!drive.syncWatch() || drive.getSyncPath().isEmpty()) {
                Logger.log("WatchSync > 未启用：syncWatch=false 或 syncPath 为空");
                return null;
            }
            WatchSync ws = new WatchSync(context, drive, drive.getUsername(), drive.getSyncPath());
            ws.initReflection();
            ws.startWatching();
            ws.schedule();
            // 启动先拉一次（PULL 触发来源之一：启动立即拉取），让远端已有记录尽快并入本机
            Logger.log("WatchSync > [触发] 启动立即 PULL");
            ws.scheduler.execute(() -> ws.pull("启动立即"));
            Logger.log("WatchSync > 启动完成");
            return ws;
        } catch (Throwable t) {
            Logger.log("WatchSync > start failed: " + t);
            return null;
        }
    }

    /** 初始化全部反射缓存。失败的方法置空或抛出，由上层兜底。 */
    private void initReflection() throws Exception {
        // 必须先解析 historyClass，再据此推导应用包名：appPackage() 内部会调用 historyClass.getName()
        historyClass = resolveHistoryClass();
        String appPkg = appPackage();
        Logger.log("WatchSync > 解析到 History 类: " + historyClass.getName() + " 应用包: " + appPkg);

        // History 基础方法
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

        // AppDatabase.get().getHistoryDao().findAll()：取本机全量历史（避开 get() 的 LIMIT 60 截断）。
        // 反射失败不影响主流程，localHistoryFull() 会自动退化为 get()。
        try {
            appDbClass = Class.forName(appPkg + ".db.AppDatabase");
            dbGet = appDbClass.getMethod("get");
            dbGetHistoryDao = appDbClass.getMethod("getHistoryDao");
            Object db = dbGet.invoke(null);
            if (db != null) {
                Object dao = dbGetHistoryDao.invoke(db);
                if (dao != null) daoFindAll = dao.getClass().getMethod("findAll");
            }
            Logger.log("WatchSync > AppDatabase.findAll 反射就绪: " + (daoFindAll != null));
        } catch (Throwable t) {
            Logger.log("WatchSync > AppDatabase 反射失败，将退化为 get() 兜底: " + t);
        }

        // VodConfig.getCid()：当前播放源 id，用于把多源 findAll 结果过滤到当前源
        try {
            Class<?> vod = Class.forName(appPkg + ".api.config.VodConfig");
            vodGetCid = vod.getMethod("getCid");
        } catch (Throwable t) {
            vodGetCid = null;
        }
    }

    /** 由已解析 History 类反推宿主应用包名（截掉 ".bean.History" 后缀）。 */
    private String appPackage() {
        String n = historyClass.getName();
        if (n.endsWith(".bean.History")) {
            return n.substring(0, n.length() - ".bean.History".length());
        }
        String pkg = context.getPackageName();
        return pkg == null ? "" : pkg;
    }

    /** 解析宿主内的 History 类：先按应用类名前缀、再按包名、最后默认候选。 */
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

    // ---------------- 本机数据库监听 ----------------

    /** 启动对本地 Room 数据库目录的监视：主库文件（tv）或 WAL（tv-wal）变动时触发推送。 */
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
                            Logger.log("WatchSync > FileObserver事件 event=" + event + " path=" + path);
                            // 只关心主库与 WAL 文件，避免无关文件触发（这是 PUSH 的唯一触发来源）
                            if (path != null && (path.equals("tv") || path.equals("tv-wal"))) {
                                onDbChanged(path, event);
                            } else {
                                Logger.log("WatchSync > FileObserver事件忽略(非tv/tv-wal文件): " + path);
                            }
                        }
                    };
                    observerMain.startWatching();
                    Logger.log("WatchSync > FileObserver 已启动并开始监听 [tv/tv-wal] → 本地库变更将触发 PUSH");
                } catch (Throwable t) {
                    Logger.log("WatchSync > observer err: " + t);
                }
            });
        } catch (Throwable t) {
            Logger.log("WatchSync > watch err: " + t);
        }
    }

    /**
     * 数据库变动回调（PUSH 的唯一触发来源，由 FileObserver 驱动）。
     * 带防抖：5 秒窗口内再来事件则补一次延迟兜底推送，避免高频操作丢事件。
     *
     * @param path  触发事件的文件名（tv / tv-wal）
     * @param event FileObserver 事件掩码
     */
    private void onDbChanged(String path, int event) {
        long now = System.currentTimeMillis();
        long last = lastPushEvent.get();
        Logger.log("WatchSync > [触发] onDbChanged: 文件=" + path + " event=" + event
                + " 距上次推送=" + (now - last) + "ms (窗口=" + PUSH_DEBOUNCE_MS + "ms)");
        if (now - last < PUSH_DEBOUNCE_MS) {
            // 5 秒内频繁变动：不要直接丢弃，而是延迟调度一次兜底推送
            if (pushPending.compareAndSet(false, true)) {
                Logger.log("WatchSync > [触发] 防抖窗口内再变更 → 延迟" + PUSH_DEBOUNCE_MS + "ms 兜底推送");
                scheduler.schedule(() -> {
                    pushPending.set(false);
                    lastPushEvent.set(System.currentTimeMillis());
                    push("FileObserver防抖兜底(延迟" + PUSH_DEBOUNCE_MS + "ms)");
                }, PUSH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            }
            return;
        }
        lastPushEvent.set(now);
        Logger.log("WatchSync > [触发] 立即推送");
        scheduler.execute(() -> push("FileObserver事件(" + path + ",event=" + event + ")"));
    }

    /**
     * 注册周期拉取（PULL 触发来源之一）：每 30 秒从远端拉一次。
     * 另一个 PULL 来源是 {@link #start(Context, Drive)} 里的启动立即拉取。
     */
    private void schedule() {
        Logger.log("WatchSync > 已注册周期 PULL：每 " + PULL_PERIOD_SEC + " 秒从远端拉取一次");
        scheduler.scheduleWithFixedDelay(() -> pull("定时(" + PULL_PERIOD_SEC + "s)"), PULL_PERIOD_SEC, PULL_PERIOD_SEC, TimeUnit.SECONDS);
    }

    // ---------------- 工具 ----------------

    /** 退化路径：History.get()（受 LIMIT 60 截断，仅当 findAll 反射不可用时使用）。 */
    private List<Object> localHistoryFallback() {
        List<Object> out = new ArrayList<>();
        try {
            Object list = historyGet.invoke(null);
            if (list != null) out.addAll((List<?>) list);
        } catch (Throwable t) {
            Logger.log("WatchSync > localHistoryFallback err: " + t);
        }
        return out;
    }

    /**
     * 本机当前播放源(cid)的<b>全量</b>历史对象。
     * 优先走 AppDatabase.findAll() 反射并过滤当前 cid —— 这才是“本机全集”，
     * 不会像 get() 那样被 LIMIT 60 截断。
     */
    private List<Object> localHistoryFull() {
        int cid = currentCid();
        if (daoFindAll != null) {
            try {
                Object db = dbGet.invoke(null);
                if (db != null) {
                    Object dao = dbGetHistoryDao.invoke(db);
                    if (dao != null) {
                        Object all = daoFindAll.invoke(dao);
                        if (all instanceof List) {
                            List<Object> out = new ArrayList<>();
                            for (Object o : (List<?>) all) {
                                if (o == null) continue;
                                // 过滤到当前播放源，避免把其他源的记录误当作本源记录
                                if (cid >= 0) {
                                    try {
                                        JSONObject j = historyToJson(o);
                                        if (j == null || j.optInt("cid", -1) != cid) continue;
                                    } catch (Throwable t) {
                                        continue;
                                    }
                                }
                                out.add(o);
                            }
                            return out;
                        }
                    }
                }
            } catch (Throwable t) {
                Logger.log("WatchSync > findAll 失败，退化 get() 兜底: " + t);
            }
        }
        return localHistoryFallback();
    }

    /** 当前播放源 id；反射不可用返回 -1（表示不过滤）。 */
    private int currentCid() {
        if (vodGetCid == null) return -1;
        try {
            Object v = vodGetCid.invoke(null);
            return v instanceof Number ? ((Number) v).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 读取 History 对象的片名，失败返回空串。 */
    private String vodNameOf(Object o) {
        try {
            Object n = histGetVodName.invoke(o);
            return n == null ? "" : n.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 将 History 对象安全转为 JSONObject。
     * 优先用其 toString()（宿主用 Gson 输出完整字段），失败再降级为反射逐字段提取。
     */
    private JSONObject historyToJson(Object o) {
        if (o == null) return null;
        try {
            String str = o.toString();
            if (str != null && str.trim().startsWith("{")) {
                return new JSONObject(str);
            }
        } catch (Throwable ignored) {
        }
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

    // ---------------- 推送 ----------------

    /**
     * 把本机当前记录合并进远端文件（PUSH —— 仅由 FileObserver 本地库变更触发）。
     * 因删除同步已移除，这里只做「记录并集」（远端 + 本机，同名取 createTime 较新），不再生成任何墓碑。
     *
     * @param source 触发来源描述（用于日志追溯是被哪条链路触发的）
     */
    private void push(String source) {
        long t0 = System.currentTimeMillis();
        Logger.log("WatchSync >>> [PUSH 触发] source=" + source);
        try {
            List<?> local = localHistoryFull();   // 本机全量（findAll，非 60 条）
            String raw = readRemote();
            String json = merge(local, raw);
            // 内容未变化则不重写远端，避免 30s 周期与 FileObserver 互相放大的写抖动
            if (json.equals(raw)) {
                Logger.log("WatchSync <<< [PUSH 结束] source=" + source + " 内容未变化→未写远端 本地条数=" + local.size() + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
                return;
            }
            writeRemote(json);
            Logger.log("WatchSync <<< [PUSH 结束] source=" + source + " 已写远端 json长度=" + json.length() + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            Logger.log("WatchSync <<< [PUSH 异常] source=" + source + " err=" + t);
        }
    }

    /**
     * 合并本机与远端，生成待写入远端的 JSON 数组。
     * <ul>
     *   <li>取两端记录的<b>并集</b>（远端 + 本机）。</li>
     *   <li>同名记录按 createTime 取较新者：远端与本地谁新谁赢，杜绝旧记录把新记录打回。</li>
     * </ul>
     */
    private String merge(List<?> local, String raw) throws Exception {
        // 片名 -> 最佳 wrap；同片名时用 createTime 决定谁胜出
        Map<String, JSONObject> chosen = new LinkedHashMap<>();
        Map<String, Long> chosenTime = new HashMap<>();

        // 远端 history
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONArray remote = new JSONArray(raw);
                for (int i = 0; i < remote.length(); i++) {
                    JSONObject item = remote.optJSONObject(i);
                    if (item == null) continue;
                    JSONObject rec = item.optJSONObject("history");
                    if (rec == null) continue;
                    String n = nameFromHistory(rec);
                    if (n.isEmpty()) continue;
                    long t = rec.optLong("createTime", 0L);
                    if (!chosen.containsKey(n) || t > chosenTime.get(n)) {
                        chosen.put(n, item);
                        chosenTime.put(n, t);
                    }
                }
            } catch (Throwable t) {
                Logger.log("WatchSync > merge: 远端解析失败，按空处理: " + t);
            }
        }

        // 本机 history（同名时仅当 createTime 更晚才替换远端那条）
        for (Object o : local) {
            String n = vodNameOf(o);
            if (n.isEmpty()) continue;
            JSONObject histJson = historyToJson(o);
            if (histJson == null) continue;
            long t = histJson.optLong("createTime", 0L);
            if (!chosen.containsKey(n) || t > chosenTime.get(n)) {
                JSONObject wrap = new JSONObject();
                wrap.put("history", histJson);
                chosen.put(n, wrap);
                chosenTime.put(n, t);
            }
        }

        JSONArray merged = new JSONArray();
        for (JSONObject w : chosen.values()) merged.put(w);
        return merged.toString();
    }

    /** 从一条 history 记录里取片名（兼容 vodName / vod_name 两种字段名）。 */
    private String nameFromHistory(JSONObject h) {
        if (h == null) return "";
        String n = h.optString("vodName", "");
        if (n.isEmpty()) n = h.optString("vod_name", "");
        return n;
    }

    // ---------------- 拉取 ----------------

    /**
     * 周期拉取远端记录并应用到本机（PULL —— 触发来源：启动立即拉取 + 每 30 秒定时拉取）。
     * 读远端 → 进度保护过滤 → 用 History.sync() 并入本机 → 收敛对账（把本机新增记录也并回远端）。
     * 不再解析/应用任何墓碑。
     *
     * @param source 触发来源描述（"启动立即" / "定时(30s)"），用于日志追溯
     */
    private void pull(String source) {
        long t0 = System.currentTimeMillis();
        Logger.log("WatchSync >>> [PULL 触发] source=" + source);
        try {
            String raw = readRemote();
            if (raw == null) {
                Logger.log("WatchSync <<< [PULL 结束] source=" + source + " 远端读取失败，跳过");
                return;
            }
            if (raw.trim().isEmpty()) {
                raw = "[]";
            }

            List<Object> mine = new ArrayList<>();
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject wrap = arr.optJSONObject(i);
                if (wrap == null) continue;
                JSONObject rec = wrap.optJSONObject("history");
                if (rec == null) continue;
                String n = nameFromHistory(rec);
                if (n.isEmpty()) continue;
                if (!canSafeMerge(rec)) continue;           // 进度保护：无进度记录不覆盖本地有进度记录
                Object obj = historyObjectFrom.invoke(null, rec.toString());
                if (obj != null) mine.add(obj);
            }

            Logger.log("WatchSync <<< [PULL 结束] source=" + source + " 远端总数=" + arr.length() + " 待入库=" + mine.size() + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
            if (!mine.isEmpty()) {
                historySync.invoke(null, mine);
            }
            reconcile(raw);
        } catch (Throwable t) {
            Logger.log("WatchSync <<< [PULL 异常] source=" + source + " err=" + t);
        }
    }

    /**
     * 收敛对账：把本机记录也并回远端（保证本机新增/更新能及时同步到其他设备），
     * 仅在远端内容真正变化时写回。
     */
    private void reconcile(String raw) {
        try {
            List<?> local = localHistoryFull();
            String merged = merge(local, raw);
            // 语义比较：只有内容真变了才写，避免 JSON 顺序/空列表导致的无意义重写
            if (!merged.equals(raw)) {
                writeRemote(merged);
            }
        } catch (Throwable t) {
            Logger.log("WatchSync > 对账 err: " + t);
        }
    }

    /** 判断远端记录是否“有进度可保存”，用于进度保护。 */
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

    /**
     * 拉取前的安全合并判断：
     * 远端记录无进度（如仅点开未播放）时，不允许覆盖本机“已有进度”的同名记录，避免进度倒退。
     */
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

    /** 读取远端同步文件全文；读取失败返回 null（调用方自行区分空文件与失败）。 */
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

    /**
     * 把 JSON 内容经 base64 编码后写到远端文件（先写 .tmp 再 mv，保证原子性，避免半截文件）。
     * 用 base64 是为了规避 shell 对引号、空格、中文等特殊字符的转义问题。
     */
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