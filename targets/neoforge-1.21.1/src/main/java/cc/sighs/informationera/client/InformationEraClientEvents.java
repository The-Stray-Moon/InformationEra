package cc.sighs.informationera.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/** 客户端按键注册（mod 事件总线，仅物理客户端）。 */
public final class InformationEraClientEvents {

    public static final KeyMapping OPEN_FILE_EXPLORER = new KeyMapping(
            "key.informationera.file_explorer",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.informationera");

    private InformationEraClientEvents() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(InformationEraClientEvents::registerKeyMappings);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_FILE_EXPLORER);
    }
}
