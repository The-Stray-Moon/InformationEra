package cc.sighs.informationera.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列模型/旧配置迁移的独立测试：命名空间 ID、旧 6 列记录补全新字段、列序往返、
 * 第三方列设置保留、名称必显、非法列序归一化。不启动游戏。
 */
public class ExplorerViewStateTest {

    private static final String LEGACY =
            "details|size|0|1|type|470,160,120,100,140,100|111100";
    private static final String NAME = ExplorerViewState.NAME_COLUMN_ID;
    private static final String EXTERNAL = "samplecolumns:rarity";

    @Test
    public void legacySixColumnStateMigratesToNamespacedIds() {
        ExplorerViewState state = ExplorerViewState.decode(LEGACY);
        assertEquals("informationera:size", state.sortField);
        assertFalse(state.sortAsc);
        assertEquals("type", state.groupBy);
        assertEquals(470.0, state.width(NAME, 0));
        assertEquals(ExplorerViewState.BUILT_IN_COLUMN_WIDTHS[6],
                state.width("informationera:registry_id", 0));
        assertTrue(state.visible(NAME, false));
        assertTrue(state.visible("informationera:size", false));
        for (int i = 6; i < ExplorerViewState.BUILT_IN_COLUMN_IDS.length; i++) {
            assertFalse(state.visible(ExplorerViewState.BUILT_IN_COLUMN_IDS[i], true),
                    "new built-in column " + i + " must start hidden");
        }
        assertEquals(ExplorerViewState.BUILT_IN_COLUMN_ORDER, state.columnOrder);

        String encoded = state.encode();
        assertTrue(encoded.startsWith("v2|"));
        assertTrue(encoded.contains("informationera:name,informationera:modified"));
    }

    @Test
    public void externalColumnOrderAndSettingsRoundTripByStableId() {
        ExplorerViewState state = ExplorerViewState.defaults();
        state.ensureColumn(EXTERNAL, 137, true);
        state.setWidth(EXTERNAL, 222.0);
        state.setVisible(EXTERNAL, false);
        List<String> order = new ArrayList<>();
        order.add(EXTERNAL);
        order.add(NAME);
        order.add("informationera:modified");
        state.setColumnOrder(order);

        ExplorerViewState decoded = ExplorerViewState.decode(state.encode());
        assertEquals(EXTERNAL, decoded.columnOrder.get(0));
        assertEquals(222.0, decoded.width(EXTERNAL, 0));
        assertFalse(decoded.visible(EXTERNAL, true));
        assertEquals(state.columnOrder, decoded.columnOrder);
        assertEquals(state, decoded);
    }

    @Test
    public void unregisteredColumnSettingsSurviveColumnOrderRewrite() {
        ExplorerViewState state = ExplorerViewState.defaults();
        state.ensureColumn(EXTERNAL, 150, false);
        state.setWidth(EXTERNAL, 321.0);
        state.setVisible(EXTERNAL, true);

        // 当前注册列里没有这个第三方列时，重排内置列不能抹掉它的设置/顺序记录。
        state.setColumnOrder(List.of(NAME, "informationera:modified"));
        assertTrue(state.hasColumn(EXTERNAL));
        assertEquals(321.0, state.width(EXTERNAL, 0));
        assertTrue(state.visible(EXTERNAL, false));
        assertTrue(state.columnOrder.contains(EXTERNAL));
    }

    @Test
    public void nameColumnIsAlwaysVisibleEvenIfLegacyConfigSaysOtherwise() {
        String legacyNameHidden = "details|name|1|1||320,160,120,100,140,100|011100";
        ExplorerViewState state = ExplorerViewState.decode(legacyNameHidden);
        assertTrue(state.visible(NAME, false));
        assertTrue(state.visible("informationera:modified", false));
        assertEquals(NAME, state.columnOrder.get(0));
    }

    @Test
    public void builtInIdsAreNamespacedAndUnique() {
        assertEquals(10, ExplorerViewState.BUILT_IN_COLUMN_IDS.length);
        assertEquals(10, ExplorerViewState.BUILT_IN_COLUMN_WIDTHS.length);
        assertEquals(10, ExplorerViewState.BUILT_IN_COLUMN_VISIBLE.length);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String id : ExplorerViewState.BUILT_IN_COLUMN_IDS) {
            assertTrue(id.contains(":"));
            assertTrue(ids.add(id));
        }
        assertEquals("informationera:registry_id", ExplorerViewState.BUILT_IN_COLUMN_IDS[6]);
        assertEquals("informationera:namespace", ExplorerViewState.BUILT_IN_COLUMN_IDS[7]);
        assertEquals("informationera:max_stack", ExplorerViewState.BUILT_IN_COLUMN_IDS[8]);
        assertEquals("informationera:durability", ExplorerViewState.BUILT_IN_COLUMN_IDS[9]);
    }
}
