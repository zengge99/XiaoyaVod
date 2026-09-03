package com.github.catvod.spider;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.github.catvod.bean.alist.Drive;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
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
 *       读本地 → 与 {@code localSnap} 对比生成墓碑 → 读远端 → 「新者胜」合并（墓碑与记录比时间戳）→ 写回远端（含墓碑）→ 本地入库（SQL 直写 upsert + SQL 删除。</li>
 *   <li>本机全集通过 {@code AppDatabase.get().getHistoryDao().findAll()} 反射获取（<b>不过滤 cid/url</b>，
 *       本机该用户的全部历史都同步进他的 watch.&lt;user&gt;.txt）。cid 在跨设备/跨源下不可靠，已弃用。</li>
 * </ul>
 *
 * <p><b>合并规则（LWW-register：新者胜）</b></p>
 * <ol>
 *   <li>每个片名同时可能有"记录(createTime)"与"墓碑(删除时间)"，取两者中<b>时间戳较大者</b>为胜者。</li>
 *   <li>墓碑胜 → 该名字被删除（本地删 + 远端保留墓碑）；记录胜 → 该记录保留/复活（墓碑被丢弃）。</li>
 *   <li>因此"本机仍持有旧副本"不会撤销删除，只有"重新看过（createTime 晚于删除时间）"才会复活。</li>
 *   <li>墓碑带 {@link #TOMBSTONE_TTL_MS} 有效期，过期不再写回远端，避免无限累积。</li>
 * </ol>
 *
 * <p><b>锚定模型（cid + username 锁死）</b></p>
 * <ul>
 *   <li>每 <b>一个 AListSh 实例对应一个 sync 实例</b>（不单例）：宿主里每个配置一个 AListSh，天然每配置一个 sync。</li>
 *   <li>sync 在 <b>homeContent 初始化时</b>解析并<b>锚定</b>当前 {@code cid} 与 {@code username}（此刻 cid 可靠稳定），
 *       实例整个生命周期锁死在这两个值上，不再二次读取。</li>
 *   <li>远端文件按锚定的 username 隔离存取（watch.&lt;user&gt;.txt）。</li>
 *   <li>读本地记录：SQLite 全量读表后按<b>记录自带的 cid</b> 过滤（{@code WHERE cid = 锚定cid}），
 *       基线(墓碑判定)永远在同一 cid 分区内比较——切源即换 AListSh 实例、换 sync，互不污染，杜绝误删。</li>
 * </ul>
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
    /** 锚定的源 cid：homeContent 初始化时取一次并锁死，本地历史只同步该 cid 分区。 */
    private int anchorCid = -1;
    /** cid 防护探测节流（毫秒）：避免每 3 秒轮询都做一次反射。 */
    private static final long CID_PROBE_MS = 2000L;
    /** 上次静默探测到的当前源 cid，供防护比对（anchorCid 恒定，此值随切换源变化）。 */
    private volatile int lastProbeCid = -1;
    /** 上次探测时间戳，用于节流。 */
    private volatile long lastProbeAt = 0L;
    /** 当前是否处于"cid 偏离锚定、本轮整体跳过"状态（只在状态翻转时打日志，避免刷屏）。 */
    private volatile boolean cidBlocked = false;
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
    private Method historyGet;              // History.get() -> 最近 60 条（仅兜底，读路径用）
    private Method historyObjectFrom;       // History.objectFrom(String) -> History（读路径构造用）
    private Method histGetVodName;          // History.getVodName()
    private Method historySave;             // History.save() —— 单条写入（与宿主一致）
    private Method histSetCid;              // History.setCid(int) —— 锚定 cid

    private Method vodConfigVod;            // 候选2: Config.vod() -> 当前 vod 配置实例（OK影视等魔改壳）
    private Method configGetId;             // 候选2: Config.getId() -> 当前配置 id（即锚定的 cid）

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
        this.syncPath = isolatedPath(syncPath, this.username);
        ensureRemoteDir();
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
     * 创建同步实例（每 AListSh 实例一个，不单例）。由 AListSh.homeContent 首次进入时调用。
     * <p>关键：在此初始化阶段解析并<b>锚定</b>当前源 cid（此刻 cid 可靠稳定），实例整个生命周期
     * 锁死在这个 cid + username 上，本地历史只读该 cid 分区，切源即换 AListSh 实例、换 sync，互不污染。</p>
     * <p>无停机/重建逻辑（不再判定 url.user）：每实例天然锚定，关机/切换由宿主换实例完成。</p>
     *
     * @return 创建成功的实例；条件不满足返回 null（静默降级，不影响播放器）。
     */
    public static WatchSync create(Context context, Drive drive) {
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
            ws.initReflection();               // 内部解析并锚定 cid
            ws.schedule();
            ws.scheduler.execute(ws::pullAndPush);
            ws.scheduler.execute(ws::refreshLocalSnap);
            Logger.log("WatchSync > 创建完成：new 实例 user=" + ws.username + " 锚定cid=" + ws.anchorCid);
            return ws;
        } catch (Throwable t) {
            Logger.log("WatchSync > create failed: " + t);
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
        historyObjectFrom = historyClass.getMethod("objectFrom", String.class);
        histGetVodName = historyClass.getMethod("getVodName");
        // 本地写入用 History.save()（单条）；复制的去重逻辑在 applyLocal 里手工做（与 sync() 的 shouldMerge 判定一致：
        // 同名 + 双方 duration>0 + 时长差<=10分钟 → 视为同一条，写前先删旧再 save 新），避免同名不同 key 重复。
        // histSetCid 用于锚定 cid；墓碑删除仍保留 SQL 直删。
        historySave = historyClass.getMethod("save");
        histSetCid = historyClass.getMethod("setCid", int.class);

        // 本机全量历史改为 SQLite 直读（见 localHistoryFull()），不再走 AppDatabase/DAO 反射：
        // 反编译确认宿主 AppDatabase/DAO 被 R8 彻底混淆（get()→n()、findAll 改名、DAO→q3/*），
        // 按名反射拿不到 findAll；而 SQLiteDatabase 是系统组件不混淆，直接 SELECT * FROM History 即可，
        // 不再初始化 appDb/daoGetter/daoFindAll。History bean 的反射仍保留（objectFrom/sync 等沿用）。

        // 候选2（OK影视等魔改壳）：com.fongmi.android.tv.bean.Config.vod() + getId()
        // id 即为当前配置/源的 cid，homeContent 初始化时此刻可靠，取一次并锚定到 anchorCid。
        try {
            Class<?> cfg = Class.forName(appPkg + ".bean.Config");
            vodConfigVod = cfg.getMethod("vod");
            try {
                configGetId = cfg.getMethod("getId");
            } catch (Throwable t) {
                configGetId = null;
            }
            Logger.log("WatchSync > 候选2 Config.vod() 反射成功: className=" + cfg.getName() + " vod=" + vodConfigVod
                    + " getId=" + (configGetId != null));
        } catch (Throwable t) {
            vodConfigVod = null;
            configGetId = null;
            Logger.log("WatchSync > 候选2 Config.vod() 反射失败: " + t);
        }
        // 锚定当前源 cid（仅此处读取一次）：Config.vod().getId()
        anchorCid = currentCid();
        Logger.log("WatchSync > 锚定 cid=" + anchorCid + " user=" + this.username + "（实例生命周期锁死）");
        Logger.log("WatchSync > initReflection 完成：本实例 user=" + this.username);
    }

    /** 解析当前源配置的 cid（多候选反射）：候选1 Config.vod().getId()；候选2 VodConfig.getCid()。取不到返回 -1。静默版，不刷日志（供高频防护探测）。 */
    private int probeCidQuiet() {
        String appPkg = appPackage();
        // 候选1（优先，因为宿主是 OK影视 等魔改壳，日志证实 Config.vod() 反射成功）：Config.vod().getId()
        if (vodConfigVod != null && configGetId != null) {
            try {
                Object cfg = vodConfigVod.invoke(null);
                if (cfg != null) {
                    Object v = configGetId.invoke(cfg);
                    if (v instanceof Number) return ((Number) v).intValue();
                }
            } catch (Throwable t) {
                // 静默
            }
        }
        // 候选2：标准 fongmi api.config.VodConfig.getCid()
        try {
            Class<?> vod = Class.forName(appPkg + ".api.config.VodConfig");
            Method m = vod.getMethod("getCid");
            Object v = m.invoke(null);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable t) {
            // 静默
        }
        return -1;
    }

    /** 解析当前源配置的 cid（带日志，供初始化锚定时使用）。取不到返回 -1。 */
    private int currentCid() {
        String appPkg = appPackage();
        // 候选1（优先，因为宿主是 OK影视 等魔改壳，日志证实 Config.vod() 反射成功）：Config.vod().getId()
        if (vodConfigVod != null && configGetId != null) {
            try {
                Object cfg = vodConfigVod.invoke(null);
                if (cfg != null) {
                    Object v = configGetId.invoke(cfg);
                    if (v instanceof Number) {
                        int id = ((Number) v).intValue();
                        Logger.log("WatchSync > currentCid: 候选1 Config.vod().getId()=" + id);
                        return id;
                    }
                }
            } catch (Throwable t) {
                Logger.log("WatchSync > currentCid: 候选1 失败: " + t);
            }
        }
        // 候选2：标准 fongmi api.config.VodConfig.getCid()
        try {
            Class<?> vod = Class.forName(appPkg + ".api.config.VodConfig");
            Method m = vod.getMethod("getCid");
            Object v = m.invoke(null);
            if (v instanceof Number) {
                int id = ((Number) v).intValue();
                Logger.log("WatchSync > currentCid: 候选2 VodConfig.getCid()=" + id);
                return id;
            }
        } catch (Throwable t) {
            Logger.log("WatchSync > currentCid: 候选2 失败: " + t);
        }
        Logger.log("WatchSync > currentCid: 无可用反射，返回 -1");
        return -1;
    }

    /**
     * cid 防护（fail-closed）：锚定失败(anchorCid<0)，或探测到当前源 cid 已偏离锚定 cid 时，返回 true。
     * 返回 true 时调用方应<b>整体跳过本轮</b>——不读本地、不读远端、不写远端、不生成墓碑、不动任何基线。
     * 锚定 cid 恒定不变，currentCid 随切换源变化；偏离即跳过，纯保险丝，零副作用。
     */
    private boolean shouldSkipCid(String who) {
        if (anchorCid < 0) {                         // 锚定失败：fail-closed，绝不动本地/远端
            if (!cidBlocked) logCidBlock(who, true);
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastProbeAt > CID_PROBE_MS) {      // 静默探测 + 节流，避免高频反射
            lastProbeAt = now;
            lastProbeCid = probeCidQuiet();
        }
        boolean blocked = (lastProbeCid != anchorCid);
        if (blocked != cidBlocked) logCidBlock(who, blocked);
        return blocked;
    }

    /** 只在 cid 阻塞状态翻转时打一条日志，避免每轮刷屏。 */
    private boolean logCidBlock(String who, boolean blocked) {
        cidBlocked = blocked;
        if (blocked) {
            Logger.log("WatchSync > " + who + ": cid 已切换/锚定失败（锚定=" + anchorCid + "，当前=" + lastProbeCid + "），本轮跳过，不动本地/远端");
        } else {
            Logger.log("WatchSync > " + who + ": cid 恢复一致（锚定=" + anchorCid + "，当前=" + lastProbeCid + "），恢复同步");
        }
        return blocked;
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
            if (shouldSkipCid("pollLocal")) return;
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
     *   - 本地 3 秒轮询；
     *   - 定时 30 秒拉取远端。
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
     *   <li>合并结果落到本地：墓碑命中的用 SQL 删除，记录用 SQL 直写 upsert。</li>
     * </ol>
     */
    private void pullAndPush() {
        try {
            if (shouldSkipCid("pullAndPush")) return;
            // ===== 1. 读本地记录（本实例锚定 cid 分区），与 localSnap 对比，生成墓碑（立即）=====
            List<?> local = localHistoryFull();                    // 只含锚定 cid 的记录
            Set<String> currentLocal = new HashSet<>();            // 当前本机片名
            for (Object o : local) {
                String n = vodNameOf(o);
                if (!n.isEmpty()) currentLocal.add(n);
            }
            long now = System.currentTimeMillis();
            Map<String, Long> myTombs = new HashMap<>();

            // 本地上次有、当前没有 → 立即生成墓碑（不再延迟确认）
            for (String n : localSnap) {
                if (!currentLocal.contains(n)) {
                    myTombs.put(n, now);
                    Logger.log("WatchSync > 检测到删除，立即生成墓碑: " + n);
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

    /**
     * 把合并结果应用到本地：
     *  - 墓碑命中：用 SQL 直删（本锚定 cid 分区内同名记录）；
     *  - records：改用应用自身的 History.save() 走 Room 连接写入，从而触发界面的自动刷新。
     * 结束后刷新 localSnap 基线。
     */
    private void applyLocal(RemoteData merged) {
        // 5a. 墓碑命中 → SQL 直删本锚定 cid 分区内同名记录
        if (!merged.tombstones.isEmpty()) {
            SQLiteDatabase db = null;
            try {
                File dbf = context.getDatabasePath("tv");
                if (dbf == null || !dbf.exists()) return;
                db = SQLiteDatabase.openDatabase(dbf.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
                for (String n : merged.tombstones.keySet()) {
                    int del = db.delete("History", "cid = ? AND vodName = ?",
                            new String[]{String.valueOf(anchorCid), n});
                    if (del > 0) Logger.log("WatchSync > 墓碑删除本地记录: " + n + "（删 " + del + " 条）");
                }
            } catch (Throwable t) {
                Logger.log("WatchSync > applyLocal(墓碑删除) err: " + t);
            } finally {
                if (db != null && db.isOpen()) try { db.close(); } catch (Throwable ignored) {}
            }
        }
        // 5b. records → 用 History.save() 单条写；写前手工去重（逻辑与 sync() 的 shouldMerge 判定一致）
        int saved = 0;
        for (int i = 0; i < merged.records.length(); i++) {
            JSONObject wrap = merged.records.optJSONObject(i);
            if (wrap == null) continue;
            JSONObject rec = wrap.optJSONObject("history");
            if (rec == null) continue;
            if (!canSafeMerge(rec)) continue;                        // 进度保护：无进度记录不覆盖本地有进度记录
            if (saveHistory(rec)) saved++;
        }
        if (saved > 0) Logger.log("WatchSync > 本地入库 " + saved + " 条（History.save() + 手工去重）");
        // 应用完之后的本地库才是真基线 → 统一走 refreshLocalSnap()（与初始化同一函数）
        refreshLocalSnap();
    }

    /**
     * 写入一条并手工去重：逻辑与 History.sync() 的 shouldMerge 判定一致——
     * 先查找本地（锚定 cid）同名记录，凡是「双方 duration>0 且时长差<=10分钟」的都视为同一条（不同 key 的旧版本）删除，
     * 再 setCid(anchorCid).save() 写入当前这条。这样避免同名不同 key 记录并存导致的重复显示。
     */
    private boolean saveHistory(JSONObject rec) {
        String name = rec.optString("vodName", "");
        long dur = rec.optLong("duration", 0L);
        dedupLocal(name, dur);                                       // 手工去重（抄 sync 判定）
        try {
            Object hist = historyObjectFrom.invoke(null, rec.toString());
            if (hist == null) return false;
            histSetCid.invoke(hist, anchorCid);                      // 锚定 cid
            historySave.invoke(hist);                                // 单条保存
            return true;
        } catch (Throwable t) {
            Throwable c = t;
            while (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) c = c.getCause();
            Logger.log("WatchSync > saveHistory err: " + c);
            return false;
        }
    }

    /** 手工去重：删除本地（锚定 cid）与指定片名/时长同一条的旧记录（判定与 sync 的 shouldMerge 一致）。 */
    private void dedupLocal(String name, long dur) {
        if (name.isEmpty()) return;
        SQLiteDatabase db = null;
        Cursor cur = null;
        try {
            File dbf = context.getDatabasePath("tv");
            if (dbf == null || !dbf.exists()) return;
            db = SQLiteDatabase.openDatabase(dbf.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
            cur = db.rawQuery("SELECT \"key\", duration FROM History WHERE cid = ? AND vodName = ?",
                    new String[]{String.valueOf(anchorCid), name});
            while (cur.moveToNext()) {
                String k = cur.getString(0);
                long ldur = cur.getLong(1);
                if (k == null) continue;
                // sync 的 mergeFrom 判定：force=true 忽略 key 是否相同；仅要求双方 duration>0 且时长差<=10分钟
                if (dur > 0 && ldur > 0 && Math.abs(dur - ldur) <= TimeUnit.MINUTES.toMillis(10)) {
                    int del = db.delete("History", "cid = ? AND \"key\" = ?", new String[]{String.valueOf(anchorCid), k});
                    if (del > 0) Logger.log("WatchSync > 去重删除旧同名记录: " + name);
                }
            }
        } catch (Throwable t) {
            Logger.log("WatchSync > dedupLocal err: " + t);
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) {}
            if (db != null && db.isOpen()) try { db.close(); } catch (Throwable ignored) {}
        }
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
     * 本机全量历史对象（cid 锚定）：SQLite 直读全表，按记录自带的 cid 过滤到<b>本实例锚定的 cid 分区</b>。
     * 即 {@code SELECT * FROM History WHERE cid = 锚定cid}；锚定失败(anchorCid<0)时 fail-closed 返回空（入口已由 shouldSkipCid 拦截，此处兜底）。
     * 天然避开 {@code History.get()} 的 LIMIT 60 截断，也不受 cid 视图漂移影响（过滤条件恒定为锚定 cid）。
     */
    private List<Object> localHistoryFull() {
        List<Object> out = new ArrayList<>();
        SQLiteDatabase db = null;
        Cursor cur = null;
        try {
            File dbf = context.getDatabasePath("tv");
            if (dbf == null || !dbf.exists()) {
                Logger.log("WatchSync > SQLite 直读失败：库文件不存在 " + (dbf == null ? "null" : dbf.getPath()));
                return localHistoryFallback();
            }
            // 收集 History bean 里 boolean 类型的字段：Room 把 boolean 存成 INTEGER(0/1)，
            // 喂给 Gson objectFrom 时必须转成 true/false，否则数字 token 反序列化到 boolean 会抛 JsonSyntaxException。
            Set<String> boolFields = new java.util.HashSet<>();
            try {
                for (Field f : historyClass.getDeclaredFields()) {
                    if (f.getType() == boolean.class) boolFields.add(f.getName());
                }
                Logger.log("WatchSync > SQLite 直读 boolean 字段: " + boolFields);
            } catch (Throwable t) {
                Logger.log("WatchSync > SQLite 直读 反射 boolean 字段失败: " + t);
            }
            db = SQLiteDatabase.openDatabase(dbf.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            // 全量读表，按记录自带的 cid 过滤到本实例锚定的 cid 分区；anchorCid<0（锚定失败）fail-closed 返回空
            if (anchorCid >= 0) {
                cur = db.rawQuery("SELECT * FROM History WHERE cid = ?", new String[]{String.valueOf(anchorCid)});
            } else {
                Logger.log("WatchSync > localHistoryFull: 锚定失败(anchorCid<0) fail-closed 返回空，不同步");
                return out;
            }
            String[] cols = cur.getColumnNames();
            while (cur.moveToNext()) {
                JSONObject j = new JSONObject();
                for (String col : cols) {
                    int idx = cur.getColumnIndex(col);
                    if (idx < 0) continue;
                    if (cur.isNull(idx)) continue;
                    try {
                        int t = cur.getType(idx);
                        if (t == Cursor.FIELD_TYPE_STRING) {
                            j.put(col, cur.getString(idx));
                        } else if (t == Cursor.FIELD_TYPE_INTEGER) {
                            if (boolFields.contains(col)) {
                                j.put(col, cur.getInt(idx) != 0);   // 真布尔 true/false
                            } else {
                                j.put(col, cur.getLong(idx));
                            }
                        } else if (t == Cursor.FIELD_TYPE_FLOAT) {
                            j.put(col, cur.getDouble(idx));
                        } else if (t == Cursor.FIELD_TYPE_BLOB) {
                            j.put(col, android.util.Base64.encodeToString(cur.getBlob(idx), android.util.Base64.NO_WRAP));
                        }
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    Object obj = historyObjectFrom.invoke(null, j.toString());
                    if (obj != null) out.add(obj);
                } catch (Throwable t) {
                    Throwable c = t;
                    while (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) c = c.getCause();
                    Logger.log("WatchSync > SQLite 直读 objectFrom 失败，跳过一行: " + c);
                }
            }
            Logger.log("WatchSync > SQLite 直读 History 全量：" + out.size() + " 条");
            return out;
        } catch (Throwable t) {
            Logger.log("WatchSync > SQLite 直读失败，退化 get() 兑底: " + t);
            return localHistoryFallback();
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) {}
            if (db != null && db.isOpen()) try { db.close(); } catch (Throwable ignored) {}
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

    /**
     * 入库前的安全合并判断（纯 JSON + SQLite，不再走 bean 反射）：
     * 远端记录无进度（position<0 或 duration<=0，如仅点开未播放）时，
     * 不允许覆盖本机“已有进度”的同名记录，避免进度倒退。
     */
    private boolean canSafeMerge(JSONObject rec) {
        try {
            long pos = rec.optLong("position", -1L);
            long dur = rec.optLong("duration", 0L);
            if (pos >= 0 && dur > 0) return true;                    // 远端有进度 → 可写
            String vodName = rec.optString("vodName", "");
            if (vodName.isEmpty()) return true;
            SQLiteDatabase db = null;
            try {
                File dbf = context.getDatabasePath("tv");
                if (dbf == null || !dbf.exists()) return true;
                db = SQLiteDatabase.openDatabase(dbf.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                try (Cursor c = db.rawQuery("SELECT position, duration FROM History WHERE cid = ? AND vodName = ?",
                        new String[]{String.valueOf(anchorCid), vodName})) {
                    while (c.moveToNext()) {
                        if (c.getLong(0) >= 0 && c.getLong(1) > 0) {
                            Logger.log("WatchSync > 进度保护：无进度记录不覆盖本地有进度记录 name=" + vodName);
                            return false;
                        }
                    }
                }
                return true;
            } finally {
                if (db != null && db.isOpen()) try { db.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------------- 服务器文件读写 ----------------


    /** 读取远端同步文件全文；读取失败返回 null（调用方自行区分空文件与失败）。 */
    private String readRemote() {
        try {
            if (shouldSkipCid("readRemote")) return null;
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
            if (shouldSkipCid("writeRemote")) return;
            String b64 = android.util.Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            String cmd = "printf '%s' '" + b64 + "' | base64 -d > \"" + syncPath + ".tmp\" && mv \"" + syncPath + ".tmp\" \"" + syncPath + "\"";
            String res = drive.exec(cmd);
            Logger.log("WatchSync > writeRemote: 已写入 " + syncPath + "（json长度=" + json.length() + "，exec返回=[" + res + "]）");
        } catch (Throwable t) {
            Logger.log("WatchSync > write err: " + t);
        }
    }

    /**
     * 确保远端 syncPath 所在目录存在（递归创建）。
     * 目录不存在时 mkdir -p 会连同父目录一并创建；已存在则直接成功（幂等）。
     */
    private void ensureRemoteDir() {
        try {
            int slash = syncPath.lastIndexOf('/');
            if (slash < 0) return;                     // 无目录层级（如 watch.alice.txt 直接在根下）
            String dir = syncPath.substring(0, slash);
            if (dir.isEmpty()) return;
            String res = drive.exec("mkdir -p \"" + dir + "\"");
            Logger.log("WatchSync > ensureRemoteDir: " + dir + " res=[" + res + "]");
        } catch (Throwable t) {
            Logger.log("WatchSync > ensureRemoteDir err: " + t);
        }
    }
}