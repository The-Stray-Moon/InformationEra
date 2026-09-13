package cc.sighs.informationera.util;

import cc.sighs.informationera.disk.ItemKey;
import com.mojang.serialization.DataResult;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 在 {@code ItemStack}（Minecraft 类型）与 {@link ItemKey}（common 层的纯数据标识）之间转换。
 *
 * <p>{@code ItemKey} 的 nbt 字段存的是「单个物品」的组件序列化结果：数量被强制为 1，
 * 因为数量不属于标识的一部分——同 id、同组件、不同数量的物品必须是同一个 key，否则
 * 「同一种物品在所有文件里共享数量」这一核心语义就不成立。
 */
public final class ItemStacks {

    private ItemStacks() {
    }

    /** 取出物品的稳定标识；空物品返回 null。 */
    public static ItemKey keyOf(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return null;
        }
        return new ItemKey(id.toString(), nbtOf(stack, registries));
    }

    /** 把物品的组件序列化成 NBT 字符串；无组件时返回空串。 */
    public static String nbtOf(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        CompoundTag tag = new CompoundTag();
        DataResult<Tag> encoded = ItemStack.CODEC.encodeStart(
                registryOps(registries), stack.copyWithCount(1));
        Tag result = encoded.result().orElse(null);
        if (result == null) {
            return "";
        }
        if (result instanceof CompoundTag compound) {
            // 去掉 id 与 count，只保留组件差异。
            compound.remove("id");
            compound.remove("count");
            if (compound.isEmpty()) {
                return "";
            }
            tag = compound;
        } else {
            tag.put("v", result);
        }
        String text = tag.getAsString();
        return text == null ? "" : text;
    }

    /** 用标识与数量构造物品栈；数量会被夹到该物品的堆叠上限。 */
    public static ItemStack stackOf(ItemKey key, long count, HolderLookup.Provider registries) {
        if (key == null || count <= 0L) {
            return ItemStack.EMPTY;
        }
        ResourceLocation id = ResourceLocation.tryParse(key.itemId());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        ItemStack stack = new ItemStack(item);
        if (key.hasNbt()) {
            CompoundTag tag = parseTag(key.nbt());
            if (tag != null) {
                tag.putString("id", key.itemId());
                tag.putInt("count", 1);
                ItemStack parsed = ItemStack.parseOptional(registries, tag);
                if (!parsed.isEmpty()) {
                    stack = parsed;
                }
            }
        }
        int max = Math.max(1, stack.getMaxStackSize());
        stack.setCount((int) Math.max(1L, Math.min(max, count)));
        return stack;
    }

    /**
     * 构造用于界面展示的物品栈：数量夹到堆叠上限，真实数量通过返回值之外的
     * 覆盖文本单独呈现（见 {@link #overlayText(long)}）。
     */
    public static ItemStack displayStackOf(ItemKey key, long count, HolderLookup.Provider registries) {
        return stackOf(key, count, registries);
    }

    /** 数量角标文本：1 个时不显示，超过一万用「万」缩写，避免角标撑破图标。 */
    public static String overlayText(long count) {
        if (count <= 1L) {
            return null;
        }
        if (count < 10_000L) {
            return Long.toString(count);
        }
        if (count < 100_000_000L) {
            return Long.toString(count / 10_000L) + "万";
        }
        return Long.toString(count / 100_000_000L) + "亿";
    }

    private static CompoundTag parseTag(String text) {
        try {
            Tag tag = net.minecraft.nbt.TagParser.parseTag(text);
            return tag instanceof CompoundTag compound ? compound : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static com.mojang.serialization.DynamicOps<Tag> registryOps(HolderLookup.Provider registries) {
        return registries != null
                ? registries.createSerializationContext(NbtOps.INSTANCE)
                : NbtOps.INSTANCE;
    }
}
