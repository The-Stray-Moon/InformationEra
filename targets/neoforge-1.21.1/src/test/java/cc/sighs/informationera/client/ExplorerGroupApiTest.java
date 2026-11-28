package cc.sighs.informationera.client;

import cc.sighs.informationera.api.client.ExplorerColumn;
import cc.sighs.informationera.api.client.ExplorerColumnRegistry;
import cc.sighs.informationera.api.client.ExplorerGroup;
import cc.sighs.informationera.api.client.ExplorerGroupRule;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开分组 API 的独立测试：列声明分组规则、无表头独立依据、ID 校验、注册冲突和内置依据顺序。
 */
public class ExplorerGroupApiTest {

    private static ExplorerGroupRule rule() {
        return ExplorerGroupRule.builder()
                .bucket(ctx -> ExplorerGroupRule.Bucket.of("low", Component.literal("Low"), 0))
                .example(Component.literal("0 - 20,000 FE"))
                .build();
    }

    @Test
    public void columnCanDeclareGroupRuleWithoutChangingExistingCalls() {
        ExplorerColumn plain = ExplorerColumn.builder("sample:plain").value(ctx -> "x").build();
        assertNull(plain.groupRule());

        ExplorerColumn grouped = ExplorerColumn.builder("sample:energy")
                .value(ctx -> "82,000 FE")
                .groupRule(rule())
                .build();
        assertNotNull(grouped.groupRule());
        assertEquals("low", grouped.groupRule().bucket(null).id());
        assertEquals("0 - 20,000 FE", grouped.groupRule().example().getString());
    }

    @Test
    public void standaloneGroupValidatesIdAndRule() {
        assertThrows(IllegalArgumentException.class,
                () -> ExplorerGroup.builder("energy").label(Component.literal("E")).rule(rule()).build());
        assertThrows(NullPointerException.class,
                () -> ExplorerGroup.builder("sample:energy").rule(null).build());
        assertThrows(IllegalArgumentException.class,
                () -> ExplorerGroup.builder("sample:bad|id").rule(rule()).build());

        ExplorerGroup group = ExplorerGroup.builder("sample:energy")
                .label(Component.literal("Energy"))
                .category(ExplorerGroup.Category.ITEM)
                .rule(rule())
                .build();
        assertEquals("sample:energy", group.id());
        assertEquals(ExplorerGroup.Category.ITEM, group.category());
        assertEquals("Energy", group.label().getString());
    }

    @Test
    public void registryRejectsDuplicateGroupIdsAndColumnCollisions() {
        ExplorerColumnRegistry registry = new ExplorerColumnRegistry();
        registry.registerGroup(ExplorerGroup.builder("sample:a").rule(rule()).build());
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerGroup(ExplorerGroup.builder("sample:a").rule(rule()).build()));
        assertEquals(1, registry.groups().size());

        ExplorerColumnRegistry collision = new ExplorerColumnRegistry();
        collision.register(ExplorerColumn.builder("sample:b").value(ctx -> "").groupRule(rule()).build());
        assertThrows(IllegalArgumentException.class,
                () -> collision.registerGroup(ExplorerGroup.builder("sample:b").rule(rule()).build()));
    }

    @Test
    public void builtInGroupsKeepLegacyOrderAndNewItemLocationBases() {
        List<ExplorerGroup> groups = ExplorerColumnService.groups();
        assertTrue(groups.size() >= ExplorerViewState.BUILT_IN_GROUP_IDS.size());
        List<String> ids = groups.subList(0, ExplorerViewState.BUILT_IN_GROUP_IDS.size())
                .stream().map(ExplorerGroup::id).toList();
        assertEquals(ExplorerViewState.BUILT_IN_GROUP_IDS, ids);

        assertNotNull(ExplorerColumnService.group(ExplorerViewState.GROUP_NAME));
        assertNotNull(ExplorerColumnService.group(ExplorerViewState.GROUP_PARENT_FOLDER));
        assertEquals(ExplorerGroup.Category.LOCATION,
                ExplorerColumnService.group(ExplorerViewState.GROUP_PARENT_FOLDER).category());
        assertEquals(ExplorerGroup.Category.ITEM,
                ExplorerColumnService.group(ExplorerViewState.GROUP_RARITY).category());
        assertNull(ExplorerColumnService.group("sample:not_registered"));

        assertNotNull(ExplorerColumnService.column(ExplorerViewState.GROUP_NAME).groupRule());
        assertNull(ExplorerColumnService.column("informationera:registry_id").groupRule());
    }
}
