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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
 *   <li><b>只在内容真正变化时才写远端</b>，减少 30 秒拉取与本地轮询之间的写放大抖动。</li>
 * </ol>
 *
 * <p><i>说明：因删除同步被移除，若远端某条记录被任一设备保留，则其它设备即便本地删除，也会在下次 pull 时被重新拉回。</i></p>
 */
public class WatchSync {

    /** 目标主机应用里的 History bean 的默认类名（作为最后兜底候选）。 */
    private static final String HISTORY_CLS = "com.fongmi.android.tv.bean.History";

    /** 本地轮询周期（毫秒）：本机记录变化即触发推送。 */
    private static final long PUSH_POLL_MS = 3000;
    /** 定期拉取远端文件的周期（秒）。 */
    private static final long PULL_PERIOD_SEC = 30;
    /** 远端最多保留的记录条数；超出时按 createTime 冲掉最旧的。 */
    private static final int REMOTE_MAX = 60;

    private final Context context;
    private final Drive drive;
    private final String username;
    /** 远端同步文件路径（已按用户名隔离）。 */
    private final String syncPath;

    /** 单线程调度器：push/pull/本地轮询都在同一线程串行执行，避免并发竞争。 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /** 本机记录快照（片名|createTime|position|duration），用于轮询比对是否变化。 */
    private List<String> lastSnapshot = Collections.emptyList();

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

    private Object appDb;                   // AppDatabase 单例实例
    private Method daoGetter;               // AppDatabase 上取 HistoryDao 的方法
    private Method daoFindAll;              // HistoryDao 上取全量的方法（findAll/getAll）
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
     * 解析宿主反射 → 启动本地轮询 + 定时拉取 → 立即拉一次。
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
            // 启动先拉一次，让远端已有记录尽快并入本机
            ws.scheduler.execute(ws::pull);
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
        // 真机 AppDatabase.get() 可能因混淆/改签名找不到，故做多候选兼容：
        //   单例方法逐个试 get/getInstance/getDatabase/getDb；DAO 方法在实例上按返回类型扫；
        //   findAll 在 DAO 实现上找“无参返回 List”的方法（findAll/getAll/loadAll）。
        // 任何一步失败都整体降级为 get() 兜底，不影响主流程。
        appDb = null;
        daoGetter = null;
        daoFindAll = null;
        try {
            Class<?> dbCls = Class.forName(appPkg + ".db.AppDatabase");
            for (String m : new String[]{"get", "getInstance", "getDatabase", "getDb"}) {
                try {
                    appDb = dbCls.getMethod(m).invoke(null);
                    if (appDb != null) break;
                } catch (Throwable ignored) {
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
     * 本地轮询：每 3 秒比对一次本机记录快照，发生变化即触发 push。
     * 取代 FileObserver 文件监听（真机上文件事件不可靠/不触发）。
     */
    private void pollLocal() {
        try {
            List<?> local = localHistoryFull();
            List<String> sig = snapshotOf(local);
            if (!sig.equals(lastSnapshot)) {
                lastSnapshot = sig;
                Logger.log("WatchSync > 本地记录变化(" + sig.size() + "条)，触发 push");
                push();
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
     *   - 本地 3 秒轮询（push 触发）；
     *   - 远端 30 秒拉取（pull）。
     */
    private void schedule() {
        scheduler.scheduleWithFixedDelay(this::pollLocal, PUSH_POLL_MS, PUSH_POLL_MS, TimeUnit.MILLISECONDS);
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

    // ---------------- 推送 ----------------

    /**
     * 把本机当前记录合并进远端文件。
     * 因删除同步已移除，这里只做「记录并集」（远端 + 本机，同名取 createTime 较新），不再生成任何墓碑。
     */
    private void push() {
        try {
            List<?> local = localHistoryFull();   // 本机全量（findAll，非 60 条）
            String raw = readRemote();
            String json = merge(local, raw);
            // 内容未变化则不重写远端，避免 30s 周期与 FileObserver 互相放大的写抖动
            if (json.equals(raw)) return;
            writeRemote(json);
        } catch (Throwable t) {
            Logger.log("WatchSync > push err: " + t);
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
        return capToLatest(merged).toString();
    }

    /**
     * 远端最多保留 {@link #REMOTE_MAX} 条记录：按每条记录的 createTime 新→旧排序，
     * 只保留最新的 {@link #REMOTE_MAX} 条，超出部分（最旧的）直接冲掉。
     */
    private JSONArray capToLatest(JSONArray merged) {
        try {
            if (merged.length() <= REMOTE_MAX) return merged;
            // 索引按 createTime 升序（旧→新）排序，再取尾部（最新）REMOTE_MAX 条
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < merged.length(); i++) order.add(i);
            order.sort((a, b) -> Long.compare(createTimeOf(merged.optJSONObject(a)), createTimeOf(merged.optJSONObject(b))));
            JSONArray out = new JSONArray();
            int start = Math.max(0, order.size() - REMOTE_MAX);
            for (int i = start; i < order.size(); i++) out.put(merged.optJSONObject(order.get(i)));
            Logger.log("WatchSync > 远端超出" + REMOTE_MAX + "条，保留最新" + out.length() + "条（冲掉" + (merged.length() - out.length()) + "条最旧）");
            return out;
        } catch (Throwable t) {
            Logger.log("WatchSync > capToLatest err: " + t);
            return merged;
        }
    }

    /** 取一条 wrap 对象（含 {"history":{...}}）里 history 的 createTime。 */
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

    // ---------------- 拉取 ----------------

    /**
     * 周期拉取远端记录并应用到本机：
     * 读远端 → 进度保护过滤 → 用 History.sync() 并入本机 → 收敛对账（把本机新增记录也并回远端）。
     * 不再解析/应用任何墓碑。
     */
    private void pull() {
        try {
            String raw = readRemote();
            if (raw == null) {
                Logger.log("WatchSync > pull: 远端读取失败");
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

            Logger.log("WatchSync > pull 完成，远端总数=" + arr.length() + " 属于本用户=" + mine.size());
            if (!mine.isEmpty()) {
                historySync.invoke(null, mine);
            }
            reconcile(raw);
        } catch (Throwable t) {
            Logger.log("WatchSync > pull err: " + t);
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