package cc.sighs.informationera.api.client;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 列取值时拿到的只读上下文，由宿主为每个条目构造。
 *
 * <p>{@link #stack()} 对文件夹或无法解析的物品返回 {@link ItemStack#EMPTY}，调用方不要修改
 * 返回的物品栈。{@link #name()} 已经应用了「显示文件扩展名」等当前视图设置。
 */
public interface ExplorerColumnContext {

    boolean folder();

    /** 显示名；文件可能已经去掉扩展名。 */
    String name();

    /** 文件数量；文件夹为 -1。 */
    long count();

    /** 注册 ID，例如 {@code minecraft:diamond_sword}；文件夹为 null。 */
    String itemId();

    /** 注册 ID 的命名空间；文件夹或未知物品为空串。 */
    String namespace();

    /** 当前语言的类型文本（文件为「物品」，文件夹为「文件夹」）。 */
    String type();

    String modified();

    String created();

    /** 作者；当前数据模型没有作者时为 null。 */
    String author();

    /** 真实物品栈；文件夹为空栈。 */
    ItemStack stack();

    /** 物品最大堆叠；文件夹或取不到物品时为 0。 */
    int maxStack();

    /** 物品最大耐久；无耐久或取不到物品时为 0。 */
    int maxDamage();
}
