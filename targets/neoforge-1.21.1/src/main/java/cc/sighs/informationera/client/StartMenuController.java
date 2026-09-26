package cc.sighs.informationera.client;

import cc.sighs.informationera.net.DiskStateHandler;
import cc.sighs.informationera.util.DeviceSlots;
import com.sighs.apricityui.element.Item;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 开始菜单面板：设备插槽（插卡区 + 功能模块区）与玩家物品栏之间的换槽。
 *
 * <p>面板是任务栏 Overlay 里的一个 div，内容全部由这里渲染。设备槽是服务端权威状态
 * （{@link DiskStateHandler#device()}），物品栏则是客户端自己的镜像——点格子换槽只发一个请求，
 * 等服务端回推状态再重画，不在客户端猜结果。
 *
 * <p>两种视图：设备视图（看插了什么）与物品栏视图（点某个设备槽后进入，再点一个物品栏格子
 * 就完成互换）。这是「装卸 / 更换槽位物品」的唯一操作路径。
 */
public final class StartMenuController {

    /** 物品栏视图显示的槽位数：快捷栏 + 主物品栏，不含护甲与副手。 */
    private static final int INVENTORY_SLOTS = 36;

    private static final String SLOT_CARD = "card";
    private static final String SLOT_MODULE = "module";

    private static boolean open;
    /** 是否处于物品栏视图。 */
    private static boolean inventoryMode;
    /** 物品栏视图里被选中的设备槽标识。 */
    private static String selectedSlot = "";

    private StartMenuController() {
    }

    static boolean isOpen() {
        return open;
    }

    /** 打开 / 收起面板。 */
    static void toggle() {
        if (open) {
            close();
            return;
        }
        open = true;
        inventoryMode = false;
        selectedSlot = "";
        render();
    }

    static void close() {
        if (!open) {
            return;
        }
        open = false;
        inventoryMode = false;
        selectedSlot = "";
        Element panel = panel();
        if (panel != null) {
            panel.setInlineStyleProperty("display", "none");
        }
        // 全屏拦截层也要一起撤掉，否则游戏界面会点不动。
        Element capture = document() == null ? null : document().getElementById("fe-taskbar-capture");
        if (capture != null) {
            capture.setInlineStyleProperty("display", "none");
        }
    }

    /** 服务端状态更新后重画：插卡、换槽的结果都在这里体现。 */
    static void refresh() {
        if (open) {
            render();
        }
    }

    private static void render() {
        Element panel = panel();
        if (panel == null) {
            return;
        }
        DeviceSlots device = DiskStateHandler.device();
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"fe-start-head\">")
          .append("<span class=\"fe-start-title\">").append(FileExplorerText.t("informationera.start.title")).append("</span>")
          .append("<span class=\"fe-start-disk\">").append(escape(diskLabel(device))).append("</span>")
          .append("<span class=\"fe-start-close\" data-action=\"close\">×</span>")
          .append("</div>");
        if (inventoryMode) {
            sb.append("<div class=\"fe-start-label\">")
              .append(FileExplorerText.t("informationera.start.pick_inventory")).append("</div>");
            sb.append("<div class=\"fe-start-inventory\">");
            for (int i = 0; i < INVENTORY_SLOTS; i++) {
                sb.append("<div class=\"fe-start-slot\" data-inv=\"").append(i).append("\">")
                  .append("<span class=\"fe-start-icon-box\"><item class=\"fe-start-icon\" interactive=\"0\"></item></span></div>");
            }
            sb.append("</div>");
            sb.append("<div class=\"fe-start-hint\">")
              .append(FileExplorerText.t("informationera.start.swap_hint", slotLabel(selectedSlot)))
              .append("</div>");
        } else {
            sb.append("<div class=\"fe-start-label\">")
              .append(FileExplorerText.t("informationera.start.card_slot")).append("</div>");
            sb.append("<div class=\"fe-start-slots\">").append(slotHtml(SLOT_CARD, device.card())).append("</div>");
            sb.append("<div class=\"fe-start-label\">")
              .append(FileExplorerText.t("informationera.start.module_slots")).append("</div>");
            sb.append("<div class=\"fe-start-slots\">");
            for (int i = 0; i < DeviceSlots.MODULE_SLOTS; i++) {
                sb.append(slotHtml(SLOT_MODULE + i, device.module(i)));
            }
            sb.append("</div>");
            if (device.card().isEmpty()) {
                sb.append("<div class=\"fe-start-hint\">")
                  .append(FileExplorerText.t("informationera.start.no_card_hint")).append("</div>");
            }
        }
        panel.setInnerHTML(sb.toString());
        panel.setInlineStyleProperty("display", "block");
        bindSlots();
        driveIcons();
    }

    private static String slotHtml(String slotId, ItemStack stack) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"fe-start-slot\" data-slot=\"").append(slotId).append("\">");
        if (stack != null && !stack.isEmpty()) {
            sb.append("<span class=\"fe-start-icon-box\"><item class=\"fe-start-icon\" interactive=\"0\"></item></span>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** 重画之后元素是新的，监听器必须重新挂。 */
    private static void bindSlots() {
        Element panel = panel();
        if (panel == null) {
            return;
        }
        Element closeButton = panel.querySelector(".fe-start-close");
        if (closeButton != null) {
            closeButton.addEventListener("click", e -> {
                consume(e);
                close();
            });
        }
        List<Element> slots = document() == null ? null : document().querySelectorAll(".fe-start-slot");
        if (slots == null) {
            return;
        }
        for (Element slot : slots) {
            String slotId = slot.getAttribute("data-slot");
            String inventoryIndex = slot.getAttribute("data-inv");
            slot.addEventListener("click", e -> {
                consume(e);
                if (inventoryIndex != null) {
                    onInventoryClick(parseInt(inventoryIndex, -1));
                } else if (slotId != null) {
                    onDeviceSlotClick(slotId);
                }
            });
        }
    }

    /** 点设备槽：切到物品栏视图，等玩家挑一个格子来互换。 */
    private static void onDeviceSlotClick(String slotId) {
        selectedSlot = slotId;
        inventoryMode = true;
        render();
    }

    /** 点物品栏格子：与刚才选中的设备槽互换。 */
    private static void onInventoryClick(int inventoryIndex) {
        if (inventoryIndex < 0 || selectedSlot.isEmpty()) {
            return;
        }
        DiskClient.swapSlot(selectedSlot, inventoryIndex);
        // 立刻回到设备视图，服务端状态回来时会重画成换过之后的样子。
        inventoryMode = false;
        selectedSlot = "";
        render();
    }

    /** 把真实物品栈喂给每个格子的 {@code <item>} 元素；这一步必须在 DOM 建好之后做。 */
    private static void driveIcons() {
        Document document = document();
        if (document == null) {
            return;
        }
        List<Element> slots = document.querySelectorAll(".fe-start-slot");
        if (slots == null) {
            return;
        }
        for (Element slot : slots) {
            Element icon = slot.querySelector(".fe-start-icon");
            if (!(icon instanceof Item item)) {
                continue;
            }
            ItemStack stack = stackFor(slot);
            if (stack.isEmpty()) {
                continue;
            }
            item.setDrivenState(stack, null, false, false, Item.Source.INGREDIENT);
        }
    }

    private static ItemStack stackFor(Element slot) {
        String inventoryIndex = slot.getAttribute("data-inv");
        if (inventoryIndex != null) {
            Inventory inventory = playerInventory();
            int index = parseInt(inventoryIndex, -1);
            return inventory == null || index < 0 ? ItemStack.EMPTY : inventory.getItem(index);
        }
        DeviceSlots device = DiskStateHandler.device();
        String slotId = slot.getAttribute("data-slot");
        if (SLOT_CARD.equals(slotId)) {
            return device.card();
        }
        if (slotId != null && slotId.startsWith(SLOT_MODULE)) {
            return device.module(parseInt(slotId.substring(SLOT_MODULE.length()), -1));
        }
        return ItemStack.EMPTY;
    }

    /** 面板标题上显示的磁盘名：没插卡就明说。 */
    private static String diskLabel(DeviceSlots device) {
        if (device.card().isEmpty()) {
            return FileExplorerText.t("informationera.start.no_disk");
        }
        if (DiskStateHandler.mirror() == null) {
            return "";
        }
        return DiskStateHandler.mirror().diskName();
    }

    private static String slotLabel(String slotId) {
        if (SLOT_CARD.equals(slotId)) {
            return FileExplorerText.t("informationera.start.card_slot");
        }
        return FileExplorerText.t("informationera.start.module_slots")
                + " " + (parseInt(slotId.substring(SLOT_MODULE.length()), 0) + 1);
    }

    /** 面板里的点击不该穿透到游戏界面。 */
    private static void consume(com.sighs.apricityui.event.Event event) {
        if (event instanceof MouseEvent mouse) {
            mouse.consumeNative();
        }
    }

    private static Inventory playerInventory() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getInventory();
    }

    private static Element panel() {
        Document document = document();
        return document == null ? null : document.getElementById("fe-start-panel");
    }

    private static Document document() {
        return FileExplorerTrigger.getDocument();
    }

    private static String escape(String text) {
        return FileExplorerController.escape(text);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
