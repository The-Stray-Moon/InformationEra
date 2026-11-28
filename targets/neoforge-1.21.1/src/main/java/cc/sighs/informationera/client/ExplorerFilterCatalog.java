package cc.sighs.informationera.client;

import cc.sighs.informationera.InformationEraNeoForge121;
import cc.sighs.informationera.disk.ItemKey;
import cc.sighs.informationera.util.ItemStacks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 筛选条件的实时目录：物品 → 模组 / 标签 / 创造模式标签页。
 *
 * <p>标签来自物品栈实际持有的 {@code TagKey}，创造页来自
 * {@link BuiltInRegistries#CREATIVE_MODE_TAB} 的展示物品。两者都会在数据包 / 标签同步后
 * 变化，因此 {@link TagsUpdatedEvent} 到达时整体失效缓存，下一次筛选重新读取。
 *
 * <p>任何单个物品、标签或创造页读取失败都只影响它自己：这里逐项捕获异常，返回可用的
 * 空集合，绝不让筛选界面因为一个异常物品打不开。
 */
@EventBusSubscriber(modid = InformationEraNeoForge121.MOD_ID, value = Dist.CLIENT)
public final class ExplorerFilterCatalog {

    /** 一个物品参与筛选的事实。 */
    static final class Facts {
        static final Facts EMPTY = new Facts("", Set.of(), Set.of());

        final String modId;
        final Set<String> tags;
        final Set<String> tabs;

        Facts(String modId, Set<String> tags, Set<String> tabs) {
            this.modId = modId;
            this.tags = tags;
            this.tabs = tabs;
        }
    }

    private static final Map<String, Facts> FACTS = new LinkedHashMap<>();
    private static final Map<String, Set<String>> TABS_BY_ITEM = new LinkedHashMap<>();
    private static boolean creativeLoaded;

    private ExplorerFilterCatalog() {
    }

    /** 标签或创造页数据变化：清空全部派生缓存。 */
    static void invalidate() {
        FACTS.clear();
        TABS_BY_ITEM.clear();
        creativeLoaded = false;
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        // 集成服务器也会触发这个事件；只有客户端主线程才能安全重建缓存和刷 DOM。
        // 客户端收到标签包时还会再触发一次，不会漏掉刷新。
        if (!net.minecraft.client.Minecraft.getInstance().isSameThread()) {
            return;
        }
        invalidate();
        for (FileExplorerController controller : new ArrayList<>(FileExplorerWindow.getWindows())) {
            try {
                controller.onFilterDataChanged();
            } catch (RuntimeException brokenWindow) {
                // 单个窗口的 DOM 状态异常不能打断标签同步或其它窗口的刷新。
            }
        }
    }

    /** 物品条目的筛选事实；文件夹或缺少物品 id 时返回空事实。 */
    static Facts facts(VirtualFileSystem.Entry entry) {
        if (entry == null || entry.folder || entry.itemId == null || entry.itemId.isBlank()) {
            return Facts.EMPTY;
        }
        return facts(entry.itemId, entry.itemKey);
    }

    private static Facts facts(String itemId, ItemKey key) {
        Facts cached = FACTS.get(itemId);
        if (cached != null) {
            return cached;
        }
        Facts built = buildFacts(itemId, key);
        // 创造页还没重建成功时（例如尚未进入世界），只返回本次结果，不能把空的 tabs
        // 永久写进缓存；否则之后即使重建成功，这个物品也会一直缺少创造页条件。
        if (creativeLoaded) {
            FACTS.put(itemId, built);
        }
        return built;
    }

    private static Facts buildFacts(String itemId, ItemKey key) {
        try {
            String modId = namespaceOf(itemId);
            Set<String> tags = new LinkedHashSet<>();
            ItemKey stackKey = key == null ? new ItemKey(itemId, "") : key;
            ItemStack stack = ItemStacks.stackOf(stackKey, 1, DiskClient.registryProvider());
            if (!stack.isEmpty()) {
                stack.getTags().forEach(tag -> {
                    ResourceLocation id = tag.location();
                    if (id != null) {
                        tags.add(id.toString());
                    }
                });
            }
            ensureCreativeTabs();
            Set<String> tabs = TABS_BY_ITEM.get(itemId);
            return new Facts(modId, Set.copyOf(tags), tabs == null ? Set.of() : Set.copyOf(tabs));
        } catch (RuntimeException | LinkageError brokenItem) {
            return new Facts(namespaceOf(itemId), Set.of(), Set.of());
        }
    }

    private static String namespaceOf(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "";
        }
        int colon = itemId.indexOf(':');
        return colon > 0 ? itemId.substring(0, colon) : "minecraft";
    }

    /**
     * 构建物品 → 创造页的反向索引；只在数据失效后重建一次。
     *
     * <p>创造页的物品列表由 {@link CreativeModeTabs#tryRebuildTabContents} 在打开创造背包时
     * 才填充，生存模式通常永远走不到那一步。这里主动按当前世界参数重建一次，否则筛选弹窗
     * 里的创造页候选会一直是空的。重建失败或还没进世界时保持未加载，下一次查询再试。
     */
    private static void ensureCreativeTabs() {
        if (creativeLoaded) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !minecraft.isSameThread()) {
            return;
        }
        try {
            CreativeModeTabs.tryRebuildTabContents(
                    minecraft.level.enabledFeatures(),
                    minecraft.player.canUseGameMasterBlocks(),
                    minecraft.level.registryAccess());
        } catch (RuntimeException | LinkageError brokenRebuild) {
            // 世界数据还不完整：继续用现有展示物品，失败时下面也不会把状态锁死。
        }
        int indexed = 0;
        try {
            for (Map.Entry<ResourceKey<CreativeModeTab>, CreativeModeTab> entry
                    : BuiltInRegistries.CREATIVE_MODE_TAB.entrySet()) {
                CreativeModeTab tab = entry.getValue();
                if (tab == null || entry.getKey() == null) {
                    continue;
                }
                String tabId = entry.getKey().location().toString();
                Collection<ItemStack> displayItems;
                try {
                    displayItems = tab.getDisplayItems();
                } catch (RuntimeException brokenTab) {
                    continue;
                }
                if (displayItems == null) {
                    continue;
                }
                for (ItemStack stack : displayItems) {
                    if (stack == null || stack.isEmpty()) {
                        continue;
                    }
                    try {
                        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                        if (itemId != null) {
                            TABS_BY_ITEM.computeIfAbsent(itemId.toString(), id -> new LinkedHashSet<>()).add(tabId);
                            indexed++;
                        }
                    } catch (RuntimeException brokenItem) {
                        // 单个展示物品读不到就跳过，不影响同页其它物品。
                    }
                }
            }
        } catch (RuntimeException | LinkageError brokenRegistry) {
            // 注册表尚未就绪：清掉可能写了一半的索引，下一次失效后会重试。
            TABS_BY_ITEM.clear();
            creativeLoaded = false;
            return;
        }
        // 一个物品都没索引到时不要永久标记完成，下一次查询会再尝试重建。
        creativeLoaded = indexed > 0;
        if (!creativeLoaded) {
            TABS_BY_ITEM.clear();
        }
    }

    /**
     * 条件的显示文本：标签和模组一律显示实际英文 id（存储、匹配、搜索都用同一份值），
     * 只有创造模式标签页换成玩家语言的标题。
     */
    static String label(ExplorerFilterSelection.Kind kind, String value) {
        if (value == null) {
            return "";
        }
        if (kind != ExplorerFilterSelection.Kind.TAB) {
            return value;
        }
        // 每次现取标题：语言文件重载后不需要额外的失效通知，配置里保存的仍是稳定 registry id。
        try {
            ResourceLocation id = ResourceLocation.tryParse(value);
            CreativeModeTab tab = id == null ? null : BuiltInRegistries.CREATIVE_MODE_TAB.get(id);
            if (tab != null) {
                String label = tab.getDisplayName().getString();
                if (label != null && !label.isBlank()) {
                    return label;
                }
            }
        } catch (RuntimeException | LinkageError brokenLabel) {
            // 创造页被移除或注册表未就绪：退回显示稳定 id。
        }
        return value;
    }

    /**
     * 当前范围内全部物品的候选值。
     *
     * <p>候选只从范围本身提取，不套用已选条件，也不套用搜索词——否则勾选一个条件后，
     * 弹窗里的候选会被自己的过滤结果越缩越小，玩家就再也加不回其它条件了。
     */
    static List<String> candidates(Collection<VirtualFileSystem.Entry> entries,
                                   ExplorerFilterSelection.Kind kind) {
        if (entries == null || entries.isEmpty() || kind == null) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (VirtualFileSystem.Entry entry : entries) {
            if (entry == null || entry.folder) {
                continue;
            }
            Facts facts = facts(entry);
            switch (kind) {
                case TAG -> values.addAll(facts.tags);
                case MOD -> {
                    if (!facts.modId.isBlank()) {
                        values.add(facts.modId);
                    }
                }
                case TAB -> values.addAll(facts.tabs);
            }
        }
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(Comparator
                .comparing((String value) -> label(kind, value), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Comparator.naturalOrder()));
        return sorted;
    }
}
