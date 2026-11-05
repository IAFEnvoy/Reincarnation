# Saturn 信息行数据驱动重构

> 历史阶段记录：Group 引用 Row/Tag 的设计继续适用；字段层、表达式取值语法和部分类型名已调整，见 [参数化 Getter 与信息模块重构](12-Saturn参数化Getter与信息模块重构.md)。当前 `InformationRow` 是定义，`InformationRowResult` 是结果。

Saturn 本地源码已将内置展示行迁移到数据包，支持多 Group 归属和可组合表达式。完整格式与扩展示例以 [Saturn 数据驱动信息行](../../../Saturn/docs/INFORMATION-DATA.md) 为准，玩家视图 API 见 [PLAYER-VIEWS](../../../Saturn/docs/PLAYER-VIEWS.md)。本次没有替换 Reincarnation 使用的 Curse Maven 发布依赖，也没有迁移其生命周期展示 provider；已发布文件 `9402280` 不包含这些本地改动。

后续字段层已迁入原生注册表，实时采集与存档读取合并到各自字段，见 [字段注册表与逐字段解析](11-Saturn字段注册表与逐字段解析.md)。以下信息行/Group 结构继续适用。

## 实现结构

| 注册表 | 类型 | 用途 |
| --- | --- | --- |
| `saturn:player_view_field` | 代码注册表 | 字段 Codec、schema、实时采集与可选存档读取 |
| `saturn:information_expression_type` | 代码注册表 | 注册 `MapCodec<? extends InformationExpression>`，定义可复用操作 |
| `saturn:information_group` | 数据包注册表 | Group 标题、顺序及 Holder/Tag 行引用 |
| `saturn:information_row` | 数据包注册表 | 独立 Row 的顺序、importance、标题、内容及条件 |

Saturn 已将 3 个 Group 和 16 条内置信息行全部放到 `data/saturn/saturn/information_group/`、`information_row/`。原固定内置 Java provider 已移除。Group 和行定义使用原生注册表的加载与同步机制，需要重新加载世界/重启服务器应用定义变更，没有额外实现 `/reload` 热更新。

`InformationRowDefinition` 是生成信息行的配方，`InformationRow` 仍是生成结果。两者分别编解码，玩家归档依旧保存事实快照，不保存执行中的表达式或真实实体。

## 多组归属

成员关系由 Group 定义，Row 不声明 groups。Group 的 `rows` 数组使用 `Either<Holder<InformationRowDefinition>, TagKey<InformationRowDefinition>>`，例如 `["saturn:health", "#saturn:basic"]`；普通 ID 是单行 Holder，# 前缀为行标签。标签文件位于 `data/<namespace>/tags/saturn/information_row/`，内置三组各引用同名行标签。

同一行只注册一次，多个 Group 可以引用它。按组查询展开引用，多个组取并集，直接/标签/跨组重叠均按行 ID 去重。全取包含未被任何 Group 引用的行，它们结果中的 groups 为空，默认页面显示到“其他信息”。生成结果携带的组列表由收集器反向计算。

单组查询和 `document.rows(group)` 按行 order、ID 排序。全取和多组并集先按所属组中排序最靠前者排序，再按行 order、ID；未分组行排最后。importance 仍独立于布局顺序。

Java 单组/多组注册重载保留，其作用为登记行并补充代码组默认选取范围；增加不带组的 `register(id, order[, importance], provider)`。同 ID 数据包 Group 会替换默认范围，纯 Java provider 不在原生 Row 注册表内，不能作为 Holder 或标签成员。同 ID 数据定义在收集时覆盖 Java provider；禁用或隐藏定义不会重新执行被覆盖的 provider。

## value 的数据驱动方式

表达式分离“读取什么”和“如何展示”：

```text
PlayerViewField ID
→ PlayerView.read(field)
→ 字段自身 Codec 编码
→ path 提取成员
→ 数字/文本/进度条/列表/表格等模板
→ InformationValue
```

例如 `{"type":"saturn:field","field":"saturn:experience","path":["level"]}` 读取经验等级。路径指向 Codec 数据结构，无需反射 Java record，也无需为每个字段单独注册展示 Type。字段 schema 与 missing reason 检查仍由视图执行。

Type 可以相互组合：生命条同时读取 health 和 max_health，并用 coalesce 在最大值缺失时退化为数字；列表/表格将元素放入局部作用域；条件、计算、格式化、翻译以及缺失策略均可通过 JSON 声明。模组可以继续注册专用 Type 处理特殊语义。

`sourceFields` 自动汇总整棵表达式，包括 visible、动态标题、tooltip 与未选中分支。零、false、空集合为已知值；不存在、无效、不支持与无权限保持 Missing，不能自动变成零。

## Reincarnation 后续接入要求

当前 `ReincarnationViews` Java provider 继续作为兼容入口使用。后续升级到包含此重构的 Saturn 发布版本时：

1. 新结果 Codec 写 `groups` 数组；新版本可读旧 `group` 对象，但旧客户端不能读新结果，应升级服务器与客户端使用的 Saturn。
2. `InformationRow.groups()` 为完整成员关系；旧 `group()` 仅为弃用兼容入口，无组时不可调用，不能用于多组筛选。
3. 从实体捕获或用 RegistryOps 解码的快照保留定义上下文。需要明确使用当前世界定义时，通过 `new InformationContext(language, server.registryAccess())` 传入；不携带上下文的视图仅收集代码注册行。
4. 生命周期信息可逐步迁到数据定义，字段读取仍使用宿主提供的 `reincarnation:life`。世代编号的大整数精度、UTC 时间与业务状态翻译需要保留原有语义，必要时注册专用表达式，而不是未经检查转换为 double。
5. Saturn 仍只解析传入数据；归档保存、事务、鉴权和文档传输继续归 Reincarnation。

## 验证范围

Saturn 的 main 源码集 `gametest.InformationTests` 验证原生注册表加载、16 条内置 JSON、表达式路径/类型/缺失、Holder/Tag 选取与标签展开去重、空组/未分组行、多组排序、覆盖规则、来源字段过滤及新旧文档 Codec；独立 `verifyPlayerViews` 继续验证核心快照与注册契约。客户端布局视觉效果、Reincarnation 升级后的集成测试和其他两个前端业务迁移不包含在本次验收中。
