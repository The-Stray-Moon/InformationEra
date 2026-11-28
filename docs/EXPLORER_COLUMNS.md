# 第三方详情列 API

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
  整批丢弃，其它 mod 与内置列继续加载；
- 列的标题、取值、比较异常按列隔离，出错列显示空值并记录一次日志，不会中断表格渲染。

## 取值上下文

`ExplorerColumnContext` 提供：文件夹标志、显示名、数量、注册 ID、命名空间、类型、修改/创建
时间、作者、真实 `ItemStack`、最大堆叠、最大耐久。`stack()` 只在 provider 实际访问时构造；
文件夹/无法解析物品返回空栈。

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
