package cc.sighs.informationera.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个筛选模板：整套筛选规则的不可变快照。
 *
 * <p>快照包含资源管理器搜索框文本、标签/模组/创造标签页/关键词、平铺与去重两个开关；
 * 不包含另一个模板的引用，所以选择模板不会递归展开。名称不区分大小写地唯一。
 */
final class ExplorerFilterTemplate {

    /** 模板使用历史在 {@link ExplorerFilterHistory} 里的类别键。 */
    static final String HISTORY_KIND = "template";

    private static final String VERSION = "v1";

    final String name;
    final String search;
    final Set<String> tags;
    final Set<String> mods;
    final Set<String> tabs;
    final Set<String> keywords;
    final boolean includeSubfolders;
    final boolean dedupe;

    ExplorerFilterTemplate(String name, String search, Collection<String> tags,
                           Collection<String> mods, Collection<String> tabs,
                           boolean includeSubfolders, boolean dedupe) {
        this(name, search, tags, mods, tabs, List.of(), includeSubfolders, dedupe);
    }

    ExplorerFilterTemplate(String name, String search, Collection<String> tags,
                           Collection<String> mods, Collection<String> tabs,
                           Collection<String> keywords,
                           boolean includeSubfolders, boolean dedupe) {
        this.name = name == null ? "" : name.trim();
        this.search = search == null ? "" : search;
        this.tags = copy(tags);
        this.mods = copy(mods);
        this.tabs = copy(tabs);
        this.keywords = copy(keywords);
        this.includeSubfolders = includeSubfolders;
        this.dedupe = dedupe;
    }

    /** 把当前搜索框与筛选选择拍成快照。 */
    static ExplorerFilterTemplate capture(String name, String search, ExplorerFilterSelection selection) {
        return new ExplorerFilterTemplate(name, search,
                selection.values(ExplorerFilterSelection.Kind.TAG),
                selection.values(ExplorerFilterSelection.Kind.MOD),
                selection.values(ExplorerFilterSelection.Kind.TAB),
                selection.values(ExplorerFilterSelection.Kind.KEYWORD),
                selection.includeSubfolders,
                selection.dedupe);
    }

    private static Set<String> copy(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = ExplorerFilterSelection.normalize(value);
            if (normalized != null) {
                copy.add(normalized);
            }
        }
        return java.util.Collections.unmodifiableSet(copy);
    }

    boolean hasName() {
        return !name.isEmpty();
    }

    /** 序列化成一行配置：自由文本与 id 列表分别 Base64，避免名称/搜索里的分隔符破坏格式。 */
    String encode() {
        return VERSION + '|' + encodeText(name) + '|' + encodeText(search)
                + '|' + (includeSubfolders ? "1" : "0")
                + '|' + (dedupe ? "1" : "0")
                + '|' + encodeText(join(tags))
                + '|' + encodeText(join(mods))
                + '|' + encodeText(join(tabs))
                + '|' + encodeText(join(keywords));
    }

    /** 解析配置行；兼容没有关键词字段的旧 v1 行。 */
    static ExplorerFilterTemplate decode(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split("\\|", -1);
        // 旧模板 8 段（无关键词）；新模板追加第 9 段关键词。
        if ((parts.length != 8 && parts.length != 9) || !VERSION.equals(parts[0])) {
            return null;
        }
        try {
            String name = decodeText(parts[1]).trim();
            if (name.isEmpty()) {
                return null;
            }
            String search = decodeText(parts[2]);
            boolean expand = "1".equals(parts[3]);
            boolean dedupe = "1".equals(parts[4]);
            List<String> keywords = parts.length == 9 ? split(decodeText(parts[8])) : List.of();
            return new ExplorerFilterTemplate(name, search,
                    split(decodeText(parts[5])),
                    split(decodeText(parts[6])),
                    split(decodeText(parts[7])),
                    keywords,
                    expand, dedupe);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static String join(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join("\n", values);
    }

    private static List<String> split(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        for (String part : raw.split("\n")) {
            String normalized = ExplorerFilterSelection.normalize(part);
            if (normalized != null) {
                values.add(normalized);
            }
        }
        return values;
    }

    private static String encodeText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
