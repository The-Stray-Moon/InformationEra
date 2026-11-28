package cc.sighs.informationera.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一个文件夹的视图状态：布局、排序、分组、每列的显隐/顺序/宽度。
 *
 * <p>列设置使用稳定的命名空间 ID（例如 {@code informationera:name}、{@code mymod:rarity}）作为键，
 * 因此新增/删除模组、改变注册顺序都不会让旧宽度或显隐串到别的列。未注册但曾经保存过的 ID 会
 * 原样保留在状态里，模组重新出现时可以恢复原设置。
 *
 * <p>旧版固定列格式（7/8 字段，无命名空间 ID）和 {@code v2|} 格式仍可解码；新格式带 {@code v3|} 前缀，
 * 增加组序方向。旧分组短名（{@code name}/{@code type}/…）在归一化时迁移为稳定依据 ID。
 */
public final class ExplorerViewState {

    /** 内置列的稳定 ID。 */
    public static final String[] BUILT_IN_COLUMN_IDS = {
            "informationera:name", "informationera:modified", "informationera:type",
            "informationera:size", "informationera:created", "informationera:author",
            "informationera:registry_id", "informationera:namespace",
            "informationera:max_stack", "informationera:durability"
    };

    /** 旧版未命名空间字段名，按规范列顺序排列；只用于迁移。 */
    public static final String[] LEGACY_COLUMN_FIELDS = {
            "name", "modified", "type", "size", "created", "author",
            "itemId", "namespace", "maxStack", "durability"
    };

    /** 内置列默认宽度，与 {@link #BUILT_IN_COLUMN_IDS} 平行。 */
    public static final double[] BUILT_IN_COLUMN_WIDTHS = {
            320.0, 160.0, 120.0, 100.0, 140.0, 100.0, 180.0, 110.0, 90.0, 90.0
    };

    /** 内置列默认显隐，与 {@link #BUILT_IN_COLUMN_IDS} 平行。 */
    public static final boolean[] BUILT_IN_COLUMN_VISIBLE = {
            true, true, true, true, false, false, false, false, false, false
    };

    /** 内置列默认顺序。 */
    public static final List<String> BUILT_IN_COLUMN_ORDER =
            List.of(BUILT_IN_COLUMN_IDS.clone());

    /** 内置分组依据的稳定 ID；完整列表供可搜索的「更多分组依据」弹窗使用。 */
    public static final String GROUP_NAME = "informationera:name";
    public static final String GROUP_MODIFIED = "informationera:modified";
    public static final String GROUP_CREATED = "informationera:created";
    public static final String GROUP_TYPE = "informationera:type";
    public static final String GROUP_SIZE = "informationera:size";
    public static final String GROUP_AUTHOR = "informationera:author";
    public static final String GROUP_SOURCE_MOD = "informationera:source_mod";
    public static final String GROUP_RARITY = "informationera:rarity";
    public static final String GROUP_DURABILITY = "informationera:durability_state";
    public static final String GROUP_MAX_STACK = "informationera:max_stack";
    public static final String GROUP_ENCHANTMENT = "informationera:enchantment";
    public static final String GROUP_PARENT_FOLDER = "informationera:parent_folder";

    public static final List<String> BUILT_IN_GROUP_IDS = List.of(
            GROUP_NAME, GROUP_MODIFIED, GROUP_CREATED, GROUP_TYPE, GROUP_SIZE, GROUP_AUTHOR,
            GROUP_SOURCE_MOD, GROUP_RARITY, GROUP_DURABILITY, GROUP_MAX_STACK,
            GROUP_ENCHANTMENT, GROUP_PARENT_FOLDER);

    /** 一级分组菜单只列旧版六个常用依据；其余进入可搜索的「更多分组依据」。 */
    public static final List<String> COMMON_GROUP_IDS = List.of(
            GROUP_NAME, GROUP_MODIFIED, GROUP_CREATED, GROUP_TYPE, GROUP_SIZE, GROUP_AUTHOR);

    /** 旧版分组字段名；只用于迁移到稳定 ID。 */
    private static final String[] LEGACY_GROUP_FIELDS = {
            "name", "modified", "created", "type", "size", "author"
    };

    private static final String[] LEGACY_GROUP_IDS = {
            GROUP_NAME, GROUP_MODIFIED, GROUP_CREATED, GROUP_TYPE, GROUP_SIZE, GROUP_AUTHOR
    };

    public static final String NAME_COLUMN_ID = BUILT_IN_COLUMN_IDS[0];
    public static final String NAMESPACE_COLUMN_ID = BUILT_IN_COLUMN_IDS[7];
    public static final String DURABILITY_COLUMN_ID = BUILT_IN_COLUMN_IDS[9];

    public static final String BUILT_IN_VIEW_MODE = "details";
    public static final String BUILT_IN_SORT_FIELD = BUILT_IN_COLUMN_IDS[0];

    private static final List<String> VIEW_MODES =
            List.of("details", "content", "list", "tiles", "slots", "small", "medium", "large", "xlarge");
    private static final double MIN_COLUMN_WIDTH = 48.0;
    private static final double MAX_COLUMN_WIDTH = 4000.0;
    private static final char FIELD_SEP = '|';
    private static final String V2_PREFIX = "v2";
    private static final String V3_PREFIX = "v3";

    /** 一列的持久化设置。 */
    public static final class ColumnSetting {
        public double width;
        public boolean visible;

        public ColumnSetting(double width, boolean visible) {
            this.width = width;
            this.visible = visible;
        }

        private ColumnSetting copy() {
            return new ColumnSetting(width, visible);
        }
    }

    public String viewMode;
    public String sortField;
    public boolean sortAsc;
    public boolean foldersFirst;
    /** 分组依据的稳定 ID；未注册的第三方依据也原样保留。 */
    public String groupBy;
    /** 只反向组序，不改变组内条目排序。 */
    public boolean groupReverse;
    /** ID -> 宽度/显隐。可以包含当前未注册的第三方列。 */
    public final Map<String, ColumnSetting> columnSettings = new LinkedHashMap<>();
    /** 持久化列序；可以包含当前未注册的第三方列 ID。 */
    public final List<String> columnOrder = new ArrayList<>();

    public ExplorerViewState() {
        viewMode = BUILT_IN_VIEW_MODE;
        sortField = BUILT_IN_SORT_FIELD;
        sortAsc = true;
        foldersFirst = true;
        groupBy = null;
        groupReverse = false;
        ensureBuiltIns();
    }

    public static ExplorerViewState defaults() {
        return new ExplorerViewState();
    }

    public ExplorerViewState copy() {
        ExplorerViewState copy = new ExplorerViewState();
        copy.viewMode = viewMode;
        copy.sortField = sortField;
        copy.sortAsc = sortAsc;
        copy.foldersFirst = foldersFirst;
        copy.groupBy = groupBy;
        copy.groupReverse = groupReverse;
        copy.columnSettings.clear();
        for (Map.Entry<String, ColumnSetting> entry : columnSettings.entrySet()) {
            copy.columnSettings.put(entry.getKey(), entry.getValue().copy());
        }
        copy.columnOrder.clear();
        copy.columnOrder.addAll(columnOrder);
        return copy;
    }

    /**
     * 确保某列有设置；不存在时使用给定默认值，并把 ID 追加到列序末尾。
     * 已存在的设置不会被默认值覆盖。
     */
    public void ensureColumn(String id, double defaultWidth, boolean defaultVisible) {
        if (!isValidColumnId(id)) {
            return;
        }
        columnSettings.computeIfAbsent(id, key -> new ColumnSetting(
                clampWidth(defaultWidth), defaultVisible));
        if (!columnOrder.contains(id)) {
            columnOrder.add(id);
        }
    }

    public boolean hasColumn(String id) {
        return id != null && columnSettings.containsKey(id);
    }

    public double width(String id, double fallback) {
        ColumnSetting setting = columnSettings.get(id);
        return setting == null ? fallback : setting.width;
    }

    public boolean visible(String id, boolean fallback) {
        ColumnSetting setting = columnSettings.get(id);
        return setting == null ? fallback : setting.visible;
    }

    public void setWidth(String id, double width) {
        ColumnSetting setting = columnSettings.get(id);
        if (setting != null) {
            setting.width = clampWidth(width);
        }
    }

    public void setVisible(String id, boolean visible) {
        ColumnSetting setting = columnSettings.get(id);
        if (setting != null) {
            setting.visible = visible;
        }
    }

    /**
     * 用一组当前注册列的 ID 重排；旧状态里存在、但本次未提供的 ID 会追加到末尾保留，
     * 这样第三方模组暂时移除后重新加入仍能回到原有设置。
     */
    public void setColumnOrder(List<String> ids) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (isValidColumnId(id)) {
                    ordered.add(id);
                }
            }
        }
        ordered.addAll(columnOrder);
        ordered.addAll(columnSettings.keySet());
        ordered.addAll(BUILT_IN_COLUMN_ORDER);
        columnOrder.clear();
        columnOrder.addAll(ordered);
        normalize();
    }

    /** 把从配置或界面读到的值收敛到合法范围。 */
    public void normalize() {
        if (!VIEW_MODES.contains(viewMode)) {
            viewMode = BUILT_IN_VIEW_MODE;
        }
        sortField = normalizeSortField(sortField);
        groupBy = normalizeGroupBasis(groupBy);
        ensureBuiltIns();
        for (ColumnSetting setting : columnSettings.values()) {
            setting.width = clampWidth(setting.width);
        }
        ColumnSetting name = columnSettings.get(NAME_COLUMN_ID);
        if (name != null) {
            name.visible = true;
        }
        normalizeOrder();
    }

    private void ensureBuiltIns() {
        for (int i = 0; i < BUILT_IN_COLUMN_IDS.length; i++) {
            String id = BUILT_IN_COLUMN_IDS[i];
            columnSettings.putIfAbsent(id, new ColumnSetting(BUILT_IN_COLUMN_WIDTHS[i], BUILT_IN_COLUMN_VISIBLE[i]));
            if (!columnOrder.contains(id)) {
                columnOrder.add(id);
            }
        }
    }

    private void normalizeOrder() {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String id : columnOrder) {
            if (isValidColumnId(id)) {
                ordered.add(id);
            }
        }
        // 只有设置、没有列序的 ID 也要保留。
        ordered.addAll(columnSettings.keySet());
        ordered.addAll(BUILT_IN_COLUMN_ORDER);
        columnOrder.clear();
        columnOrder.addAll(ordered);
        for (String id : columnOrder) {
            columnSettings.computeIfAbsent(id, key -> new ColumnSetting(120.0, false));
        }
    }

    private static double clampWidth(double width) {
        if (!Double.isFinite(width)) {
            return 120.0;
        }
        return Math.max(MIN_COLUMN_WIDTH, Math.min(MAX_COLUMN_WIDTH, width));
    }

    private static String normalizeSortField(String field) {
        if (field == null || field.isBlank()) {
            return BUILT_IN_SORT_FIELD;
        }
        String legacy = legacyColumnId(field);
        if (legacy != null) {
            return legacy;
        }
        return isValidColumnId(field) ? field : BUILT_IN_SORT_FIELD;
    }

    /**
     * 归一化分组依据：旧短名迁移到稳定 ID；未注册但有命名空间的第三方 ID 原样保留。
     *
     * <p>保留未知 ID 是为了模组临时卸载后重新装回仍能恢复分组，真正是否生效由
     * {@code ExplorerColumnService.group(id)} 决定；不可用时按未分组显示，但配置不丢。
     */
    public static String normalizeGroupBasis(String group) {
        if (group == null) {
            return null;
        }
        String value = group.trim();
        if (value.isEmpty()) {
            return null;
        }
        for (int i = 0; i < LEGACY_GROUP_FIELDS.length; i++) {
            if (LEGACY_GROUP_FIELDS[i].equals(value)) {
                return LEGACY_GROUP_IDS[i];
            }
        }
        return isValidColumnId(value) ? value : null;
    }

    /** 旧字段名转稳定 ID；未知返回 null。 */
    public static String legacyColumnId(String field) {
        if (field == null) {
            return null;
        }
        for (int i = 0; i < LEGACY_COLUMN_FIELDS.length; i++) {
            if (LEGACY_COLUMN_FIELDS[i].equals(field)) {
                return BUILT_IN_COLUMN_IDS[i];
            }
        }
        return null;
    }

    /** 稳定 ID 必须是 namespace:path，且不包含持久化保留字符。 */
    public static boolean isValidColumnId(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            return false;
        }
        if (id.indexOf('|') >= 0 || id.indexOf(';') >= 0 || id.indexOf(',') >= 0
                || id.indexOf('=') >= 0) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            if (Character.isWhitespace(id.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 序列化。新格式：v3|view|sort|asc|folders|group|groupReverse|orderIds|settings。 */
    public String encode() {
        normalize();
        StringBuilder sb = new StringBuilder(160);
        sb.append(V3_PREFIX).append(FIELD_SEP)
          .append(viewMode).append(FIELD_SEP)
          .append(sortField).append(FIELD_SEP)
          .append(sortAsc ? '1' : '0').append(FIELD_SEP)
          .append(foldersFirst ? '1' : '0').append(FIELD_SEP)
          .append(groupBy == null ? "" : groupBy).append(FIELD_SEP)
          .append(groupReverse ? '1' : '0').append(FIELD_SEP);
        for (int i = 0; i < columnOrder.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(columnOrder.get(i));
        }
        sb.append(FIELD_SEP);
        boolean first = true;
        for (String id : columnOrder) {
            ColumnSetting setting = columnSettings.get(id);
            if (setting == null) {
                continue;
            }
            if (!first) {
                sb.append(';');
            }
            first = false;
            sb.append(id).append(',').append(String.format(Locale.ROOT, "%.0f", setting.width))
              .append(',').append(setting.visible ? '1' : '0');
        }
        return sb.toString();
    }

    /**
     * 解析新格式或旧版固定列格式。
     *
     * <p>旧版：{@code view|sort|asc|folders|group|widths|visible|orderNames}（orderNames 可选）。
     * 旧 widths/visible 按 {@link #LEGACY_COLUMN_FIELDS} 的顺序对应内置列。
     */
    public static ExplorerViewState decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaults();
        }
        String[] parts = raw.split("\\" + FIELD_SEP, -1);
        if (parts.length > 0) {
            String prefix = parts[0].trim();
            if (V3_PREFIX.equals(prefix)) {
                return decodeV3(parts);
            }
            if (V2_PREFIX.equals(prefix)) {
                return decodeV2(parts);
            }
        }
        return decodeLegacy(parts);
    }

    private static ExplorerViewState decodeV3(String[] parts) {
        if (parts.length < 9) {
            return defaults();
        }
        ExplorerViewState state = new ExplorerViewState();
        state.viewMode = parts[1].trim();
        state.sortField = parts[2].trim();
        state.sortAsc = !"0".equals(parts[3].trim());
        state.foldersFirst = !"0".equals(parts[4].trim());
        String group = parts[5].trim();
        state.groupBy = group.isEmpty() ? null : group;
        state.groupReverse = "1".equals(parts[6].trim());
        state.columnSettings.clear();
        if (!parts[8].isBlank()) {
            for (String entry : parts[8].split(";")) {
                String[] fields = entry.split(",", 3);
                if (fields.length < 3 || !isValidColumnId(fields[0].trim())) {
                    continue;
                }
                try {
                    state.columnSettings.put(fields[0].trim(), new ColumnSetting(
                            Double.parseDouble(fields[1].trim()), "1".equals(fields[2].trim())));
                } catch (NumberFormatException ignored) {
                    state.columnSettings.put(fields[0].trim(), new ColumnSetting(120.0, false));
                }
            }
        }
        state.columnOrder.clear();
        if (!parts[7].isBlank()) {
            for (String id : parts[7].split(",")) {
                if (isValidColumnId(id.trim())) {
                    state.columnOrder.add(id.trim());
                }
            }
        }
        state.normalize();
        return state;
    }

    private static ExplorerViewState decodeV2(String[] parts) {
        if (parts.length < 8) {
            return defaults();
        }
        ExplorerViewState state = new ExplorerViewState();
        state.viewMode = parts[1].trim();
        state.sortField = parts[2].trim();
        state.sortAsc = !"0".equals(parts[3].trim());
        state.foldersFirst = !"0".equals(parts[4].trim());
        String group = parts[5].trim();
        state.groupBy = group.isEmpty() ? null : group;
        state.columnSettings.clear();
        if (!parts[7].isBlank()) {
            for (String entry : parts[7].split(";")) {
                String[] fields = entry.split(",", 3);
                if (fields.length < 3 || !isValidColumnId(fields[0].trim())) {
                    continue;
                }
                try {
                    state.columnSettings.put(fields[0].trim(), new ColumnSetting(
                            Double.parseDouble(fields[1].trim()), "1".equals(fields[2].trim())));
                } catch (NumberFormatException ignored) {
                    state.columnSettings.put(fields[0].trim(), new ColumnSetting(120.0, false));
                }
            }
        }
        state.columnOrder.clear();
        if (!parts[6].isBlank()) {
            for (String id : parts[6].split(",")) {
                if (isValidColumnId(id.trim())) {
                    state.columnOrder.add(id.trim());
                }
            }
        }
        state.normalize();
        return state;
    }

    private static ExplorerViewState decodeLegacy(String[] parts) {
        if (parts.length < 7) {
            return defaults();
        }
        ExplorerViewState state = new ExplorerViewState();
        state.viewMode = parts[0].trim();
        state.sortField = parts[1].trim();
        state.sortAsc = !"0".equals(parts[2].trim());
        state.foldersFirst = !"0".equals(parts[3].trim());
        String group = parts[4].trim();
        state.groupBy = group.isEmpty() ? null : group;
        state.columnOrder.clear();
        if (parts.length >= 8 && !parts[7].isBlank()) {
            for (String field : parts[7].split(",")) {
                String id = legacyColumnId(field.trim());
                if (id != null) {
                    state.columnOrder.add(id);
                }
            }
        }
        state.columnOrder.addAll(BUILT_IN_COLUMN_ORDER);
        String[] widths = parts[5].split(",");
        String visible = parts[6].trim();
        for (int i = 0; i < LEGACY_COLUMN_FIELDS.length && i < BUILT_IN_COLUMN_IDS.length; i++) {
            String id = BUILT_IN_COLUMN_IDS[i];
            ColumnSetting setting = state.columnSettings.get(id);
            if (setting == null) {
                continue;
            }
            if (i < widths.length) {
                try {
                    setting.width = Double.parseDouble(widths[i].trim());
                } catch (NumberFormatException ignored) {
                    setting.width = BUILT_IN_COLUMN_WIDTHS[i];
                }
            }
            if (i < visible.length()) {
                setting.visible = visible.charAt(i) == '1';
            }
        }
        state.normalize();
        return state;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExplorerViewState state)) {
            return false;
        }
        if (sortAsc != state.sortAsc || foldersFirst != state.foldersFirst
                || groupReverse != state.groupReverse
                || !Objects.equals(viewMode, state.viewMode)
                || !Objects.equals(sortField, state.sortField)
                || !Objects.equals(groupBy, state.groupBy)
                || !columnOrder.equals(state.columnOrder)
                || columnSettings.size() != state.columnSettings.size()) {
            return false;
        }
        for (Map.Entry<String, ColumnSetting> entry : columnSettings.entrySet()) {
            ColumnSetting otherSetting = state.columnSettings.get(entry.getKey());
            if (otherSetting == null
                    || Double.compare(entry.getValue().width, otherSetting.width) != 0
                    || entry.getValue().visible != otherSetting.visible) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(viewMode, sortField, sortAsc, foldersFirst, groupBy, groupReverse, columnOrder);
        int settingsHash = 0;
        for (Map.Entry<String, ColumnSetting> entry : columnSettings.entrySet()) {
            settingsHash += Objects.hash(entry.getKey(), entry.getValue().width, entry.getValue().visible);
        }
        return 31 * result + settingsHash;
    }

    @Override
    public String toString() {
        return encode();
    }
}
