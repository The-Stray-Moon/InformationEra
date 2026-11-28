package cc.sighs.informationera.client;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显示偏好存储的独立单元测试：玩家作用域的全局/默认模板、磁盘作用域的文件夹视图、
 * 换卡不串写、默认与恢复、多窗口式读改写，以及配置序列化往返。
 *
 * <p>不启动游戏、不加载 NeoForge 配置，只验证纯数据层。
 */
public class ExplorerViewStoreTest {

    private static final String PLAYER_A = "uuid-player-a";
    private static final String PLAYER_B = "uuid-player-b";
    private static final String DISK_A = "11111111-1111-1111-1111-111111111111";
    private static final String DISK_B = "22222222-2222-2222-2222-222222222222";
    private static final String FOLDER_A = "f_alpha";
    private static final String FOLDER_B = "f_beta";
    /** 两张卡上都会存在的内置目录 id。 */
    private static final String SHARED_PRESET = "blocks";

    @Test
    public void unknownPlayerAndFolderGetBuiltInDefaults() {
        ExplorerViewStore store = new ExplorerViewStore();
        ExplorerViewState state = store.view(PLAYER_A, DISK_A, FOLDER_A);
        assertEquals(ExplorerViewState.BUILT_IN_VIEW_MODE, state.viewMode);
        assertEquals(ExplorerViewState.BUILT_IN_SORT_FIELD, state.sortField);
        assertTrue(state.sortAsc);
        assertTrue(state.foldersFirst);
        assertNull(state.groupBy);
        assertArrayEquals(ExplorerViewState.BUILT_IN_COLUMN_WIDTHS, state.columnWidths);
        assertArrayEquals(ExplorerViewState.BUILT_IN_COLUMN_VISIBLE, state.columnVisible);
        assertFalse(store.hasOverride(PLAYER_A, DISK_A, FOLDER_A));
    }

    @Test
    public void folderOverridesAreIsolatedByPlayer() {
        ExplorerViewStore store = createStore();
        ExplorerViewState forA = store.view(PLAYER_A, DISK_A, FOLDER_A);
        forA.sortField = "size";
        forA.sortAsc = false;
        store.setView(PLAYER_A, DISK_A, FOLDER_A, forA);

        ExplorerViewState forB = store.view(PLAYER_B, DISK_A, FOLDER_A);
        assertEquals("name", forB.sortField);
        assertTrue(forB.sortAsc);
        assertFalse(store.hasOverride(PLAYER_B, DISK_A, FOLDER_A));
    }

    @Test
    public void folderOverridesAreIsolatedByFolderAndDisk() {
        ExplorerViewStore store = createStore();
        ExplorerViewState forA = store.view(PLAYER_A, DISK_A, FOLDER_A);
        forA.viewMode = "large";
        store.setView(PLAYER_A, DISK_A, FOLDER_A, forA);

        assertEquals("large", store.view(PLAYER_A, DISK_A, FOLDER_A).viewMode);
        assertEquals("details", store.view(PLAYER_A, DISK_A, FOLDER_B).viewMode);
        // 同一个内置目录 id 在另一张卡上必须是另一份记录。
        assertEquals("details", store.view(PLAYER_A, DISK_B, FOLDER_A).viewMode);
    }

    @Test
    public void samePresetFolderOnTwoDisksKeepsIndependentViews() {
        ExplorerViewStore store = createStore();
        ExplorerViewState diskA = store.view(PLAYER_A, DISK_A, SHARED_PRESET);
        diskA.viewMode = "list";
        diskA.sortField = "modified";
        diskA.columnWidths[0] = 410.0;
        store.setView(PLAYER_A, DISK_A, SHARED_PRESET, diskA);

        ExplorerViewState diskB = store.view(PLAYER_A, DISK_B, SHARED_PRESET);
        diskB.viewMode = "medium";
        diskB.groupBy = "type";
        store.setView(PLAYER_A, DISK_B, SHARED_PRESET, diskB);

        ExplorerViewState a = store.view(PLAYER_A, DISK_A, SHARED_PRESET);
        ExplorerViewState b = store.view(PLAYER_A, DISK_B, SHARED_PRESET);
        assertEquals("list", a.viewMode);
        assertEquals("modified", a.sortField);
        assertEquals(410.0, a.columnWidths[0]);
        assertNull(a.groupBy);
        assertEquals("medium", b.viewMode);
        assertEquals("type", b.groupBy);
        assertEquals(ExplorerViewState.BUILT_IN_COLUMN_WIDTHS[0], b.columnWidths[0]);
    }

    @Test
    public void globalDisplayAndDefaultTemplateFollowPlayerAcrossDisks() {
        ExplorerViewStore store = createStore();
        ExplorerDisplayState display = store.display(PLAYER_A);
        display.showPreview = true;
        display.compact = true;
        store.setDisplay(PLAYER_A, display);

        ExplorerViewState template = store.defaultView(PLAYER_A);
        template.viewMode = "tiles";
        template.groupBy = "type";
        store.setDefaultView(PLAYER_A, template);

        // 同一玩家换到另一张卡/另一个世界：全局显示与默认模板仍然生效。
        assertTrue(store.display(PLAYER_A).showPreview);
        assertTrue(store.display(PLAYER_A).compact);
        assertEquals("tiles", store.view(PLAYER_A, DISK_B, FOLDER_B).viewMode);
        assertEquals("type", store.view(PLAYER_A, DISK_B, FOLDER_B).groupBy);
        // 另一个玩家不受影响。
        assertFalse(store.display(PLAYER_B).showPreview);
        assertEquals("details", store.view(PLAYER_B, DISK_B, FOLDER_B).viewMode);
    }

    @Test
    public void defaultTemplateAppliesToNewFoldersWithoutTouchingExistingOverrides() {
        ExplorerViewStore store = createStore();
        ExplorerViewState existing = store.view(PLAYER_A, DISK_A, FOLDER_A);
        existing.viewMode = "list";
        store.setView(PLAYER_A, DISK_A, FOLDER_A, existing);

        ExplorerViewState template = store.defaultView(PLAYER_A);
        template.viewMode = "tiles";
        template.groupBy = "type";
        store.setDefaultView(PLAYER_A, template);

        assertEquals("list", store.view(PLAYER_A, DISK_A, FOLDER_A).viewMode);
        assertNull(store.view(PLAYER_A, DISK_A, FOLDER_A).groupBy);
        assertEquals("tiles", store.view(PLAYER_A, DISK_A, FOLDER_B).viewMode);
        assertEquals("type", store.view(PLAYER_A, DISK_A, FOLDER_B).groupBy);
    }

    @Test
    public void settingDefaultTemplateDropsRedundantFolderOverridesOnEveryDisk() {
        ExplorerViewStore store = createStore();
        ExplorerViewState overrideA = store.view(PLAYER_A, DISK_A, FOLDER_A);
        overrideA.viewMode = "tiles";
        store.setView(PLAYER_A, DISK_A, FOLDER_A, overrideA);
        ExplorerViewState overrideB = store.view(PLAYER_A, DISK_B, SHARED_PRESET);
        overrideB.viewMode = "tiles";
        store.setView(PLAYER_A, DISK_B, SHARED_PRESET, overrideB);

        ExplorerViewState template = store.defaultView(PLAYER_A);
        template.viewMode = "tiles";
        store.setDefaultView(PLAYER_A, template);
        assertFalse(store.hasOverride(PLAYER_A, DISK_A, FOLDER_A));
        assertFalse(store.hasOverride(PLAYER_A, DISK_B, SHARED_PRESET));
        assertEquals("tiles", store.view(PLAYER_A, DISK_B, SHARED_PRESET).viewMode);
    }

    @Test
    public void restoringFolderFallsBackToDefaultTemplate() {
        ExplorerViewStore store = createStore();
        ExplorerViewState template = store.defaultView(PLAYER_A);
        template.sortField = "modified";
        store.setDefaultView(PLAYER_A, template);

        ExplorerViewState override = store.view(PLAYER_A, DISK_A, FOLDER_A);
        override.sortField = "author";
        store.setView(PLAYER_A, DISK_A, FOLDER_A, override);
        assertTrue(store.hasOverride(PLAYER_A, DISK_A, FOLDER_A));
        assertEquals("author", store.view(PLAYER_A, DISK_A, FOLDER_A).sortField);

        assertTrue(store.resetView(PLAYER_A, DISK_A, FOLDER_A));
        assertFalse(store.hasOverride(PLAYER_A, DISK_A, FOLDER_A));
        assertEquals("modified", store.view(PLAYER_A, DISK_A, FOLDER_A).sortField);
    }

    @Test
    public void savingTheEffectiveDefaultRemovesTheRedundantOverride() {
        ExplorerViewStore store = createStore();
        ExplorerViewState state = store.view(PLAYER_A, DISK_A, FOLDER_A);
        state.viewMode = "content";
        store.setView(PLAYER_A, DISK_A, FOLDER_A, state);
        assertTrue(store.hasOverride(PLAYER_A, DISK_A, FOLDER_A));

        store.setView(PLAYER_A, DISK_A, FOLDER_A, store.defaultView(PLAYER_A));
        assertFalse(store.hasOverride(PLAYER_A, DISK_A, FOLDER_A));
    }

    @Test
    public void resetAllViewsOnlyTouchesTheRequestedDisk() {
        ExplorerViewStore store = createStore();
        ExplorerViewState a = store.view(PLAYER_A, DISK_A, FOLDER_A);
        a.viewMode = "list";
        store.setView(PLAYER_A, DISK_A, FOLDER_A, a);
        ExplorerViewState b = store.view(PLAYER_A, DISK_B, FOLDER_A);
        b.viewMode = "medium";
        store.setView(PLAYER_A, DISK_B, FOLDER_A, b);

        assertTrue(store.resetAllViews(PLAYER_A, DISK_A));
        assertFalse(store.hasOverride(PLAYER_A, DISK_A, FOLDER_A));
        assertTrue(store.hasOverride(PLAYER_A, DISK_B, FOLDER_A));
        assertFalse(store.resetAllViews(PLAYER_A, DISK_A));
    }

    /**
     * 模拟两个窗口：各自只改自己的那一项，第二次写入不能把第一次的修改覆盖掉。
     */
    @Test
    public void readModifyWriteDoesNotClobberAnotherWindowsField() {
        ExplorerViewStore store = createStore();
        ExplorerViewState first = store.view(PLAYER_A, DISK_A, FOLDER_A);
        first.sortField = "size";
        store.setView(PLAYER_A, DISK_A, FOLDER_A, first);

        ExplorerViewState second = store.view(PLAYER_A, DISK_A, FOLDER_A);
        second.columnWidths[0] = 500.0;
        store.setView(PLAYER_A, DISK_A, FOLDER_A, second);

        ExplorerViewState merged = store.view(PLAYER_A, DISK_A, FOLDER_A);
        assertEquals("size", merged.sortField);
        assertEquals(500.0, merged.columnWidths[0]);
    }

    /**
     * 切换磁盘前还没写盘的修改留在旧磁盘记录里；切换后的修改落到新磁盘，二者不串写。
     */
    @Test
    public void delayedChangesStayWithTheirDisk() {
        ExplorerViewStore store = createStore();
        ExplorerViewState a = store.view(PLAYER_A, DISK_A, SHARED_PRESET);
        a.viewMode = "xlarge";
        store.setView(PLAYER_A, DISK_A, SHARED_PRESET, a);

        ExplorerViewState b = store.view(PLAYER_A, DISK_B, SHARED_PRESET);
        b.viewMode = "small";
        store.setView(PLAYER_A, DISK_B, SHARED_PRESET, b);

        ExplorerViewStore decoded = ExplorerViewStore.decode(store.encode());
        assertEquals("xlarge", decoded.view(PLAYER_A, DISK_A, SHARED_PRESET).viewMode);
        assertEquals("small", decoded.view(PLAYER_A, DISK_B, SHARED_PRESET).viewMode);
    }

    @Test
    public void globalDisplayIsIsolatedByIdentityAndEqualValuesAreNotStored() {
        ExplorerViewStore store = createStore();
        ExplorerDisplayState forA = store.display(PLAYER_A);
        forA.checkboxes = true;
        forA.navWidth = 300.0;
        store.setDisplay(PLAYER_A, forA);

        assertTrue(store.display(PLAYER_A).checkboxes);
        assertEquals(300.0, store.display(PLAYER_A).navWidth);
        assertFalse(store.display(PLAYER_B).checkboxes);
        assertEquals(ExplorerDisplayState.BUILT_IN_NAV_WIDTH, store.display(PLAYER_B).navWidth);

        store.setDisplay(PLAYER_A, ExplorerDisplayState.defaults());
        assertTrue(store.encode().stream().noneMatch(line -> line.startsWith("g2|")));
    }

    @Test
    public void encodeDecodeRoundTripPreservesEverything() {
        ExplorerViewStore store = createStore();
        ExplorerDisplayState display = store.display(PLAYER_A);
        display.showPreview = true;
        display.showDetails = true;
        display.previewWidth = 410.0;
        display.compact = true;
        store.setDisplay(PLAYER_A, display);

        ExplorerViewState template = store.defaultView(PLAYER_A);
        template.viewMode = "tiles";
        template.groupBy = "size";
        store.setDefaultView(PLAYER_A, template);

        ExplorerViewState override = store.view(PLAYER_A, DISK_A, FOLDER_A);
        override.viewMode = "xlarge";
        override.sortField = "created";
        override.sortAsc = false;
        override.foldersFirst = false;
        override.columnVisible[4] = true;
        override.columnWidths[4] = 222.0;
        store.setView(PLAYER_A, DISK_A, FOLDER_A, override);

        ExplorerViewState otherDisk = store.view(PLAYER_A, DISK_B, SHARED_PRESET);
        otherDisk.viewMode = "small";
        store.setView(PLAYER_A, DISK_B, SHARED_PRESET, otherDisk);

        ExplorerViewStore decoded = ExplorerViewStore.decode(store.encode());
        assertEquals(store.encode(), decoded.encode());

        ExplorerDisplayState decodedDisplay = decoded.display(PLAYER_A);
        assertTrue(decodedDisplay.showPreview);
        assertTrue(decodedDisplay.showDetails);
        assertEquals(410.0, decodedDisplay.previewWidth);
        assertTrue(decodedDisplay.compact);

        ExplorerViewState decodedOverride = decoded.view(PLAYER_A, DISK_A, FOLDER_A);
        assertEquals("xlarge", decodedOverride.viewMode);
        assertEquals("created", decodedOverride.sortField);
        assertFalse(decodedOverride.sortAsc);
        assertFalse(decodedOverride.foldersFirst);
        assertTrue(decodedOverride.columnVisible[4]);
        assertEquals(222.0, decodedOverride.columnWidths[4]);
        assertEquals("size", decoded.defaultView(PLAYER_A).groupBy);
        assertEquals("small", decoded.view(PLAYER_A, DISK_B, SHARED_PRESET).viewMode);
    }

    @Test
    public void malformedLinesAndValuesFallBackWithoutThrowing() {
        ExplorerViewStore store = ExplorerViewStore.decode(List.of(
                "",
                "garbage",
                "g||1|0|0|240|240|0|0|1",
                "g2||1|0|0|240|240|0|0|1",
                "f2|" + PLAYER_A + "|" + DISK_A + "|" + FOLDER_A + "|not-a-view",
                "d2|" + PLAYER_A + "|details|name|1|1||0,0,0,0,0,0|000000"));

        ExplorerDisplayState display = store.display(PLAYER_A);
        assertEquals(ExplorerDisplayState.BUILT_IN_NAV_WIDTH, display.navWidth);
        ExplorerViewState view = store.view(PLAYER_A, DISK_A, FOLDER_A);
        assertEquals(ExplorerViewState.BUILT_IN_VIEW_MODE, view.viewMode);
        assertTrue(view.columnWidths[0] >= 48.0);
        assertTrue(view.columnVisible[0]);
    }

    @Test
    public void missingFoldersArePrunedOnlyForTheRequestedPlayerAndDisk() {
        ExplorerViewStore store = createStore();
        ExplorerViewState forA = store.view(PLAYER_A, DISK_A, FOLDER_A);
        forA.viewMode = "list";
        store.setView(PLAYER_A, DISK_A, FOLDER_A, forA);
        ExplorerViewState forB = store.view(PLAYER_B, DISK_A, FOLDER_A);
        forB.viewMode = "medium";
        store.setView(PLAYER_B, DISK_A, FOLDER_A, forB);
        ExplorerViewState otherDisk = store.view(PLAYER_A, DISK_B, FOLDER_A);
        otherDisk.viewMode = "tiles";
        store.setView(PLAYER_A, DISK_B, FOLDER_A, otherDisk);

        assertTrue(store.pruneFolders(PLAYER_A, DISK_A, key -> false));
        assertFalse(store.hasOverride(PLAYER_A, DISK_A, FOLDER_A));
        assertTrue(store.hasOverride(PLAYER_B, DISK_A, FOLDER_A));
        assertTrue(store.hasOverride(PLAYER_A, DISK_B, FOLDER_A));
    }

    @Test
    public void oldVersionedLinesAreIgnored() {
        // 上一轮尚未发布的格式：identity 里带服务器，tag 无 2 后缀；不应被误读成玩家/磁盘。
        ExplorerViewStore store = ExplorerViewStore.decode(List.of(
                "g|380df991@localhost|1|1|1|320|330|1|1|1",
                "d|380df991@localhost|large|name|1|1||320,160,120,100,140,100|111100",
                "f|380df991@localhost|blocks|details|size|0|1|type|470,160,120,100,140,100|111100"));
        assertTrue(store.encode().isEmpty());
    }

    @Test
    public void outOfRangeValuesAreNormalized() {
        ExplorerViewState view = ExplorerViewState.defaults();
        view.viewMode = "nonsense";
        view.sortField = "nonsense";
        view.groupBy = "nonsense";
        view.columnWidths[0] = Double.NaN;
        view.columnWidths[1] = -10.0;
        Arrays.fill(view.columnVisible, false);
        view.normalize();

        assertEquals(ExplorerViewState.BUILT_IN_VIEW_MODE, view.viewMode);
        assertEquals(ExplorerViewState.BUILT_IN_SORT_FIELD, view.sortField);
        assertNull(view.groupBy);
        assertEquals(ExplorerViewState.BUILT_IN_COLUMN_WIDTHS[0], view.columnWidths[0]);
        assertTrue(view.columnWidths[1] >= 48.0);
        assertTrue(view.columnVisible[0]);

        ExplorerDisplayState display = ExplorerDisplayState.defaults();
        display.navWidth = -5.0;
        display.previewWidth = Double.NaN;
        display.normalize();
        assertTrue(display.navWidth >= 120.0);
        assertEquals(ExplorerDisplayState.BUILT_IN_PREVIEW_WIDTH, display.previewWidth);
    }

    private static ExplorerViewStore createStore() {
        // 每次用新实例，测试之间不共享状态。
        return new ExplorerViewStore();
    }
}
