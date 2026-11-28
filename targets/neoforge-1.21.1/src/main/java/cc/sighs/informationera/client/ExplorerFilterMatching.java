package cc.sighs.informationera.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

/**
 * 筛选条件的输入解析：自动补全、回车和点击共用同一套候选/标签匹配规则。
 *
 * <p>补全只匹配传入的显示文本：标签和模组显示实际英文 id，创造标签页显示当前本地化名称。
 * 本地化名称的拼音/首字母支持来自 {@link ExplorerSearch}（安装 JECh 时启用；未安装时只做
 * 本地化原名的子串匹配）。匹配结果始终返回稳定 id，写入 chip/配置的也是稳定 id。
 *
 * <p>标签和模组允许手工输入当前目录里暂时没有的 id；创造标签页不接受任意文本，避免把
 * 本地化名称或拼音误存成 registry id。
 */
final class ExplorerFilterMatching {

    private ExplorerFilterMatching() {
    }

    /** 自动补全候选：按显示文本匹配，返回稳定 id。 */
    static List<String> suggestions(String rawQuery, int limit, List<String> candidates,
                                    Function<String, String> labelOf, boolean localizedSearch) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        List<String> result = new ArrayList<>();
        if (query.isEmpty() || limit <= 0 || candidates == null) {
            return result;
        }
        for (String value : candidates) {
            if (matchesLabel(labelOf.apply(value), query, localizedSearch)) {
                result.add(value);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    private static boolean matchesLabel(String label, String query, boolean localizedSearch) {
        return localizedSearch
                ? ExplorerSearch.matches(label, query)
                : ExplorerSearch.matchesPlain(label, query);
    }

    /** 精确匹配显示文本，其次允许直接输入稳定 id；没有命中返回 null。 */
    static String resolveKnown(String rawText, List<String> candidates, Function<String, String> labelOf) {
        String text = ExplorerFilterSelection.normalize(rawText);
        if (text == null || candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (labelOf.apply(candidate).equalsIgnoreCase(text)) {
                return candidate;
            }
        }
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(text)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 把输入解析成一组稳定 id。
     *
     * <p>整段文本先做精确匹配；否则按中英文逗号拆分逐项解析。每一项先精确匹配，再取第一条
     * 自动补全；仍没有命中时，只有允许手工 id 的类别（标签/模组）才保留原始文本。
     */
    static List<String> resolve(String rawText, boolean allowRawValue, List<String> candidates,
                                Function<String, String> labelOf, boolean localizedSearch) {
        String text = ExplorerFilterSelection.normalize(rawText);
        if (text == null) {
            return List.of();
        }
        String exact = resolveKnown(text, candidates, labelOf);
        if (exact != null) {
            return List.of(exact);
        }
        if (text.indexOf(',') >= 0 || text.indexOf('，') >= 0) {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (String part : text.split("[,，]")) {
                String piece = ExplorerFilterSelection.normalize(part);
                if (piece == null) {
                    continue;
                }
                String resolved = resolvePiece(piece, allowRawValue, candidates, labelOf, localizedSearch);
                if (resolved != null) {
                    values.add(resolved);
                }
            }
            return new ArrayList<>(values);
        }
        String resolved = resolvePiece(text, allowRawValue, candidates, labelOf, localizedSearch);
        return resolved == null ? List.of() : List.of(resolved);
    }

    private static String resolvePiece(String piece, boolean allowRawValue, List<String> candidates,
                                       Function<String, String> labelOf, boolean localizedSearch) {
        String exact = resolveKnown(piece, candidates, labelOf);
        if (exact != null) {
            return exact;
        }
        List<String> suggestions = suggestions(piece, 1, candidates, labelOf, localizedSearch);
        if (!suggestions.isEmpty()) {
            return suggestions.get(0);
        }
        return allowRawValue ? piece : null;
    }
}
