package cc.sighs.informationera.api.client;

import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 一个可在资源管理器「分组依据」里选择的独立分组依据。
 *
 * <p>详情列可以通过 {@link ExplorerColumn.Builder#groupRule(ExplorerGroupRule)} 声明分组规则，
 * 不需要额外注册；没有详情列、但希望出现在分组菜单里的概念用
 * {@link ExplorerColumnRegistry#registerGroup(ExplorerGroup)} 注册。两种情况都使用稳定的
 * {@code namespace:path} 作为依据 ID。
 */
public final class ExplorerGroup {

    public enum Category {
        GENERAL,
        ITEM,
        LOCATION,
        EXTENSION;

        public String translationKey() {
            return "informationera.fe.group_category." + name().toLowerCase(Locale.ROOT);
        }
    }

    private final String id;
    private final Supplier<Component> label;
    private final Category category;
    private final ExplorerGroupRule rule;

    private ExplorerGroup(String id, Supplier<Component> label, Category category, ExplorerGroupRule rule) {
        this.id = id;
        this.label = label;
        this.category = category;
        this.rule = rule;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    public Component label() {
        Component value = label.get();
        return value == null ? Component.literal(id) : value;
    }

    public Category category() {
        return category;
    }

    public ExplorerGroupRule rule() {
        return rule;
    }

    public Component example() {
        return rule.example();
    }

    public ExplorerGroupRule.Bucket bucket(ExplorerColumnContext context) {
        return rule.bucket(context);
    }

    public static final class Builder {
        private final String id;
        private Supplier<Component> label;
        private Category category = Category.EXTENSION;
        private ExplorerGroupRule rule;

        private Builder(String id) {
            this.id = id;
            this.label = () -> Component.literal(id == null ? "group" : id);
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

        public Builder category(Category category) {
            this.category = Objects.requireNonNull(category, "category");
            return this;
        }

        public Builder rule(ExplorerGroupRule rule) {
            this.rule = Objects.requireNonNull(rule, "rule");
            return this;
        }

        public ExplorerGroup build() {
            if (id == null) {
                throw new IllegalArgumentException("group id must not be null");
            }
            int colon = id.indexOf(':');
            if (colon <= 0 || colon == id.length() - 1) {
                throw new IllegalArgumentException("group id must be namespace:path: " + id);
            }
            if (id.indexOf('|') >= 0 || id.indexOf(';') >= 0 || id.indexOf(',') >= 0
                    || id.indexOf('=') >= 0 || id.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("group id contains reserved characters: " + id);
            }
            if (rule == null) {
                throw new IllegalArgumentException("group rule is required: " + id);
            }
            return new ExplorerGroup(id, label, category, rule);
        }
    }
}
