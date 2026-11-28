package cc.sighs.informationera.api.client;

import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 列或独立分组依据使用的分桶规则。
 *
 * <p>一个条目只归入一个桶。桶必须同时提供稳定的 {@code id}、给玩家看的本地化标题和用于组序的
 * 排序值：组身份是「依据 ID + 桶 ID」，因此两个模组即使显示同名也不会合并；切换语言只改变标题，
 * 不会改变分组设置。示例（能量列显示精确数值，分组只返回区间）：
 *
 * <pre>{@code
 * ExplorerGroupRule.builder()
 *         .bucket(ctx -> {
 *             long energy = readEnergy(ctx.stack());
 *             if (energy < 20_000) {
 *                 return ExplorerGroupRule.Bucket.of("low",
 *                         Component.translatable("mymod.group.energy.low"), 0);
 *             }
 *             if (energy < 80_000) {
 *                 return ExplorerGroupRule.Bucket.of("medium",
 *                         Component.translatable("mymod.group.energy.medium"), 1);
 *             }
 *             return ExplorerGroupRule.Bucket.of("high",
 *                     Component.translatable("mymod.group.energy.high"), 2);
 *         })
 *         .example(Component.translatable("mymod.group.energy.example"))
 *         .build();
 * }</pre>
 *
 * <p>规则抛出的异常由宿主按依据隔离：只有该依据退回到「未知」桶，其它依据和表格照常工作。
 */
public final class ExplorerGroupRule {

    private final Function<ExplorerColumnContext, Bucket> bucket;
    private final Supplier<Component> example;

    private ExplorerGroupRule(Function<ExplorerColumnContext, Bucket> bucket, Supplier<Component> example) {
        this.bucket = bucket;
        this.example = example;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Bucket bucket(ExplorerColumnContext context) {
        return bucket.apply(context);
    }

    /** 分组候选在弹窗里的简短示例；语言切换后重新读取。 */
    public Component example() {
        Component value = example.get();
        return value == null ? Component.empty() : value;
    }

    public static final class Bucket {
        private final String id;
        private final Component label;
        private final Comparable<?> sortValue;

        private Bucket(String id, Component label, Comparable<?> sortValue) {
            this.id = id;
            this.label = label;
            this.sortValue = sortValue;
        }

        /** 桶 ID 只在本依据内比较；建议使用稳定英文键，不要用翻译文本。 */
        public static Bucket of(String id, Component label, Comparable<?> sortValue) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("group bucket id must not be blank");
            }
            if (id.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("group bucket id contains NUL");
            }
            return new Bucket(id, Objects.requireNonNull(label, "label"), sortValue);
        }

        public static Bucket of(String id, Component label) {
            return of(id, label, null);
        }

        public String id() {
            return id;
        }

        public Component label() {
            return label;
        }

        /** 用于组序；null 时回退到标题。 */
        public Comparable<?> sortValue() {
            return sortValue;
        }
    }

    public static final class Builder {
        private Function<ExplorerColumnContext, Bucket> bucket;
        private Supplier<Component> example = () -> Component.empty();

        private Builder() {
        }

        /** 必填：按条目上下文返回稳定桶 ID、标题和组序值。 */
        public Builder bucket(Function<ExplorerColumnContext, Bucket> bucket) {
            this.bucket = Objects.requireNonNull(bucket, "bucket");
            return this;
        }

        public Builder example(Component example) {
            Objects.requireNonNull(example, "example");
            this.example = () -> example;
            return this;
        }

        public Builder example(Supplier<Component> example) {
            this.example = Objects.requireNonNull(example, "example");
            return this;
        }

        public ExplorerGroupRule build() {
            if (bucket == null) {
                throw new IllegalArgumentException("group bucket function is required");
            }
            return new ExplorerGroupRule(bucket, example);
        }
    }
}
