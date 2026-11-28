package cc.sighs.informationera.client;

import cc.sighs.informationera.InformationEraNeoForge121;
import com.sighs.apricityui.client.Client;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 资源管理器里物品条目悬停时显示的原版物品提示框。
 *
 * <p>不走窗口自带的 {@code #fe-tooltip} 文本块，而是用 {@link GuiGraphics#renderTooltip} 画
 * 真正的原版 tooltip：物品组件、模组追加的行、附魔/属性/耐久、容器（潜影盒等）预览图，
 * 全部和物品栏里看到的一致。图标网格、列表/详情表格、搜索结果用的都是同一个条目结构，
 * 所以这里只按 {@code .fe-item} 行统一处理，三种视图自动共用。
 *
 * <p>提示框不是在 AUI 文档里画的，所以必须自己找渲染时机：没有界面时挂
 * {@link RenderGuiEvent.Post}，有界面（资源管理器窗口是常驻 Overlay，可以盖在任意 Screen 上）
 * 时挂 {@link ScreenEvent.Render.Post}。两者都用 {@link EventPriority#LOWEST}，保证在 AUI
 * 画完窗口之后才画，提示框才不会被窗口盖住。
 *
 * <p>每帧重画前会复核「鼠标是否还停在原来那一行上」；行被重建、窗口最小化/关闭、
 * 鼠标移走时立即清掉引用，避免残留一个过期的 {@link ItemStack}。
 */
@EventBusSubscriber(modid = InformationEraNeoForge121.MOD_ID, value = Dist.CLIENT)
public final class ExplorerItemTooltip {

    /** 正在显示提示框的窗口文档。 */
    private static Document document;
    /** 鼠标下的条目行（AUI 的 {@code .fe-item}）。 */
    private static Element row;
    private static ItemStack stack = ItemStack.EMPTY;
    private static String location;
    /** 正在拼装/绘制提示框的层数；供 {@link DepositHintTooltip} 判断该不该塞「存入」提示。 */
    private static int renderDepth;
    /**
     * 提示框额外抬高的 Z。
     *
     * <p>AUI 的文档用真实深度做层叠（每个文档基准 Z 从 1 开始、按 251.25 递增，窗口 body 再
     * 加一截 translateZ），原版提示框自带的 +400 不够高，会被窗口挡住。5000 仍远小于 GUI
     * 的远平面（≥21000），不会被裁掉。
     */
    private static final float TOOLTIP_Z = 5000.0F;

    private ExplorerItemTooltip() {
    }

    /** 开始（或切换）显示某一行的物品提示。 */
    static void show(Document doc, Element itemRow, ItemStack itemStack, String fileLocation) {
        if (doc == null || itemRow == null || itemStack == null || itemStack.isEmpty()) {
            hide(doc);
            return;
        }
        document = doc;
        row = itemRow;
        stack = itemStack;
        location = fileLocation;
    }

    /** 收起本窗口的物品提示；{@code doc} 为 null 时无条件收起。 */
    static void hide(Document doc) {
        if (document == null || doc == null || document == doc) {
            document = null;
            row = null;
            stack = ItemStack.EMPTY;
            location = null;
        }
    }

    /** 每 tick 复核一次，鼠标离开条目后及时释放堆栈引用。 */
    static void tick(Document doc) {
        if (document == null || document != doc) {
            return;
        }
        if (!stillHovered()) {
            hide(doc);
        }
    }

    /**
     * 当前是否正在绘制资源管理器自己的物品提示。
     *
     * <p>这个提示里 Alt+左键的语义是「取出」，而不是「存入」；「存入」提示只适用于
     * 容器槽位，不该混进来。
     */
    static boolean isRendering() {
        return renderDepth > 0;
    }

    private static boolean stillHovered() {
        if (document == null || !document.isActive()
                || row == null || !row.isConnected() || !row.isHover
                || stack == null || stack.isEmpty()
                || FileExplorerWindow.isFileDragActive()) {
            return false;
        }
        Position mouse = document.getMouseDocumentPosition();
        if (mouse == null) {
            return false;
        }
        // 同一文档里可能盖着右键菜单等更高的元素；命中不再是这一行说明提示该收了。
        Element hit = document.hitTest(mouse);
        return hit != null && hit.closest(".fe-item") == row;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // 有 Screen 时由 ScreenEvent 分支负责；这里再画就会重复。
        if (mc.screen != null) {
            return;
        }
        render(event.getGuiGraphics(), mc);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderScreen(ScreenEvent.Render.Post event) {
        Minecraft mc = Minecraft.getInstance();
        // NeoForge 也会给每个 GUI Layer 发同一个事件，只认当前真正的 Screen，避免重复绘制。
        if (mc.screen == null || event.getScreen() != mc.screen) {
            return;
        }
        render(event.getGuiGraphics(), mc);
    }

    private static void render(GuiGraphics graphics, Minecraft mc) {
        if (!stillHovered()) {
            hide(null);
            return;
        }
        Position mouse = Client.getMousePosition();
        if (mouse == null) {
            return;
        }
        // 原版的物品提示构造与绘制都放在这里：期间触发的 ItemTooltipEvent 就是这个物品
        // 自己的提示，不是容器槽位上的提示，DepositHintTooltip 会据此跳过「存入」那一行。
        renderDepth++;
        graphics.pose().pushPose();
        // AUI 的窗口是带 Z 的深度图层（body translateZ + 文档基准 Z），比原版提示框默认的
        // +400 更深。把整块提示再抬到一个足够高的 Z，保证它画在窗口和物品图标之上。
        graphics.pose().translate(0.0F, 0.0F, TOOLTIP_Z);
        try {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, stack));
            if (lines.isEmpty()) {
                return;
            }
            if (location != null) {
                lines.add(Component.literal(location).withStyle(ChatFormatting.GRAY));
            }
            graphics.renderTooltip(mc.font, lines, stack.getTooltipImage(), stack,
                    (int) mouse.x, (int) mouse.y);
        } finally {
            graphics.pose().popPose();
            renderDepth--;
        }
    }
}
