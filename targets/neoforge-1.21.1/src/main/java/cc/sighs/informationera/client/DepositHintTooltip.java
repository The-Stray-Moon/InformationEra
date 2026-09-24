package cc.sighs.informationera.client;

import cc.sighs.informationera.InformationEraNeoForge121;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;

/**
 * 在物品提示框开头补一行「Alt+左键 存入」的操作说明。
 *
 * <p>只在资源管理器窗口**正在显示**时插入：窗口没开、或已被最小化时，Alt+左键并不会把物品
 * 存进任何地方，此时提示只会误导玩家。
 *
 * <p>用 {@link ItemTooltipEvent} 而不是自己画提示：这个事件对**所有**物品提示都生效，
 * 不需要逐个界面去挂钩子。
 */
@EventBusSubscriber(modid = InformationEraNeoForge121.MOD_ID, value = Dist.CLIENT)
public final class DepositHintTooltip {

    private DepositHintTooltip() {
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!FileExplorerWindow.isAnyWindowShowing()) {
            return;
        }
        List<Component> lines = event.getToolTip();
        if (lines == null) {
            return;
        }
        // 插到最前面：这是操作说明，应该先于物品自身的属性被看到。
        // 用灰色区分于物品数据，避免被误读成物品的一条属性。
        lines.add(0, Component.translatable("informationera.tooltip.alt_deposit")
                .withStyle(ChatFormatting.GRAY));
    }
}
