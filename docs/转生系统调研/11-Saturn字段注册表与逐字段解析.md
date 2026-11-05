# Saturn 字段注册表与逐字段解析

> 历史阶段记录：本文的 Field API 已由参数化 `EntityValueGetter` 替代，当前结构、包名与迁移规则见 [参数化 Getter 与信息模块重构](12-Saturn参数化Getter与信息模块重构.md)。以下保留当时的设计与验证结论，不作为当前 API 接入示例。

Saturn 本地源码已新增 `saturn:player_view_field` 原生代码注册表。`PlayerViewField<T>` 同时负责字段身份、schema、Codec、实时采集和可选的存档读取，不再单独注册 Contributor / SavedReader。

完整扩展示例见 [Saturn 玩家视图 API](../../../Saturn/docs/PLAYER-VIEWS.md#注册类型化扩展)。本次没有升级 Reincarnation 的 Curse Maven 发布依赖，也未修改 MiXianTu / System 的业务代码。

## 当前结构

```text
DeferredRegister<PlayerViewField<?>>
→ SaturnRegistries.PLAYER_VIEW_FIELD
→ PlayerViews.parse(player / savedContext)
→ 各字段 capture / readSaved
→ PlayerViewSnapshot
→ 信息行表达式 / Java provider
```

每个字段声明：

- `id()`：稳定字段 ID，必须与注册 ID 一致。
- `schemaVersion()`：默认 1，必须为正数。
- `codec()`：类型化载荷编解码和验证。
- `capture(Player)`：返回自己的 `ViewValue<T>`，默认 `UNSUPPORTED`。
- `readSaved(SavedPlayerViewContext)`：仅解析调用方传入数据，默认 `NOT_CAPTURED`。

内置 22 个字段分别位于 `view.field.builtin`，由 `registry.content.SaturnPlayerViewFields` 使用 DeferredRegister 注册。`PlayerViewFields` 保留类型化常量和查询工具，内部不再维护手写字段 Map。注册表遵循原生注册与冻结生命周期，使用严格 ID 查找，无默认字段回退。字段拥有 Java Codec 与回调，因此无法仅通过数据包定义新的字段解析行为。

`PlayerViews` 只负责线程/账号检查、遍历和快照组装。字段直接返回值或 missing，不传入共享 writer，不声明字段归属集合。回调异常、null 返回、Codec 载荷失败只标记当前字段为 `INVALID`，不再连带标记同一采集器中的其他字段。

## 其他注册表取舍

| 对象 | 建议 |
| --- | --- |
| `PlayerViewContributor` / `SavedPlayerViewReader` | 已并入字段，无需各建一张表 |
| `InformationExpression`、Row、Group | 已有原生注册表，继续复用 |
| `InformationValue` 的类型 | 可在需要自定义复杂展示值时开放；同时需要文本投影、客户端显示扩展及未知类型回退，仅注册 Codec 不足以让三个前端正确显示 |
| Java 行 provider | 当前保留为兼容入口；一般自定义计算优先扩展表达式 Type。直接给 provider 建表仍不能使其自动成为 Group/Tag 可引用的数据行 |
| `PlayerViewAccess` | 宿主绑定的查询服务，继续由调用方传入，避免全局注册表绑定某个归档或网络实例 |
| missing、来源、tone 等枚举 | 作为共享协议语义保留，当前无需改为开放注册项 |

## 保存数据与兼容

已有快照中存在的封套始终优先，包括明确的 missing、未知 schema 和无效载荷；只有完全没有封套的字段才调用 `readSaved`。存档解析返回 `NOT_CAPTURED` 时继续不写封套，宿主可在解析后用 `builder.contains(field)` 判断是否补入姓名、鼠标物品、计分板、队伍等额外信息。其他明确的 missing 原因会保留。

快照版本、字段 ID、schema 和载荷格式保持。未知字段封套仍可无损往返，信息表达式仍用字段 ID 容错查表，缺少字段模组时返回 `UNSUPPORTED`。不将整个快照改成必须绑定 Holder 才能解码的格式。字段不参与 Saturn 的旧命名空间别名复制，避免同一事实被重复采集。

旧 `PlayerViewFields.register`、`PlayerViews.registerContributor/registerReader`、`PlayerViewContributor`、`SavedPlayerViewReader` 已移除。宿主升级到包含此变更的 Saturn 时，需要将原采集器逐字段迁入字段实现，再通过自己的 DeferredRegister 注册。Reincarnation 的 `reincarnation:life` 应保留字段 ID、schema 和 `SoulRecord.CODEC`，采集逻辑移入该字段；快照读写和宿主额外字段补充逻辑可继续沿用。

Saturn 仍不存数据，不加载玩家文件，也不负责权限和玩家数据网络。内置字段只读实体和传入数据，不创建附件，不重新结算资源与玩法。

## 验证

独立 `verifyPlayerViews` 验证真实字段表查找、逐字段读取、错误隔离、未知字段与 schema、宿主后续补充、录制 missing 优先级，以及原有快照/展示契约。main 源码集 `gametest.PlayerViewFieldTests` 验证实际 DeferredRegister 注册与冻结、扩展字段实时/存档解析、ServerPlayer 采集、不创建附件和可变物品快照隔离；由原 `InformationTests` 统一安装，仅 GameTest 模式注册测试和专用字段。没有使用 test-mod。

```powershell
.\gradlew.bat compileJava verifyPlayerViews runGameTestServer build --console=plain
```

本次通过 70 项核心检查、8 项 GameTest 和完整 build。验证日志位于 `E:\Java\Saturn\build\player-view-field-final-verification.log`。
