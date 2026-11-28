package cc.sighs.informationera.api.client;

import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 一列详情表定义。第三方模组通过 {@link ExplorerColumnRegistry#register(ExplorerColumn)} 注册。
 *
 * <p>{@link #id()} 必须是稳定的 {@code namespace:path}，用于跨重启保存宽度、显隐和列序。
 * 不要使用显示文案或数组下标作为 ID；这样增删模组、调整注册顺序都不会让旧设置串到别的列。
 */
public final class ExplorerColumn {

    private final String id;
    private final Supplier<Component> label;
    private final int defaultWidth;
    private final boolean defaultVisible;
    private final Function<ExplorerColumnContext, String> value;
    private final Function<ExplorerColumnContext, ? extends Comparable<?>> sortValue;
    private final Comparator<ExplorerColumnContext> comparator;

    private ExplorerColumn(String id, Supplier<Component> label, int defaultWidth, boolean defaultVisible,
                           Function<ExplorerColumnContext, String> value,
                           Function<ExplorerColumnContext, ? extends Comparable<?>> sortValue,
                           Comparator<ExplorerColumnContext> comparator) {
        this.id = id;
        this.label = label;
        this.defaultWidth = defaultWidth;
        this.defaultVisible = defaultVisible;
        this.value = value;
        this.sortValue = sortValue;
        this.comparator = comparator;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    /** 列标题；延迟解析，语言切换后再次读取会得到新文案。 */
    public Component label() {
        return label.get();
    }

    public int defaultWidth() {
        return defaultWidth;
    }

    public boolean defaultVisible() {
        return defaultVisible;
    }

    /** 单元格显示文本。 */
    public String value(ExplorerColumnContext context) {
        return value.apply(context);
    }

    /** 排序键；返回 null 时回退到显示文本。 */
    public Comparable<?> sortValue(ExplorerColumnContext context) {
        return sortValue == null ? null : sortValue.apply(context);
    }

    /** 自定义比较器；优先于 sortValue/显示文本。 */
    public Comparator<ExplorerColumnContext> comparator() {
        return comparator;
    }

    public static final class Builder {
        private final String id;
        private Supplier<Component> label;
        private int defaultWidth = 120;
        private boolean defaultVisible = false;
        private Function<ExplorerColumnContext, String> value;
        private Function<ExplorerColumnContext, ? extends Comparable<?>> sortValue;
        private Comparator<ExplorerColumnContext> comparator;

        private Builder(String id) {
            this.id = id;
            this.label = () -> Component.literal(id == null ? "column" : id);
        }

        public Builder label(Component label) {
            Objects.requireNonNull(label, "label");
            this.label = () -> label;
            return this;
        }

        public Builder label(Supplier<Component> label) {
            this.label = Objects.requireNonNull(label, "label");
            return this;
        }

        public Builder defaultWidth(int width) {
            this.defaultWidth = width;
            return this;
        }

        public Builder defaultVisible(boolean visible) {
            this.defaultVisible = visible;
            return this;
        }

        public Builder value(Function<ExplorerColumnContext, String> value) {
            this.value = Objects.requireNonNull(value, "value");
            return this;
        }

        /** 可选：返回 String/Number/Enum 等可比较值，供详情表排序。 */
        @SuppressWarnings("unchecked")
        public Builder sortValue(Function<ExplorerColumnContext, ? extends Comparable<?>> sortValue) {
            this.sortValue = (Function<ExplorerColumnContext, Comparable<?>>) sortValue;
            return this;
        }

        /** 可选：完全自定义比较器；异常由宿主隔离。 */
        public Builder comparator(Comparator<ExplorerColumnContext> comparator) {
            this.comparator = comparator;
            return this;
        }

        public ExplorerColumn build() {
            if (id == null) {
                throw new IllegalArgumentException("column id must not be null");
            }
            int colon = id.indexOf(':');
            if (colon <= 0 || colon == id.length() - 1) {
                throw new IllegalArgumentException("column id must be namespace:path: " + id);
            }
            if (id.indexOf('|') >= 0 || id.indexOf(';') >= 0 || id.indexOf(',') >= 0
                    || id.indexOf('=') >= 0 || id.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("column id contains reserved characters: " + id);
            }
            if (value == null) {
                throw new IllegalArgumentException("column value function is required: " + id);
            }
            int width = Math.max(48, Math.min(4000, defaultWidth));
            return new ExplorerColumn(id, label, width, defaultVisible, value, sortValue, comparator);
        }
    }
}
