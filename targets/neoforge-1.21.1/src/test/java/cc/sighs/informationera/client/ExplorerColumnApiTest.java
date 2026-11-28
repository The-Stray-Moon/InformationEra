package cc.sighs.informationera.client;

import cc.sighs.informationera.api.client.ExplorerColumn;
import cc.sighs.informationera.api.client.ExplorerColumnRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开列 API 的独立单元测试：ID 校验、默认值、注册重复、内置列以及异常隔离。
 */
public class ExplorerColumnApiTest {

    @Test
    public void idMustBeNamespacedAndValueIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> ExplorerColumn.builder("rarity").value(ctx -> "").build());
        assertThrows(IllegalArgumentException.class,
                () -> ExplorerColumn.builder("sample:rarity").build());
        assertThrows(IllegalArgumentException.class,
                () -> ExplorerColumn.builder("sample:bad|id").value(ctx -> "").build());
        ExplorerColumn column = ExplorerColumn.builder("sample:rarity")
                .value(ctx -> "common")
                .build();
        assertEquals("sample:rarity", column.id());
        assertFalse(column.defaultVisible());
        assertEquals(120, column.defaultWidth());
    }

    @Test
    public void builderClampsWidthAndKeepsLabelValue() {
        ExplorerColumn column = ExplorerColumn.builder("sample:value")
                .defaultWidth(1)
                .defaultVisible(true)
                .value(ctx -> "42")
                .sortValue(ctx -> 42)
                .build();
        assertEquals(48, column.defaultWidth());
        assertTrue(column.defaultVisible());
        assertEquals("42", column.value(null));
        assertEquals(42, column.sortValue(null));
    }

    @Test
    public void registryRejectsDuplicateIds() {
        ExplorerColumnRegistry registry = new ExplorerColumnRegistry();
        registry.register(ExplorerColumn.builder("sample:a").value(ctx -> "").build());
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(ExplorerColumn.builder("sample:a").value(ctx -> "").build()));
        assertEquals(1, registry.columns().size());
    }

    @Test
    public void builtInColumnsUseStableNamespacedIds() {
        List<ExplorerColumn> columns = ExplorerColumnService.columns();
        assertTrue(columns.size() >= ExplorerViewState.BUILT_IN_COLUMN_IDS.length);
        for (String id : ExplorerViewState.BUILT_IN_COLUMN_IDS) {
            assertTrue(ExplorerColumnService.column(id) != null, "missing built-in " + id);
        }
        assertEquals(List.of(
                        "informationera:name", "informationera:modified", "informationera:type",
                        "informationera:size", "informationera:created", "informationera:author"),
                ExplorerColumnService.commonColumnIds());
    }

    @Test
    public void providerFailuresAreIsolatedPerColumn() {
        ExplorerColumn badValue = ExplorerColumn.builder("sample:bad_value")
                .value(ctx -> {
                    throw new IllegalStateException("intentional value failure");
                })
                .build();
        ExplorerColumn badLabel = ExplorerColumn.builder("sample:bad_label")
                .label(() -> {
                    throw new IllegalStateException("intentional label failure");
                })
                .value(ctx -> "ok")
                .build();
        ExplorerColumn badCompare = ExplorerColumn.builder("sample:bad_compare")
                .value(ctx -> "ok")
                .comparator((a, b) -> {
                    throw new IllegalStateException("intentional compare failure");
                })
                .build();
        assertEquals("", ExplorerColumnService.value(badValue, null));
        assertEquals("sample:bad_label", ExplorerColumnService.label(badLabel).getString());
        assertEquals(0, ExplorerColumnService.compare(badCompare, null, null));
    }

    @Test
    public void numericAndStringComparatorsWork() {
        ExplorerColumn number = ExplorerColumn.builder("sample:number")
                .value(ctx -> "")
                .sortValue(ctx -> 7)
                .build();
        ExplorerColumn string = ExplorerColumn.builder("sample:string")
                .value(ctx -> "")
                .sortValue(ctx -> "abc")
                .build();
        assertEquals(0, ExplorerColumnService.compare(number, null, null));
        assertEquals(0, ExplorerColumnService.compare(string, null, null));
    }

}
