package cc.sighs.informationera.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 筛选模板集合：按名称不区分大小写去重，保存同名模板会显式替换已有项。
 *
 * <p>模板顺序只在配置里保持稳定；对外展示时统一按名称排序。该类不接触 UI，
 * 便于单独测试快照往返和同名覆盖。
 */
final class ExplorerFilterTemplateStore {

    private final Map<String, ExplorerFilterTemplate> templates = new LinkedHashMap<>();

    /** 按名称查找；不区分大小写，找不到返回 null。 */
    ExplorerFilterTemplate find(String name) {
        if (name == null) {
            return null;
        }
        return templates.get(key(name));
    }

    boolean exists(String name) {
        return name != null && templates.containsKey(key(name));
    }

    /** 保存或显式覆盖同名模板；名称已由调用方确认非空。 */
    void save(ExplorerFilterTemplate template) {
        if (template == null || !template.hasName()) {
            return;
        }
        String normalized = key(template.name);
        templates.put(normalized, template);
    }

    /** 全部模板，按显示名称排序。 */
    List<ExplorerFilterTemplate> all() {
        List<ExplorerFilterTemplate> result = new ArrayList<>(templates.values());
        result.sort(Comparator.comparing(template -> template.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    List<String> names() {
        List<String> names = new ArrayList<>(templates.size());
        for (ExplorerFilterTemplate template : all()) {
            names.add(template.name);
        }
        return names;
    }

    List<String> encode() {
        List<String> raw = new ArrayList<>(templates.size());
        for (ExplorerFilterTemplate template : all()) {
            raw.add(template.encode());
        }
        return raw;
    }

    static ExplorerFilterTemplateStore decode(List<? extends String> raw) {
        ExplorerFilterTemplateStore store = new ExplorerFilterTemplateStore();
        if (raw == null) {
            return store;
        }
        for (String line : raw) {
            ExplorerFilterTemplate template = ExplorerFilterTemplate.decode(line);
            if (template != null) {
                store.save(template);
            }
        }
        return store;
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
