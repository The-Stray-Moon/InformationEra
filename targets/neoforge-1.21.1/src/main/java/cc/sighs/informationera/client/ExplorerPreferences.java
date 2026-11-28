package cc.sighs.informationera.client;

import cc.sighs.informationera.InformationEraNeoForge121;
import cc.sighs.informationera.disk.DiskFolder;
import cc.sighs.informationera.item.StorageCardItem;
import cc.sighs.informationera.net.DiskStateHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 资源管理器的客户端偏好，落盘在 {@code config/informationera-client.toml}。
 *
 * <p>导航栏「快速访问」里的条目按 Windows 文件资源管理器的跳转列表（Jump List）机制建模：
 * 一个文件夹在列表里只有**一条**记录，带固定状态、使用次数、最后访问时间——对应 Windows
 * 那个 {@code automaticDestinations-ms} 文件里 DestList 的一条 entry（pin status / 访问计数 /
 * 最后修改时间）。资源管理器每次打开一个文件夹，Shell 都会调 {@code SHAddToRecentDocs} 记一笔，
 * 记的就是这份 usage。
 *
 * <p>界面上的「常用文件夹」段就是**未固定**的条目按使用权重降序取前 {@link #MAX_DESTINATIONS} 条，
 * 固定项单独排在它前面。使用权重 = 使用次数按闲置时间指数衰减（半衰期见
 * {@link #USE_HALF_LIFE_DAYS}）：久不去的目录权重被时间摊薄，最后被挤出列表，与 Windows
 * 把老条目老化掉的行为一致。它看起来「既像最近访问、又像最常访问」正是这个原因——Windows
 * 官方措辞也只是 "accessed most recently and most frequently"，具体加权公式没有公开，
 * 这里是可解释的近似。
 *
 * <p>为什么放在客户端配置而不是服务端存档：这些是**玩家的界面习惯**（固定了哪些位置、常去
 * 哪些目录），换服务器也应该跟着走，而且不需要服务端知道。只存内存的话每次重启都会清空——
 * 那正是玩家看到「固定过的都没了」的原因。
 *
 * <p>同一个文件还保存资源管理器的显示习惯（窗格显隐/宽度、紧凑视图、复选框、扩展名）与
 * 每个文件夹的布局/排序/分组/列设置。全局设置和默认模板按**本机玩家 UUID** 保存，换世界、
 * 换服务器仍沿用同一套习惯；文件夹设置再带**储存卡 disk_uuid**，避免不同卡上相同的内置
 * 目录 id 互相套用。快速访问仍按原来的全局方式保存。
 */
@EventBusSubscriber(modid = InformationEraNeoForge121.MOD_ID, value = Dist.CLIENT)
public final class ExplorerPreferences {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 跳转列表条目，每条编码成 {@code 固定标志|使用次数|最后访问毫秒|文件夹 key}。 */
    private static final ModConfigSpec.ConfigValue<List<? extends String>> DESTINATIONS = BUILDER
            .comment("Quick access destinations: <pinned>|<uses>|<lastAccessMillis>|<folderKey>.")
            .defineListAllowEmpty("destinations", new ArrayList<String>(), () -> "", ExplorerPreferences::isValidEntry);

    // 下面两个是旧格式（固定项、最近访问各一条纯 key 列表）。留着声明只为把老配置迁进
    // destinations，迁移后会被清空，读的时候也只在 destinations 为空时才看它们。

    private static final ModConfigSpec.ConfigValue<List<? extends String>> LEGACY_PINNED = BUILDER
            .comment("Deprecated, migrated into 'destinations'.")
            .defineListAllowEmpty("pinned", new ArrayList<String>(), () -> "", ExplorerPreferences::isValidEntry);

    private static final ModConfigSpec.ConfigValue<List<? extends String>> LEGACY_RECENT = BUILDER
            .comment("Deprecated, migrated into 'destinations'.")
            .defineListAllowEmpty("recent", new ArrayList<String>(), () -> "", ExplorerPreferences::isValidEntry);

    /**
     * 资源管理器的显示状态，一行一条记录：
     * <ul>
     *   <li>{@code g|玩家|全局显示}：窗格显隐/宽度、紧凑视图、复选框、扩展名，跟随本机玩家跨世界。</li>
     *   <li>{@code d|玩家|视图}：该玩家的默认视图模板，新建/未设置过的文件夹使用。</li>
     *   <li>{@code f|玩家|磁盘|文件夹|视图}：某磁盘上某文件夹自己的覆盖设置。</li>
     * </ul>
     *
     * <p>磁盘字段是储存卡的 {@code disk_uuid} 十六进制串。不同卡上的根目录、预置分类目录
     * 使用相同 id，必须带磁盘身份才不会串用。和快速访问一样放在客户端配置里：这些是玩家的
     * 界面习惯，按本机玩家保存；快速访问仍是原来的全局跳转列表。
     */
    private static final ModConfigSpec.ConfigValue<List<? extends String>> VIEW_STATE = BUILDER
            .comment("Explorer display and per-folder view state. Global/default values follow the local player; "
                    + "folder overrides include the storage card disk uuid. Managed by the mod.")
            .defineListAllowEmpty("viewState", new ArrayList<String>(), () -> "", ExplorerPreferences::isValidEntry);

    /**
     * 筛选条件的使用历史，一行一条 {@code kind|value|uses|lastAccessMillis}。
     *
     * <p>标签、模组、创造标签页、关键词四类共用一张表，历史全存档通用，不按玩家/磁盘隔离。
     */
    private static final ModConfigSpec.ConfigValue<List<? extends String>> FILTER_USAGE = BUILDER
            .comment("Explorer filter usage history: kind|value|uses|lastAccessMillis. Shared by all worlds.")
            .defineListAllowEmpty("filterUsage", new ArrayList<String>(), () -> "", ExplorerPreferences::isValidEntry);

    /**
     * 保存的筛选模板，一行一个完整快照。模板全存档通用，不按玩家/磁盘分片。
     */
    private static final ModConfigSpec.ConfigValue<List<? extends String>> FILTER_TEMPLATES = BUILDER
            .comment("Saved explorer filter templates. Managed by the mod; shared by all worlds.")
            .defineListAllowEmpty("filterTemplates", new ArrayList<String>(), () -> "", ExplorerPreferences::isValidEntry);

    /**
     * 「常用文件夹」的条数上限。
     *
     * <p>固定四条：这一段的定位就是「最常用的那几个入口」，再多就退化成目录列表了。
     * 超出上限的条目从末尾丢弃——Windows 截断跳转列表也是「从下往上」。
     *
     * <p>只裁未固定的条目：固定项是玩家明确要留下的，不该被频繁项挤掉。
     */
    private static final int MAX_DESTINATIONS = 4;

    /**
     * 使用次数的半衰期（天）：闲置越久，历史使用次数在排序里的权重越低。
     *
     * <p>Windows 同样会把老条目老化掉，但衰减公式没有公开，这里取最直观的指数半衰期——一个
     * 目录闲置一个月，它的使用次数在排序里就只剩一半权重，闲置越久越容易被挤出列表。衰减只
     * 作用在排序和裁剪上，不会改写记录下来的次数本身。
     */
    private static final double USE_HALF_LIFE_DAYS = 30.0;

    /** 一天的毫秒数，衰减换算用。 */
    private static final double MILLIS_PER_DAY = 86_400_000.0;

    /** 条目内部字段的分隔符。 */
    private static final char FIELD_SEP = '|';

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** 内存镜像：界面读它，改完写回配置。顺序即展示顺序，固定项在前。 */
    private static final List<Destination> destinations = new ArrayList<>();
    private static boolean loaded;

    /** 显示偏好的内存存储；每个身份、每个文件夹各一条，修改时只动目标记录。 */
    private static ExplorerViewStore viewStore = new ExplorerViewStore();
    /** 筛选条件的使用历史（标签/模组/创造标签页/关键词共用），全存档通用。 */
    private static ExplorerFilterHistory filterHistory = new ExplorerFilterHistory();
    /** 保存的筛选模板；全存档通用。 */
    private static ExplorerFilterTemplateStore filterTemplateStore = new ExplorerFilterTemplateStore();
    /** 是否有尚未落盘的显示偏好修改。 */
    private static boolean viewDirty;
    /** 最近一次显示偏好修改的时间，用于延迟合并写盘。 */
    private static long viewChangedAt;
    /** 连续修改（例如拖动列宽）安静这么久之后才写配置。 */
    private static final long VIEW_FLUSH_DELAY_MS = 750L;

    private ExplorerPreferences() {
    }

    /** 一条跳转列表记录：一个文件夹 + 固定状态 + 使用次数 + 最后访问时间。 */
    private static final class Destination {
        private final String key;
        private boolean pinned;
        private int uses;
        private long lastAccess;

        private Destination(String key, boolean pinned, int uses, long lastAccess) {
            this.key = key;
            this.pinned = pinned;
            this.uses = uses;
            this.lastAccess = lastAccess;
        }
    }

    /** 配置加载/重载后同步到内存镜像。 */
    @SubscribeEvent
    public static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            syncFromConfig();
        }
    }

    @SubscribeEvent
    public static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            syncFromConfig();
        }
    }

    /**
     * 从配置读入内存镜像。
     *
     * <p>先全部读到局部变量、确认无异常后再提交到内存：配置未加载时 {@code get()} 会抛异常，
     * 如果边读边清，会在失败时把内存里的既有数据抹掉。
     */
    private static void syncFromConfig() {
        List<Destination> loadedDestinations = new ArrayList<>();
        for (String raw : DESTINATIONS.get()) {
            Destination destination = decode(raw);
            if (destination != null && indexOf(loadedDestinations, destination.key) < 0) {
                loadedDestinations.add(destination);
            }
        }
        // 旧配置迁移。只在 destinations 还是空的时候做，免得把玩家已经攒下的记录覆盖掉。
        boolean migrate = loadedDestinations.isEmpty();
        if (migrate) {
            long now = System.currentTimeMillis();
            for (String key : LEGACY_PINNED.get()) {
                if (isValidEntry(key) && indexOf(loadedDestinations, key) < 0) {
                    loadedDestinations.add(new Destination(key, true, 0, 0L));
                }
            }
            List<? extends String> legacyRecent = LEGACY_RECENT.get();
            for (int i = 0; i < legacyRecent.size(); i++) {
                String key = legacyRecent.get(i);
                if (isValidEntry(key) && indexOf(loadedDestinations, key) < 0) {
                    // 旧 recent 是「最近的在前」：越靠前说明时间越新，用递减的时间戳还原先后。
                    loadedDestinations.add(new Destination(key, false, 1, now - i));
                }
            }
            migrate = !loadedDestinations.isEmpty();
        }
        ExplorerViewStore loadedViews = ExplorerViewStore.decode(VIEW_STATE.get());
        ExplorerFilterHistory loadedFilters = ExplorerFilterHistory.decode(FILTER_USAGE.get());
        ExplorerFilterTemplateStore loadedTemplates = ExplorerFilterTemplateStore.decode(FILTER_TEMPLATES.get());
        // 全部读取都成功，才覆盖内存镜像。显示偏好若还有未落盘的修改，以内存为准，
        // 避免外部重载把玩家刚做的调整吞掉。
        destinations.clear();
        destinations.addAll(loadedDestinations);
        filterHistory = loadedFilters;
        filterTemplateStore = loadedTemplates;
        if (!viewDirty) {
            viewStore = loadedViews;
        }
        rank();
        loaded = true;
        if (migrate) {
            // 迁移完就把旧键清掉，之后只认 destinations。
            LEGACY_PINNED.set(new ArrayList<String>());
            LEGACY_RECENT.set(new ArrayList<String>());
            persist();
        }
    }

    /**
     * 确保配置已读入内存。
     *
     * <p>配置事件在某些启动顺序下可能晚于界面创建，所以界面取用前再兜一次。
     * 但**不能在配置尚未真正加载时就把 loaded 置为 true**：那样会读到默认空值并缓存下来，
     * 之后任何一次写入都会把玩家的配置清空。读失败就一直保持未加载状态。
     */
    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        try {
            syncFromConfig();
        } catch (IllegalStateException notLoadedYet) {
            // 配置还没加载好，保持 loaded = false，下次再试。
        }
    }

    /**
     * 每 tick 调用：显示偏好是高频操作（拖动列宽/窗格），延迟到修改安静下来再写盘，
     * 避免每次鼠标移动都触发一次配置文件保存。
     */
    public static void tick() {
        if (!viewDirty) {
            return;
        }
        if (System.currentTimeMillis() - viewChangedAt >= VIEW_FLUSH_DELAY_MS) {
            flush();
        }
    }

    /** 立即把待写的显示偏好落盘（窗口关闭、离开世界时调用）。 */
    public static void flush() {
        if (!viewDirty || !loaded) {
            return;
        }
        VIEW_STATE.set(viewStore.encode());
        SPEC.save();
        viewDirty = false;
    }

    private static void touchView() {
        viewDirty = true;
        viewChangedAt = System.currentTimeMillis();
    }

    /**
     * 本机玩家身份：只取玩家 UUID。
     *
     * <p>全局显示习惯和默认视图模板跟着玩家走，所以不能把服务器/存档编进来：
     * 同一个玩家进入不同世界时应该沿用同一套界面习惯。
     */
    static String playerIdentity() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? "anonymous" : mc.player.getUUID().toString();
    }

    /**
     * 当前磁盘身份：设备插槽里储存卡的 {@code disk_uuid}。
     *
     * <p>服务端按这张卡的 uuid 分片保存磁盘，根目录与内置分类目录 id 在不同卡上完全相同；
     * 文件夹视图必须带上这个稳定身份，不能用磁盘名（可重命名）或设备槽位推导。
     * 还没收到设备状态、或卡尚未绑定时返回 null，此时不读也不写任何文件夹设置。
     */
    static String diskIdentity() {
        ItemStack card = DiskStateHandler.device().card();
        UUID id = StorageCardItem.diskId(card);
        return id == null ? null : id.toString();
    }

    private static boolean isValidEntry(Object value) {
        return value instanceof String s && !s.isBlank();
    }

    // ------------------------------------------------------------ 编解码与排序

    /**
     * 解析一条记录。key 放在最后一个字段、用 {@code split(…, 4)} 切，这样 key 里出现分隔符
     * 也不会解析错。
     */
    private static Destination decode(String raw) {
        String[] parts = raw.split("\\" + FIELD_SEP, 4);
        if (parts.length < 4 || parts[3].isBlank()) {
            return null;
        }
        return new Destination(parts[3], "1".equals(parts[0].trim()),
                parseInt(parts[1]), parseLong(parts[2]));
    }

    private static String encode(Destination destination) {
        return (destination.pinned ? "1" : "0") + FIELD_SEP + destination.uses
                + FIELD_SEP + destination.lastAccess + FIELD_SEP + destination.key;
    }

    private static int parseInt(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private static long parseLong(String raw) {
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException malformed) {
            return 0L;
        }
    }

    /**
     * 一个条目当前的使用权重：历史使用次数按闲置时间指数衰减。
     *
     * <p>从没访问过就被固定的条目（次数与时间都是 0）权重为 0，取消固定后自然排在末尾。
     */
    private static double weight(Destination destination, long now) {
        double idleMillis = Math.max(0L, now - destination.lastAccess);
        return destination.uses * Math.pow(0.5, idleMillis / (USE_HALF_LIFE_DAYS * MILLIS_PER_DAY));
    }

    /**
     * 重排并裁剪：固定项在前（保持固定时的先后顺序），其余按 {@link #weight} 降序。
     *
     * <p>{@code List.sort} 是稳定排序，所以固定项之间的相对顺序不会被搅乱。
     */
    private static void rank() {
        long now = System.currentTimeMillis();
        destinations.sort((left, right) -> {
            if (left.pinned != right.pinned) {
                return left.pinned ? -1 : 1;
            }
            if (left.pinned) {
                return 0;
            }
            int byWeight = Double.compare(weight(right, now), weight(left, now));
            return byWeight != 0 ? byWeight : Long.compare(right.lastAccess, left.lastAccess);
        });
        int shown = 0;
        Iterator<Destination> iterator = destinations.iterator();
        while (iterator.hasNext()) {
            Destination destination = iterator.next();
            if (destination.pinned) {
                continue;
            }
            if (++shown > MAX_DESTINATIONS) {
                iterator.remove();
            }
        }
    }

    private static void persist() {
        List<String> raw = new ArrayList<>(destinations.size());
        for (Destination destination : destinations) {
            raw.add(encode(destination));
        }
        DESTINATIONS.set(raw);
        FILTER_USAGE.set(filterHistory.encode());
        FILTER_TEMPLATES.set(filterTemplateStore.encode());
        if (viewDirty) {
            VIEW_STATE.set(viewStore.encode());
        }
        SPEC.save();
        viewDirty = false;
    }

    private static int indexOf(List<Destination> list, String key) {
        if (key == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- 对外接口

    /** 固定项，按固定的先后顺序。 */
    public static List<String> pinned() {
        ensureLoaded();
        List<String> keys = new ArrayList<>();
        for (Destination destination : destinations) {
            if (destination.pinned) {
                keys.add(destination.key);
            }
        }
        return keys;
    }

    /** 常用项：未固定的条目，已按使用次数/最后访问排好并裁到上限。 */
    public static List<String> frequent() {
        ensureLoaded();
        List<String> keys = new ArrayList<>();
        for (Destination destination : destinations) {
            if (!destination.pinned) {
                keys.add(destination.key);
            }
        }
        return keys;
    }

    /** 固定项 + 常用项，任务栏跳转列表和地址栏补全用。 */
    public static List<String> quickAccess() {
        ensureLoaded();
        List<String> keys = new ArrayList<>(destinations.size());
        for (Destination destination : destinations) {
            keys.add(destination.key);
        }
        return keys;
    }

    public static boolean isPinned(String key) {
        ensureLoaded();
        int index = indexOf(destinations, key);
        return index >= 0 && destinations.get(index).pinned;
    }

    /**
     * 切换固定状态；返回切换后是否处于固定状态。
     *
     * <p>固定一个从没访问过的文件夹会新建一条记录：取消固定后它仍然留在「常用文件夹」里，
     * 与 Windows 取消固定后条目退回跳转列表的行为一致。
     */
    public static boolean togglePin(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        ensureLoaded();
        int index = indexOf(destinations, key);
        boolean nowPinned;
        if (index < 0) {
            destinations.add(new Destination(key, true, 0, 0L));
            nowPinned = true;
        } else {
            Destination destination = destinations.get(index);
            destination.pinned = !destination.pinned;
            nowPinned = destination.pinned;
        }
        rank();
        persist();
        return nowPinned;
    }

    /**
     * 记录一次访问：已有记录就累加使用次数并刷新时间，否则新建一条。
     *
     * <p>使用次数和最后访问时间就是 Windows 那边「最近 + 最常」两个维度的来源。
     */
    public static void record(String key) {
        if (key == null || key.isBlank() || DiskFolder.HOST_ID.equals(key)) {
            // 最上层的「玩家名」是视图造的虚拟目录，没必要占用常用列表的位置。
            return;
        }
        ensureLoaded();
        long now = System.currentTimeMillis();
        int index = indexOf(destinations, key);
        if (index < 0) {
            destinations.add(new Destination(key, false, 1, now));
        } else {
            Destination destination = destinations.get(index);
            if (destination.uses < Integer.MAX_VALUE) {
                destination.uses++;
            }
            destination.lastAccess = now;
        }
        rank();
        persist();
    }

    /** 丢弃已不存在的路径（例如磁盘被重置过）。 */
    public static void pruneUnknown(Predicate<String> exists) {
        ensureLoaded();
        boolean changed = destinations.removeIf(destination -> !exists.test(destination.key));
        String disk = diskIdentity();
        if (disk != null && viewStore.pruneFolders(playerIdentity(), disk, exists)) {
            touchView();
            changed = true;
        }
        if (changed) {
            persist();
        }
    }

    // ------------------------------------------------------------ 筛选历史

    /**
     * 记录一次筛选条件的使用。
     *
     * <p>筛选历史全存档通用：它描述的是玩家习惯（常用哪些标签/模组/创造标签页/关键词），
     * 与当前世界或磁盘无关，因此不像文件夹视图那样按玩家和磁盘分片。
     */
    public static void recordFilterUse(String kind, String value) {
        if (kind == null || kind.isBlank() || value == null || value.isBlank()) {
            return;
        }
        ensureLoaded();
        filterHistory.record(kind, value, System.currentTimeMillis());
        persist();
    }

    /** 某类筛选条件的高频值，按使用次数降序。 */
    public static List<String> frequentFilterValues(String kind, int limit) {
        ensureLoaded();
        return filterHistory.top(kind, limit);
    }

    /** 某类筛选条件的最近值，按最后使用时间降序。 */
    public static List<String> recentFilterValues(String kind, int limit) {
        ensureLoaded();
        return filterHistory.recent(kind, limit);
    }

    /** 全部模板名称，按名称排序，用于输入补全和“全部模板”。 */
    static List<String> filterTemplateNames() {
        ensureLoaded();
        return filterTemplateStore.names();
    }

    /** 按名称查找模板（不区分大小写）。 */
    static ExplorerFilterTemplate filterTemplate(String name) {
        ensureLoaded();
        return filterTemplateStore.find(name);
    }

    static boolean filterTemplateExists(String name) {
        ensureLoaded();
        return filterTemplateStore.exists(name);
    }

    /** 保存或显式覆盖模板；是否允许覆盖由 UI 的同名确认控制。 */
    static void saveFilterTemplate(ExplorerFilterTemplate template) {
        if (template == null || !template.hasName()) {
            return;
        }
        ensureLoaded();
        filterTemplateStore.save(template);
        persist();
    }

    // ------------------------------------------- 显示偏好（玩家 / 磁盘两级作用域）

    /** 本机玩家的全局显示设置。 */
    public static ExplorerDisplayState display() {
        ensureLoaded();
        return viewStore.display(playerIdentity());
    }

    /**
     * 只修改全局显示设置里的指定字段，再返回最终状态。
     *
     * <p>多个窗口同时打开时，每个窗口都基于存储里的最新记录改自己的那一项，
     * 不会用本地快照整体覆盖另一个窗口刚写进去的设置。
     */
    public static ExplorerDisplayState updateDisplay(Consumer<ExplorerDisplayState> mutation) {
        ensureLoaded();
        String player = playerIdentity();
        ExplorerDisplayState before = viewStore.display(player);
        ExplorerDisplayState state = before.copy();
        mutation.accept(state);
        state.normalize();
        viewStore.setDisplay(player, state);
        if (!state.equals(before)) {
            touchView();
        }
        return viewStore.display(player);
    }

    /**
     * 当前磁盘上某文件夹最终生效的视图设置。
     *
     * <p>磁盘身份尚未到达时不读写任何磁盘相关记录，只返回本机玩家的默认模板；
     * 设备状态到达后窗口会重新调用这里。
     */
    public static ExplorerViewState viewState(String folderKey) {
        ensureLoaded();
        String player = playerIdentity();
        String disk = diskIdentity();
        if (disk == null) {
            return viewStore.defaultView(player);
        }
        return viewStore.view(player, disk, normalizeFolderKey(folderKey));
    }

    /**
     * 只修改当前文件夹视图里的指定字段，再返回最终状态。
     *
     * <p>按「玩家 + 磁盘 + 文件夹」先读最新记录再改，避免两个窗口同时调整同一文件夹、
     * 或切换磁盘时把别的盘设置覆盖掉。
     */
    public static ExplorerViewState updateView(String folderKey, Consumer<ExplorerViewState> mutation) {
        ensureLoaded();
        String player = playerIdentity();
        String disk = diskIdentity();
        String key = normalizeFolderKey(folderKey);
        if (disk == null) {
            // 设备状态还没到：只更新临时显示，不落任何磁盘相关记录。
            ExplorerViewState transientState = viewStore.defaultView(player);
            mutation.accept(transientState);
            transientState.normalize();
            return transientState;
        }
        ExplorerViewState before = viewStore.view(player, disk, key);
        ExplorerViewState state = before.copy();
        mutation.accept(state);
        state.normalize();
        viewStore.setView(player, disk, key, state);
        if (!state.equals(before)) {
            touchView();
        }
        return viewStore.view(player, disk, key);
    }

    /** 把当前文件夹的生效视图存为本机玩家的默认模板。 */
    public static void setDefaultView(String folderKey) {
        ensureLoaded();
        String player = playerIdentity();
        String disk = diskIdentity();
        if (disk == null) {
            return;
        }
        ExplorerViewState before = viewStore.defaultView(player);
        ExplorerViewState next = viewStore.view(player, disk, normalizeFolderKey(folderKey));
        if (next.equals(before)) {
            return;
        }
        viewStore.setDefaultView(player, next);
        touchView();
    }

    /** 清除当前磁盘上当前文件夹的独立设置，让它回到默认模板。 */
    public static void resetFolderView(String folderKey) {
        ensureLoaded();
        String disk = diskIdentity();
        if (disk != null && viewStore.resetView(playerIdentity(), disk, normalizeFolderKey(folderKey))) {
            touchView();
        }
    }

    /** 清除当前磁盘上所有文件夹的独立设置（默认模板保留）。 */
    public static void resetAllFolderViews() {
        ensureLoaded();
        String disk = diskIdentity();
        if (disk != null && viewStore.resetAllViews(playerIdentity(), disk)) {
            touchView();
        }
    }

    private static String normalizeFolderKey(String folderKey) {
        return folderKey == null || folderKey.isBlank() ? DiskFolder.ROOT_ID : folderKey;
    }
}
