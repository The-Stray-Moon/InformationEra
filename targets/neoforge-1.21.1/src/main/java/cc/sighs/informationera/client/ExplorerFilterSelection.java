package cc.sighs.informationera.client;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 资源管理器的筛选条件：标签、模组、创造模式标签页三组多值条件。
 *
 * <p>同类条件取并集（OR），不同类之间取交集（AND）：勾选 {@code minecraft:planks} 与
 * {@code minecraft:logs} 表示“属于其中任一标签”；再勾选某模组则还必须来自该模组。
 * 空类不参与判断，所以只勾选模组时不会因为“没有标签条件”而排除物品。
 *
 * <p>{@link #includeSubfolders} 只描述筛选窗格里的“展开所有子文件夹”开关，不参与
 * {@link #matches}；它是否生效由控制器决定（筛选关闭时不得影响显示）。
 */
final class ExplorerFilterSelection {

    /** 条件类别；{@link #key} 是写入客户端配置的稳定字段。 */
    enum Kind {
        TAG("tag"),
        MOD("mod"),
        TAB("tab");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }

        String labelKey() {
            return switch (this) {
                case TAG -> "informationera.fe.filter_tags";
                case MOD -> "informationera.fe.filter_mods";
                case TAB -> "informationera.fe.filter_tabs";
            };
        }

        static Kind byKey(String key) {
            if (key != null) {
                for (Kind kind : values()) {
                    if (kind.key.equals(key)) {
                        return kind;
                    }
                }
            }
            return null;
        }
    }

    private final Map<Kind, LinkedHashSet<String>> values = new EnumMap<>(Kind.class);
    boolean includeSubfolders;

    ExplorerFilterSelection() {
        for (Kind kind : Kind.values()) {
            values.put(kind, new LinkedHashSet<>());
        }
    }

    /**
     * 加入一个条件值；空值忽略，重复值不会改变集合。
     *
     * @return 是否真的新增了一条
     */
    boolean add(Kind kind, String value) {
        String normalized = normalize(value);
        return normalized != null && kind != null && values.get(kind).add(normalized);
    }

    boolean remove(Kind kind, String value) {
        String normalized = normalize(value);
        return normalized != null && kind != null && values.get(kind).remove(normalized);
    }

    boolean contains(Kind kind, String value) {
        String normalized = normalize(value);
        return normalized != null && kind != null && values.get(kind).contains(normalized);
    }

    Set<String> values(Kind kind) {
        Set<String> set = kind == null ? null : values.get(kind);
        if (set == null || set.isEmpty()) {
            return Set.of();
        }
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(set));
    }

    int size(Kind kind) {
        return kind == null ? 0 : values.get(kind).size();
    }

    int totalSize() {
        int total = 0;
        for (LinkedHashSet<String> set : values.values()) {
            total += set.size();
        }
        return total;
    }

    boolean isEmpty() {
        return totalSize() == 0;
    }

    void clear() {
        for (LinkedHashSet<String> set : values.values()) {
            set.clear();
        }
    }

    /**
     * 物品是否满足当前条件。
     *
     * @param modId 物品 id 的命名空间；未知时为 null
     * @param tags  物品实际拥有的标签 id
     * @param tabs  物品出现的创造模式标签页 id
     */
    boolean matches(String modId, Collection<String> tags, Collection<String> tabs) {
        LinkedHashSet<String> mods = values.get(Kind.MOD);
        if (!mods.isEmpty() && (modId == null || !mods.contains(modId))) {
            return false;
        }
        LinkedHashSet<String> wantedTags = values.get(Kind.TAG);
        if (!wantedTags.isEmpty() && !intersects(wantedTags, tags)) {
            return false;
        }
        LinkedHashSet<String> wantedTabs = values.get(Kind.TAB);
        return wantedTabs.isEmpty() || intersects(wantedTabs, tabs);
    }

    private static boolean intersects(Set<String> wanted, Collection<String> actual) {
        if (actual == null || actual.isEmpty()) {
            return false;
        }
        for (String value : actual) {
            if (wanted.contains(value)) {
                return true;
            }
        }
        return false;
    }

    /** 条件值统一去首尾空白；空串视为无效。 */
    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
