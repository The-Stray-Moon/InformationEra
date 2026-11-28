package cc.sighs.informationera.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显示偏好存储的独立单元测试：玩家作用域的全局/默认模板、磁盘作用域的文件夹视图、
 * 稳定列 ID、换卡不串写、默认与恢复、多窗口式读改写，以及配置序列化往返。
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
    private static final String SHARED_PRESET = "blocks";
    private static final String NAME = ExplorerViewState.NAME_COLUMN_ID;
    private static final String SIZE = "informationera:size";
    private static final String MODIFIED = "informationera:modified";
    private static final String AUTHOR = "informationera:author";
    private static final String CREATED = "informationera:created";
    private static final String EXTERNAL = "samplecolumns:rarity";

    @Test
    public void unknownPlayerAndFolderGetBuiltInDefaults() {
        ExplorerViewStore store = new ExplorerViewStore();
        ExplorerViewState state = store.view(PLAYER_A, DISK_A, FOLDER_A);
        assertEquals(ExplorerViewState.BUILT_IN_VIEW_MODE, state.viewMode);
        assertEquals(ExplorerViewState.BUILT_IN_SORT_FIELD, state.sortField);
        assertTrue(state.sortAsc);
        assertTrue(state.foldersFirst);
        assertNull(state.groupBy);
        assertEquals(ExplorerViewState.BUILT_IN_COLUMN_WIDTHS[0], state.width(NAME, 0));
        assertTrue(state.visible(NAME, false));
        assertTrue(state.visible("informationera:size", false));
        assertFalse(store.hasOverride(PLAYER_A, DISK_A, FOLDER_A));
    }

    @Test
    public void folderOverridesAreIsolatedByPlayer() {
        ExplorerViewStore store = createStore();
        ExplorerViewState forA = store.view(PLAYER_A, DISK_A, FOLDER_A);
        forA.sortField = SIZE;
        forA.sortAsc = false;
        store.setView(PLAYER_A, DISK_A, FOLDER_A, forA);

        ExplorerViewState forB = store.view(PLAYER_B, DISK_A, FOLDER_A);
        assertEquals(ExplorerViewState.BUILT_IN_SORT_FIELD, forB.sortField);
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
        assertEquals("details", store.view(PLAYER_A, DISK_B, FOLDER_A).viewMode);
    }

    @Test
    public void samePresetFolderOnTwoDisksKeepsIndependentViews() {
        ExplorerViewStore store = createStore();
        ExplorerViewState diskA = store.view(PLAYER_A, DISK_A, SHARED_PRESET);
        diskA.viewMode = "list";
        diskA.sortField = MODIFIED;
        diskA.setWidth(NAME, 410.0);
        store.setView(PLAYER_A, DISK_A, SHARED_PRESET, diskA);

        ExplorerViewState diskB = store.view(PLAYER_A, DISK_B, SHARED_PRESET);
        diskB.viewMode = "medium";
        diskB.groupBy = ExplorerViewState.GROUP_TYPE;
        store.setView(PLAYER_A, DISK_B, SHARED_PRESET, diskB);

        ExplorerViewState a = store.view(PLAYER_A, DISK_A, SHARED_PRESET);
        ExplorerViewState b = store.view(PLAYER_A, DISK_B, SHARED_PRESET);
        assertEquals("list", a.viewMode);
        assertEquals(MODIFIED, a.sortField);
        assertEquals(410.0, a.width(NAME, 0));
        assertNull(a.groupBy);
        assertEquals("medium", b.viewMode);
        assertEquals(ExplorerViewState.GROUP_TYPE, b.groupBy);
        assertEquals(ExplorerViewState.BUILT_IN_COLUMN_WIDTHS[0], b.width(NAME, 0));
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
        template.groupBy = ExplorerViewState.GROUP_TYPE;
        store.setDefaultView(PLAYER_A, template);

        assertTrue(store.display(PLAYER_A).showPreview);
        assertTrue(store.display(PLAYER_A).compact);
        assertEquals("tiles", store.view(PLAYER_A, DISK_B, FOLDER_B).viewMode);
        assertEquals(ExplorerViewState.GROUP_TYPE, store.view(PLAYER_A, DISK_B, FOLDER_B).groupBy);
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
        template.groupBy = ExplorerViewState.GROUP_TYPE;
        store.setDefaultView(PLAYER_A, template);

        assertEquals("list", store.view(PLAYER_A, DISK_A, FOLDER_A).viewMode);
        assertNull(store.view(PLAYER_A, DISK_A, FOLDER_A).groupBy);
        assertEquals("tiles", store.view(PLAYER_A, DISK_A, FOLDER_B).viewMode);
        assertEquals(ExplorerViewState.GROUP_TYPE, store.view(PLAYER_A, DISK_A, FOLDER_B).groupBy);
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
        template.sortField = MODIFIED;
        store.setDefaultView(PLAYER_A, template);

        ExplorerViewState override = store.view(PLAYER_A, DISK_A, FOLDER_A);
        override.sortField = AUTHOR;
        store.setView(PLAYER_A, DISK_A, FOLDER_A, override);
        assertTrue(store.hasOverride(PLAYER_A, DISK_A, FOLDER_A));
        assertEquals(AUTHOR, store.view(PLAYER_A, DISK_A, FOLDER_A).sortField);

        assertTrue(store.resetView(PLAYER_A, DISK_A, FOLDER_A));
        assertFalse(store.hasOverride(PLAYER_A, DISK_A, FOLDER_A));
        assertEquals(MODIFIED, store.view(PLAYER_A, DISK_A, FOLDER_A).sortField);
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

    @Test
    public void readModifyWriteDoesNotClobberAnotherWindowsField() {
        ExplorerViewStore store = createStore();
        ExplorerViewState first = store.view(PLAYER_A, DISK_A, FOLDER_A);
        first.sortField = SIZE;
        store.setView(PLAYER_A, DISK_A, FOLDER_A, first);

        ExplorerViewState second = store.view(PLAYER_A, DISK_A, FOLDER_A);
        second.setWidth(NAME, 500.0);
        store.setView(PLAYER_A, DISK_A, FOLDER_A, second);

        ExplorerViewState merged = store.view(PLAYER_A, DISK_A, FOLDER_A);
        assertEquals(SIZE, merged.sortField);
        assertEquals(500.0, merged.width(NAME, 0));
    }

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
    public void externalColumnSettingsRemainByIdWhenModsAreRemovedAndReadded() {
        ExplorerViewStore store = createStore();
        ExplorerViewState state = store.view(PLAYER_A, DISK_A, FOLDER_A);
        state.ensureColumn(EXTERNAL, 140, false);
        state.setWidth(EXTERNAL, 260.0);
        state.setVisible(EXTERNAL, true);
        store.setView(PLAYER_A, DISK_A, FOLDER_A, state);

        ExplorerViewState reloaded = ExplorerViewStore.decode(store.encode())
                .view(PLAYER_A, DISK_A, FOLDER_A);
        assertTrue(reloaded.hasColumn(EXTERNAL));
        assertEquals(260.0, reloaded.width(EXTERNAL, 0));
        assertTrue(reloaded.visible(EXTERNAL, false));

        // 模拟当前注册列表只剩内置列：重排后第三方设置仍保留，不会被下标串到其它列。
        reloaded.setColumnOrder(List.of(NAME, MODIFIED));
        assertTrue(reloaded.hasColumn(EXTERNAL));
        assertEquals(260.0, reloaded.width(EXTERNAL, 0));
        assertEquals(ExplorerViewState.BUILT_IN_COLUMN_WIDTHS[0], reloaded.width(NAME, 0));
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
        template.groupBy = ExplorerViewState.GROUP_SIZE;
        store.setDefaultView(PLAYER_A, template);

        ExplorerViewState override = store.view(PLAYER_A, DISK_A, FOLDER_A);
        override.viewMode = "xlarge";
        override.sortField = CREATED;
        override.sortAsc = false;
        override.foldersFirst = false;
        override.ensureColumn(EXTERNAL, 140, true);
        override.setWidth(EXTERNAL, 333.0);
        override.setWidth(NAME, 222.0);
        override.setVisible("informationera:created", true);
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
        assertEquals(CREATED, decodedOverride.sortField);
        assertFalse(decodedOverride.sortAsc);
        assertFalse(decodedOverride.foldersFirst);
        assertTrue(decodedOverride.visible("informationera:created", false));
        assertEquals(333.0, decodedOverride.width(EXTERNAL, 0));
        assertEquals(222.0, decodedOverride.width(NAME, 0));
        assertEquals(ExplorerViewState.GROUP_SIZE, decoded.defaultView(PLAYER_A).groupBy);
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
        assertTrue(view.width(NAME, 0) >= 48.0);
        assertTrue(view.visible(NAME, false));
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
    public void groupBasisAndReversePersistPerFolder() {
        ExplorerViewStore store = createStore();
        ExplorerViewState first = store.view(PLAYER_A, DISK_A, FOLDER_A);
        first.groupBy = ExplorerViewState.GROUP_ENCHANTMENT;
        first.groupReverse = true;
        store.setView(PLAYER_A, DISK_A, FOLDER_A, first);

        ExplorerViewState other = store.view(PLAYER_A, DISK_A, FOLDER_B);
        other.groupBy = ExplorerViewState.GROUP_RARITY;
        other.groupReverse = false;
        store.setView(PLAYER_A, DISK_A, FOLDER_B, other);

        ExplorerViewStore decoded = ExplorerViewStore.decode(store.encode());
        assertEquals(ExplorerViewState.GROUP_ENCHANTMENT,
                decoded.view(PLAYER_A, DISK_A, FOLDER_A).groupBy);
        assertTrue(decoded.view(PLAYER_A, DISK_A, FOLDER_A).groupReverse);
        assertEquals(ExplorerViewState.GROUP_RARITY,
                decoded.view(PLAYER_A, DISK_A, FOLDER_B).groupBy);
        assertFalse(decoded.view(PLAYER_A, DISK_A, FOLDER_B).groupReverse);
    }

    @Test
    public void legacySevenPartFolderStateMigratesInsideTheStore() {
        ExplorerViewStore store = ExplorerViewStore.decode(List.of(
                "f2|" + PLAYER_A + "|" + DISK_A + "|blocks|details|size|0|1|type|470,160,120,100,140,100|111100"));
        ExplorerViewState state = store.view(PLAYER_A, DISK_A, "blocks");
        assertEquals(470.0, state.width(NAME, 0));
        assertTrue(state.visible(NAME, false));
        for (int i = 6; i < ExplorerViewState.BUILT_IN_COLUMN_IDS.length; i++) {
            assertFalse(state.visible(ExplorerViewState.BUILT_IN_COLUMN_IDS[i], true));
        }
        assertEquals(ExplorerViewState.BUILT_IN_COLUMN_ORDER, state.columnOrder);
        assertTrue(store.encode().get(0).contains("v3|"));
        assertTrue(store.encode().get(0).contains("informationera:name"));
    }

    @Test
    public void oldVersionedLinesAreIgnored() {
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
        ExplorerViewState.ColumnSetting name = view.columnSettings.get(NAME);
        name.width = Double.NaN;
        view.columnSettings.get(MODIFIED).width = -10.0;
        view.columnSettings.get(NAME).visible = false;
        view.normalize();

        assertEquals(ExplorerViewState.BUILT_IN_VIEW_MODE, view.viewMode);
        assertEquals(ExplorerViewState.BUILT_IN_SORT_FIELD, view.sortField);
        assertNull(view.groupBy);
        assertTrue(view.width(NAME, 0) >= 48.0);
        assertTrue(view.width(MODIFIED, 0) >= 48.0);
        assertTrue(view.visible(NAME, false));
    }

    private static ExplorerViewStore createStore() {
        return new ExplorerViewStore();
    }
}
