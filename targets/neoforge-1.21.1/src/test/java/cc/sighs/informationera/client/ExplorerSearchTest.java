package cc.sighs.informationera.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索基础行为的独立测试：本地原名、大小写、通配符始终可用；不调用 JECh 的入口
 * 不会把拼音当作命中。JECh 的实际全拼/首字母行为在真实客户端验证。
 */
public class ExplorerSearchTest {

    @Test
    public void plainMatchesLocalizedNameCaseInsensitively() {
        assertTrue(ExplorerSearch.matchesPlain("建筑方块", "建筑"));
        assertTrue(ExplorerSearch.matchesPlain("Building Blocks", "BUILD"));
        assertTrue(ExplorerSearch.matchesPlain("minecraft:building_blocks", "BLOCKS"));
    }

    @Test
    public void plainSupportsWildcardsButNotPinyin() {
        assertTrue(ExplorerSearch.matchesPlain("Building Blocks", "*Blocks"));
        assertTrue(ExplorerSearch.matchesPlain("minecraft:building_blocks", "minecraft:*"));
        assertFalse(ExplorerSearch.matchesPlain("建筑方块", "jz"));
    }

    @Test
    public void plainEmptyQueryMatches() {
        assertTrue(ExplorerSearch.matchesPlain("anything", ""));
        assertTrue(ExplorerSearch.matchesPlain("anything", null));
    }
}
