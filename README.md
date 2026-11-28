# Information Era

A Minecraft mod about the Information Era.

- mod id：informationera
- Java 包名 / Gradle group：cc.sighs.informationera
- 作者：Tower of Sighs
- 声明的许可证：All Rights Reserved

## 项目状态

Information Era 将玩家库存组织成文件与文件夹。资源管理器、存储卡及游戏内存取目前主要实现在 NeoForge 1.21.1；其他 target 是开发入口，不代表功能已移植。详见[产品设计](docs/PRODUCT_DESIGN.md)。

资源管理器的筛选窗格与预览窗格互斥，占用同一个右侧位置。筛选支持按物品标签、模组 id、创造标签页、关键词复合过滤：同类条件取并集，不同类之间取交集，并与顶部搜索框同时生效。“展开所有子文件夹”会在当前目录中平铺显示整棵子树的物品，但条目的真实位置不变，右键菜单、路径、属性与拖拽仍按实际父文件夹操作。顶部“物品去重”只做视觉去重：在搜索和筛选之后按真实 ItemKey（含组件）保留一个代表，不合计库存、不删除文件夹、不修改磁盘数据。各类条件都支持多值 chip、自动补全、回车提交和弹窗选择；弹窗依次展示模糊搜索、已选条件、高频使用、最近使用与当前目录候选，使用历史全存档通用，保存在 `config/informationera-client.toml`。标签和模组始终按实际英文 ID 匹配；创造标签页按当前本地化名称显示和补全，安装 JECh 时支持全拼/首字母，未安装时按本地化原名子串匹配，写入 chip、模板和配置的仍是稳定 registry ID。

“关键词”条件位于创造标签页之后、模板之前，候选来自当前范围全部物品的真实本地化名称（不使用磁盘文件的自定义名）：英文按完整词且不区分大小写，中文取有界的连续汉字子串，名称中段的有效词组同样进入候选，不做前后缀语义排除；按不同物品种类的名称包含次数排序，同频优先较长的完整词。展开子目录会随既有范围扩大候选，切换语言会刷新名称和候选。关键词与其他条件一样支持 chip、补全、回车、五区弹窗和模板快照；旧模板仍可加载。

资源管理器支持单层分组：保留名称、修改日期、创建日期、类型、数量、作者六个旧依据，并新增来源模组、稀有度、当前耐久状态（待修理/已磨损/完好/无耐久）、实际堆叠上限、附魔状态（区分装备附魔与储存附魔）和真实父文件夹。先应用搜索、筛选与去重，再分组；组身份由稳定依据 ID 与桶 ID 组成，标题本地化，组序按自然序并可单独反向，组内排序继续跟随详情表头。组头显示当前结果条目数，支持按组选中、折叠和全部展开/折叠。“排序 → 分组依据”提供常用项与可搜索的“更多分组依据…”弹窗（常规/物品属性/位置/扩展，带示例）；详情表头可对支持分组的列直接分组或取消。分组依据与组序按文件夹保存，并纳入默认视图模板；旧配置中的短分组名会迁移，折叠状态只在本次会话保留。第三方模组可通过 `@ExplorerColumns` 为详情列声明可选分组规则，或注册无表头的独立依据；接入与能量区间示例见[详细信息列 API](docs/EXPLORER_COLUMNS.md)。

筛选窗格采用紧凑布局，条件输入框与文件夹搜索框复用同一 240px 常规宽度，并保持相同的高度、字体和垂直/左侧内边距；窗格窄于该宽度时输入框按可用空间收缩。顶部“清除”按钮一键清空搜索、条件 chip、平铺/去重开关和当前应用模板，但保留已保存模板与使用历史。底部可将搜索框、标签/模组/创造标签页稳定 ID、关键词、平铺与去重开关整体保存为筛选模板。模板全存档通用，模板列表与使用历史同样保存在该客户端配置文件中；应用模板会完整替换当前规则，手工修改任一条件后不再标记为完全匹配。模板同样支持输入补全、chip 和五段式选择弹窗（搜索、当前应用、高频、最近、全部模板）。

构建框架基线：[SighsTemple `73d8e5e`](https://github.com/Tower-of-Sighs/SighsTemple/commit/73d8e5e68694cb7a5c3a1f5ef6208176b4f36ffc)。

以下矩阵在初始化时从实际 target 目录及 `ci.properties` 生成。CI 启用仅表示会参与验证，不等于已验证支持；后续增删版本或改变工具链时同步维护本表。

| Target | Gradle JVM | CI 配置 | 项目验证状态 |
| --- | --- | --- | --- |
| fabric-1.20.1 | JDK 17 | true | 构建与共享测试通过；游戏内待验证 |
| fabric-1.21.1 | JDK 21 | true | 构建与共享测试通过；游戏内待验证 |
| fabric-26.1 | JDK 25 | true | 构建与共享测试通过；游戏内待验证 |
| forge-1.18.2 | JDK 21 | true | 构建与共享测试通过；游戏内待验证 |
| forge-1.19.2 | JDK 21 | true | 构建与共享测试通过；游戏内待验证 |
| forge-1.20.1 | JDK 21 | true | 构建与共享测试通过；游戏内待验证 |
| neoforge-1.21.1 | JDK 21 | true | 构建与共享测试通过；游戏内待验证 |
| neoforge-26.1 | JDK 25 | true | 构建与共享测试通过；游戏内待验证 |

## 开发与构建

开发规范见 [AGENTS.md](AGENTS.md)。共享业务与资源位于 `common/`；loader / Minecraft 差异位于 `targets/<loader>-<version>/`。所有 target 直接编译 common 原源码，不引用共享 jar，也不等待复制到 build 目录。

- 单版本：IDEA 打开对应 target 目录，使用该目录的 wrapper 和矩阵声明的 JDK。
- 全版本：IDEA 打开 `idea-workspace/`；当前主版本为 **neoforge-1.21.1**，配置的 Gradle JVM 为 **JDK 21**。该值来自 target 描述文件，仍须核对工作区 wrapper 与所用 JDK 的兼容性。
- 工作区只完整导入主 target。其他版本、文档、脚本及根配置作为仓库浏览内容展示，不承诺非主版本的 API 补全。common 源码只归属主 target，不额外关联根工程、common 或其他完整 target。
- 切换主版本修改 `idea-workspace/gradle.properties` 的 `workspace_main_target` 后重新加载 Gradle。composite build 共用工作区的 Gradle/JVM，不使用被包含 target 的 wrapper；若不兼容，独立打开 target 或调整工作区工具链。
- 普通 common 内容修改无需运行源码同步任务；变更 Gradle 配置或 `commonJava` 排除规则后需重新加载 Gradle。

在所选 target 目录执行：

```powershell
.\gradlew.bat clean build
.\gradlew.bat verifyHotDebug
.\gradlew.bat runClient
# 或 runServer；Minecraft EULA 必须由用户阅读并接受
```

在仓库根可执行 `./gradlew -p common build` 单独检查共享层，但不能替代目标矩阵验证。根 `-Ptarget=<目录名> build` 调用该 target 的独立 wrapper；`-PallTargets=true build` 自动选择已启用且 `ci.java` 不高于当前 Gradle JVM 的目标，并打印跳过项。最低 JDK 筛选不保证旧 wrapper 支持任意新版 JDK，必要时分别构建。根 `build` 默认只验证 common。

## AutoJS 热调试与热执行

运行时调试必须使用 AutoJS，不得仅凭源码推断、临时日志或构建成功宣称游戏行为正确。

每个 target 的 `debug_*` 属性声明来自 Sighs Maven 的 AutoJS、Rhino 及可用的 KubeJS。具体版本和模式以该 target 配置为准；没有 `debug_kubejs` 时仅用 Rhino，不假设存在 KubeJS 事件或 `kubejs_log`。调试依赖仅用于开发游戏运行，不嵌入发布 jar，不成为玩家端必需依赖。

1. 使用相应 JDK 执行 `verifyHotDebug`，再启动 target 的游戏实例。
2. 默认 `GET http://127.0.0.1:25585/health` 探活，通过 MCP 客户端连接 `http://127.0.0.1:25585/mcp`，先调用 `status` 确认 side 和引擎模式。
3. 用 `eval_js` 执行短脚本，例如 server side 的 `AutoJS.snapshot()`；检查 `isError`、`ok`、`diagnostics`，触发行为后回读状态并形成断言。界面和输入用 client，权威世界修改用 server。
4. 修改脚本用 `reload_scripts`；KubeJS 模式结合 `kubejs_log` 检查错误，Rhino 模式重建作用域后重新注入验证脚本。配方、标签等按需另做 `/reload`。
5. Java、Mixin 和启动期注册内容不能靠脚本热重载替换，需构建重启后再用 AutoJS 验证。

配置位于游戏目录 `config/autojs.json`；多实例用 `AUTOJS_PORT` 分配不同端口。端点默认可执行任意 Java，只用于本机开发，不得暴露公网或玩家生产环境。共享机器配置 token，请求带 `Authorization: Bearer <token>`，不提交凭据。完整操作手册可从 AutoJS 发布 jar 提取 `SKILL.md`。仓库或端点不可用时明确记录阻塞，不用本地替代 jar 冒充远端验证。

## 发布与维护

- curseforge: project ID not configured in root gradle.properties; environment overrides and credentials remain unverified.
- modrinth: project ID not configured in root gradle.properties; environment overrides and credentials remain unverified.

发布前验证承诺支持的每个 target：构建、检查 jar 的 metadata/共享类/资源、使用 AutoJS 做最小 client 和 dedicated server 行为验证，并记录实际范围。初始化没有运行这些验证，不得把上表的待验证状态当作兼容性承诺。

- [发布指南](docs/PUBLISHING.md)
- [维护流程](docs/MAINTENANCE_WORKFLOW.md)
- [自动发现与新增 target](docs/CI_TARGET_DISCOVERY.md)
- [版本迁移参考](docs/version-differences/README.md)

工程来源：[开发框架](https://github.com/Tower-of-Sighs/SighsTemple)。本 README 与 AGENTS.md 已转为项目文档，后续由项目维护者更新，不再运行初始化脚本覆盖。
