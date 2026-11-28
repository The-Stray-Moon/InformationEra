package cc.sighs.informationera.client;

import cc.sighs.informationera.disk.ItemKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 视觉去重的独立测试：按真实 ItemKey（含组件）保留第一个代表，文件夹与无标识条目永不删除。
 */
public class ExplorerItemDedupeTest {

    @Test
    public void keepsFirstRepresentativeAndAlwaysKeepsFolders() {
        List<FakeEntry> entries = new ArrayList<>();
        entries.add(new FakeEntry(true, null, "folder"));
        entries.add(new FakeEntry(false, new ItemKey("minecraft:apple", ""), "apple-a"));
        entries.add(new FakeEntry(false, new ItemKey("minecraft:apple", ""), "apple-b"));
        entries.add(new FakeEntry(false, new ItemKey("minecraft:apple", "{Damage:1}"), "apple-damaged"));
        entries.add(new FakeEntry(false, null, "unknown"));

        ExplorerItemDedupe.retainFirstByItemKey(entries,
                entry -> entry.folder() || entry.key() == null,
                FakeEntry::key);

        assertEquals(List.of("folder", "apple-a", "apple-damaged", "unknown"),
                entries.stream().map(FakeEntry::name).toList());
    }

    @Test
    public void differentComponentsAreDifferentKeys() {
        List<FakeEntry> entries = new ArrayList<>();
        entries.add(new FakeEntry(false, new ItemKey("minecraft:potion", "{Potion:\"minecraft:swiftness\"}"), "swift"));
        entries.add(new FakeEntry(false, new ItemKey("minecraft:potion", "{Potion:\"minecraft:healing\"}"), "heal"));
        entries.add(new FakeEntry(false, new ItemKey("minecraft:potion", "{Potion:\"minecraft:swiftness\"}"), "swift-2"));

        ExplorerItemDedupe.retainFirstByItemKey(entries, entry -> false, FakeEntry::key);

        assertEquals(List.of("swift", "heal"), entries.stream().map(FakeEntry::name).toList());
    }

    @Test
    public void doesNotSumCountsOrMutateKeptEntries() {
        FakeEntry first = new FakeEntry(false, new ItemKey("minecraft:apple", ""), "apple-a", 3L);
        FakeEntry second = new FakeEntry(false, new ItemKey("minecraft:apple", ""), "apple-b", 7L);
        List<FakeEntry> entries = new ArrayList<>(List.of(first, second));

        ExplorerItemDedupe.retainFirstByItemKey(entries, entry -> false, FakeEntry::key);

        assertEquals(1, entries.size());
        assertEquals(3L, entries.get(0).count());
        assertEquals("apple-a", entries.get(0).name());
    }

    private record FakeEntry(boolean folder, ItemKey key, String name, long count) {
        FakeEntry(boolean folder, ItemKey key, String name) {
            this(folder, key, name, 0L);
        }
    }
}
