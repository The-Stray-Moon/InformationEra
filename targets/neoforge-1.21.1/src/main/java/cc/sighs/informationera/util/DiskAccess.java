package cc.sighs.informationera.util;

import cc.sighs.informationera.item.ModItems;
import cc.sighs.informationera.item.StorageCardItem;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.ArrayList;
import java.util.List;

/**
 * 校验玩家对信息磁盘的访问资格与储存空间。
 *
 * <p>两件事都由玩家**身上带着的东西**现算，不写进存档：
 * <ul>
 *   <li>虚实转换的资格：装备了量子坍缩反应炉，才能把物品坍缩进磁盘、或把信息还原成物品；</li>
 *   <li>储存空间：基础额度加上身上所有储存卡提供的额度。</li>
 * </ul>
 * 不落盘是有意的——反应炉摘了就存不进去、储存卡丢下额度立刻掉回去，和物品本身的状态一致。
 *
 * <p>客户端也用同一份判定来决定「开始菜单按钮要不要出现」，所以这里收的是 {@link Player}
 * 而不是 {@code ServerPlayer}：两端必须得出同一个结论，否则会出现「按钮亮着但服务端拒绝」。
 */
public final class DiskAccess {

    /** 一块磁盘的基础储存空间（物品个数）：一张储存卡都不带也有这么多。 */
    public static final long BASE_CAPACITY = 4096L;

    private DiskAccess() {
    }

    /** 玩家身上有没有量子坍缩反应炉（curios 饰品槽或物品栏里都算）。 */
    public static boolean canConvert(Player player) {
        if (player == null) {
            return false;
        }
        Item reactor = ModItems.QUANTUM_COLLAPSE_REACTOR.get();
        // 饰品槽里的东西不在玩家物品栏里，得单独问 curios。
        if (CuriosApi.getCuriosInventory(player)
                .map(inventory -> inventory.isEquipped(reactor))
                .orElse(false)) {
            return true;
        }
        // 没装进饰品槽、只是带在背包里也算数——少一个「为什么没反应」的坑。
        for (ItemStack stack : carried(player)) {
            if (stack.is(reactor)) {
                return true;
            }
        }
        return false;
    }

    /** 这块磁盘的储存空间：基础额度 + 身上所有储存卡。 */
    public static long capacity(Player player) {
        return capacity(player, 0);
    }

    /**
     * 再加上设备里插着的那几张卡一起算。
     *
     * <p>卡插进设备之后就不在物品栏里了，只按 {@link #capacity(Player)} 算会把它漏掉——
     * 那就成了「插了卡反而更容易满」。所以调用方要把设备里那张卡的数量单独传进来。
     */
    public static long capacity(Player player, int deviceCards) {
        if (player == null) {
            return BASE_CAPACITY;
        }
        Item card = ModItems.STORAGE_CARD.get();
        long cards = Math.max(0, deviceCards);
        for (ItemStack stack : carried(player)) {
            if (stack.is(card)) {
                cards += stack.getCount();
            }
        }
        return BASE_CAPACITY + cards * StorageCardItem.CAPACITY_PER_CARD;
    }

    /**
     * 玩家身上所有装着东西的槽位：主物品栏、护甲、副手。
     *
     * <p>{@code Inventory} 的容器索引已经把这三块串起来了，所以直接按容器遍历即可。
     */
    private static List<ItemStack> carried(Player player) {
        Inventory inventory = player.getInventory();
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        return stacks;
    }
}
