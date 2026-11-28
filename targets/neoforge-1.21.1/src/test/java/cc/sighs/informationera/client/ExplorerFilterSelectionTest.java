package cc.sighs.informationera.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 筛选条件匹配的独立测试：同类 OR、不同类 AND、空类忽略、去重/移除，
 * 以及“展开所有子文件夹”不参与匹配。不启动游戏。
 */
public class ExplorerFilterSelectionTest {

    private static final String PLANKS = "minecraft:planks";
    private static final String LOGS = "minecraft:logs";

    @Test
    public void emptySelectionMatchesEverything() {
        ExplorerFilterSelection selection = new ExplorerFilterSelection();
        assertTrue(selection.isEmpty());
        assertTrue(selection.matches(null, Set.of(), Set.of()));
        assertTrue(selection.matches("minecraft", Set.of(PLANKS), Set.of("minecraft:building_blocks")));
    }

    @Test
    public void sameKindIsOr() {
        ExplorerFilterSelection selection = new ExplorerFilterSelection();
        selection.add(ExplorerFilterSelection.Kind.TAG, PLANKS);
        selection.add(ExplorerFilterSelection.Kind.TAG, LOGS);

        assertTrue(selection.matches("minecraft", Set.of(PLANKS), Set.of()));
        assertTrue(selection.matches("minecraft", Set.of("minecraft:logs", "minecraft:overworld"), Set.of()));
        assertFalse(selection.matches("minecraft", Set.of("minecraft:stone"), Set.of()));
        assertFalse(selection.matches("minecraft", Set.of(), Set.of()));
    }

    @Test
    public void differentKindsAreAnd() {
        ExplorerFilterSelection selection = new ExplorerFilterSelection();
        selection.add(ExplorerFilterSelection.Kind.TAG, PLANKS);
        selection.add(ExplorerFilterSelection.Kind.MOD, "minecraft");
        selection.add(ExplorerFilterSelection.Kind.TAB, "minecraft:building_blocks");

        assertTrue(selection.matches("minecraft", Set.of(PLANKS), Set.of("minecraft:building_blocks")));
        // 标签满足但创造页不满足。
        assertFalse(selection.matches("minecraft", Set.of(PLANKS), Set.of("minecraft:tools")));
        // 标签和创造页满足但模组不满足。
        assertFalse(selection.matches("sample", Set.of(PLANKS), Set.of("minecraft:building_blocks")));
        // 缺少事实时不能误判为满足。
        assertFalse(selection.matches(null, Set.of(PLANKS), Set.of("minecraft:building_blocks")));
    }

    @Test
    public void emptyCategoryDoesNotConstrain() {
        ExplorerFilterSelection selection = new ExplorerFilterSelection();
        selection.add(ExplorerFilterSelection.Kind.MOD, "sample");

        assertTrue(selection.matches("sample", Set.of(), Set.of()));
        assertFalse(selection.matches("minecraft", Set.of(PLANKS), Set.of("minecraft:building_blocks")));
    }

    @Test
    public void addDeduplicatesAndRemoves() {
        ExplorerFilterSelection selection = new ExplorerFilterSelection();
        assertTrue(selection.add(ExplorerFilterSelection.Kind.TAG, "  minecraft:planks  "));
        assertFalse(selection.add(ExplorerFilterSelection.Kind.TAG, PLANKS));
        assertFalse(selection.add(ExplorerFilterSelection.Kind.TAG, "   "));
        assertFalse(selection.add(ExplorerFilterSelection.Kind.TAG, null));
        assertEquals(Set.of(PLANKS), selection.values(ExplorerFilterSelection.Kind.TAG));
        assertEquals(1, selection.size(ExplorerFilterSelection.Kind.TAG));

        assertTrue(selection.remove(ExplorerFilterSelection.Kind.TAG, PLANKS));
        assertFalse(selection.remove(ExplorerFilterSelection.Kind.TAG, PLANKS));
        assertTrue(selection.isEmpty());
    }

    @Test
    public void valuesKeepInsertionOrderAndAreNotLive() {
        ExplorerFilterSelection selection = new ExplorerFilterSelection();
        selection.add(ExplorerFilterSelection.Kind.TAG, LOGS);
        selection.add(ExplorerFilterSelection.Kind.TAG, PLANKS);

        List<String> before = List.copyOf(selection.values(ExplorerFilterSelection.Kind.TAG));
        assertEquals(List.of(LOGS, PLANKS), before);

        selection.add(ExplorerFilterSelection.Kind.TAG, "minecraft:stone");
        assertEquals(2, before.size());
        assertEquals(3, selection.size(ExplorerFilterSelection.Kind.TAG));
    }

    @Test
    public void expandSubfoldersDoesNotAffectMatching() {
        ExplorerFilterSelection selection = new ExplorerFilterSelection();
        selection.add(ExplorerFilterSelection.Kind.MOD, "sample");
        selection.includeSubfolders = true;

        assertTrue(selection.matches("sample", Set.of(), Set.of()));
        assertFalse(selection.matches("minecraft", Set.of(), Set.of()));
    }
}
