package cc.sighs.informationera.disk;

/**
 * 一种物品的稳定标识：物品 id + 组件/标签 NBT 的规范序列化。
 *
 * <p>数量不参与标识，因此「同 id + 同 NBT + 不同数量」被视为同一种可堆叠物品，
 * 可以合并进同一个 {@link InventoryStack}。{@code nbt} 使用 NBT 的字符串形式
 * （例如 {@code {Damage:12}}），无组件时为空字符串，保证 common 层不依赖任何 Minecraft 类型。
 */
public final class ItemKey implements Comparable<ItemKey> {

    private final String itemId;
    private final String nbt;

    public ItemKey(String itemId, String nbt) {
        if (itemId == null || itemId.isEmpty()) {
            throw new IllegalArgumentException("itemId must not be empty");
        }
        this.itemId = itemId;
        this.nbt = nbt == null ? "" : nbt;
    }

    public String itemId() {
        return itemId;
    }

    public String nbt() {
        return nbt;
    }

    public boolean hasNbt() {
        return !nbt.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ItemKey)) {
            return false;
        }
        ItemKey key = (ItemKey) other;
        return itemId.equals(key.itemId) && nbt.equals(key.nbt);
    }

    @Override
    public int hashCode() {
        return itemId.hashCode() * 31 + nbt.hashCode();
    }

    @Override
    public int compareTo(ItemKey other) {
        int byId = itemId.compareTo(other.itemId);
        return byId != 0 ? byId : nbt.compareTo(other.nbt);
    }

    @Override
    public String toString() {
        return nbt.isEmpty() ? itemId : itemId + nbt;
    }
}
