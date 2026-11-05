# Saturn 参数化 Getter 与信息模块重构

> 本文记录 9405441 阶段的接入。当前 9406442 的注册表分组、扁平结果与三个项目迁移见 [适配说明](13-Saturn注册表信息行与扁平结果适配.md)。

本地 Saturn 已用 `EntityValueGetter<T>` 替代 Field，数据入口统一到 `com.iafenvoy.saturn.data.information`。目标是让取值支持参数，按属性/资源/效果 ID 查询，避免依赖列表位置或为每个属性注册一个字段。Saturn 仍只解析调用方提供的数据，玩家文件访问、归档、网络和授权由宿主负责。

Saturn 已发布 `curse.maven:saturn-lib-1767455:9405441`（1.2.1），Reincarnation、MiXianTu、System 均已更新依赖和业务调用。三个模组的元数据均要求 Saturn 至少 1.2.1。旧文件 `9402280` 不包含下文的新 API。

## 模块与职责

| 包 | 内容 |
| --- | --- |
| `api.playerview` | PlayerView、writer、行 provider 与宿主查询契约 |
| `data.information` | PlayerViews、PlayerViewSnapshot、PlayerInformation、PlayerInformationRegistry、InformationText；定义 InformationRow / InformationGroup |
| `data.information.getter` | EntityValueGetter、内置常量 EntityValueGetters、完整参数身份 EntityValueKey |
| `data.information.getter.builtin` | 每个 getter 类型的独立实现 |
| `data.information.expression` | InformationExpression、求值上下文与中间结果 |
| `data.information.expression.builtin` | 每个表达式类型的独立实现 |
| `data.information.model` | InformationRowResult、InfomationGroup、文档、元数据、缺失原因及 DTO |

`InformationRow` 是执行定义，`InformationRowResult` 是展示结果；`InformationGroup` 是引用行的定义，`InfomationGroup` 是结果中的组元数据。Group 仍用 `Either<Holder<InformationRow>, TagKey<InformationRow>>` 选取行，Row 定义不声明 groups。全取、按组取、去重及 importance 均保留。

## 注册表与参数

`saturn:entity_value_getter` 是无默认值的原生代码注册表，入口为 `SaturnRegistries.ENTITY_VALUE_GETTER` / `SaturnResourceKeys.ENTITY_VALUE_GETTER`，条目类型是 `MapCodec<? extends EntityValueGetter<?>>`。参数由注册的 MapCodec 解码，结果由 getter.valueCodec() 编解码，两者职责分开。

getter 可以实现：

- `capture(Entity)`：只读现场实体，默认 UNSUPPORTED。
- `readSaved(SavedPlayerViewContext)`：只解析传入存档，默认 NOT_CAPTURED。
- `fallback(PlayerView)`：从已保存的事实投影，默认 NOT_CAPTURED，不寻找实体。
- `dependencies()`：声明快照投影需要的宽查询，加入采集计划与来源集合。
- `schemaVersion()`：结果 schema，默认 1。

内置保留 22 个无参宽查询，增加 4 个带参类型：

| 类型 | 参数 | 结果 |
| --- | --- | --- |
| attribute | attribute ID；value 为 base/effective，默认 effective | double |
| resource | resource ID；value 为 current/minimum/maximum，默认 current | double |
| effect | effect ID；value 为 amplifier/duration，默认 amplifier | int |
| score | objective 名称 | int |

表达式把查询作为独立对象嵌入：

```json
{
  "type": "saturn:number",
  "value": {
    "type": "saturn:getter",
    "getter": {
      "type": "saturn:attribute",
      "attribute": "minecraft:max_health",
      "value": "base"
    }
  }
}
```

`path` 仅用于复合结果的成员访问，例如 experience 的 level。单属性查询按 ID 匹配，不依赖 attributes 数组顺序。不同参数参与完整查询身份与求值缓存，不会覆盖彼此。

`PlayerViews.parse` 合并可从空对象解码的默认查询、调用方显式请求、当前世界启用行（含全部分支、标题和 tooltip）的查询与依赖。必填参数不能自动枚举，需由行或宿主请求；Java provider 若需要带参结果也应显式请求。单个查询异常、null 或结果编码失败仅标记该查询 INVALID。

## 快照兼容与边界

顶层版本保持 1。无参 `{type}` 结果继续使用旧 fields，内置 ID/schema/载荷保持兼容；带参结果写入可选 queries：

```json
{
  "getter": {"type":"saturn:attribute","attribute":"minecraft:max_health","value":"base"},
  "data": {"schema":1,"state":"known","origin":"captured","value":20}
}
```

未知 getter 定义、字段封套和结果载荷均保留往返。确切查询结果不存在时可以从宽列表投影；已有明确 missing、未知 schema 或无效载荷不得被投影覆盖。存档读取返回 NOT_CAPTURED 不创建封套，宿主仍可用 builder.contains(getter) 决定是否补充。

历史属性只能确认基值、资源边界未记录时仍为缺失，不用当前规则推测。已知列表没有对应成员为 NOT_APPLICABLE。快照不持有现场实体、附件和调用方可变集合。

`snapshot.filter(predicate)` 同时过滤 fields / queries，粒度为 getter 类型 ID；`sourceFields` 历史命名保留，现在包含类型与依赖 ID。例如 attribute 来源包括 saturn:attribute 和 saturn:attributes，不能据此按某个属性参数授权，宿主若需要更细限制应自行投影。固定顶层未知键由普通 Codec 忽略；旧版虽可加载含 queries 的结构，但不能使用或保留 queries。

## 宿主迁移

1. 将 PlayerViewField 改为 EntityValueGetter，旧载荷 codec 改为 valueCodec，新增注册用参数 MapCodec。无参实现可用 enum 单例和 MapCodec.unit。
2. 删除 Contributor / SavedReader 注册，将实时和存档解析放入 getter；capture 的参数由 Player 改为 Entity，按所需实体类型判断。
3. 迁移旧 view.* 导入到新模块；原结果 InformationRow 改为 InformationRowResult。
4. `saturn:field` 表达式改成 `saturn:getter`，field ID 改成 getter 对象，path 可保留。
5. 前世归档保持事实快照；缺少参数结果时仅从已保存宽列表投影，不恢复或借用今世 Player。

三个宿主的接入如下：

- Reincarnation 将 `reincarnation:life` 注册为无参 `LifeGetter`，实时读取仍只支持 ServerPlayer；归档/摘要继续由宿主补入 SoulRecord。ID、schema 1 与结果 Codec 保持不变，旧 fields 中的前世摘要仍能解析。快照、网络文档、界面和命令已迁移包名及结果类型。
- MiXianTu 将 `mxt:information` 注册为无参 `MxtInformationGetter`，参数 Codec 通过模组事件总线注册；实时与已保存附件解析分别由 getter.capture / readSaved 进入，展示仍读取独立快照。原 Contributor / SavedReader 注册移除；结果 map 的 ID、schema 与 Row Codec 保持，人物面板和既有测试探针已迁移。
- System 的玩家信息卡片与 Agent 查询继续使用既有白名单、分组和文字上限，入口迁到 data.information，行结果改用 InformationRowResult。没有增加第二套玩家数据采集或存储。

MiXianTu 的 7 条修炼行进一步迁入 `data/mxt/saturn/information_row/`，分组 `mxt:cultivation` 引用 `#mxt:cultivation` 行标签。文字组件和 tooltip 由已有 mxt:information 快照事实读取，缺少数据隐藏；Java 行与分组注册已移除，面板标题也读取当前组定义。测试包包含跨命名空间标签追加夹具。

Saturn 的直接 Java 行/组注册、PlayerInformationRowProvider、只枚举代码组的无参 groups()、结果单组访问器 group() 均已标记 `@Deprecated(forRemoval = true)`，行为暂时保留。全取、按组取、groups(view) 与结果的 groups() 保留。Reincarnation 仍有直接 Java 注册调用，后续应把生命周期展示行迁为定义；当前弃用标记尚未包含在已发布的 9405441 中。

getter value、算术/比较/逻辑 operation、missing 和展示 type 等固定选项已使用 enum Codec，非法值返回 DataResult 错误；InformationExpression.CODEC 保持 lazyInitialized。

## 查询与验证

```mcfunction
/saturn information @s
/saturn information @s saturn:basic
```

`/saturn information <target> [<group>]` 要求 gamemaster 权限，支持玩家名、UUID、单实体选择器与组 ID 补全。省略 group 全取，否则按组取；文字由同一 InformationText 投影生成，仅回复执行者，返回逻辑行数。未知组与空组有明确提示，内置提示含中英文翻译键。

本轮 `compileJava verifyPlayerViews runGameTestServer` 已通过：84 项独立检查、9 项 GameTest。指令测试实际执行命令，逐行核对全部/分组输出，同时检查补全、未知组、空组、权限和非玩家查询。GameTest 位于 main 的 gametest 子包，不使用 test-mod；界面视觉仍需人工验收。

宿主更新到 9405441 后，三个项目的编译与打包均通过；MiXianTu 的 compileTestModJava 和 System 的 testModClasses 也通过。Reincarnation 实跑 23 项 GameTest 全部通过，包含新归档、旧档解析与信息文档网络编解码。MiXianTu / System 本次未实跑游戏测试或客户端，编译通过不代表界面已完成运行验收。

当前完整 API 与示例以 [Saturn 玩家视图](../../../Saturn/docs/PLAYER-VIEWS.md) 和 [数据驱动信息行](../../../Saturn/docs/INFORMATION-DATA.md) 为准。
