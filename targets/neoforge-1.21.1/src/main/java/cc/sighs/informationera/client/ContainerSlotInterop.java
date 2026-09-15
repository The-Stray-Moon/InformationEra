package cc.sighs.informationera.client;

import cc.sighs.informationera.InformationEraNeoForge121;
import cc.sighs.informationera.disk.ItemKey;
import cc.sighs.informationera.util.ItemStacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;
import com.sighs.apricityui.render.Operation;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器槽位与磁盘之间的两种快捷交互。
 *
 * <h2>Alt + 左键：把槽位物品存入磁盘</h2>
 * 点击普通槽位时，物品默认在光标与槽位之间移动；按住 Alt 则改为存入磁盘，
 * 存入目标由 {@link DiskClient#resolveTargetFolder} 决议：
 * 显式传入 &gt; 聚焦的资源管理器窗口当前文件夹 &gt; 下载目录。
 * 也就是说「没打开资源管理器就存进下载」这条规则在这里落地。
 *
 * <h2>从资源管理器拖到槽位：取出物品</h2>
 * 拖拽落点判定在 {@link FileExplorerWindow} 的跨窗口拖拽流程里，这里只提供
 * 「把屏幕坐标映射到某个容器槽位」的能力，供其调用。
 */
@EventBusSubscriber(modid = InformationEraNeoForge121.MOD_ID, value = Dist.CLIENT)
public final class ContainerSlotInterop {

    private ContainerSlotInterop() {
    }

    /**
     * 拦截 Alt + 左键的槽位点击。
     *
     * <p>用可取消的 Pre 事件，在槽位真正处理点击之前把它变成「存入磁盘」，
     * 因此不会与 Shift 快速移动、右键分堆等原版语义冲突。
     */
    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        if (!isAltDown()) {
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        Slot slot = screen.getSlotUnderMouse();
        if (slot == null || !slot.hasItem() || !slot.mayPickup(Minecraft.getInstance().player)) {
            return;
        }
        ItemStack stack = slot.getItem();
        ItemKey key = ItemStacks.keyOf(stack, registries());
        if (key == null) {
            return;
        }
        // 槽位索引使用菜单内的全局下标，服务端据此重新读取真实物品与数量。
        // 显示名一并带上：物品的本地化名只有客户端知道。
        String displayName = stack.getHoverName().getString();
        DiskClient.deposit(null, List.of(slot.index), List.of(displayName));
        DiskClient.showStatus(FileExplorerText.t("informationera.msg.depositing", displayName));
        // 取消原版点击：物品不应该在光标上出现。
        event.setCanceled(true);
    }

    /** Alt 是否按下。AUI 的 Operation 不提供修饰键查询，直接问 GLFW。 */
    static boolean isAltDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) {
            return false;
        }
        long handle = mc.getWindow().getWindow();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }

    static boolean isControlDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) {
            return false;
        }
        long handle = mc.getWindow().getWindow();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    /**
     * 把 MC 的 GUI 坐标映射到当前容器屏的槽位；没命中返回 null。
     * 供跨窗口拖拽的落点判定使用。
     */
    public static Slot slotAt(double guiX, double guiY) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            return null;
        }
        AbstractContainerMenu menu = screen.getMenu();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (!slot.isActive()) {
                continue;
            }
            // 槽位坐标是屏幕内相对坐标，需要加上界面的左上偏移。
            int left = screen.getGuiLeft();
            int top = screen.getGuiTop();
            double x = guiX - left - slot.x;
            double y = guiY - top - slot.y;
            if (x >= -1 && x < 17 && y >= -1 && y < 17) {
                return slot;
            }
        }
        return null;
    }

    /** 是否正停在某个容器屏上（用于判断「拖到槽位」是否成立）。 */
    public static boolean onContainerScreen() {
        return Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>;
    }

    private static net.minecraft.core.HolderLookup.Provider registries() {
        return DiskClient.registryProvider();
    }
}
