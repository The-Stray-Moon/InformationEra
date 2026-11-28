package cc.sighs.informationera.client;

import cc.sighs.informationera.disk.ItemKey;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 列表的「仅视觉去重」：按真实 {@link ItemKey}（物品 id + 组件/NBT）只保留第一个代表。
 *
 * <p>这里不聚合数量、不修改磁盘数据，也不删除文件夹。包装的 {@link Predicate} 决定哪些
 * 条目永远保留（文件夹、没有物品标识的异常条目）；只有其余条目参与键去重。
 */
final class ExplorerItemDedupe {

    private ExplorerItemDedupe() {
    }

    /** 原地删除重复项；保留的条目对象不变，后续操作仍指向真实代表。 */
    static <T> void retainFirstByItemKey(List<T> entries, Predicate<T> keepAlways,
                                         Function<T, ItemKey> keyOf) {
        if (entries == null || entries.size() < 2) {
            return;
        }
        Set<ItemKey> seen = new HashSet<>();
        entries.removeIf(entry -> {
            if (keepAlways.test(entry)) {
                return false;
            }
            ItemKey key = keyOf.apply(entry);
            return key != null && !seen.add(key);
        });
    }
}
