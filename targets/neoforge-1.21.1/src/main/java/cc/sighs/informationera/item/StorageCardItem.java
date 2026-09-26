package cc.sighs.informationera.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.UUID;

/**
 * 储存卡：一块信息磁盘的载体，插进开始菜单的插卡区之后资源管理器才可用。
 *
 * <p>卡上只绑一个 uuid，**内容跟 uuid 走而不是跟这张卡这件物品走**：同一个 uuid 的卡在谁手里
 * 看到的都是同一块盘，卡被复制、被交易都不影响盘里的东西。uuid 在第一次插卡时才分配。
 *
 * <p>磁盘容量也由卡提供（见 {@code DiskAccess}）：卡不在身上，容量立刻掉回去。
 */
public class StorageCardItem extends Item {

    /** 一张卡提供的储存空间，单位是「物品个数」。 */
    public static final long CAPACITY_PER_CARD = 16384L;

    public StorageCardItem(Properties properties) {
        super(properties);
    }

    /** 卡上绑定的磁盘 uuid；没绑定过或数据坏掉返回 null。 */
    public static UUID diskId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String raw = stack.get(ModDataComponents.DISK_UUID.get());
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * 确保卡上绑定了磁盘 uuid，没有就现分一个。
     *
     * <p>分配时机是「第一次插进设备」而不是拿到手：创造模式拿出来的、指令给的卡都不带 uuid，
     * 免得凭空占掉一个磁盘编号。
     */
    public static UUID ensureDiskId(ItemStack stack) {
        UUID id = diskId(stack);
        if (id != null) {
            return id;
        }
        id = UUID.randomUUID();
        stack.set(ModDataComponents.DISK_UUID.get(), id.toString());
        return id;
    }

    /** 磁盘的初始名称：取 uuid 的后四位。 */
    public static String defaultDiskName(UUID id) {
        if (id == null) {
            return "";
        }
        String text = id.toString().replace("-", "");
        return text.length() <= 4 ? text : text.substring(text.length() - 4);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        UUID id = diskId(stack);
        if (id == null) {
            tooltip.add(Component.translatable("item.informationera.storage_card.unbound")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            tooltip.add(Component.translatable("item.informationera.storage_card.bound", defaultDiskName(id))
                    .withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.translatable("item.informationera.storage_card.tooltip",
                CAPACITY_PER_CARD * stack.getCount()).withStyle(ChatFormatting.GRAY));
    }
}
