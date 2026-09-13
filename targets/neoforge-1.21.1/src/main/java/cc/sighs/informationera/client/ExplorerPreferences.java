package cc.sighs.informationera.client;

import cc.sighs.informationera.InformationEraNeoForge121;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 资源管理器的客户端偏好，落盘在 {@code config/informationera-client.toml}。
 *
 * <p>为什么放在客户端配置而不是服务端存档：这些是**玩家的界面习惯**（固定了哪些位置、
 * 最近访问过什么），换服务器也应该跟着走，而且不需要服务端知道。
 *
 * <p>导航栏的「快速访问」和「最近使用」如果只存内存，每次重启客户端都会清空——
 * 那正是玩家看到「固定过的都没了」的原因。
 */
@EventBusSubscriber(modid = InformationEraNeoForge121.MOD_ID, value = Dist.CLIENT)
public final class ExplorerPreferences {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 固定到「快速访问」的文件夹 id，按固定顺序保存。 */
    private static final ModConfigSpec.ConfigValue<List<? extends String>> PINNED = BUILDER
            .comment("Folders pinned to the Quick access section, by folder id.")
            .defineListAllowEmpty("pinned", new ArrayList<String>(), () -> "", ExplorerPreferences::isValidEntry);

    /** 最近访问过的文件夹 id，最近的在前。 */
    private static final ModConfigSpec.ConfigValue<List<? extends String>> RECENT = BUILDER
            .comment("Recently visited folder ids, most recent first.")
            .defineListAllowEmpty("recent", new ArrayList<String>(), () -> "", ExplorerPreferences::isValidEntry);

    /** 最近访问记录的上限，与内存里的裁剪保持一致。 */
    private static final int RECENT_LIMIT = 15;

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** 内存镜像：界面读它，改完写回配置。 */
    private static final Set<String> pinned = new LinkedHashSet<>();
    private static final List<String> recent = new ArrayList<>();
    private static boolean loaded;

    private ExplorerPreferences() {
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
        List<String> pinnedLoaded = new ArrayList<>();
        for (String value : PINNED.get()) {
            if (isValidEntry(value) && !pinnedLoaded.contains(value)) {
                pinnedLoaded.add(value);
            }
        }
        List<String> recentLoaded = new ArrayList<>();
        for (String value : RECENT.get()) {
            if (isValidEntry(value) && !recentLoaded.contains(value)) {
                recentLoaded.add(value);
            }
        }
        // 两次读取都成功，才覆盖内存镜像。
        pinned.clear();
        pinned.addAll(pinnedLoaded);
        recent.clear();
        recent.addAll(recentLoaded);
        loaded = true;
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

    private static boolean isValidEntry(Object value) {
        return value instanceof String s && !s.isBlank();
    }

    // ---------------------------------------------------------------- 固定项

    public static List<String> pinned() {
        ensureLoaded();
        return new ArrayList<>(pinned);
    }

    public static boolean isPinned(String key) {
        ensureLoaded();
        return key != null && pinned.contains(key);
    }

    /** 切换固定状态；返回切换后是否处于固定状态。 */
    public static boolean togglePin(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        ensureLoaded();
        boolean nowPinned;
        if (pinned.contains(key)) {
            pinned.remove(key);
            nowPinned = false;
        } else {
            pinned.add(key);
            nowPinned = true;
        }
        persistPinned();
        return nowPinned;
    }

    private static void persistPinned() {
        PINNED.set(new ArrayList<>(pinned));
        SPEC.save();
    }

    // ---------------------------------------------------------------- 最近访问

    public static List<String> recent() {
        ensureLoaded();
        return new ArrayList<>(recent);
    }

    /**
     * 记录一次访问：去重、最近的在前、超出上限裁剪。
     *
     * <p>写盘做了节流——导航很频繁，每次都写文件没有必要。
     */
    public static void recordRecent(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        ensureLoaded();
        boolean changed = recent.remove(key);
        recent.add(0, key);
        while (recent.size() > RECENT_LIMIT) {
            recent.remove(recent.size() - 1);
            changed = true;
        }
        if (changed || recent.indexOf(key) != 0) {
            persistRecent();
        }
    }

    private static void persistRecent() {
        RECENT.set(new ArrayList<>(recent));
        SPEC.save();
    }

    /** 丢弃已不存在的路径（例如磁盘被重置过）。 */
    public static void pruneUnknown(java.util.function.Predicate<String> exists) {
        ensureLoaded();
        boolean changed = pinned.removeIf(key -> !exists.test(key));
        changed |= recent.removeIf(key -> !exists.test(key));
        if (changed) {
            persistPinned();
            persistRecent();
        }
    }
}
