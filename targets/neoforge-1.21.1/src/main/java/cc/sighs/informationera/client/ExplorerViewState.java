package cc.sighs.informationera.client;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 一个文件夹的视图状态：布局、排序、分组、列的显隐与宽度。
 *
 * <p>这些设置按「身份 + 文件夹」保存，所以同一台客户端上的不同玩家、不同服务器之间
 * 不会互相串用。本类只做纯数据与序列化，不接触界面和配置，便于独立测试。
 */
public final class ExplorerViewState {

    /** 列数；与控制器里 {@code COL_FIELDS} 的顺序一致。 */
    public static final int COLUMN_COUNT = 6;

    /** 列字段名，也用于与旧配置里的字段对齐。 */
    public static final String[] COLUMN_FIELDS = {"name", "modified", "type", "size", "created", "author"};

    /** 内置的列默认宽度（名称 / 修改日期 / 类型 / 大小 / 创建日期 / 作者）。 */
    public static final double[] BUILT_IN_COLUMN_WIDTHS = {320.0, 160.0, 120.0, 100.0, 140.0, 100.0};

    /** 内置的列默认显隐：名称、修改日期、类型、大小默认显示。 */
    public static final boolean[] BUILT_IN_COLUMN_VISIBLE = {true, true, true, true, false, false};

    /** 内置的视图模式。 */
    public static final String BUILT_IN_VIEW_MODE = "details";
    /** 内置的排序字段。 */
    public static final String BUILT_IN_SORT_FIELD = "name";

    private static final List<String> VIEW_MODES =
            List.of("details", "content", "list", "tiles", "small", "medium", "large", "xlarge");
    private static final List<String> SORT_FIELDS =
            List.of("name", "modified", "created", "type", "size", "author");
    private static final List<String> GROUP_FIELDS = SORT_FIELDS;
    private static final double MIN_COLUMN_WIDTH = 48.0;
    private static final double MAX_COLUMN_WIDTH = 4000.0;
    private static final char FIELD_SEP = '|';

    public String viewMode;
    public String sortField;
    public boolean sortAsc;
    public boolean foldersFirst;
    /** 分组字段；{@code null} 表示不分组。 */
    public String groupBy;
    public final double[] columnWidths;
    public final boolean[] columnVisible;

    /** 新建一份内置默认状态。 */
    public ExplorerViewState() {
        this.viewMode = BUILT_IN_VIEW_MODE;
        this.sortField = BUILT_IN_SORT_FIELD;
        this.sortAsc = true;
        this.foldersFirst = true;
        this.groupBy = null;
        this.columnWidths = BUILT_IN_COLUMN_WIDTHS.clone();
        this.columnVisible = BUILT_IN_COLUMN_VISIBLE.clone();
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
        System.arraycopy(columnWidths, 0, copy.columnWidths, 0, COLUMN_COUNT);
        System.arraycopy(columnVisible, 0, copy.columnVisible, 0, COLUMN_COUNT);
        return copy;
    }

    /**
     * 把从配置或界面读到的值收敛到合法范围。
     *
     * <p>配置可能被手工编辑，界面也可能传入不存在的模式；这里统一兜底到内置值，
     * 避免渲染时出现空列宽或无法识别的视图。
     */
    public void normalize() {
        if (!VIEW_MODES.contains(viewMode)) {
            viewMode = BUILT_IN_VIEW_MODE;
        }
        if (!SORT_FIELDS.contains(sortField)) {
            sortField = BUILT_IN_SORT_FIELD;
        }
        if (groupBy != null && (groupBy.isBlank() || !GROUP_FIELDS.contains(groupBy))) {
            groupBy = null;
        }
        boolean anyVisible = false;
        for (int col = 0; col < COLUMN_COUNT; col++) {
            double width = columnWidths[col];
            if (!Double.isFinite(width)) {
                width = BUILT_IN_COLUMN_WIDTHS[col];
            }
            columnWidths[col] = Math.max(MIN_COLUMN_WIDTH, Math.min(MAX_COLUMN_WIDTH, width));
            anyVisible |= columnVisible[col];
        }
        // 至少留一列可见，否则表格会渲染成空行。
        if (!anyVisible) {
            columnVisible[0] = true;
        }
    }

    /** 序列化成一行：视图|排序字段|升序|文件夹置顶|分组|列宽|列显隐。 */
    public String encode() {
        StringBuilder sb = new StringBuilder(96);
        sb.append(viewMode).append(FIELD_SEP)
          .append(sortField).append(FIELD_SEP)
          .append(sortAsc ? '1' : '0').append(FIELD_SEP)
          .append(foldersFirst ? '1' : '0').append(FIELD_SEP)
          .append(groupBy == null ? "" : groupBy).append(FIELD_SEP);
        for (int col = 0; col < COLUMN_COUNT; col++) {
            if (col > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.0f", columnWidths[col]));
        }
        sb.append(FIELD_SEP);
        for (int col = 0; col < COLUMN_COUNT; col++) {
            sb.append(columnVisible[col] ? '1' : '0');
        }
        return sb.toString();
    }

    /** 解析 {@link #encode()} 的行；格式不完整时退回内置默认值。 */
    public static ExplorerViewState decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaults();
        }
        String[] parts = raw.split("\\" + FIELD_SEP, -1);
        if (parts.length < 7) {
            return defaults();
        }
        ExplorerViewState state = defaults();
        state.viewMode = parts[0].trim();
        state.sortField = parts[1].trim();
        state.sortAsc = !"0".equals(parts[2].trim());
        state.foldersFirst = !"0".equals(parts[3].trim());
        String group = parts[4].trim();
        state.groupBy = group.isEmpty() ? null : group;
        parseWidths(parts[5], state.columnWidths);
        parseVisible(parts[6], state.columnVisible);
        state.normalize();
        return state;
    }

    private static void parseWidths(String raw, double[] target) {
        String[] values = raw.split(",");
        for (int col = 0; col < COLUMN_COUNT && col < values.length; col++) {
            try {
                target[col] = Double.parseDouble(values[col].trim());
            } catch (NumberFormatException malformed) {
                target[col] = BUILT_IN_COLUMN_WIDTHS[col];
            }
        }
    }

    private static void parseVisible(String raw, boolean[] target) {
        String value = raw.trim();
        for (int col = 0; col < COLUMN_COUNT && col < value.length(); col++) {
            target[col] = value.charAt(col) == '1';
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExplorerViewState state)) {
            return false;
        }
        return sortAsc == state.sortAsc
                && foldersFirst == state.foldersFirst
                && Objects.equals(viewMode, state.viewMode)
                && Objects.equals(sortField, state.sortField)
                && Objects.equals(groupBy, state.groupBy)
                && Arrays.equals(columnWidths, state.columnWidths)
                && Arrays.equals(columnVisible, state.columnVisible);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(viewMode, sortField, sortAsc, foldersFirst, groupBy);
        result = 31 * result + Arrays.hashCode(columnWidths);
        result = 31 * result + Arrays.hashCode(columnVisible);
        return result;
    }

    @Override
    public String toString() {
        return encode();
    }
}
