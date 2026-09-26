package cc.sighs.informationera.util;

import cc.sighs.informationera.disk.ItemKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 开始菜单设备里的插槽内容：插卡区的一张储存卡 + 功能模块区的若干模块槽。
 *
 * <p>服务端按玩家 uuid 保存一份，客户端拿到的是同一格式的只读镜像，所以编解码只写在这里
 * 一处、两边共用。行格式（字段用制表符分隔，组件 SNBT 放最后一段）：
 *
 * <pre>
 * c&lt;TAB&gt;&lt;物品 id&gt;&lt;TAB&gt;&lt;数量&gt;&lt;TAB&gt;&lt;组件 SNBT&gt;
 * m&lt;槽位号&gt;&lt;TAB&gt;&lt;物品 id&gt;&lt;TAB&gt;&lt;数量&gt;&lt;TAB&gt;&lt;组件 SNBT&gt;
 * </pre>
 *
 * 空槽不写行，因此解码后没出现的槽位就是空的。
 */
public final class DeviceSlots {

    /** 功能模块区的槽位数。 */
    public static final int MODULE_SLOTS = 2;

    private static final String CARD_KIND = "c";
    private static final String MODULE_KIND = "m";
    private static final char FIELD_SEP = '\t';

    private ItemStack card = ItemStack.EMPTY;
    private final List<ItemStack> modules = new ArrayList<>(MODULE_SLOTS);

    public DeviceSlots() {
        for (int i = 0; i < MODULE_SLOTS; i++) {
            modules.add(ItemStack.EMPTY);
        }
    }

    /** 插卡区里的卡；空表示没插卡。 */
    public ItemStack card() {
        return card;
    }

    public void setCard(ItemStack stack) {
        card = stack == null ? ItemStack.EMPTY : stack;
    }

    public List<ItemStack> modules() {
        return Collections.unmodifiableList(modules);
    }

    public ItemStack module(int index) {
        return index >= 0 && index < modules.size() ? modules.get(index) : ItemStack.EMPTY;
    }

    public void setModule(int index, ItemStack stack) {
        if (index >= 0 && index < modules.size()) {
            modules.set(index, stack == null ? ItemStack.EMPTY : stack);
        }
    }

    public String encode(HolderLookup.Provider registries) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, CARD_KIND, card, registries);
        for (int i = 0; i < modules.size(); i++) {
            appendLine(sb, MODULE_KIND + i, modules.get(i), registries);
        }
        return sb.toString();
    }

    /** 解码；空串或坏行一律跳过，返回一个槽位全空的设备。 */
    public static DeviceSlots decode(String encoded, HolderLookup.Provider registries) {
        DeviceSlots slots = new DeviceSlots();
        if (encoded == null || encoded.isEmpty()) {
            return slots;
        }
        for (String line : encoded.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(String.valueOf(FIELD_SEP), 4);
            if (parts.length < 4) {
                continue;
            }
            ItemStack stack = stackOf(parts[1], parts[2], parts[3], registries);
            if (CARD_KIND.equals(parts[0])) {
                slots.setCard(stack);
            } else if (parts[0].startsWith(MODULE_KIND)) {
                slots.setModule(parseInt(parts[0].substring(MODULE_KIND.length()), -1), stack);
            }
        }
        return slots;
    }

    private static void appendLine(StringBuilder sb, String kind, ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        ItemKey key = ItemStacks.keyOf(stack, registries);
        if (key == null) {
            return;
        }
        sb.append(kind).append(FIELD_SEP).append(key.itemId()).append(FIELD_SEP)
          .append(stack.getCount()).append(FIELD_SEP).append(key.nbt()).append('\n');
    }

    private static ItemStack stackOf(String itemId, String countText, String nbt, HolderLookup.Provider registries) {
        if (itemId == null || itemId.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int count = parseInt(countText, 1);
        if (count <= 0) {
            return ItemStack.EMPTY;
        }
        return ItemStacks.stackOf(new ItemKey(itemId, nbt == null ? "" : nbt), count, registries);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
