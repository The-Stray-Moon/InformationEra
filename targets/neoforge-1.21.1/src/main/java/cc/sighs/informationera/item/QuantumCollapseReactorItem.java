package cc.sighs.informationera.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 量子坍缩反应炉：模组的核心饰品，负责「物品 ↔ 信息」的虚实转换。
 *
 * <p>把它装备在 curios 的 {@code trinkets} 槽位（或者带在身上）时，玩家才可以把物品坍缩
 * 进磁盘、也可以把磁盘里的信息还原成物品。没有它的时候资源管理器只能浏览，存不进去也取不出来。
 */
public class QuantumCollapseReactorItem extends Item {

    public QuantumCollapseReactorItem(Properties properties) {
        super(properties);
    }

    /** 核心物品，自带附魔光效。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.informationera.quantum_collapse_reactor.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }
}
