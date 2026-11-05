# MiXianTu 寿元系统迁移可行性与耦合分析

本文保留迁移前的调研结论。迁移已实现，当前行为、API、配置和验证见 [寿元系统迁移与数据驱动联动](15-寿元系统迁移与数据驱动联动.md)。以下“当前”均指调研时的实现。

## 结论

可以迁入 Reincarnation，并保持 Reincarnation 不依赖 MiXianTu。寿元已经拆成独立附件，账本、增减、结算和提醒的耦合较低；信息展示、数据包和脚本入口需要适配；目前夹在寿元服务中的修炼重置逻辑耦合最高，应留给 MiXianTu。

两项目使用相同的 Minecraft / NeoForge / Java 平台，Jupiter、Saturn 的依赖坐标也一致。寿元核心不需要引入 MiXianTu 的其他依赖。全文搜索发现 MiXianTu 的主源码中有 **23 个 Java 文件**提到寿元，其中包含注册、配置和桥接文件，并非 23 个业务模块。

建议分工：

- **Reincarnation**：寿元附件、增减 API、在线结算、寿尽事件、配置、提醒、指令、Saturn getter 和信息行；玩家寿尽转生调用已有完整转生流程。
- **MiXianTu**：境界突破增加寿元、修炼数据继承策略、既有数据包和 KubeJS 入口的适配。
- **Saturn**：继续提供注册表、公式、信息解析等通用设施；寿元的权威数据仍由 Reincarnation 保存。

## 当前实现与可复用部分

主入口是 MiXianTu 的 `runtime/cultivation/LifeSpanService.java`，数据位于 `attachment/LifespanAttachment.java`。

| 部分 | 当前行为 | 迁移判断 |
| --- | --- | --- |
| 附件 | 两个 `long`：`lifespan_remaining`、`lifespan_total`；MapCodec 序列化，继承 Saturn 的变更标记基类 | 基本可复用 |
| 增减 | `set` 同时重写剩余与总额；正 `add` 同增两项，负 `add` 只减剩余；加零不创建附件；处理溢出 | 基本可复用，补边界验证 |
| 初始化 | `seed` 按配置基数初始化未记账实体，玩家登录时调用 | 需要增加新世和恢复初始化 |
| 结算 | 实体 tick 后按世界 `gameTime % settleInterval` 结算；只处理已有附件的活体；创造、旁观玩家跳过 | 可复用规则，换世操作应延后执行 |
| 提醒与显示 | 跨过剩余比例阈值时动作栏提示；用配置除数把 tick 显示成岁 | 可复用，移出 MiXianTu 翻译键和配置 |
| 寿尽 | `Pre` 可取消；取消后若续命为正则继续，否则关闭账本；未取消时走 `NONE` / `DEATH` / `REINCARNATE`，再发 `Post` | 状态规则可借鉴，后果必须接本模组生命周期 |

账本目前区分三种状态：`(-1, -1)` 表示从未记账；非负剩余表示有限寿元；`(-1, 0)` 表示本次寿尽已处理、不再倒计时。`total` 是本世累计获授额度，不是每周期恢复的资源上限。寿元不应直接等同于 Saturn 的普通资源。

当前寿元配置共七项：

| 配置 | 源码默认值 | 含义 |
| --- | --- | --- |
| `enabled` | `false` | 是否运行寿元结算 |
| `base_lifespan` | `60` | 初始寿元，**单位是 tick** |
| `settle_interval` | `20` | 结算间隔 |
| `age_per_settle` | `20` | 每次扣除量 |
| `on_expire` | `DEATH` | 寿尽后果 |
| `warning_fraction` | `0.1` | 剩余比例提醒阈值 |
| `ticks_per_year` | `24000` | 显示换算除数 |

基数 `60` 并不是 60 岁；按默认速度只有约三秒寿元。迁移时需要重新确定合适的默认值，不能仅按配置名字理解单位。时间基准是在线游戏 tick，不是墙上时钟，离线不会补扣；按全局周期采样也不等于每个实体精确累计了等长在线时段。

已核对本地 patched Minecraft sources：普通实体从 `ServerLevel.tickNonPassenger` 发出 tick 事件，乘客也从 `Entity.rideTick` 发出。因此当前版本不能因 bridge 的旧注释而推断“骑乘时不扣寿元”。

## 耦合清单

下表路径以 MiXianTu 的 `src/main/java/com/iafenvoy/mxt/` 为根。

| 位置 | 耦合内容 | 建议处理 |
| --- | --- | --- |
| `registry/MxtAttachments.java` | 注册 `mxt:lifespan`，配置序列化、同步、`copyOnDeath`，每实体 tick 消费 dirty 标记 | 新模组单独注册附件与同步；仅继承变更标记基类不会自动加入 Mxt 的同步列表 |
| `runtime/cultivation/LifeSpanService.java` 的私有 `reincarnate` | 访问六个 Mxt 附件：修炼、小阶段、渡劫、灵根体质、功法、魂属性；还操作 Saturn 资源并重算能力、被动属性 | 最高耦合；不搬进 Reincarnation，交给 Mxt 继承适配 |
| `runtime/cultivation/CultivationService.java:115` | 境界突破成功后求值 `RealmStage.lifespan`，调用 `LifeSpanService.add` | 保留境界字段和求值，只改成新 API 调用 |
| `data/action/builtin/entity/ModifyLifespanAction.java`、`registry/MxtEntityActions.java` | `mxt:modify_lifespan` 和 `mxt:reincarnate` 两个行为类型 | 核心可以注册新命名空间的类型；旧入口如需兼容，由 Mxt 转调 |
| `util/formula/LifespanVariable.java`、`registry/MxtFormulaVariables.java` | 暴露 `lifespan_remaining`、`lifespan_total` 两个公式名 | 转移提供者，保持公式名；不能两边同时注册相同公式名 |
| `screen/information/MxtPlayerView.java:126`、`MxtSavedPlayerView.java:37` | 在线读旧服务，离线用旧附件 Codec；提前格式化成文本行 | 改成 Reincarnation getter 读取数值，Mxt 分组引用对应 row |
| `command/server/LifespanCommand.java`、`command/ServerCommandManager.java` | `/mxt lifespan` 与 `/lifespan`；`get/set/add/reincarnate` | 核心指令迁入，旧别名按需要转调；换世后必须使用返回的新玩家 |
| `compat/kubejs/` 四个相关文件 | `MxtLifespan` 绑定、API 转调、寿尽和显式重生事件转发 | KubeJS 依赖可留在 Mxt，不必强加给 Reincarnation；旧事件类型需要桥接，不能只替换 import |
| `runtime/damage/DamageCalculationService.java`、伤害类型和标签 JSON | 非玩家寿尽通过 Mxt 伤害服务，`mxt:lifespan` 加入 `mxt:no_bonus` | 核心使用原版伤害接口和自己的伤害类型；Mxt 在自己的标签中适配，保留伤害事件和保护机制 |
| `runtime/alchemy/SpiritHerbGrowthService.java:238` | 灵草的单次补算上限读取 `lifespan.ticksPerYear` | 这是时间配置的额外耦合，建议拆成灵草自己的补算配置，避免改寿元显示影响灵草生长 |

Mxt 的转世配置还有八项：是否致死、重置修炼、清小阶段、取消渡劫、清资源、保留灵根体质、保留功法、保留魂属性。这些属于 Mxt 的业务策略，不是通用寿元核心的必要字段。

Saturn 公式解析器会报告重复公式名并保留先注册的提供者。因此即使两个 registry id 分别叫 `mxt:lifespan` 和 `reincarnation:lifespan`，同时声明同一个公式名仍会冲突。

## 与本模组现有生命周期的差异

### 寿尽后果不同

MiXianTu 的 `DEATH` 对玩家执行 `setGameMode(SPECTATOR)`，不会发死亡事件、不会掉落，也不会走原版重生。它不会触发 Reincarnation 的 `auto_reincarnate_on_death`。

MiXianTu 的 `REINCARNATE` 是对同一个 `LivingEntity` 原地修改附件。没有世代推进、前世归档、玩家实例替换和转生事务；它也支持非玩家活体。

Reincarnation 则针对在线 `ServerPlayer` 创建新玩家、推进世代、归档旧世并保存可恢复事务。寿尽转生应直接调用 `ReincarnationApi.reincarnate(player, request)`，原因使用可翻译 `Component`，不必先杀死玩家或等待死亡自动转生开关。

目前转生 API 会返回新对象。寿尽处理、脚本和指令不能在调用后继续把旧 player 或旧 lifespan 附件当作当前数据。现有 `LifeSpanEndEvent.Post` 的旧对象语义也不能机械照搬；耗尽结果需要区分旧世记录和新世玩家。

### 不在实体 tick 调用栈中立即替换玩家

建议在寿尽结算时排队，在服务端 tick 结束的固定位置执行完整换世。队列至少关联账号和预期世代，执行前重新取得在线玩家、核对世代与当前寿元，并按账号去重。不能在重登或其他转生后执行过期任务。

这属于拟增加的调度，不是现有服务已经提供的寿尽队列。还需要处理取消、提交前失败和已进入事务恢复三种结果，避免每个结算周期重复请求换世或重复发寿尽通知。

### 普通重生与转生分别继承

寿元附件可保留 `copyOnDeath`，让普通死亡继续消耗同一世寿元；它不应默认加入 `reincarnation:keep_on_rebirth`，换世后应获得新账本。当前转生保留标签还会检查附件自身的 `copyOnDeath`；开启清未知模组数据时，标签自动继承也会被关闭。

新的 `ServerPlayer` 不会自动带旧寿元，也不会因正常转生触发登录事件。只有 `PlayerLoggedInEvent` 初始化是不够的，应在新世保存前初始化，并覆盖恢复路径。

推荐使用已有 `ReincarnationHandler` 保存事务所需的初始寿元参数，在 `reset/recover` 中幂等写入。使用附件 Codec 编解码快照，不另建文件格式。`ReincarnationEvent.Reset` 也能执行保存前初始化，但必须同样保证恢复重复执行结果一致；`Post` 只用于通知，不承担持久数据初始化。

Mxt 可通过保留标签选择完整附件；更细的字段策略和派生能力重算由自己的 handler 负责。全新玩家本来就没有旧修为、渡劫等数据，无需把旧的“全复制后再逐项清理”带回来。

### 非玩家支持要明确边界

寿元读写和扣减可以继续支持 `LivingEntity`，但当前完整转生 API 只接受 `ServerPlayer`。非玩家的 `REINCARNATE` 需要单独定义策略，不能宣称迁移后自动获得玩家那套世代归档能力，也不宜静默换成原地洗修为。保留非玩家原地重置时，应作为 Mxt 扩展明确处理。

## 信息栏和存档兼容

寿元信息建议注册独立 `EntityValueGetter`：在线只读已有附件；`readSaved` 用 Codec 从调用方传入的附件存档解析；结果包含原始剩余和总额。信息 row 用注册表表达式显示，加入本模组 life 分组，Mxt cultivation 分组可引用同一 row，不再复制一份展示逻辑。

前世只读取归档寿元，不调用 `seed`、不推进倒计时、不查询当前在线玩家补值。历史归档已有玩家附件原文，因此通常不需要新建寿元历史文件。显示换算采用当前规则还是固化到快照，需在实现时明确。

完整抽离需要同时处理以下兼容边界：

1. **权威账本只有一个。** 同装两个模组时必须停用旧结算和旧附件写入，否则会出现两套寿元。只把新实现复制到 Reincarnation 是新增副本，不是完整抽离。
2. **旧附件 id。** 从 `mxt:lifespan` 改成新 id 不会自动迁移。在线旧存档和历史归档都需要明确读取策略；历史可保留旧数据并由 `readSaved` 解析。移除旧附件注册前，还需验证 NeoForge 对未知附件的加载处理，不能假定登录后仍能取到旧字段。
3. **旧内容入口。** `mxt:modify_lifespan`、`mxt:reincarnate`、`mxt:lifespan` 信息行及 KubeJS 名称如需继续支持，保留薄适配入口。仅保留 JSON 字段形状不能补回缺失的注册表条目或 Java 事件类型。
4. **依赖方向。** 完整抽离可由 Mxt 依赖 Reincarnation；若采用可选集成，未安装时的功能与独占提供者规则需要定义。Reincarnation 核心不反向依赖 Mxt。

抽查 MortalCultivation 内容还发现：部分丹药写的是 Saturn 资源 `mortal_cultivation:lifespan`，不是 `LifespanAttachment`。例如 `yishou_pill.json` 用 `mxt:add_resource`。搬迁寿元核心不会自动让这些丹药续寿，后续内容适配应另行处理；本次未修改该项目。

## 建议实现顺序与验证范围

1. 在 Reincarnation 实现独立附件、账本服务、配置、寿尽事件和新世初始化；账本规则保留通用活体支持。
2. 接入服务端寿尽队列与完整转生事务，明确玩家 `DEATH` 语义及非玩家后果。
3. 注册行为类型、公式变量、寿元 getter、row、group 引用和中英文翻译；提供管理指令。
4. 在 Mxt 接通突破增寿与继承 handler，处理旧入口、旧存档和灵草配置，停用旧结算。
5. 覆盖普通死亡、寿尽换世、取消、故障恢复、旧世查询及同装环境。

MiXianTu 现有 `LifespanProbes` 登记了 **13 个寿元探针**，覆盖账本、开关、结算、取消、非玩家死亡、修炼重置、突破与行为等；类注释明确不覆盖玩家专属行为。本次只阅读了这些测试，不能将其当作本次已通过的运行结果。

迁入后，核心账本断言可以改写到本项目 main source set 的 `gametest` 子包。必须另外覆盖：普通死亡保留寿元、转生获得新寿元且旧世数值不变、取消不推进世代、队列去重与过期请求、初始化参数在恢复中保持一致、`copyOnDeath` 与保留标签的关系、启用系统后无附件玩家的初始化、同装 Mxt 后只有一处扣减和一个公式提供者。玩家观战/创造、真实骑乘、警告与 GUI 显示也需要实际验证。

总体改动量：独立核心为中等规模；两个模组协同完成抽离与旧数据兼容为中等偏大规模。工作量主要来自生命周期语义和既有入口，而非两个寿元数值本身。
