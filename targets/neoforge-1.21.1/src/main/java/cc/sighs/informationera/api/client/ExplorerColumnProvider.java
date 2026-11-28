package cc.sighs.informationera.api.client;

/**
 * 第三方模组向资源管理器详情表注册列的入口。
 *
 * <p>实现类加上 {@link ExplorerColumns} 注解后会被自动发现；实现类必须 public，并有 public
 * 无参构造器。一个 provider 的注册过程是隔离的：{@link #registerColumns(ExplorerColumnRegistry)}
 * 抛出异常时，该 provider 本次已注册的列会整体丢弃，其它 mod 的列照常加载。
 *
 * <p>示例：
 * <pre>{@code
 * @ExplorerColumns
 * public final class MyColumns implements ExplorerColumnProvider {
 *     public MyColumns() {
 *     }
 *
 *     @Override
 *     public void registerColumns(ExplorerColumnRegistry registry) {
 *         registry.register(ExplorerColumn.builder("mymod:rarity")
 *                 .label(Component.translatable("mymod.column.rarity"))
 *                 .defaultWidth(110)
 *                 .defaultVisible(true)
 *                 .value(ctx -> ctx.folder() ? "" : ctx.stack().getRarity().name())
 *                 .sortValue(ctx -> ctx.folder() ? "" : ctx.stack().getRarity().name())
 *                 .build());
 *     }
 * }
 * }</pre>
 */
public interface ExplorerColumnProvider {

    /**
     * 注册列。可以一次注册多列；ID 必须稳定且全局唯一（建议使用自身 modid 作为命名空间）。
     */
    void registerColumns(ExplorerColumnRegistry registry);
}
