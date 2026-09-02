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
import java.util.Iterator;
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
 *
 * <p><b>同步模型</b></p>
 * <ul>
 *   <li>每个用户一个远端文件（如 watch.&lt;username&gt;.txt），用户之间物理隔离。</li>
 *   <li>远端文件是 JSON 数组，元素分两类：
 *       <ul>
 *         <li>{@code {"kind":"history","history":{...}}} —— 一条观看记录（History 对象的完整 JSON）。</li>
 *         <li>{@code {"kind":"tombstone","name":"片名","time":毫秒}} —— 一条“删除墓碑”，表示该片名在某时刻被删除。</li>
 *       </ul></li>
 *   <li>本机状态（{@code watch_sync_state.json}）持久化两份数据：
 *       <ul>
 *         <li>{@code lastPushed}：本机“曾经拥有过”的片名集合，用于检测本机删除动作。</li>
 *         <li>{@code tombs}：本机墓碑表（片名 → 删除时间），用于让删除尽快在本地生效、并防止被拉回。</li>
 *       </ul></li>
 * </ul>
 *
 * <p><b>冲突解决原则</b></p>
 * <ol>
 *   <li><b>删除权威</b>：任何一台设备删除记录 → 生成墓碑 → 全网删除。删除比历史记录优先。</li>
 *   <li><b>复活（重新观看）</b>：仅当本机存在“观看时间（createTime）晚于墓碑时间”的记录时，才判定为“删后又重新看过”，从而撤销墓碑、恢复记录全网同步。这是唯一合法的复活路径。</li>
 *   <li><b>同名记录取新</b>：同片名存在多版本（createTime 不同）时，合并取 createTime 较新者，谁新谁赢，避免旧记录把新记录打回。</li>
 * </ol>
 *
 * <p><b>本次修复要点（相对旧版）</b></p>
 * <ol>
 *   <li><b>本机全集改为 findAll（全量、不受 LIMIT 60 截断）</b>：
 *       旧版用 {@code History.get()}（DAO {@code LIMIT 60 ORDER BY createTime DESC} 的最近 60 条）当“本机全集”，
 *       导致记录一旦被 60 条窗口挤出就被误判为“用户删除”而生成墓碑，进而全网丢记录。现改为经
 *       {@code AppDatabase.get().getHistoryDao().findAll()} 反射取全量并过滤当前播放源（cid），彻底消除该误判。</li>
 *   <li><b>复活判定统一用 createTime &gt; 墓碑时间</b>：旧版 push/merge 只看“本地记录是否存在”就丢弃墓碑，
 *       导致任何一台仍持有旧副本的设备都能把已删除的记录写回，删除被永久撤销。现统一为 createTime 判定，
 *       与拉取侧 applyRemoteTombstones 的判定标准一致。</li>
 *   <li><b>merge 先收齐墓碑再处理记录、同名按 createTime 取新</b>：消除“先记录后墓碑”的数组顺序脏状态，
 *       并修复旧版“本地覆盖远端记录（不看 createTime）”导致新记录被打回/丢失的问题。</li>
 *   <li><b>只在内容真正变化时才写远端</b>：减少 30 秒周期拉取与 FileObserver 之间的写放大抖动。</li>
 * </ol>
 */
public class WatchSync {

    /** 目标主机应用里的 History bean 的默认类名（作为最后兜底候选）。 */
    private static final String HISTORY_CLS = "com.fongmi.android.tv.bean.History";
    /** 本机同步状态文件（位于应用私有目录，非远端）。 */
    private static final String STATE_FILE = "watch_sync_state.json";

    /** 本机数据库变动后的推送防抖窗口（毫秒）。 */
    private static final long PUSH_DEBOUNCE_MS = 5000;
    /** 定期拉取远端文件的周期（秒）。 */
    private static final long PULL_PERIOD_SEC = 30;
    /** 墓碑有效期（60 天），超过则不再参与输出与解析。 */
    private static final long TOMBSTONE_TTL_MS = 60L * 24 * 60 * 60 * 1000;

    private final Context context;
    private final Drive drive;
    private final String username;
    /** 远端同步文件路径（已按用户名隔离）。 */
    private final String syncPath;

    /** 单线程调度器：push/pull/定时对账都在同一线程串行执行，避免并发竞争。 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /** 最近一次触发推送的事件时间，用于防抖。 */
    private final AtomicLong lastPushEvent = new AtomicLong(0);
    /** 防抖积压标志：防抖窗口内再次变更时，延迟补一次兜底推送，避免高频删除吞掉事件。 */
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
    private Method histDelete;              // History.delete() -> void

    private Class<?> appDbClass;            // AppDatabase
    private Method dbGet;                   // AppDatabase.get()
    private Method dbGetHistoryDao;         // AppDatabase.get().getHistoryDao()
    private Method daoFindAll;              // HistoryDao.findAll() -> 本机全量
    private Method vodGetCid;               // VodConfig.getCid() -> 当前播放源 id（可为 null）

    /** 本机墓碑表：片名 -> 删除时间。 */
    private final Map<String, Long> localTombs = new HashMap<>();
    /** 本机曾拥有的片名集合（持久化），用作删除检测依据。 */
    private final Set<String> lastPushed = new HashSet<>();

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
     * 加载本机状态 → 解析宿主反射 → 启动数据库监视 → 定时拉取 → 立即拉一次。
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
            ws.loadState();
            ws.initReflection();
            ws.startWatching();
            ws.schedule();
            // 启动先拉一次，让远端已有记录尽快并入本机
            ws.scheduler.execute(ws::pull);
            Logger.log("WatchSync > 启动完成");
            return ws;
        } catch (Throwable t) {
            Logger.log("WatchSync > start failed: " + t);
            return null;
        }
    }

    // ---------------- 本机状态持久化 ----------------

    /** 本机状态文件路径（应用私有目录）。 */
    private File stateFile() {
        return new File(context.getFilesDir(), STATE_FILE);
    }

    /** 从本机状态文件恢复 lastPushed 与本地墓碑表。任何字段缺失/损坏都静默跳过。 */
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
                    Iterator<String> it = tb.keys();
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

    /** 把 lastPushed 与本地墓碑表写回本机状态文件（供重启后续用）。 */
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

    /** 初始化全部反射缓存。失败的方法置空或抛出，由上层兜底。 */
    private void initReflection() throws Exception {
        String appPkg = appPackage();
        historyClass = resolveHistoryClass();
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
        try {
            histDelete = historyClass.getMethod("delete");
        } catch (Throwable t) {
            histDelete = null;
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
                            Logger.log("WatchSync > 文件事件 event=" + event + " path=" + path);
                            // 只关心主库与 WAL 文件，避免无关文件触发
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

    /** 数据库变动回调：带防抖；防抖窗口内再来事件则补一次延迟兜底推送，避免高频操作丢事件。 */
    private void onDbChanged() {
        long now = System.currentTimeMillis();
        long last = lastPushEvent.get();
        if (now - last < PUSH_DEBOUNCE_MS) {
            // 5 秒内频繁变动：不要直接丢弃，而是延迟调度一次兜底推送
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

    /** 周期性拉取远端并合并回本地。 */
    private void schedule() {
        scheduler.scheduleWithFixedDelay(this::pull, PULL_PERIOD_SEC, PULL_PERIOD_SEC, TimeUnit.SECONDS);
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
     * 不会像 get() 那样被 LIMIT 60 截断，从而避免把“自然淘汰”误判成“用户删除”。
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

    /** 取列表里所有非空片名集合。 */
    private Set<String> namesOf(List<?> list) {
        Set<String> set = new HashSet<>();
        if (list == null) return set;
        for (Object o : list) {
            String n = vodNameOf(o);
            if (!n.isEmpty()) set.add(n);
        }
        return set;
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

    /** 从远端原始文本解析墓碑表（片名 -> 时间），自动过滤已过 TTL 的墓碑。 */
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
                    // 每用户一个文件，文件即本用户，不再按 user 字段过滤
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

    /**
     * 应用远端墓碑到本机：删除本机同名记录并写入本地墓碑表。
     * 唯一的例外是<b>复活</b>——本机存在观看时间晚于墓碑时间的记录（删后又重新看过），则跳过删除。
     */
    private void applyRemoteTombstones(Map<String, Long> remoteTombs) {
        if (histDelete == null) return;
        for (Map.Entry<String, Long> e : remoteTombs.entrySet()) {
            String name = e.getKey();
            long tombTime = e.getValue();
            try {
                Object locals = historyFindByName.invoke(null, name);
                List<?> list = locals == null ? new ArrayList<>() : (List<?>) locals;
                // 复活判定：本机已重新观看该记录（其 createTime 晚于墓碑删除时间）→ 撤销墓碑，不再删除
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

    /** 列表里是否存在观看时间晚于 tombTime 的记录（即删除后又看过 → 复活依据）。 */
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

    /** 读取 History 对象的观看创建时间；异常/缺字段返回 -1。 */
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

    /** 本机是否存在“重新观看晚于指定时间”的同名记录（复活判定通用入口）。 */
    private boolean localRecreatedNewerThan(String name, long time) {
        try {
            Object locals = historyFindByName.invoke(null, name);
            List<?> list = locals == null ? new ArrayList<>() : (List<?>) locals;
            return hasRecordNewerThan(list, time);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 推送 ----------------

    /**
     * 把本机当前状态合并到远端文件。
     * 流程：复活（createTime 判定）→ 删除检测（全量本机集）→ 更新 lastPushed → 合并 → 仅在变化时写远端。
     */
    private void push() {
        try {
            // 本机全量（findAll，非 60 条），作为删除检测的真全集
            List<?> local = localHistoryFull();
            Set<String> current = namesOf(local);
            long now = System.currentTimeMillis();

            // 复活：仅当本机确实重新观看过（createTime 晚于墓碑时间）才撤销墓碑让其全网恢复
            for (String name : current) {
                Long t = localTombs.get(name);
                if (t != null && localRecreatedNewerThan(name, t)) {
                    localTombs.remove(name);
                    Logger.log("WatchSync > 本机重新观看，复活记录（撤销墓碑）: " + name);
                }
            }

            // 删除检测：凡本机曾拥有、且当前全量里已消失、又无墓碑的记录 → 生成墓碑（视为用户删除，全网传播）
            for (String name : lastPushed) {
                if (!current.contains(name) && !localTombs.containsKey(name)) {
                    localTombs.put(name, now);
                    Logger.log("WatchSync > 检测到本机删除，生成墓碑: " + name);
                }
            }

            // 更新“本机曾拥有”集合为当前全量（只追踪本地真正拥有的名称，避免误把远端记录当成本机删除）
            lastPushed.clear();
            lastPushed.addAll(current);

            String raw = readRemote();
            String json = merge(local, raw);
            // 内容未变化则不重写远端，避免 30s 周期与 FileObserver 互相放大的写抖动
            if (json.equals(raw)) {
                saveState();
                return;
            }
            writeRemote(json);
            saveState();
        } catch (Throwable t) {
            Logger.log("WatchSync > push err: " + t);
        }
    }

    /**
     * 合并本机与远端，生成待写入远端的 JSON 数组。
     * <ul>
     *   <li>分两遍处理：第一遍只收集墓碑（含复活判定），第二遍再处理记录——避免“先记录后墓碑”的顺序脏状态。</li>
     *   <li>同名记录按 createTime 取较新者：远端与本地谁新谁赢，杜绝旧记录把新记录打回。</li>
     *   <li>被墓碑过滤掉的记录（含本机残余的过期副本）不输出。</li>
     * </ul>
     */
    private String merge(List<?> local, String raw) throws Exception {
        long now = System.currentTimeMillis();
        // 生效墓碑集合（决定哪些记录被过滤）与 输出墓碑集合（决定哪些墓碑写回远端）
        Map<String, Long> allTombs = new LinkedHashMap<>(localTombs);
        Map<String, Long> tombMap = new LinkedHashMap<>(localTombs);

        // 第一遍：只收集墓碑（本地墓碑 + 远端墓碑），并做 createTime 复活判定
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONArray remote = new JSONArray(raw);
                for (int i = 0; i < remote.length(); i++) {
                    JSONObject item = remote.optJSONObject(i);
                    if (item == null || !"tombstone".equals(item.optString("kind"))) continue;
                    String tn = item.optString("name", "");
                    long tt = item.optLong("time", 0);
                    if (tn.isEmpty()) continue;
                    // 复活：本机存在观看时间晚于墓碑的记录（真·重新观看）→ 丢弃该墓碑，让记录正常同步
                    if (localRecreatedNewerThan(tn, tt)) {
                        allTombs.remove(tn);
                        tombMap.remove(tn);
                        Logger.log("WatchSync > 本机重新观看，丢弃远端墓碑: " + tn);
                        continue;
                    }
                    allTombs.put(tn, Math.max(allTombs.getOrDefault(tn, 0L), tt));
                    tombMap.put(tn, Math.max(tombMap.getOrDefault(tn, 0L), tt));
                }
            } catch (Throwable t) {
                Logger.log("WatchSync > merge: 墓碑解析失败，按无墓碑处理: " + t);
            }
        }

        // 收集候选记录：片名 -> 最佳 wrap；同时记录其 createTime，用于同名取新
        Map<String, JSONObject> chosen = new LinkedHashMap<>();
        Map<String, Long> chosenTime = new HashMap<>();

        // 第二遍 a：远端 history（被墓碑过滤；同名时若 createTime 更晚则占位）
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONArray remote = new JSONArray(raw);
                for (int i = 0; i < remote.length(); i++) {
                    JSONObject wrap = remote.optJSONObject(i);
                    if (wrap == null || !"history".equals(wrap.optString("kind"))) continue;
                    JSONObject rec = wrap.optJSONObject("history");
                    if (rec == null) continue;
                    String n = nameFromHistory(rec);
                    if (n.isEmpty() || allTombs.containsKey(n)) continue;
                    long t = rec.optLong("createTime", 0L);
                    if (!chosen.containsKey(n) || t > chosenTime.get(n)) {
                        chosen.put(n, wrap);
                        chosenTime.put(n, t);
                    }
                }
            } catch (Throwable t) {
                Logger.log("WatchSync > merge: 远端 history 解析失败，按空处理: " + t);
            }
        }

        // 第二遍 b：本机 history（被墓碑过滤；同名时仅当 createTime 更晚才替换远端那条）
        for (Object o : local) {
            String n = vodNameOf(o);
            if (n.isEmpty() || allTombs.containsKey(n)) continue;
            JSONObject histJson = historyToJson(o);
            if (histJson == null) continue;
            long t = histJson.optLong("createTime", 0L);
            if (!chosen.containsKey(n) || t > chosenTime.get(n)) {
                JSONObject wrap = new JSONObject();
                wrap.put("kind", "history");
                wrap.put("history", histJson);
                chosen.put(n, wrap);
                chosenTime.put(n, t);
            }
        }

        JSONArray merged = new JSONArray();
        for (JSONObject w : chosen.values()) merged.put(w);

        // 输出墓碑（过滤空名与已过 TTL 的）
        for (Map.Entry<String, Long> e : tombMap.entrySet()) {
            if (e.getKey().isEmpty() || now - e.getValue() > TOMBSTONE_TTL_MS) continue;
            JSONObject tb = new JSONObject();
            tb.put("kind", "tombstone");
            tb.put("name", e.getKey());
            tb.put("time", e.getValue());
            merged.put(tb);
        }
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
     * 周期拉取远端并应用到本机：
     * 先做一次本机删除对账（防 FileObserver 漏触发）→ 读远端 → 应用远端墓碑 → 选择性入库（进度保护）→ 收敛对账。
     */
    private void pull() {
        try {
            // 拉取前先对账一次本机删除，防止 fileObserver 没来得及 push 而产生复活
            List<?> localBefore = localHistoryFull();
            Set<String> curLocal = namesOf(localBefore);
            long now = System.currentTimeMillis();
            for (String name : lastPushed) {
                if (!curLocal.contains(name) && !localTombs.containsKey(name)) {
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
                if (wrap == null || !"history".equals(wrap.optString("kind"))) continue;
                JSONObject rec = wrap.optJSONObject("history");
                if (rec == null) continue;
                String n = nameFromHistory(rec);
                if (n.isEmpty()) continue;
                if (remoteTombs.containsKey(n)) continue;   // 远端墓碑，跳过
                if (localTombs.containsKey(n)) continue;    // 本机刚删，坚决不拉，防止复活
                if (!canSafeMerge(rec)) continue;           // 进度保护：无进度记录不覆盖本地有进度记录
                Object obj = historyObjectFrom.invoke(null, rec.toString());
                if (obj != null) mine.add(obj);
            }

            Logger.log("WatchSync > pull 完成，远端总数=" + arr.length() + " 属于本用户=" + mine.size());
            if (!mine.isEmpty()) {
                historySync.invoke(null, mine);
                // 入库后并入“本机曾拥有”集合，供后续删除检测使用
                lastPushed.addAll(namesOf(localHistoryFull()));
                saveState();
            }
            reconcile(raw);
        } catch (Throwable t) {
            Logger.log("WatchSync > pull err: " + t);
        }
    }

    /**
     * 定时收敛对账：以全量本机集为准补生成墓碑（FileObserver 漏触发的兜底），
     * 并仅在远端内容真正变化时写回。
     */
    private void reconcile(String raw) {
        try {
            List<?> local = localHistoryFull();
            Set<String> names = namesOf(local);
            long now = System.currentTimeMillis();
            // 对账兜底墓碑判定：本机曾拥有、当前已无、且无墓碑的记录 → 补生成墓碑，杜绝复活
            for (String name : lastPushed) {
                if (!names.contains(name) && !localTombs.containsKey(name)) {
                    localTombs.put(name, now);
                    Logger.log("WatchSync > 对账检测到本机删除，补生成墓碑: " + name);
                }
            }
            String merged = merge(local, raw);
            // 语义比较：只有内容真变了才写，避免 JSON 顺序/空列表导致的无意义重写
            if (!merged.equals(raw)) {
                writeRemote(merged);
            }
            saveState();
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