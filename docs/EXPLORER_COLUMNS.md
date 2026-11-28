# 第三方详情列与分组 API

Information Era 1.21.1 客户端允许第三方模组向资源管理器「详细信息」表注册列。API 位于
`cc.sighs.informationera.api.client`，只依赖客户端类；服务端不会扫描或加载 provider。

## 最小示例

```java
@ExplorerColumns
public final class MyColumns implements ExplorerColumnProvider {
    public MyColumns() {
    }

    @Override
    public void registerColumns(ExplorerColumnRegistry registry) {
        registry.register(ExplorerColumn.builder("mymod:rarity")
                .label(Component.translatable("mymod.column.rarity"))
                .defaultWidth(110)
                .defaultVisible(true)
                .value(ctx -> ctx.folder() || ctx.stack().isEmpty()
                        ? "" : ctx.stack().getRarity().name())
                .sortValue(ctx -> ctx.folder() || ctx.stack().isEmpty()
                        ? "" : ctx.stack().getRarity().name())
                .build());
    }
}
```

provider 必须：

- 实现 `ExplorerColumnProvider`；
- 使用 `@ExplorerColumns` 标记；
- 是 public 类，并有 public 无参构造器；
- 使用稳定的 `namespace:path` 列 ID（建议用自身 modid 作为命名空间）。

## 生命周期

- `FMLClientSetupEvent` 时通过 `ModFileScanData` 扫描所有 mod 的 `@ExplorerColumns` 类；
- 每个 provider 在独立 `ExplorerColumnRegistry` 中注册，成功后合并；注册抛异常时该 provider
  整批丢弃，其它 mod 与内置列/依据继续加载；
- 列或依据 ID 冲突时跳过后来者并记录日志；列的标题、取值、比较异常按列隔离，分桶异常按依据
  隔离并回退到「未知」桶，不会中断表格或其它分组。

## 取值上下文

`ExplorerColumnContext` 提供：文件夹标志、显示名、数量、注册 ID、命名空间、类型、修改/创建
时间、作者、真实 `ItemStack`、最大堆叠、最大耐久，以及条目真实父目录的稳定 ID
（`parentId()`）和可读路径（`parentPath()`）。`stack()` 只在 provider 实际访问时构造；
文件夹/无法解析物品返回空栈。

## 分组规则

列可以可选地声明分组规则，现有 provider 不修改也不会受影响：

```java
registry.register(ExplorerColumn.builder("mymod:energy")
        .label(Component.translatable("mymod.column.energy"))
        .value(ctx -> formatEnergy(readEnergy(ctx.stack())))
        .groupRule(ExplorerGroupRule.builder()
                // 显示精确数值，分组只返回稳定区间 ID 与本地化标题。
                .bucket(ctx -> {
                    long energy = readEnergy(ctx.stack());
                    if (energy < 20_000) {
                        return ExplorerGroupRule.Bucket.of("low",
                                Component.translatable("mymod.group.energy.low"), 0);
                    }
                    if (energy < 80_000) {
                        return ExplorerGroupRule.Bucket.of("medium",
                                Component.translatable("mymod.group.energy.medium"), 1);
                    }
                    return ExplorerGroupRule.Bucket.of("high",
                            Component.translatable("mymod.group.energy.high"), 2);
                })
                .example(Component.translatable("mymod.group.energy.example"))
                .build())
        .build());
```

分组依据 ID 就是列 ID。组身份是「依据 ID + 桶 ID」：`low`/`medium`/`high` 必须是稳定英文键，
标题只用于显示，因此切换语言、两个模组使用同名标题都不会串组。`Bucket.of` 的第三个参数是
组序值（数字按数值、字符串按自然序），组序反向由玩家单独切换，不会改变组内条目排序。

没有详情列、但需要出现在分组菜单里的概念，可以在同一个 provider 中注册独立依据：

```java
registry.registerGroup(ExplorerGroup.builder("mymod:energy_band")
        .label(Component.translatable("mymod.group.energy_band"))
        .category(ExplorerGroup.Category.ITEM)
        .rule(energyRule)
        .build());
```

分类只影响「更多分组依据…」弹窗的归组（常规 / 物品属性 / 位置 / 扩展）；第三方列派生的依据
默认进入扩展。依据 ID 冲突会跳过后来者并记录日志；桶 ID 只在同一依据内比较，同名标题不会合并。
单个桶规则抛异常时只有该依据退回「未知」，其它依据和表格照常渲染。未注册的依据 ID 会保留在
文件夹配置中，临时卸载模组后重新装回即可恢复分组。

## 持久化

列的宽度、显隐和顺序按稳定列 ID 保存，作用域仍为「玩家 + 磁盘 + 文件夹」。旧版固定列格式
会迁移到带命名空间的 ID；删掉 mod 时其列设置保留在配置中，重新加入后按原 ID 恢复，不会被
按下标套到其它列。

## 示例与测试

独立样例模组源码/产物位于 `.tmp/e2e/sample-columns/`，其中包含：

- `samplecolumns:rarity`：显示 `ItemStack.getRarity()`，默认可见；
- `samplecolumns:max_damage`：显示 `getMaxDamage()`，默认隐藏，可在「其他…」勾选；
- `samplecolumns:explosive`：故意在 value 中抛异常，用于验证单元格异常隔离；
- `BrokenSampleProvider`：故意在 provider 注册阶段抛异常，用于验证 provider 隔离。

真实客户端验证记录见 `.tmp/e2e/evidence/SAMPLE_COLUMNS_REPORT.md`。
