package cc.sighs.informationera.client;

import cc.sighs.informationera.InformationEraNeoForge121;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;

/**
 * 在物品提示框开头补一行「Alt+左键 存入」的操作说明。
 *
 * <p>只在资源管理器窗口**正在显示**、且鼠标下确实是一个容器槽位时插入：窗口没开、已被最小化，
 * 或者当前界面没有槽位时，Alt+左键并不会把物品存进任何地方，此时提示只会误导玩家。
 *
 * <p>资源管理器自己悬停物品时显示的也是原版提示框，但那里 Alt+左键是「取出」而不是「存入」，
 * 所以绘制资源管理器提示框期间（{@link ExplorerItemTooltip#isRendering()}）一律不插入这一行。
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
        if (ExplorerItemTooltip.isRendering()) {
            return;
        }
        if (!FileExplorerWindow.isAnyWindowShowing()) {
            return;
        }
        // 存入只发生在容器槽位上；其它界面的物品提示（例如资源管理器条目、创造模式搜索）
        // 没有可以存入的槽位，不该出现这条说明。判定标准与 ContainerSlotInterop 一致：
        // 鼠标下必须真的有一个有物品的槽位，按 Alt+左键才会生效。
        if (!(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        Slot slot = screen.getSlotUnderMouse();
        if (slot == null || !slot.hasItem()) {
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
