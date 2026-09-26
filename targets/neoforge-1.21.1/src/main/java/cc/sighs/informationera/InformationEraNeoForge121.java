package cc.sighs.informationera;

import cc.sighs.informationera.client.InformationEraClientEvents;
import cc.sighs.informationera.server.PlayerDiskStore;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(InformationEraNeoForge121.MOD_ID)
public final class InformationEraNeoForge121 {
    public static final String MOD_ID = "informationera";

    /** 每多少 tick 把脏掉的磁盘落盘一次，避免每次操作都写盘。 */
    private static final int SAVE_INTERVAL_TICKS = 200;

    private int saveTimer;

    public InformationEraNeoForge121(IEventBus modEventBus, net.neoforged.fml.ModContainer modContainer, Dist dist) {
        // 物品注册两端都要做：客户端也要有物品定义才能画出图标。
        cc.sighs.informationera.item.ModItems.register(modEventBus);
        if (dist.isClient()) {
            InformationEraClientEvents.register(modEventBus);
            // 客户端偏好（快速访问的固定项、常用文件夹记录）落盘在 config/informationera-client.toml。
            modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT,
                    cc.sighs.informationera.client.ExplorerPreferences.SPEC);
        }
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    /** 定期把标记为脏的磁盘刷进存档数据；真正写文件由 SavedData 自己按需完成。 */
    private void onServerTick(ServerTickEvent.Post event) {
        if (++saveTimer < SAVE_INTERVAL_TICKS) {
            return;
        }
        saveTimer = 0;
        PlayerDiskStore store = PlayerDiskStore.get(event.getServer());
        if (store != null) {
            store.flush();
        }
    }

    /** 服务器停止前强制刷一次，避免丢失最后一段时间的变更。 */
    private void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        PlayerDiskStore store = PlayerDiskStore.get(server);
        if (store != null) {
            store.flush();
            store.setDirty();
        }
    }
}
