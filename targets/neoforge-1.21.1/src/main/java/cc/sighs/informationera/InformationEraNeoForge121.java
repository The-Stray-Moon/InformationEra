package cc.sighs.informationera;

import cc.sighs.informationera.client.InformationEraClientEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(InformationEraNeoForge121.MOD_ID)
public final class InformationEraNeoForge121 {
    public static final String MOD_ID = "informationera";

    public InformationEraNeoForge121(IEventBus modEventBus, Dist dist) {
        if (dist.isClient()) {
            InformationEraClientEvents.register(modEventBus);
        }
    }
}