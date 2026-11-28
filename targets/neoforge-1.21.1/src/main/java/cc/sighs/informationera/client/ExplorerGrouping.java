package cc.sighs.informationera.client;

import cc.sighs.informationera.api.client.ExplorerColumnContext;
import cc.sighs.informationera.api.client.ExplorerGroup;
import cc.sighs.informationera.api.client.ExplorerGroupRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 分组数据的统一入口：把已筛选、去重、排序后的条目分成稳定组，并只在这里决定组序。
 *
 * <p>组身份是「依据 ID + 桶 ID」，显示标题和组序值只用于展示；两个模组即使标题相同也不会合并，
 * 切换语言也不会改变折叠键。组内条目保持传入顺序（即表头排序），组序由桶的自然序决定，
 * 可单独反向；两者互不影响。
 */
final class ExplorerGrouping {

    private ExplorerGrouping() {
    }

    record Bucket(String id, String label, Comparable<?> sortValue) {
    }

    static final class Section {
        final String basisId;
        final String bucketId;
        final String label;
        final Comparable<?> sortValue;
        final List<VirtualFileSystem.Entry> entries = new ArrayList<>();

        Section(String basisId, String bucketId, String label, Comparable<?> sortValue) {
            this.basisId = basisId;
            this.bucketId = bucketId;
            this.label = label;
            this.sortValue = sortValue;
        }

        String key() {
            return ExplorerGrouping.key(basisId, bucketId);
        }

        int count() {
            return entries.size();
        }
    }

    static String key(String basisId, String bucketId) {
        return (basisId == null ? "" : basisId) + '\u0000' + (bucketId == null ? "" : bucketId);
    }

    /**
     * 按依据分桶；保持条目原顺序。依据未注册或没有条目时返回空列表（保留 groupBy 设置）。
     */
    static List<Section> sections(String basisId, List<VirtualFileSystem.Entry> entries,
                                  Function<VirtualFileSystem.Entry, ExplorerColumnContext> contexts) {
        ExplorerGroup group = ExplorerColumnService.group(basisId);
        if (group == null || entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<String, Section> sections = new LinkedHashMap<>();
        for (VirtualFileSystem.Entry entry : entries) {
            if (entry == null) {
                continue;
            }
            ExplorerColumnContext context;
            try {
                context = contexts == null ? null : contexts.apply(entry);
            } catch (Throwable failure) {
                context = null;
            }
            Bucket bucket = resolveBucket(group, context);
            Section section = sections.get(bucket.id());
            if (section == null) {
                section = new Section(basisId, bucket.id(), bucket.label(), bucket.sortValue());
                sections.put(bucket.id(), section);
            }
            section.entries.add(entry);
        }
        return new ArrayList<>(sections.values());
    }

    private static Bucket resolveBucket(ExplorerGroup group, ExplorerColumnContext context) {
        ExplorerGroupRule.Bucket raw = ExplorerColumnService.bucket(group, context);
        String id = raw.id();
        String label;
        try {
            label = raw.label().getString();
        } catch (Throwable failure) {
            label = id;
        }
        if (label == null || label.isBlank()) {
            label = id;
        }
        Comparable<?> sort = raw.sortValue();
        if (sort == null) {
            sort = label;
        }
        return new Bucket(id, label, sort);
    }

    /** 只改组序；组内条目顺序保持不变。 */
    static void sortSections(List<Section> sections, boolean reverse) {
        if (sections == null || sections.size() < 2) {
            return;
        }
        Comparator<Section> comparator = (a, b) -> {
            int cmp = compareValues(a.sortValue, b.sortValue);
            if (cmp == 0) {
                cmp = VirtualFileSystem.naturalCompare(a.label, b.label);
            }
            if (cmp == 0) {
                cmp = VirtualFileSystem.naturalCompare(a.bucketId, b.bucketId);
            }
            return cmp;
        };
        sections.sort(reverse ? comparator.reversed() : comparator);
    }

    static int compareValues(Comparable<?> left, Comparable<?> right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        if (left instanceof Number a && right instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        if (left instanceof Boolean a && right instanceof Boolean b) {
            return Boolean.compare(a, b);
        }
        if (left instanceof String || right instanceof String) {
            return VirtualFileSystem.naturalCompare(String.valueOf(left), String.valueOf(right));
        }
        if (left.getClass() == right.getClass() && left instanceof Comparable<?> comparable) {
            @SuppressWarnings("unchecked")
            Comparable<Object> typed = (Comparable<Object>) comparable;
            try {
                return typed.compareTo(right);
            } catch (RuntimeException incompatible) {
                return VirtualFileSystem.naturalCompare(String.valueOf(left), String.valueOf(right));
            }
        }
        return VirtualFileSystem.naturalCompare(String.valueOf(left), String.valueOf(right));
    }

    // ---------- 可独立测试的桶规则 ----------

    static String durabilityBucketId(int maxDamage, int damage) {
        if (maxDamage <= 0) {
            return "none";
        }
        if (damage <= 0) {
            return "full";
        }
        double remaining = (maxDamage - damage) / (double) maxDamage;
        return remaining <= 0.25 ? "repair" : "worn";
    }

    static String stackBucketId(int maxStack) {
        return maxStack <= 0 ? "unknown" : "stack:" + maxStack;
    }

    static String enchantmentBucketId(boolean stored, boolean enchanted) {
        if (stored) {
            return "stored";
        }
        return enchanted ? "enchanted" : "none";
    }
}
