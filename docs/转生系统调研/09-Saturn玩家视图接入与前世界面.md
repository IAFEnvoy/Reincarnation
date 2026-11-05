# Saturn 玩家视图接入与前世界面

Reincarnation 当前使用 Saturn `9449659`（1.2.5）与 ApricityUI `1.2.9`。前世界面使用注册表分组与扁平 `InformationEntry`，客户端自行使用 AUI 布局。早期三个项目的注册表接入说明见 [三个项目适配](13-Saturn注册表信息行与扁平结果适配.md)。本轮仅修改 Reincarnation。

## 1. 职责与归档

Saturn 仍只解析调用方提供的数据、解析 getter 与数据包行/分组定义，并提供文字投影与通用开屏机制。文件读取、事务、归档、校验、访问权限、数据发送与本模组页面布局均由 Reincarnation 负责。

新事务在推进世代和重建玩家前，将 `PlayerViewSnapshot.CODEC` 编码结果写入 `archive.dat` 的可选 `player_view` 键，与原始 `player`、`stats`、`advancements`、`scores`、`team`、`carried` 和摘要一起参与原有 SHA-256 校验。外层归档版本仍为 1；已有恢复流程忽略新增键，旧归档也无需重写。

实时解析通过 `PlayerViews.parse(player, meta)` 调用 getter 采集数据。宿主补入其明确持有的统计和进度文本，以及归档结束时的 `SoulRecord`。因此原始附件即使在新世被清除，已捕获的前世字段仍保留。没有 getter 或未被采集的第三方字段不承诺完整恢复。

记录引用使用 `PlayerViewRef(account, reincarnation:life, "generation/" + generation)`；内部世代仍从 0 开始。历史快照 ID 使用事务 UUID，当前快照每次捕获使用新 ID。同一账号的不同世代不会共用记录引用，也不按 UUID 借用当前客户端实体。

## 2. 生命周期扩展

固有类型在模组事件总线注册，行与分组全部由数据包加载：

- `reincarnation:life` getter，schema 1，值仍为现有 `SoulRecord`，使用其版本化 Codec。
- `reincarnation:life_source` getter，将宿主持有的快照来源类型交给表达式；旧快照缺少该结果时从自身 metadata 投影。
- `reincarnation:life` expression，`value` 为枚举参数，负责世数、UTC 时间、原因等业务展示，不读取实体或文件。
- `data/reincarnation/saturn/information_row/` 中的 15 条展示定义，以及 `information_group/life.json` 和 `#reincarnation:life` 行标签。

分组 `reincarnation:life` 引用行标签。行的 label、order、可见条件与缺失策略由 JSON 声明，所有行通过同一条收集路径参与 `collectAll` 和按组查询。缺少 SoulRecord 时隐藏世代行；只有摘要视图显示摘要位置。可见世数用 `LifeNumbers.display` 从 1 开始，保留 long 全范围精度。已有中英文翻译键继续使用。

`ReincarnationViews` 负责宿主快照装配，不注册展示行。分组由 `ReincarnationViews.group(registries)` 取得当前注册表 Holder，结果直接用 `entry.value()/label()/tooltip()`。归档写入结束时的 SoulRecord 和正确的 ARCHIVE 来源，避免使用现场采集的当前世状态。

## 3. 查询与旧档

可信服务端调用入口：

```java
Optional<PlayerViewSnapshot> view = ReincarnationApi.view(account, generation);
```

`generation` 为从 0 开始的存储 ID；必须在服务端主线程调用，并由调用方先检查查看者权限。不存在返回空；当前世正在恢复、归档损坏或校验失败则报告错误。

| 输入 | 处理 |
| --- | --- |
| 当前在线玩家 | 从当前实体捕获只读快照，不写玩家存档或更新历史记录 |
| 当前离线玩家 | 使用最近保存的 `SoulRecord`，明确标为 `SUMMARY` |
| 已有 `player_view` 的前世 | 校验事务状态、账号、世代、摘要一致性和归档哈希；解码快照，优先保留已捕获字段 |
| 没有快照的旧完整归档 | 将保存的 Player NBT、统计/进度文本传入 Saturn；宿主补充姓名、生命周期、分数、队伍和鼠标物品 |
| 只有摘要的记录 | 展示摘要能确认的数据；健康、背包明细等未记录字段保持缺失 |

旧档只补完全没有记录的字段，不用当前规则覆盖已有捕获值或明确的缺失状态。保存的属性基值不能证明当时有效最大生命；摘要只有等级/总经验时，不编造经验进度构造完整经验字段。单个旧字段损坏标为 `INVALID`，不让其他有效字段一起消失。缺一半事务文件、哈希不匹配或引用不一致属于归档错误，不回退为伪造的完整视图。

整个查看过程不构造 `ServerPlayer`、不调用玩家加载/登录恢复、不回写旧归档。旧档读取器按 Saturn 支持的 Minecraft 当前 NBT 结构解析；本次没有增加跨 Minecraft 版本的 DataFixer 迁移。

## 4. 玩家入口与传输

```text
/reincarnation view
/reincarnation view <玩家名或UUID>
/reincarnation view <玩家名或UUID> <世数，从1开始>
/reincarnation view <玩家名或UUID> <世数，从1开始> <列表页码，从1开始>
```

前三条分别打开自己的当前信息页、指定账号当前世、指定世，并自动定位所选世代在左侧的页码。第四条单独控制列表页码，右侧仍显示指定世代。必须由游戏内玩家执行；普通玩家仅能查看本人，查他人沿用管理员权限。原来的 `status/history/info` 文字入口保留，`info` 中的可点击提示打开对应世代。

服务端在鉴权后加载快照，使用 `PlayerInformation.collectAll` 组装 `InformationDocument`，再附加账号、姓名、所选内部世代 ID、列表页码/总页数与当前页摘要，构成 `LifeViewOpenScreenData`。摘要仅包含内部世代 ID、结束时间和可翻译原因，不发送其他世代的完整信息文档。当前世与历史记录统一按内部世代 ID 降序排列，每页最多 32 条，不受文字历史查询的配置影响。

转生原因从请求到 `SoulRecord`、Saturn 快照、信息行和世代摘要均为 `Component`，持久化使用 `ComponentSerialization.CODEC`，展示时直接使用保存的组件；不再分别存储或拼接原因原文与翻译键。旧版原因字段在读取时兼容转换，翻译参数和文本样式会继续传递到客户端。

数据通过 Saturn 的 `saturn:open_screen_s2c` 发送，`reincarnation:life_view` 是 `SaturnRegistries.OPEN_SCREEN_DATA` 中的类型 ID。客户端只接收展示文档与摘要，不接收原始归档、快照字段封套或整份附件。展示行仍可能包含背包、坐标和扩展业务内容，因此不能省略宿主鉴权。

实际编码后的展示内容上限为 1,000,000 字节。服务端发送前预编码检查，超过上限返回翻译错误，不静默截断；StreamCodec 同时使用原版 `registryFriendlyLengthPrefixed` 限制编码与解码。结果 Codec 通过 row/groups 的注册表 ID 引用已同步的定义，编解码必须使用当前 RegistryOps。

公共开屏类型通过 `ReincarnationOpenScreenData` 注册，不单独增加网络消息。客户端在 `FMLClientSetupEvent.enqueueWork` 中向 `ClientScreenDispatcher` 注册页面构造器，开屏与刷新由 Saturn 在主线程分派。公共传输类不引用 Minecraft 客户端或 Screen。AUI 仅为客户端必需依赖，专用服务器不加载页面类。

## 5. AUI 页面

`LifeInformationScreen` 继承 `ApricityScreen`，资源位于 `assets/apricityui/apricity/reincarnation/information/`，与 MiXianTu 一样使用 GUI 缩放坐标、动态行和可见区域复用，避免在 AUI 内部依赖列表裁切。布局为左侧世代列表、右侧分组详情，无玩家模型。

页面直接引用 AUI 1.2.6 内置的 `/apricityui/theme/ore/ore.css`，在 `body` 上启用 `ore-theme`，不复制主题资源。面板、按钮、列表交互和滚动条复用 Ore 组件类，文本状态颜色使用主题变量。页面 CSS 仅补充布局、选中项强调及 GUI 尺寸适配：正文沿用 Minecraft 的 9px 字体以匹配 Java 换行测量，顶栏与分页按钮保持 20px 高；覆盖主题的最小尺寸和按下时 padding，避免撑开布局。面板与列表取消占用空间的边框，保留主题背景和内凹阴影，以保持绝对定位与鼠标命中坐标一致。Java 只设置语义样式类，Component 显式颜色和格式继续优先保留。

- 左侧显示当前世和历史世，点击切换，支持上下页、滚轮和拖动滚动条；打开时定位到所选项。状态与结束原因直接显示在列表内，不再弹出 Tooltip。
- 右侧使用 `InformationDocument.groups()` 和 `rows(group)` 的排序。点击 Group 标题展开/折叠，提供全部展开/折叠按钮，默认展开 `reincarnation:life` 和 `saturn:basic`。未分组行放入“未分组”，多组行在各所属组内显示。
- 值统一通过 `InformationText.lines` 转文字，支持全部 `InformationValue` 类型。每组按实际标签宽度计算两列，标签与值较长时都直接换行，页面不生成重复内容的 Tooltip，也不显示行定义的 Tooltip。Component 的颜色、粗体、斜体、下划线与删除线按样式段转为 AUI span。
- 开始、结束和数据记录时间在客户端显示时转换为查看者系统当前时区，格式为 `yyyy-MM-dd HH:mm:ss`，标签不再标注 UTC。识别依据为行的 `LifeExpression` 时间语义，不绑定行 ID；服务端仍传递精确的 ISO Instant，避免远程服务器时区影响显示。缺失时间沿用缺失状态，不识别的自定义文本原样保留。
- 标题、玩家名与 UUID、全部展开/折叠和关闭按钮集中在最上方同一行，窄窗口优先展示玩家信息。下方直接显示两侧分类标题和列表，移除底部操作说明；分页仅占左侧底部，右侧详情延伸至面板底部内边距。加载提示临时替换右侧所选世代标题。按钮、页码与文字行使用明确的行高和垂直居中，左右列表加入内边距。世代列表的原因使用较淡颜色，分组标题用旋转箭头表示开合状态。
- 左右列表独立滚动。滚轮保留小数增量，并以 140ms 的 cubic ease-out 缓动到目标像素位置；拖动滚动条立即更新位置。分组用 200ms 高度与箭头旋转动画，内容逐步显现，支持动画途中反向点击。点击分组时保持该标题的位置，列表变短后限制滚动范围，避免滚动条越界或回弹。动画按实际帧间时间推进，与帧率无关。
- 行背景和文字根据当前可见范围手动裁定，列表边缘的文字淡出，点击命中使用同一份动画后位置。仅为可见内容复用 AUI 元素，不给复用行添加位置 CSS transition，也不依赖 overflow 遮罩。
- 同账号的页面更新复用页面状态，切换世代保留折叠状态与左侧滚动位置，并将右侧滚动复位；翻页仅重定位左侧。窗口变化或资源重载后重新绑定文档，保留滚动与折叠状态。

点击世代或翻页发送现有 `reincarnation view <UUID> <世数> <页码>` 指令，每次均重新检查服务端权限、归档状态与编码大小。等待响应时暂时阻止重复请求；失败通过聊天返回错误，页面保留当前信息，5 秒后可重试。没有独立的客户端请求消息、后台轮询或共享查询缓存，页面也不借用当前玩家实体补齐前世数据。

## 6. 验证与后续边界

验证命令：

```powershell
.\gradlew.bat build runGameTestServer --offline --console=plain
```

玩家视图测试覆盖新归档快照、重置后前世健康和背包、今世/前世引用隔离、查看不改归档、哈希篡改拒绝、旧 NBT 读取、捕获值优先、损坏字段隔离、摘要缺失值、按分组/全取和第一世显示。`player_views_browser`、`player_views_screen_transport` 验证 38 条世代摘要的排序和分页、自动定位、显式翻页保持详情、越界拒绝、GUI 所有权鉴权、Saturn 开屏消息实际发送和分派 Codec 往返、样式与嵌套值保留、long 最大世代精度，以及载荷大小检查和解码限制。

基础接入的 `build` 与 24 项必需 GameTest 全部通过，日志位于 `build/aui-information-verification.log`。本次布局和动画调整通过 `build`（`build/aui-information-polish.log`），并独立验证缓动的中间位置、30/60/144/240 FPS 下的终点、同时间不同帧数的一致性、途中反向开合、标题锚定偏移、内容缩短时的范围约束与无回弹，以及拖动时取消缓动。中英文翻译键一致且无缺失，AUI HTML/CSS 和动画类已进入 `build/libs/reincarnation-1.0.0.jar`。

Ore 主题接入通过离线 `build`，日志为 `build/aui-information-ore-theme.log`；已核对主题链接可解析至 AUI 1.2.6 的资源、组件类与颜色变量存在、页面 DOM 与翻译键完整、打包后的 HTML/CSS 与源码一致。主题由 AUI 提供，不重复打包；本次未重跑专用服务器 GameTest。

动画逻辑验证不能代替 AUI 实际渲染与鼠标交互验收，调整后的页面仍需游戏内确认。人物三维预览不在本次需求内，当前也没有网络自动刷新。
