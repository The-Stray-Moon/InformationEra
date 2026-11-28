package cc.sighs.informationera.client;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键词目录词频的独立测试：真实物品类型去重后的语料按频率/长度排序，英文完整词，
 * 中文连续子串（包括名称中段），异常长名称不会无限生成。
 *
 * <p>名称直接作为测试语料传入，不启动 Minecraft；真实本地化名称与切语言刷新在客户端验证。
 */
public class ExplorerKeywordIndexTest {

    private static Map<String, String> names(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    public void chineseFrequencyAndLengthOrder() {
        List<String> candidates = ExplorerKeywordIndex.candidatesForNames(names(
                "a", "橡木原木",
                "b", "橡木木板",
                "c", "橡木",
                "d", "下界合金锭",
                "e", "下界合金块",
                "f", "白色羊毛",
                "g", "黑色羊毛",
                "h", "橡木台阶",
                "i", "金苹果",
                "j", "强化橡木台阶",
                "k", "精制橡木木板"));

        assertTrue(candidates.contains("橡木"));
        assertTrue(candidates.contains("下界合金"));
        assertTrue(candidates.contains("羊毛"));
        assertTrue(candidates.contains("原木"));
        assertTrue(candidates.contains("木板"));
        assertTrue(candidates.contains("橡木台阶"));
        assertTrue(candidates.contains("金苹果"));
        // 高频优先；同频“下界合金”比“羊毛”长。
        assertTrue(candidates.indexOf("橡木") < candidates.indexOf("下界合金"));
        assertTrue(candidates.indexOf("下界合金") < candidates.indexOf("羊毛"));
        // 不做 prefix/suffix 语义排除：中段和同频碎片也保留。
        assertTrue(candidates.contains("界合"));
        assertTrue(candidates.contains("木原"));
        assertTrue(candidates.contains("下界合"));
        assertTrue(candidates.contains("色羊毛"));
        assertTrue(candidates.contains("橡木台"));
    }

    @Test
    public void middleKeywordHitsBothInternalOccurrences() {
        List<String> candidates = ExplorerKeywordIndex.candidatesForNames(names(
                "a", "强化橡木台阶",
                "b", "精制橡木木板"));

        assertTrue(candidates.contains("橡木"));
        assertTrue(ExplorerKeywordIndex.matchesAny(List.of("橡木"), "强化橡木台阶"));
        assertTrue(ExplorerKeywordIndex.matchesAny(List.of("橡木"), "精制橡木木板"));
    }

    @Test
    public void normalLengthNameKeepsMiddleSubstrings() {
        String normal = "甲乙丙丁戊己庚辛壬癸".repeat(4);
        List<String> candidates = ExplorerKeywordIndex.candidatesForNames(names("normal", normal));

        // 名称中段（第 18-21 个汉字）必须保留，不能被长度上限截掉。
        assertTrue(candidates.contains("壬癸甲乙"));
        assertTrue(candidates.contains("甲乙"));
    }

    @Test
    public void englishFrequencyBeatsAlphabeticalOrder() {
        List<String> candidates = ExplorerKeywordIndex.candidatesForNames(names(
                "a", "Zebra",
                "b", "Zebra",
                "c", "Apple",
                "d", "Netherite Ingot",
                "e", "Netherite Block"));

        // zebra/netherite 都是 2 个物品种类；同频时完整长词 netherite 在 zebra 前。
        assertTrue(candidates.indexOf("netherite") < candidates.indexOf("zebra"));
        assertTrue(candidates.indexOf("zebra") < candidates.indexOf("apple"));
    }

    @Test
    public void englishMatchingUsesWholeWordsCaseInsensitively() {
        assertTrue(ExplorerKeywordIndex.containsKeyword("oak", "Oak Log"));
        assertTrue(ExplorerKeywordIndex.containsKeyword("OAK", "Oak Log"));
        assertFalse(ExplorerKeywordIndex.containsKeyword("oak", "Oaken"));
        assertFalse(ExplorerKeywordIndex.containsKeyword("wool", "Woolen"));
    }

    @Test
    public void chineseMatchingUsesAdjacentPhrase() {
        assertTrue(ExplorerKeywordIndex.containsKeyword("橡木", "橡木原木"));
        assertTrue(ExplorerKeywordIndex.containsKeyword("下界合金", "下界合金锭"));
        assertTrue(ExplorerKeywordIndex.containsKeyword("羊毛", "白色羊毛"));
    }

    @Test
    public void sameKindIsOrAcrossSelectedKeywords() {
        assertTrue(ExplorerKeywordIndex.matchesAny(List.of("橡木", "羊毛"), "橡木原木"));
        assertTrue(ExplorerKeywordIndex.matchesAny(List.of("橡木", "羊毛"), "白色羊毛"));
        assertFalse(ExplorerKeywordIndex.matchesAny(List.of("橡木", "羊毛"), "下界合金锭"));
        assertFalse(ExplorerKeywordIndex.matchesAny(List.of(), "橡木原木"));
    }

    @Test
    public void extremeNameDoesNotExplode() {
        String huge = "异".repeat(5000);
        List<String> candidates = ExplorerKeywordIndex.candidatesForNames(names("huge", huge));
        assertTrue(candidates.size() <= 12000);
        assertTrue(candidates.size() < 1000);
    }
}
