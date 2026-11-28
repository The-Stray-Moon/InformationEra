package cc.sighs.informationera.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 关键词筛选的目录索引：从物品的**真实本地化名称**提取英文完整词和中文连续词组。
 *
 * <p>频率按不同物品 id 计数，同一个物品在多个文件夹、多个数量或多个引用里只算一次。
 * 中文生成有界的连续子串（不按前后缀做语义排除），因此名称中段的有效词组也能成为候选；
 * 候选按出现物品数降序，同频按词长降序，较长完整词排在短碎片前。名称/关键词长度和总量有
 * 上限，避免异常长名称导致内存膨胀。
 *
 * <p>物品名称来自 {@code new ItemStack(item).getHoverName()}，不带磁盘文件的自定义文件名，
 * 也不带物品 NBT 自定义名。语言切换或资源重载后按 {@link Language} 实例/语言代码清空名称缓存。
 */
final class ExplorerKeywordIndex {

    /** 单个关键词最大汉字数；极长名称只处理到这个长度，避免 n-gram 爆炸。 */
    private static final int MAX_KEYWORD_CODE_POINTS = 16;
    /** 单个物品最多贡献的关键词数；覆盖约 64 个汉字的全部中段子串。 */
    private static final int MAX_KEYWORDS_PER_ITEM = 1024;
    /** 整个目录最多保留的候选数。 */
    private static final int MAX_KEYWORDS = 12000;
    /** 英文单词最大长度。 */
    private static final int MAX_ENGLISH_WORD_CODE_POINTS = 48;

    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    private static Language languageStamp;
    private static String languageCode;
    private static int languageVersion;

    private ExplorerKeywordIndex() {
    }

    /** 当前语言版本；语言切换/资源重载时递增，控制器据此清空自己的候选缓存并重绘。 */
    static int languageVersion() {
        syncLanguage();
        return languageVersion;
    }

    /** 物品 id 的默认本地化名称；不读取磁盘文件名或 NBT 自定义名。 */
    static String localizedName(String itemId) {
        syncLanguage();
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        String cached = NAMES.get(itemId);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String name = resolveLocalizedName(itemId);
        NAMES.put(itemId, name == null ? "" : name);
        return name;
    }

    private static String resolveLocalizedName(String itemId) {
        try {
            ResourceLocation id = ResourceLocation.tryParse(itemId);
            Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
            if (item == null) {
                return null;
            }
            // 新 ItemStack 不带磁盘条目的自定义名称/组件，只取物品类型的本地化名称。
            String name = new ItemStack(item).getHoverName().getString();
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException | LinkageError brokenItem) {
            return null;
        }
    }

    /** 当前范围全部物品的候选关键词；按目录词频排序，不按字母重排。 */
    static List<String> candidates(Collection<VirtualFileSystem.Entry> entries) {
        syncLanguage();
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        // 按 itemId 去重：目录里多个文件/副本/数量只算一个物品种类。
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (VirtualFileSystem.Entry entry : entries) {
            if (entry == null || entry.folder || entry.itemId == null || entry.itemId.isBlank()
                    || names.containsKey(entry.itemId)) {
                continue;
            }
            String name = localizedName(entry.itemId);
            if (name != null) {
                names.put(entry.itemId, name);
            }
        }
        return candidatesForNames(names);
    }

    /** 纯名称集合的词频计算入口（测试也使用）。key 为物品种类 id，value 为本地化名称。 */
    static List<String> candidatesForNames(Map<String, String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (String name : names.values()) {
            for (String word : extract(name)) {
                if (!frequency.containsKey(word) && frequency.size() >= MAX_KEYWORDS) {
                    continue;
                }
                frequency.merge(word, 1, Integer::sum);
            }
        }
        List<String> candidates = new ArrayList<>(frequency.keySet());
        candidates.sort(Comparator
                .comparingInt((String word) -> frequency.getOrDefault(word, 0)).reversed()
                .thenComparing(Comparator.comparingInt(ExplorerKeywordIndex::codePointLength).reversed())
                .thenComparing(Comparator.naturalOrder()));
        if (candidates.size() > MAX_KEYWORDS) {
            return new ArrayList<>(candidates.subList(0, MAX_KEYWORDS));
        }
        return candidates;
    }

    /** 已选关键词中任意一个命中本地化名称即满足同类 OR。 */
    static boolean matchesAny(Collection<String> keywords, String localizedName) {
        if (keywords == null || keywords.isEmpty() || localizedName == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (containsKeyword(keyword, localizedName)) {
                return true;
            }
        }
        return false;
    }

    /** 英文关键词按完整词、大小写不敏感匹配；中文关键词按连续子串匹配。 */
    static boolean containsKeyword(String keyword, String localizedName) {
        String key = ExplorerFilterSelection.normalize(keyword);
        if (key == null || localizedName == null) {
            return false;
        }
        if (containsHan(key)) {
            return localizedName.contains(key);
        }
        String lowerKey = key.toLowerCase(Locale.ROOT);
        if (!isSingleEnglishWord(lowerKey)) {
            return localizedName.toLowerCase(Locale.ROOT).contains(lowerKey);
        }
        int[] cps = localizedName.codePoints().toArray();
        int i = 0;
        while (i < cps.length) {
            if (!isEnglishWordChar(cps[i])) {
                i++;
                continue;
            }
            int start = i;
            while (i < cps.length && isEnglishWordChar(cps[i])) {
                i++;
            }
            String word = new String(cps, start, i - start).toLowerCase(Locale.ROOT);
            if (word.equals(lowerKey)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSingleEnglishWord(String lowerKey) {
        if (lowerKey.isEmpty()) {
            return false;
        }
        int[] cps = lowerKey.codePoints().toArray();
        for (int cp : cps) {
            if (!isEnglishWordChar(cp)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> extract(String name) {
        Set<String> words = new LinkedHashSet<>();
        if (name == null || name.isBlank()) {
            return words;
        }
        int[] cps = name.codePoints().toArray();
        int i = 0;
        while (i < cps.length && words.size() < MAX_KEYWORDS_PER_ITEM) {
            int cp = cps[i];
            if (isHan(cp)) {
                int start = i;
                while (i < cps.length && isHan(cps[i])) {
                    i++;
                }
                addHanRun(cps, start, i, words);
            } else if (isEnglishWordChar(cp)) {
                int start = i;
                while (i < cps.length && isEnglishWordChar(cps[i])) {
                    i++;
                }
                addEnglishWord(cps, start, i, words);
            } else {
                i++;
            }
        }
        return words;
    }

    private static void addHanRun(int[] cps, int start, int end, Set<String> words) {
        int runLength = end - start;
        int maxLength = Math.min(runLength, MAX_KEYWORD_CODE_POINTS);
        for (int length = 2; length <= maxLength; length++) {
            for (int offset = start; offset + length <= end; offset++) {
                String word = new String(cps, offset, length);
                if (!words.contains(word)) {
                    if (words.size() >= MAX_KEYWORDS_PER_ITEM) {
                        return;
                    }
                    words.add(word);
                }
            }
        }
    }

    private static void addEnglishWord(int[] cps, int start, int end, Set<String> words) {
        int length = end - start;
        if (length < 2 || length > MAX_ENGLISH_WORD_CODE_POINTS) {
            return;
        }
        words.add(new String(cps, start, length).toLowerCase(Locale.ROOT));
    }

    private static boolean containsHan(String value) {
        for (int cp : value.codePoints().toArray()) {
            if (isHan(cp)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static boolean isEnglishWordChar(int codePoint) {
        return !isHan(codePoint) && Character.isLetterOrDigit(codePoint);
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static void syncLanguage() {
        Minecraft minecraft = Minecraft.getInstance();
        String code = minecraft.getLanguageManager() == null
                ? "" : minecraft.getLanguageManager().getSelected();
        Language current = Language.getInstance();
        if (current != languageStamp || !Objects.equals(code, languageCode)) {
            languageStamp = current;
            languageCode = code;
            NAMES.clear();
            languageVersion++;
        }
    }
}
