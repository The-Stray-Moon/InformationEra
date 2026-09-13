package cc.sighs.informationera.client;

import cc.sighs.informationera.InformationEraNeoForge121;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** 客户端每 tick 检查按键（游戏事件总线，仅物理客户端）。 */
@EventBusSubscriber(modid = InformationEraNeoForge121.MOD_ID, value = Dist.CLIENT)
public final class InformationEraClientTick {

    private InformationEraClientTick() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        FileExplorerTrigger.tick();
        // 一切交互只在世界里生效：主菜单/创建世界界面上没有可操作的磁盘。
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            // 离开世界时把窗口收掉，否则会悬在标题界面上。
            if (FileExplorerWindow.windowCount() > 0) {
                FileExplorerWindow.closeAll();
            }
            // 丢弃标题界面上积压的按键，避免进世界后突然开窗。
            while (InformationEraClientEvents.OPEN_FILE_EXPLORER.consumeClick()) {
                // 故意忽略。
            }
            return;
        }
        FileExplorerWindow.dragTick();
        FileExplorerWindow.tabDragTick();
        FileExplorerWindow.tooltipTick();
        while (InformationEraClientEvents.OPEN_FILE_EXPLORER.consumeClick()) {
            FileExplorerWindow.open();
        }
    }
}
