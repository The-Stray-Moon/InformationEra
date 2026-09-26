package cc.sighs.informationera.item;

import cc.sighs.informationera.InformationEraNeoForge121;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的物品注册。
 *
 * <p>目前只有两件：储存卡（给磁盘加容量）和量子坍缩反应炉（负责虚实转换的核心饰品）。
 * 两件物品都只在这里定义、只注册一次，服务端和客户端都要注册——少了客户端那一半，
 * 物品栏里的图标就渲染不出来。
 */
public final class ModItems {

    private ModItems() {
    }

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(InformationEraNeoForge121.MOD_ID);

    /** 储存卡：可以叠一叠带着走，每张都给磁盘加一份容量。 */
    public static final DeferredItem<StorageCardItem> STORAGE_CARD = ITEMS.registerItem(
            "storage_card", StorageCardItem::new, new Item.Properties().stacksTo(16));

    /** 量子坍缩反应炉：核心饰品，一次只带一个。 */
    public static final DeferredItem<QuantumCollapseReactorItem> QUANTUM_COLLAPSE_REACTOR = ITEMS.registerItem(
            "quantum_collapse_reactor", QuantumCollapseReactorItem::new, new Item.Properties().stacksTo(1));

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        ModDataComponents.register(modEventBus);
        modEventBus.addListener(ModItems::addToCreativeTab);
    }

    /** 放进原版的「工具与实用物品」页，省得只能用指令拿。 */
    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(STORAGE_CARD);
            event.accept(QUANTUM_COLLAPSE_REACTOR);
        }
    }
}
