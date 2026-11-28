package cc.sighs.informationera.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分组纯逻辑测试：耐久分桶边界、堆叠上限、附存附魔优先级、组键隔离、自然序与反向组序。
 * 不启动游戏。
 */
public class ExplorerGroupingTest {

    @Test
    public void durabilityBucketsUseRemainingRatio() {
        assertEquals("none", ExplorerGrouping.durabilityBucketId(0, 0));
        assertEquals("full", ExplorerGrouping.durabilityBucketId(100, 0));
        assertEquals("repair", ExplorerGrouping.durabilityBucketId(100, 75));
        assertEquals("repair", ExplorerGrouping.durabilityBucketId(100, 100));
        assertEquals("worn", ExplorerGrouping.durabilityBucketId(100, 74));
        assertEquals("worn", ExplorerGrouping.durabilityBucketId(100, 1));
        assertEquals("full", ExplorerGrouping.durabilityBucketId(1, 0));
    }

    @Test
    public void stackAndEnchantmentBucketsAreStable() {
        assertEquals("stack:1", ExplorerGrouping.stackBucketId(1));
        assertEquals("stack:16", ExplorerGrouping.stackBucketId(16));
        assertEquals("stack:64", ExplorerGrouping.stackBucketId(64));
        assertEquals("stack:7", ExplorerGrouping.stackBucketId(7));
        assertEquals("unknown", ExplorerGrouping.stackBucketId(0));

        assertEquals("stored", ExplorerGrouping.enchantmentBucketId(true, false));
        assertEquals("stored", ExplorerGrouping.enchantmentBucketId(true, true));
        assertEquals("enchanted", ExplorerGrouping.enchantmentBucketId(false, true));
        assertEquals("none", ExplorerGrouping.enchantmentBucketId(false, false));
    }

    @Test
    public void groupKeysIncludeBasisAndBucket() {
        String a = ExplorerGrouping.key("mymod:rarity", "common");
        String b = ExplorerGrouping.key("mymod:source", "common");
        assertNotEquals(a, b);
        assertEquals(a, ExplorerGrouping.key("mymod:rarity", "common"));
    }

    @Test
    public void sectionsSortNaturallyAndReverseOnlyGroupOrder() {
        List<ExplorerGrouping.Section> sections = new ArrayList<>();
        sections.add(new ExplorerGrouping.Section("basis", "ten", "10", 10));
        sections.add(new ExplorerGrouping.Section("basis", "two", "2", 2));
        sections.add(new ExplorerGrouping.Section("basis", "one", "1", 1));
        sections.add(new ExplorerGrouping.Section("basis", "a", "A", "A"));
        sections.add(new ExplorerGrouping.Section("basis", "b", "B", "B"));

        ExplorerGrouping.sortSections(sections, false);
        assertEquals(List.of("1", "2", "10", "A", "B"),
                sections.stream().map(section -> section.label).toList());
        for (ExplorerGrouping.Section section : sections) {
            section.entries.add(null);
        }

        ExplorerGrouping.sortSections(sections, true);
        assertEquals(List.of("B", "A", "10", "2", "1"),
                sections.stream().map(section -> section.label).toList());
        assertTrue(sections.stream().allMatch(section -> section.count() == 1));
    }

    @Test
    public void valueComparisonUsesNaturalNumbersAndStrings() {
        assertEquals(0, ExplorerGrouping.compareValues(2, 2));
        assertTrue(ExplorerGrouping.compareValues(2, 10) < 0);
        assertTrue(ExplorerGrouping.compareValues("item2", "item10") < 0);
        assertTrue(ExplorerGrouping.compareValues(null, 1) > 0);
    }
}
