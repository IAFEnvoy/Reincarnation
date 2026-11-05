# Saturn 注册表信息行与扁平结果适配

Reincarnation、MiXianTu、System 统一依赖 `curse.maven:saturn-lib-1767455:9406442`，对应 Saturn 1.2.2。三个模组的最低版本声明均为 `[1.2.2,)`。System 保留 `gradle.properties` 的 `saturn_file_id` 配置方式。

## 共享模型

`InformationGroup` 与 `InformationRow` 各有一份注册表定义。前端通过当前世界的 `Holder<InformationGroup>` 选择组，使用 `group.value().title()` 读取标题。组成员仍通过 `Either<Holder<InformationRow>, TagKey<InformationRow>>` 声明；所有组与行由数据包加载。

`InformationEntry` 保存 Row Holder、Group Holder 集合与求值后的 label/value/tooltip；直接用 `entry.value()` 等访问。order、importance、tone 和来源从行定义读取，不再复制成 Result/Content 嵌套模型。文档与网络 Codec 需要包含当前行/组注册表的 RegistryOps。展示结果不用于长期归档，归档仍保存原始数据与玩家事实快照。

## Reincarnation

`ReincarnationViews` 只装配快照与取得当前分组，不再调用 Java 行/组注册。15 条世代行位于 `data/reincarnation/saturn/information_row/`；分组 `reincarnation:life` 引用 `#reincarnation:life`，可通过标签追加展示行。

`reincarnation:life` 表达式的必填 `value` 支持 source、generation、state、started、ended、observed、reason、level、total_experience、inventory_items、advancements、deaths、played_seconds、transaction、summary_position。行可见性、标题、显示顺序和缺失策略由 JSON 控制。表达式只读取传入快照；编号按整数精确转换，UTC 时间、翻译原因和摘要坐标沿用原行为。

`LifeGetter` 的 ID、schema 1 和 SoulRecord 载荷保持。新增 `life_source` 供表达式读取宿主确定的来源；结束时写 ARCHIVE，摘要写 SUMMARY。旧快照没有该查询时从自己的 metadata 取得来源，不使用当前玩家状态，也不重写归档。

前世查看仍由宿主鉴权，再传送已求值文档。现有载荷使用注册表感知 Codec，网络只发送条目值和定义引用。

## MiXianTu

修炼行继续使用既有 7 条数据定义和 `#mxt:cultivation`。人物面板和探针从当前世界取得组 Holder，直接读取 InformationEntry。快照行的 Tone 类型改为 `InformationRow.Tone`，枚举名称和序列化文本不变，已捕获修炼值、tooltip 与样式格式保持。

基本信息页保留 `saturn:basic`，修炼页保留 `mxt:cultivation`。中英文接入文档同步使用 `/information query/gui` 管理员指令。探针已迁移新 API，编译和资源处理不代表实际运行。

## System

玩家查询和卡片按 ResourceKey 从当前世界解析 basic/status Holder，使用同一份扁平文档。原有 11 条白名单、最多 32 行、每行 256 字和故障隔离保持。卡片模板与 Agent 输出结构不变；没有新增采集器或持久化层。

依赖版本表、最低版本和卡片文档同步更新。验证仅编译与打包，不启动 System 或 MiXianTu 游戏进程。

## 验证结果

- 三个项目的 compileJava、资源处理与 build 通过；MiXianTu 的 compileTestModJava、System 的 testModClasses 通过。
- Reincarnation 的 23 项必需 GameTest 通过，覆盖 15 条 JSON 世代行、归档来源、摘要专属位置、缺少 life_source 的旧快照回退、Long.MAX_VALUE 编号精度与真实文档网络往返。
- 15 条世代行的标签均在中英文语言文件中存在，发布 JAR 包含全部定义；三个当前产物均声明 Saturn 至少 1.2.2。
- MiXianTu 文档的 218 对中英文页面无缺口，站内链接检查和文档构建通过，已确认中英文产物包含新 API 说明。
- MiXianTu/System 未启动游戏，人物界面与卡片未作视觉验收；探针编译通过不代表运行通过。

日志：各项目 `build/saturn-9406442-update-check.log`；Reincarnation 运行测试为 `build/saturn-9406442-gametest.log`。
