package cc.sighs.informationera.client;

import cc.sighs.informationera.InformationEraNeoForge121;
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
        FileExplorerWindow.dragTick();
        while (InformationEraClientEvents.OPEN_FILE_EXPLORER.consumeClick()) {
            FileExplorerWindow.open();
        }
    }
}
