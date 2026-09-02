package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.bean.alist.Drive;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 观看记录多端同步（方向 A：复用 alist 服务器文件作为共享记录仓）。
 *
 * <p><b>同步模型</b></p>
 * <ul>
 *   <li>每个用户一个远端文件（如 watch.&lt;username&gt;.txt），用户之间物理隔离。</li>
 *   <li>远端文件是 JSON 对象：{@code {"records":[...], "tombstones":{片名:时间戳}}}。
 *       <ul>
 *         <li>{@code records}：观看记录数组，每项 {@code {"history":{...}}}（History 完整 JSON），保存完整观看历史（不设上限）。</li>
 *         <li>{@code tombstones}：删除墓碑（片名 → 删除时间戳），用于把删除传播到其它设备并防止复活。</li>
 *       </ul>
 *       兼容旧格式：若读到的是裸 JSON 数组，则当作 records 处理。</li>
 *   <li><b>统一流程 {@link #pullAndPush()}</b>：本地轮询（3 秒）检测到本机记录变化 / 定时（30 秒）都走它：
 *       读本地 → 与 {@code localSnap} 对比生成墓碑 → 读远端 → 「新者胜」合并（墓碑与记录比时间戳）→ 写回远端（含墓碑）→ 本地入库（historySync 增/改 + historyDel 删墓碑命中的）。</li>
 *   <li>本机全集通过 {@code AppDatabase.get().getHistoryDao().findAll()} 反射获取并过滤当前播放源(cid)，
 *       避免 {@code History.get()} 的 LIMIT 60 截断把"自然淘汰"误判成删除。</li>
 * </ul>
 *
 * <p><b>合并规则（LWW-register：新者胜）</b></p>
 * <ol>
 *   <li>每个片名同时可能有"记录(createTime)"与"墓碑(删除时间)"，取两者中<b>时间戳较大者</b>为胜者。</li>
 *   <li>墓碑胜 → 该名字被删除（本地删 + 远端保留墓碑）；记录胜 → 该记录保留/复活（墓碑被丢弃）。</li>
 *   <li>因此"本机仍持有旧副本"不会撤销删除，只有"重新看过（createTime 晚于删除时间）"才会复活。</li>
 *   <li>墓碑带 {@link #TOMBSTONE_TTL_MS} 有效期，过期不再写回远端，避免无限累积。</li>
 * </ol>
 */
public class WatchSync {

    /** 目标主机应用里的 History bean 的默认类名（作为最后兜底候选）。 */
    private static final String HISTORY_CLS = "com.fongmi.android.tv.bean.History";

    /** 本地轮询周期（毫秒）：本机记录变化即触发同步。 */
    private static final long PUSH_POLL_MS = 3000;
    /** 定期同步远端文件的周期（秒）。 */
    private static final long PULL_PERIOD_SEC = 30;
    /** 墓碑有效期（毫秒）：超过该时长不再写回远端，避免墓碑无限累积。 */
    private static final long TOMBSTONE_TTL_MS = 60L * 24 * 60 * 60 * 1000; // 60 天

    private final Context context;
    private final Drive drive;
    private final String username;
    /** 远端同步文件路径（已按用户名隔离）。 */
    private final String syncPath;

    /** 单线程调度器：轮询/同步都在同一线程串行执行，避免并发竞争。 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /** 本机记录快照（片名|createTime|position|duration），用于轮询比对是否发生变化。 */
    private List<String> lastSnapshot = Collections.emptyList();
    /** 本机上次同步后的历史片名集合（仅内存、不持久化），用于与当前本地对比生成删除墓碑。 */
    private final Set<String> localSnap = new HashSet<>();

    // ---------------- 反射缓存 ----------------
    // 由于 WatchSync 是 spider 插件，运行在宿主播放器内，History 等类是宿主应用的，
    // 因此一律通过反射调用，避免直接编译期依赖。
    private Class<?> historyClass;          // com.fongmi.android.tv.bean.History
    private Method historyGet;              // History.get() -> 最近 60 条（仅作兜底）
    private Method historyFindByName;       // History.findByName(String) -> List
    private Method historyObjectFrom;       // History.objectFrom(String) -> History
    private Method historySync;             // History.sync(List) -> void（只增/改，不删）
    private Method histDel;                 // History.delete() -> History（删除单条本地记录）
    private Method histGetVodName;          // History.getVodName()
    private Method histCanSave;             // History.canSave()（可为 null）
    private Method histGetPosition;         // History.getPosition()（可为 null）
    private Method histGetDuration;         // History.getDuration()（可为 null）

    private Object appDb;                   // AppDatabase（或 _Impl）单例实例
    private Method daoGetter;               // AppDatabase 上取 HistoryDao 的方法
    private Method daoFindAll;              // HistoryDao 上取全量的方法（findAll/getAll/loadAll）
    private Method vodGetCid;               // VodConfig.getCid() -> 当前播放源 id（可为 null）

    /** 远端解析结果。 */
    private static class RemoteData {
        JSONArray records = new JSONArray();
        Map<String, Long> tombstones = new LinkedHashMap<>();
    }

    private WatchSync(Context context, Drive drive, String username, String syncPath) {
        this.context = context;
        this.drive = drive;
        this.username = username == null ? "" : username;
        // 每用户一个远端文件：避免多用户共享单文件互相干扰（如 watch.txt -> watch.<username>.txt）
        //todo syncPath要考虑目录不存在的情况，需要递归创建目录。
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
     * 解析宿主反射 → 启动本地轮询 + 定时同步 → 首次同步 + 初始化 localSnap 基线。
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
            ws.schedule();
            // 启动先同步一次，让远端已有记录尽快并入本机
            ws.scheduler.execute(ws::pullAndPush);
            // 首次同步后初始化 localSnap 基线（记录后续用于生成删除墓碑的本地历史）
            // 用与“每次同步后更新”同一个函数 refreshLocalSnap()，保证初始与更新一致
            ws.scheduler.execute(ws::refreshLocalSnap);
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
        // 删除操作：History.delete()（实例方法，按 cid+key 删单条）——用于把墓碑命中的本地记录删掉
        try {
            histDel = historyClass.getMethod("delete");
        } catch (Throwable t) {
            histDel = null;
            Logger.log("WatchSync > History.delete() 反射失败，将无法在本地执行墓碑删除: " + t);
        }

        // AppDatabase.get().getHistoryDao().findAll()：取本机全量历史（避开 get() 的 LIMIT 60 截断）。
        // 真机 AppDatabase 可能因混淆/改签名找不到 get()，故做多候选兼容：
        //   优先试 AppDatabase，再试 Room 生成的 AppDatabase_Impl；
        //   单例方法先 getMethod 再回退 getDeclaredMethod（连私有/包级也能拿）；
        //   DAO 方法在实例上按返回类型扫；findAll 在 DAO 实现上找“无参返回 List”的方法。
        // 任何一步失败都整体降级为 get() 兜底，不影响主流程。
        appDb = null;
        daoGetter = null;
        daoFindAll = null;
        try {
            Class<?> dbCls = null;
            for (String c : new String[]{appPkg + ".db.AppDatabase", appPkg + ".db.AppDatabase_Impl"}) {
                try {
                    dbCls = Class.forName(c);
                    if (dbCls != null) break;
                } catch (Throwable ignored) {
                }
            }
            if (dbCls != null) {
                for (String m : new String[]{"get", "getInstance", "getDatabase", "getDb"}) {
                    Method mm = null;
                    try {
                        mm = dbCls.getMethod(m);
                    } catch (Throwable ignored) {
                    }
                    if (mm == null) {
                        try {
                            mm = dbCls.getDeclaredMethod(m);
                            if (mm != null) mm.setAccessible(true);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (mm != null) {
                        try {
                            appDb = mm.invoke(null);
                            if (appDb != null) break;
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (appDb != null) {
                    for (Method me : appDb.getClass().getMethods()) {
                        if (me.getParameterCount() != 0) continue;
                        if (me.getName().toLowerCase().contains("historydao")) {
                            daoGetter = me;
                            break;
                        }
                    }
                    if (daoGetter != null) {
                        Object dao = daoGetter.invoke(appDb);
                        if (dao != null) {
                            for (Method me : dao.getClass().getMethods()) {
                                if (me.getParameterCount() != 0) continue;
                                if (!List.class.isAssignableFrom(me.getReturnType())) continue;
                                String n = me.getName().toLowerCase();
                                if (n.equals("findall") || n.equals("getall") || n.equals("loadall")) {
                                    daoFindAll = me;
                                    break;
                                }
                            }
                        }
                    }
                }
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

    // ---------------- 本地轮询（替代 FileObserver，更可靠） ----------------

    /**
     * 本地轮询：每 3 秒比对一次本机记录快照，发生变化即触发统一同步流程。
     */
    private void pollLocal() {
        try {
            List<?> local = localHistoryFull();
            List<String> sig = snapshotOf(local);
            if (!sig.equals(lastSnapshot)) {
                Logger.log("WatchSync > 本地记录变化(" + sig.size() + "条)，触发同步");
                pullAndPush();
            }
        } catch (Throwable t) {
            Logger.log("WatchSync > pollLocal err: " + t);
        }
    }

    /** 生成本机记录快照（片名|createTime|position|duration），排序以便稳定比对。 */
    private List<String> snapshotOf(List<?> local) {
        List<String> sigs = new ArrayList<>();
        for (Object o : local) {
            String n = vodNameOf(o);
            if (n.isEmpty()) continue;
            JSONObject j = historyToJson(o);
            if (j == null) continue;
            sigs.add(n + "|" + j.optLong("createTime", 0L) + "|" + j.optLong("position", 0L) + "|" + j.optLong("duration", 0L));
        }
        Collections.sort(sigs);
        return sigs;
    }

    /**
     * 注册周期任务：
     *   - 本地 3 秒轮询（本机记录变化触发同步）；
     *   - 定时 30 秒拉取远端（统一走 pullAndPush）。
     */
    private void schedule() {
        scheduler.scheduleWithFixedDelay(this::pollLocal, PUSH_POLL_MS, PUSH_POLL_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(() -> pullAndPush(), PULL_PERIOD_SEC, PULL_PERIOD_SEC, TimeUnit.SECONDS);
    }

    // -------------------- 统一同步流程 pullAndPush --------------------

    /**
     * 统一同步（新增、更新、删除都走这里），替代旧的 push / pull / reconcile：
     * <ol>
     *   <li>读取本地记录，与 {@code localSnap} 对比，对"上次有、现在没有"的片名生成本地墓碑；</li>
     *   <li>读取远端记录（records + tombstones）；</li>
     *   <li>合并本地与远端（墓碑与记录都按「新者胜」比时间戳）；</li>
     *   <li>把合并结果写回远端（含墓碑，墓碑按 {@link #TOMBSTONE_TTL_MS} 裁剪）；</li>
     *   <li>合并结果落到本地：墓碑命中的用 historyDel 删除，记录用 historySync 增/改。</li>
     * </ol>
     */
    private void pullAndPush() {
        try {
            // ===== 1. 读本地记录，与 localSnap 对比，生成墓碑 =====
            List<?> local = localHistoryFull();                    // 本机全量
            Set<String> currentLocal = new HashSet<>();            // 当前本机片名
            for (Object o : local) {
                String n = vodNameOf(o);
                if (!n.isEmpty()) currentLocal.add(n);
            }
            long now = System.currentTimeMillis();
            Map<String, Long> myTombs = new HashMap<>();
            for (String n : localSnap) {                            // 上次同步时本机有、现在没了 → 本机删除
                if (!currentLocal.contains(n)) {
                    myTombs.put(n, now);
                    Logger.log("WatchSync > 检测到本机删除，生成墓碑: " + n);
                }
            }

            // ===== 2. 读远端记录 =====
            String raw = readRemote();
            if (raw == null) {
                Logger.log("WatchSync > pullAndPush: 远端读取失败");
                return;
            }
            RemoteData rd = parseRemote(raw);

            // ===== 3. 合并（history 与 tombstone 都按新者胜）=====
            RemoteData merged = mergeAll(local, myTombs, rd);

            // ===== 4. 推送合并结果到远端（含墓碑）=====
            String json = serialize(merged);
            if (!json.equals(raw)) {                                 // 内容变了才写，减少写放大
                writeRemote(json);
            }

            // ===== 5. 合并结果本地入库：墓碑命中删除 + records 增/改 =====
            applyLocal(merged);

            // 更新基线
            lastSnapshot = snapshotOf(localHistoryFull());           // 同步后的本地状态作为轮询基线
            Logger.log("WatchSync > pullAndPush 完成：records=" + merged.records.length() + " 墓碑=" + merged.tombstones.size());
        } catch (Throwable t) {
            Logger.log("WatchSync > pullAndPush err: " + t);
        }
    }

    /**
     * 刷新 localSnap 基线：重读本机全集并重建“本地历史片名集合”。
     * <b>初始化和每次同步后都用这一个函数</b>，保证两者语义一致（都是以当前库为准）。
     */
    private void refreshLocalSnap() {
        try {
            Set<String> s = namesOf(localHistoryFull());
            localSnap.clear();
            localSnap.addAll(s);
            Logger.log("WatchSync > localSnap 快照已刷新：本地 " + s.size() + " 条");
        } catch (Throwable t) {
            Logger.log("WatchSync > refreshLocalSnap err: " + t);
        }
    }

    /** 把合并结果应用到本地：墓碑命中的删除，records 的用 historySync 增/改。结束后刷新 localSnap 基线。 */
    private void applyLocal(RemoteData merged) {
        // 5a. 墓碑命中 → 删除本地同名记录（historyDel）
        if (histDel != null && !merged.tombstones.isEmpty()) {
            for (String n : merged.tombstones.keySet()) {
                try {
                    Object locals = historyFindByName.invoke(null, n);
                    List<?> list = locals == null ? new ArrayList<>() : (List<?>) locals;
                    for (Object it : list) {
                        histDel.invoke(it);
                    }
                    if (!list.isEmpty()) {
                        Logger.log("WatchSync > 墓碑删除本地记录: " + n);
                    }
                } catch (Throwable t) {
                    Logger.log("WatchSync > 墓碑删除本地 err (" + n + "): " + t);
                }
            }
        }
        // 5b. records → 入库（historySync 自带 createTime 新者胜 + 进度保护）
        List<Object> mine = new ArrayList<>();
        for (int i = 0; i < merged.records.length(); i++) {
            JSONObject wrap = merged.records.optJSONObject(i);
            if (wrap == null) continue;
            JSONObject rec = wrap.optJSONObject("history");
            if (rec == null) continue;
            if (!canSafeMerge(rec)) continue;                        // 进度保护：无进度记录不覆盖本地有进度记录
            try {
                Object obj = historyObjectFrom.invoke(null, rec.toString());
                if (obj != null) {
                    mine.add(obj);
                }
            } catch (Throwable t) {
                Logger.log("WatchSync > historyObjectFrom err: " + t);
            }
        }
        if (!mine.isEmpty()) {
            try {
                historySync.invoke(null, mine);
            } catch (Throwable t) {
                Logger.log("WatchSync > historySync err: " + t);
            }
        }
        // 应用完之后的本地库才是真基线 → 统一走 refreshLocalSnap()（与初始化同一函数）
        refreshLocalSnap();
    }

    /**
     * 合并：本地 + 远端（records 与 tombstones 都按「新者胜」比时间戳）。
     * 对每个片名，比较记录 createTime 与墓碑删除时间，谁大谁赢：
     * 记录胜 → 保留该记录（复活）；墓碑胜 → 标记删除（本地删 + 远端留墓碑）。
     */
    private RemoteData mergeAll(List<?> local, Map<String, Long> myTombs, RemoteData rd) {
        // 片名 -> 新者 createTime / 对应 history JSON
        Map<String, Long> histTime = new LinkedHashMap<>();
        Map<String, JSONObject> histJson = new LinkedHashMap<>();
        // 片名 -> 新者墓碑时间
        Map<String, Long> tombTime = new LinkedHashMap<>();

        // 本地 history（同名取 createTime 较新者）
        for (Object o : local) {
            String n = vodNameOf(o);
            if (n.isEmpty()) continue;
            JSONObject h = historyToJson(o);
            if (h == null) continue;
            long t = h.optLong("createTime", 0L);
            if (!histTime.containsKey(n) || t > histTime.get(n)) {
                histTime.put(n, t);
                histJson.put(n, h);
            }
        }
        // 远端 history（同名取 createTime 较新者）
        for (int i = 0; i < rd.records.length(); i++) {
            JSONObject wrap = rd.records.optJSONObject(i);
            if (wrap == null) continue;
            JSONObject h = wrap.optJSONObject("history");
            if (h == null) continue;
            String n = nameFromHistory(h);
            if (n.isEmpty()) continue;
            long t = h.optLong("createTime", 0L);
            if (!histTime.containsKey(n) || t > histTime.get(n)) {
                histTime.put(n, t);
                histJson.put(n, h);
            }
        }
        // 本机墓碑（本次生成）
        for (Map.Entry<String, Long> e : myTombs.entrySet()) {
            String n = e.getKey();
            long t = e.getValue();
            if (!tombTime.containsKey(n) || t > tombTime.get(n)) tombTime.put(n, t);
        }
        // 远端墓碑
        for (Map.Entry<String, Long> e : rd.tombstones.entrySet()) {
            String n = e.getKey();
            long t = e.getValue();
            if (!tombTime.containsKey(n) || t > tombTime.get(n)) tombTime.put(n, t);
        }

        // 新者胜
        RemoteData out = new RemoteData();
        Set<String> names = new LinkedHashSet<>();
        names.addAll(histTime.keySet());
        names.addAll(tombTime.keySet());
        long now = System.currentTimeMillis();
        for (String n : names) {
            if (n.isEmpty()) continue;
            Long ht = histTime.get(n);
            Long tt = tombTime.get(n);
            if (ht != null && (tt == null || ht >= tt)) {
                // 记录胜（含平手取记录）→ 保留历史，丢弃墓碑
                try {
                    JSONObject wrap = new JSONObject();
                    wrap.put("history", histJson.get(n));
                    out.records.put(wrap);
                } catch (Throwable ignored) {
                }
            } else if (tt != null && now - tt <= TOMBSTONE_TTL_MS) {
                // 墓碑胜 → 删除；仅保留未过 TTL 的墓碑，避免无限累积
                out.tombstones.put(n, tt);
            }
        }
        return out;
    }


    /** 取一条 wrap（含 {"history":{...}}）里 history 的 createTime。 */
    private long createTimeOf(JSONObject wrap) {
        if (wrap == null) return 0;
        JSONObject h = wrap.optJSONObject("history");
        return h == null ? 0 : h.optLong("createTime", 0L);
    }

    /** 从一条 history 记录里取片名（兼容 vodName / vod_name 两种字段名）。 */
    private String nameFromHistory(JSONObject h) {
        if (h == null) return "";
        String n = h.optString("vodName", "");
        if (n.isEmpty()) n = h.optString("vod_name", "");
        return n;
    }

    // ---------------- 远端文件解析 / 序列化 ----------------

    /** 解析远端文件为 RemoteData（records + tombstones）。兼容旧格式（裸 JSON 数组当 records）。 */
    private RemoteData parseRemote(String raw) {
        RemoteData rd = new RemoteData();
        if (raw == null || raw.trim().isEmpty()) return rd;
        try {
            String s = raw.trim();
            if (s.startsWith("{")) {
                JSONObject obj = new JSONObject(s);
                JSONArray recs = obj.optJSONArray("records");
                if (recs != null) rd.records = recs;
                JSONObject tb = obj.optJSONObject("tombstones");
                if (tb != null) {
                    Iterator<String> it = tb.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        rd.tombstones.put(k, tb.optLong(k, 0L));
                    }
                }
            } else {
                // 旧格式：裸数组 [{"history":...}]（也可能是旧墓碑数组，这里只取 history）
                JSONArray arr = new JSONArray(s);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item != null && item.optJSONObject("history") != null) {
                        rd.records.put(item);
                    }
                }
            }
        } catch (Throwable t) {
            Logger.log("WatchSync > parseRemote 失败，按空处理: " + t);
        }
        return rd;
    }

    /** 序列化 RemoteData 为远端 JSON 文本（{"records":[...],"tombstones":{...}}）。 */
    private String serialize(RemoteData rd) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("records", rd.records);
            JSONObject tb = new JSONObject();
            for (Map.Entry<String, Long> e : rd.tombstones.entrySet()) tb.put(e.getKey(), e.getValue());
            obj.put("tombstones", tb);
            return obj.toString();
        } catch (Throwable t) {
            Logger.log("WatchSync > serialize err: " + t);
            return "{\"records\":[],\"tombstones\":{}}";
        }
    }

    // ---------------- 本机数据读取 ----------------

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
        if (appDb != null && daoGetter != null && daoFindAll != null) {
            try {
                Object dao = daoGetter.invoke(appDb);
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

    // ---------------- 进度保护 ----------------

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
     * 入库前的安全合并判断：
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