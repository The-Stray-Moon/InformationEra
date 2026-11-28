package cc.sighs.informationera.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 筛选模板快照与存储的独立测试：自由文本/中文/分隔符往返、空名称拒绝、
 * 同名不区分大小写覆盖、模板排序。不启动游戏。
 */
public class ExplorerFilterTemplateStoreTest {

    private static ExplorerFilterSelection sampleSelection() {
        ExplorerFilterSelection selection = new ExplorerFilterSelection();
        selection.add(ExplorerFilterSelection.Kind.TAG, "minecraft:planks");
        selection.add(ExplorerFilterSelection.Kind.TAG, "c:wood");
        selection.add(ExplorerFilterSelection.Kind.MOD, "sample");
        selection.add(ExplorerFilterSelection.Kind.TAB, "minecraft:building_blocks");
        selection.add(ExplorerFilterSelection.Kind.KEYWORD, "下界合金");
        selection.includeSubfolders = true;
        selection.dedupe = true;
        return selection;
    }

    @Test
    public void snapshotRoundTripsAllFields() {
        ExplorerFilterTemplate template = ExplorerFilterTemplate.capture(
                "战斗 | 模板", "铁 | iron", sampleSelection());

        ExplorerFilterTemplate decoded = ExplorerFilterTemplate.decode(template.encode());
        assertEquals("战斗 | 模板", decoded.name);
        assertEquals("铁 | iron", decoded.search);
        assertEquals(Set.of("minecraft:planks", "c:wood"), decoded.tags);
        assertEquals(Set.of("sample"), decoded.mods);
        assertEquals(Set.of("minecraft:building_blocks"), decoded.tabs);
        assertEquals(Set.of("下界合金"), decoded.keywords);
        assertTrue(decoded.includeSubfolders);
        assertTrue(decoded.dedupe);
    }

    @Test
    public void emptyTrailingFieldsRoundTrip() {
        ExplorerFilterTemplate template = new ExplorerFilterTemplate(
                "empty", "", List.of(), List.of(), List.of(), false, false);
        ExplorerFilterTemplate decoded = ExplorerFilterTemplate.decode(template.encode());
        assertEquals("empty", decoded.name);
        assertEquals("", decoded.search);
        assertTrue(decoded.tags.isEmpty());
        assertTrue(decoded.mods.isEmpty());
        assertTrue(decoded.tabs.isEmpty());
        assertTrue(decoded.keywords.isEmpty());
    }

    @Test
    public void legacyTemplateWithoutKeywordFieldStillLoads() {
        ExplorerFilterTemplate template = ExplorerFilterTemplate.capture(
                "legacy", "oak", sampleSelection());
        String encoded = template.encode();
        String legacy = encoded.substring(0, encoded.lastIndexOf('|'));

        ExplorerFilterTemplate decoded = ExplorerFilterTemplate.decode(legacy);
        assertEquals("legacy", decoded.name);
        assertTrue(decoded.keywords.isEmpty());
        assertEquals(Set.of("minecraft:building_blocks"), decoded.tabs);
    }

    @Test
    public void emptyNameAndMalformedLinesAreRejected() {
        assertNull(ExplorerFilterTemplate.decode(null));
        assertNull(ExplorerFilterTemplate.decode(""));
        assertNull(ExplorerFilterTemplate.decode("v1|name|search|0"));
        assertNull(ExplorerFilterTemplate.decode("v1||search|0|0|||"));
        assertNull(ExplorerFilterTemplate.decode("v2|name|search|0|0|||"));
        assertNull(ExplorerFilterTemplate.decode("v1|!!!|search|0|0|||"));
    }

    @Test
    public void storeOverwritesSameNameIgnoringCase() {
        ExplorerFilterTemplateStore store = new ExplorerFilterTemplateStore();
        store.save(ExplorerFilterTemplate.capture("Combat", "", sampleSelection()));
        assertTrue(store.exists("combat"));
        assertEquals("Combat", store.find("COMBAT").name);

        ExplorerFilterSelection other = new ExplorerFilterSelection();
        other.add(ExplorerFilterSelection.Kind.MOD, "other");
        store.save(ExplorerFilterTemplate.capture("combat", "x", other));

        assertEquals(1, store.all().size());
        ExplorerFilterTemplate stored = store.find("Combat");
        assertEquals("combat", stored.name);
        assertEquals("x", stored.search);
        assertEquals(Set.of("other"), stored.mods);
    }

    @Test
    public void storeIgnoresEmptyName() {
        ExplorerFilterTemplateStore store = new ExplorerFilterTemplateStore();
        store.save(new ExplorerFilterTemplate("  ", "", List.of(), List.of(), List.of(), false, false));
        assertTrue(store.all().isEmpty());
        assertTrue(store.names().isEmpty());
        assertNull(store.find(""));
    }

    @Test
    public void namesAreSortedForDisplay() {
        ExplorerFilterTemplateStore store = new ExplorerFilterTemplateStore();
        store.save(ExplorerFilterTemplate.capture("zeta", "", sampleSelection()));
        store.save(ExplorerFilterTemplate.capture("Alpha", "", sampleSelection()));
        store.save(ExplorerFilterTemplate.capture("beta", "", sampleSelection()));

        assertEquals(List.of("Alpha", "beta", "zeta"), store.names());
    }

    @Test
    public void storeEncodeDecodeKeepsAllTemplates() {
        ExplorerFilterTemplateStore store = new ExplorerFilterTemplateStore();
        store.save(ExplorerFilterTemplate.capture("one", "a", sampleSelection()));
        store.save(ExplorerFilterTemplate.capture("two", "b", new ExplorerFilterSelection()));

        ExplorerFilterTemplateStore decoded = ExplorerFilterTemplateStore.decode(store.encode());
        assertEquals(List.of("one", "two"), decoded.names());
        assertEquals("a", decoded.find("one").search);
        assertEquals("b", decoded.find("two").search);
    }
}
