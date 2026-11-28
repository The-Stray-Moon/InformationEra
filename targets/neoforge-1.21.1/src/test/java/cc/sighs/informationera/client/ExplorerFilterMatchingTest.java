package cc.sighs.informationera.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 筛选输入匹配的独立测试：补全与回车共用显示文本匹配；创造标签页用本地化名称匹配、
 * 但返回稳定 id；标签/模组保留英文 id 语义并允许手工值。
 *
 * <p>测试类路径没有 JECh，因此这里验证无 JECh 的本地化原名回退；JECh 全拼/首字母
 * 需要在真实客户端中验证。
 */
public class ExplorerFilterMatchingTest {

    private static final List<String> TABS = List.of(
            "minecraft:building_blocks",
            "minecraft:tools",
            "minecraft:food");
    private static final Function<String, String> TAB_LABELS = value -> switch (value) {
        case "minecraft:building_blocks" -> "建筑方块";
        case "minecraft:tools" -> "工具";
        case "minecraft:food" -> "食物";
        default -> value;
    };

    @Test
    public void localizedTabNameMatchesWithoutJech() {
        assertEquals(List.of("minecraft:building_blocks"),
                ExplorerFilterMatching.suggestions("建筑", 8, TABS, TAB_LABELS, true));
        assertEquals(List.of("minecraft:tools"),
                ExplorerFilterMatching.suggestions("工具", 8, TABS, TAB_LABELS, true));
    }

    @Test
    public void tabPinyinDoesNotBecomeRawStableIdWithoutJech() {
        // 不依赖 JECh 的测试环境里拼音不命中；更重要的是不能把输入原文当作 registry id。
        assertTrue(ExplorerFilterMatching.suggestions("jz", 8, TABS, TAB_LABELS, true).isEmpty());
        assertTrue(ExplorerFilterMatching.resolve("jz", false, TABS, TAB_LABELS, true).isEmpty());
    }

    @Test
    public void tabResolutionKeepsStableId() {
        assertEquals(List.of("minecraft:building_blocks"),
                ExplorerFilterMatching.resolve("建筑", false, TABS, TAB_LABELS, true));
        assertEquals(List.of("minecraft:building_blocks"),
                ExplorerFilterMatching.resolve("建筑方块", false, TABS, TAB_LABELS, true));
        assertEquals(List.of("minecraft:building_blocks"),
                ExplorerFilterMatching.resolve("minecraft:building_blocks", false, TABS, TAB_LABELS, true));
    }

    @Test
    public void enterUsesSameCandidateAsAutocomplete() {
        List<String> suggestions = ExplorerFilterMatching.suggestions("工具", 8, TABS, TAB_LABELS, true);
        assertEquals(suggestions.subList(0, 1),
                ExplorerFilterMatching.resolve("工具", false, TABS, TAB_LABELS, true));
    }

    @Test
    public void commaInputResolvesEachPiece() {
        assertEquals(List.of("minecraft:building_blocks", "minecraft:tools"),
                ExplorerFilterMatching.resolve("建筑方块，工具", false, TABS, TAB_LABELS, true));
    }

    @Test
    public void tagAndModKeepIdSemanticsAndAllowRawValues() {
        List<String> ids = List.of("minecraft:planks", "sample");
        Function<String, String> labels = Function.identity();

        assertEquals(List.of("minecraft:planks"),
                ExplorerFilterMatching.suggestions("PLANKS", 8, ids, labels, false));
        assertEquals(List.of("custom_tag"),
                ExplorerFilterMatching.resolve("custom_tag", true, ids, labels, false));
        assertEquals(List.of("minecraft:planks"),
                ExplorerFilterMatching.resolve("Minecraft:Planks", true, ids, labels, false));
    }

    @Test
    public void tagSemanticsDoNotUseLocalizedOrPinyinMatching() {
        List<String> ids = List.of("minecraft:planks");
        Function<String, String> labels = Function.identity();
        assertTrue(ExplorerFilterMatching.suggestions("木板", 8, ids, labels, false).isEmpty());
        assertTrue(ExplorerFilterMatching.suggestions("muban", 8, ids, labels, false).isEmpty());
        assertEquals(List.of("minecraft:planks"),
                ExplorerFilterMatching.suggestions("PLANKS", 8, ids, labels, false));
    }

    @Test
    public void caseInsensitiveLocalizedFallback() {
        List<String> candidates = List.of("minecraft:building_blocks");
        Function<String, String> labels = value -> "Building Blocks";
        assertEquals(List.of("minecraft:building_blocks"),
                ExplorerFilterMatching.suggestions("BUILD", 8, candidates, labels, true));
    }
}
