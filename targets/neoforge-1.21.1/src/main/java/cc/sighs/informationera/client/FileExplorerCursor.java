package cc.sighs.informationera.client;

import cc.sighs.informationera.InformationEraNeoForge121;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.render.Operation;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 兜底光标：AUI 的 CSS cursor 逻辑链正常，但 {@code glfwSetCursor} 在 persistent overlay
 * 环境下没有真正反映到系统光标。这里在渲染结束后，用 AUI 的 hitTest 结果判断类型，
 * 再用 GLFW 直接应用系统光标。
 */
@EventBusSubscriber(modid = InformationEraNeoForge121.MOD_ID, value = Dist.CLIENT)
public final class FileExplorerCursor {

    private static long arrow;
    private static long ibeam;
    private static long hand;
    private static long hresize;
    private static long vresize;
    private static long resizeAll;

    private FileExplorerCursor() {
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Post event) {
        if (FileExplorerWindow.windowCount() == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return;
        }
        ensureCursors();
        Position mouse = Operation.getMousePositionDirectly();
        String kind = mouse == null ? null : FileExplorerWindow.resolveCursorKind(mouse);
        long handle = handleFor(kind);
        if (handle != 0L) {
            GLFW.glfwSetCursor(mc.getWindow().getWindow(), handle);
        }
    }

    private static long handleFor(String kind) {
        if (kind == null) {
            return arrow;
        }
        return switch (kind) {
            case "ew" -> hresize;
            case "ns" -> vresize;
            case "nwse" -> resizeAll;
            case "pointer" -> hand;
            case "text" -> ibeam;
            default -> arrow;
        };
    }

    private static void ensureCursors() {
        if (arrow != 0L) {
            return;
        }
        arrow = GLFW.glfwCreateStandardCursor(GLFW.GLFW_ARROW_CURSOR);
        ibeam = GLFW.glfwCreateStandardCursor(GLFW.GLFW_IBEAM_CURSOR);
        hand = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
        hresize = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HRESIZE_CURSOR);
        vresize = GLFW.glfwCreateStandardCursor(GLFW.GLFW_VRESIZE_CURSOR);
        resizeAll = GLFW.glfwCreateStandardCursor(GLFW.GLFW_RESIZE_ALL_CURSOR);
    }
}
