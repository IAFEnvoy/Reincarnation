# 转生系统：Minecraft 26.1.2 源码调研与设计方案

> 本次复核：**2026-11-01，Minecraft 26.1.2 + NeoForge 26.1.2.99 + Java 25**。`build.gradle` 从 `gradle.properties` 读取 `neo_version=26.1.2.99`。
> 当前源码：`build/moddev/artifacts/minecraft-patched-26.1.2.99-sources.jar` 与 Gradle 缓存中的 `neoforge-26.1.2.99-sources.jar`；源码复核解包仍位于 `E:\Java\MortalCultivation\.gradle\reincarnation-source-review\`。文档现已迁入 `E:\Java\Reincarnation\docs`，兼容 Codec 验证在 Reincarnation 工程执行。
> 原报告基于 `.114`，原有行号保留为历史定位；新增证据以 `.99` 为准。**2026-11-02 实现更新**：通用引用基础层、持久化转生事务、玩家重建/重置、恢复、关系清理、处理器 API 和文字历史查询已接入独立 Reincarnation 工程。实际行为以 [07-转生流程与历史查询实现](转生系统调研/07-转生流程与历史查询实现.md) 为准；[06](转生系统调研/06-通用引用桥接实现与验证.md) 保留第一阶段验证记录。本文的界面、具体修仙模组和任意第三方存储条目仍是扩展/集成设计。

**本次设计决策**：通过 Mixin 将绑定世代保存在 `EntityReference` 中，玩家 UUID 保持稳定。持久化推荐采用**引用 Codec 桥接 + NBT 节点临时标记 + 完整载荷统一元数据表**：原版 UUID 字段、列表和 Map 形状不变，不逐个实体或引用字段登记兼容。旧世引用在解析和匹配时失效，未加载实体无需先遍历。当前主线见 §2.8 及 [05-通用EntityReference持久化桥接](转生系统调研/05-通用EntityReference持久化桥接.md)；§2.7.2 的逐字段旁存与结构模式保留为候选研究。

---

## 0. 结论摘要（先看这个）

| 方案 | 判定 | 一句话理由 |
|---|---|---|
| **方案 1：每次转生随机 UUID** | ❌ **不可作为主干** | UUID 在 26.1.2 里不是"可替换的玩家标识"，而是从 GameProfile 每次登录重新推导出来的；换实体 UUID 不但不会清数据，反而会在下次登录时把**旧世数据读回来**；换 profile UUID 则会让封禁/白名单/OP 变成"按世有效"。 |
| **方案 2：导出数据 + 清空原玩家** | ⚠️ **方向对，但你描述的实现不成立** | "手工清空一个活着的 `ServerPlayer`"会漏掉大量 `private final` 字段，而且 `stats` / `advancements` / `recipeBook` 是 final 且由 `PlayerList` 的 **private Map** 提供，没有 AT/Mixin 根本重置不了。 |
| **方案 3（推荐）：稳定 UUID + 世代 + 扩展 `EntityReference` + 引擎重建 + 归档** | ✅ **推荐** | `EntityReference` 保存绑定世代，通过 NBT 基础层统一持久化，解析与 `matches` 时校验。转生推进当前世代，使采用该引用的旧关系失效；玩家重建、裸 UUID 数据与玩法附属状态另按策略处理。 |

账号 UUID 表示“谁”，`generation` 表示“哪一世”。`EntityReference` 可以承担统一的世代检查，但不负责清空背包、统计或所有裸 UUID 数据。通用算法原型通过 21 项检查，实际 Mixin 首轮通过 7 项专服 GameTest；客户端、搬运与任意第三方路径不能据此宣称全部覆盖。

---

## 1. 方案 1 为什么不成立（逐条源码证据）

### 1.1 UUID 不是运行时字段，而是每次登录从 GameProfile 推导出来的

```java
// Player.java:180-183  构造时
public Player(Level level, GameProfile gameProfile) {
    super(EntityType.PLAYER, level);
    this.setUUID(gameProfile.id());
    this.gameProfile = gameProfile;
```

```java
// Player.java:646-649  每次读档时【无条件回写】
protected void readAdditionalSaveData(ValueInput input) {
    super.readAdditionalSaveData(input);
    this.setUUID(this.gameProfile.id());   // ← 存档里的 UUID 被丢弃
```

也就是说：**即使你把 UUID 写进了 `.dat`，下次读档也会被 profile UUID 覆盖回去**。而且 `Player.gameProfile` 是 `private final`（`Player.java:168`），authlib 的 `GameProfile` 本身是不可变 record，**没有任何 setter**。

登录链路上 UUID 的确定时机：

```
ServerLoginPacketListenerImpl.handleHello          (:118-133)  在线模式发 ClientboundHelloPacket
  → handleKey → sessionService().hasJoinedServer() (:199-207)  ← 权威 UUID 在这里确定
  → startClientVerification(profile)               (:135-138)  authenticatedProfile
  → handleLoginAcknowledgement                     (:243-251)
       CommonListenerCookie.createInitial(profile)  (:246)      ← 不可变 record
  → ServerConfigurationPacketListenerImpl.returnToWorld
       new PrepareSpawnTask(server, new NameAndId(gameProfile))
  → PrepareSpawnTask.spawn                         (:178-201)
       new ServerPlayer(server, spawnLevel, cookie.gameProfile(), clientInformation)  (:181)
       playerList.loadPlayerData(this.nameAndId)   (:187)      ← 按 profile UUID 找 .dat
       input.ifPresent(player::load)               (:189)      ← 触发上面的 :649 回写
       playerList.placeNewPlayer(connection, player, cookie)  (:192)
```

**关键结论：这条链路上没有任何 NeoForge 事件可以插入。** `ServerLoginPacketListenerImpl` 整个文件里没有一处 `net.neoforged` 引用；`PlayerEvent` 的子类清单（`_nfsrc/.../event/entity/player/PlayerEvent.java`）里没有登录/身份类事件；唯一两个沾边的 `PlayerEvent.LoadFromFile` / `SaveToFile` 把 UUID 作为**只读** `final String` 暴露，且在 `PrepareSpawnTask.java:190` 触发——**已经在上面的 `:189` 回写之后了**。

想换 UUID，只能 Mixin 掉 `ServerLoginPacketListenerImpl` / `CommonListenerCookie` / `PrepareSpawnTask` 之一，或者包一层假的 `MinecraftSessionService`。这是"改引擎"，不是"用 API"。

### 1.2 只换实体 UUID → 下次登录旧世数据会"复活"（最致命的坑）

注意存档的**写入路径和读取路径用的不是同一个身份**：

```java
// PlayerDataStorage.java:38-43  写：用【实体 UUID】
Path tmpFile  = Files.createTempFile(playerDirPath, player.getStringUUID() + "-", ".dat");
Path realFile = playerDirPath.resolve(player.getStringUUID() + ".dat");
```

```java
// PlayerDataStorage.java:64-65  读：用【profile UUID】
private Optional<CompoundTag> load(NameAndId nameAndId, String suffix) {
    File realFile = new File(this.playerDir, nameAndId.id() + suffix);
```

于是 `setUUID(X)`（X ≠ profile P）之后：

1. 本次会话的存档被写到 **`players/data/X.dat`**；
2. **`players/data/P.dat`（转生前的老数据）完全没有被动过**；
3. 下次登录，身份重新推导为 P → 加载 `P.dat` → **玩家回到转生前**，"转生"被撤销；
4. 每一世都会新增一个 `X1.dat`、`X2.dat`… 永久堆积，谁也读不回来。

而且同一个会话里两个统计系统的键就已经不一致了：

```java
// PlayerList.java:773-778   stats        按 profile.id()
public ServerStatsCounter getPlayerStats(Player player) {
    GameProfile gameProfile = player.getGameProfile();
    return this.stats.computeIfAbsent(gameProfile.id(), id -> { ... });
}
// PlayerList.java:804-805   advancements 按实体 UUID
public PlayerAdvancements getPlayerAdvancements(ServerPlayer player) {
    UUID uuid = player.getUUID();
```

也就是说：**方案 1 的"换实体 UUID"版本，连"清空"这个最基本的目标都达不到**，它只是把新数据丢进一个没人会读的文件里。

（顺带一提，`stats` 还有一条**按玩家名**的旧文件回退逻辑：`PlayerList.locateStatsFile` (`:781-802`) 在 `<uuid>.json` 不存在时会去找 `<name>.json` 并改名——这说明"名字"在这套系统里也是身份的一部分，见 §1.4。）

### 1.3 换 profile UUID → 权限系统身份分裂（封禁可被"转生"绕过）

26.1.2 里 OP / 白名单 / 封禁**全部以 UUID 为键**：

```java
// ServerOpList.java:64-66
protected String getKeyForUser(NameAndId user) { return user.id().toString(); }
// UserWhiteList.java:59-61  同样
// UserBanList.java:27-29    同样
```

而登录检查发生在**换 UUID 之前的那个 profile** 上：

```java
// ServerConfigurationPacketListenerImpl.java:215（子代理核实）
Component loginError = playerList.canPlayerLogin(this.connection.getRemoteAddress(), new NameAndId(this.gameProfile));
```

两种插入点的后果分别是：

- **在 cookie 之前替换 profile** → `canPlayerLogin` 用的是新 UUID → **封禁/白名单变成"按世有效"**。被 ban 的玩家一转生就自动解封（在服务器管理上这是严重漏洞）。
- **在 spawn 时替换 profile**（保住封禁）→ 但运行时的 `player.nameAndId()`（`Player.java:1338-1340`）用的是新 UUID，于是 `MinecraftServer.getProfilePermissions`（`:1866-1881`）→ `PlayerList.isOp`（`:601-605`）查不到 → **在线 OP 掉了**，并且管理员这一世用 `/op` 授予的权限会写进新 UUID，下一世又丢。

无论哪种，你都还得再 Mixin 一遍权限解析。另外 `usercache.json`（`CachedUserNameToIdResolver`，`profilesByName` / `profilesByUUID` 两张表）也会随之错位。

### 1.4 就算 UUID 换成功了，也清不掉"按名字"存的状态

```java
// Player.java:1761-1762
public String getScoreboardName() {
    return this.getGameProfile().name();     // ← 注意：不是 UUID
}
```

`Entity.getScoreboardName()` 返回 `stringUUID`（`Entity.java:3197-3200`），但 `Player` 覆盖成了**玩家名**。因此换 UUID **不会**清掉：

- 计分板分数与目标（`Scoreboard.resetAllPlayerScores` / `getPlayersTeam` 都是按 `ScoreHolder`，玩家即"名字"）
- 队伍成员（`PlayerTeam` 按名字）
- 地图上的玩家标记（`MapItemSavedData.decorations` 是 `Map<String, MapDecoration>`，用 `getPlainTextName()` 做键，见 `MapItemSavedData.java:75,168-185`；不过它**不在存档 CODEC 里**，只存内存，重登即消失，所以影响较小）
- 成书作者（`WrittenBookContent.author`，`WrittenBookContent.java:34`）
- 玩家头颅（`SkullBlockEntity.owner` 是 `ResolvableProfile` = 名字 + UUID，`SkullBlockEntity.java:25`）
- 上面 §1.2 提到的按名字回退的 stats 文件

所以"MC 里大部分玩家的引用都是 UUID 的，直接替换可以快速实现所有数据的无效化"这个前提**不成立**——恰恰相反，一部分状态按名字、一部分按 UUID、还有一部分按实体 id / 对象引用，换 UUID 只能清掉中间那一类。

### 1.5 原地 `setUUID` 会撕裂实体索引，产生"永远删不掉的幻影"

```java
// EntityLookup.java:17
private final Map<UUID, T> byUuid = Maps.newHashMap();

// EntityLookup.java:42-45
public void remove(T entity) {
    this.byUuid.remove(entity.getUUID());   // ← 用的是【当前】UUID
    this.byId.remove(entity.getId());
}
```

如果实体注册之后再改 UUID，`remove(entity)` 会去删**新** UUID（不存在），**旧 UUID 的键被永久滞留**：`getEntity(oldUuid)` 依然返回这个活着的实体，`count()` 虚高，之后任何真拿到旧 UUID 的实体 `add` 时只会打印 `"Duplicate entity UUID {}: {}"`（`:34-35`）并被**静默丢弃**（`add` 的 else 分支）。

```java
// PersistentEntitySectionManager.java:35
private final Set<UUID> knownUuids = Sets.newHashSet();
// :62-64
private boolean addEntityUuid(T entity) {
    if (!this.knownUuids.add(entity.getUUID())) {
        LOGGER.warn("UUID of added entity already exists: {}", entity);
```

`knownUuids` 同理只在 `Callback.onRemove` 里按**当时的** UUID 移除（`:476`），旧 UUID 永久残留，导致 `isLoaded(oldUuid)`（`:308-310`）永远为真，`ServerLevel.tryAddFreshEntityWithPassengers` 的去重判断（`ServerLevel.java:1028`）失效。

玩家侧更糟：

```java
// PlayerList.java:338-345
UUID uuid = player.getUUID();
ServerPlayer serverPlayer = this.playersByUUID.get(uuid);
if (serverPlayer == player) {           // ← 改过 UUID 后这里取不到，整块跳过
    this.playersByUUID.remove(uuid);
    this.stats.remove(uuid);
    this.advancements.remove(uuid);
    ...
}
```

**`playersByUUID` / `stats` / `advancements` 三张表在任何登出路径上都不会被清理。**

（补充：好消息是**区块加载与实体同步跟踪不受影响**——`ChunkMap.entityMap` 按 int 实体 id、`PlayerMap` 按对象引用、`DistanceManager` / `TicketStorage` 按 chunk long 键。所以换 UUID 不会把玩家的区块加载搞坏，这一点不构成反对理由。）

### 1.6 一堆"按 UUID 记账"的世界状态会残留或错乱

同一份源码里以 UUID 为键的长期结构（节选，均已核实）：

| 结构 | 位置 | 换 UUID 的后果 |
|---|---|---|
| `GossipContainer.gossips` | `GossipContainer.java:33` | 村民好感/流言残留（同名同 UUID 时也会残留） |
| `Raid.heroesOfTheVillage` | `Raid.java:108` | "村庄英雄"残留 |
| `CustomBossEvent.players` | `CustomBossEvent.java:23` | 自定义 Boss 栏残留 |
| `AngerManagement`（监守者愤怒） | `world/entity/monster/warden/AngerManagement.java` | 愤怒记忆错位 |
| `CreakingHeartBlockEntity.creakingInfo` | `CreakingHeartBlockEntity.java:59` | 嘎枝绑定错位 |
| Vault / TrialSpawner 的已领奖集合 | `world/level/block/entity/...`（Vault 系列） | **可重复领奖**（换 UUID 相当于"新人"，本来是漏洞，但语义上不是你要的） |
| `TrackingDebugSynchronizer.entitySources` | `util/debug/TrackingDebugSynchronizer.java:144` | 旧 UUID 键在轮询时 `Objects.requireNonNull(level.getEntity(key))` → **潜在 NPE**（仅在激活调试订阅时） |
| NeoForge 实体强制加载票据 | `TicketStorage.entityForcedChunks` / `ForcedChunkManager`（`_nfsrc`），`Map<UUID, TicketSet>` | 票据删不掉，区块被永久强制加载 |
| 聊天签名链 | `ServerGamePacketListenerImpl.java:285,2190` | 会话中途改 UUID，签名链可能失效 |

### 1.7 客户端：会话中途换 UUID 永远无法收敛

这是**独立于服务端**的一条硬伤：

- 客户端本地身份来自服务端的登录包：`ClientHandshakePacketListenerImpl.java:174-176` `GameProfile localGameProfile = packet.gameProfile();`，一路经 `CommonListenerCookie` 带到 `ClientPacketListener.localGameProfile`（`:390,432-434`），最终 `LocalPlayer` 用它构造（`LocalPlayer.java:172`）。
- **`ClientboundRespawnPacket` 里根本没有 UUID / profile**（`record ClientboundRespawnPacket(CommonPlayerSpawnInfo, byte)`）。
- 重生时客户端是用那个**陈旧的 cookie** 重建 `LocalPlayer` 的：`ClientPacketListener.java:1290-1301`，只 `setId(oldPlayer.getId())`。
- 客户端**永远收不到自己实体的 `ClientboundAddEntityPacket`**（`ChunkMap.updatePlayer` 里 `if (player != this.entity)`），所以没有任何包能刷新它自己的 UUID。

结论：**服务端中途换 UUID，客户端 `LocalPlayer` 的 UUID 会一直停在旧值，直到重连**。后果（子代理逐条核实过，均为"状态降级"而非崩溃）：

- `AbstractClientPlayer.getPlayerInfo()`（`:38-44`）按 `this.getUUID()` 查 `playerInfoMap` → 查不到 → `getSkin()` 回退到 `DefaultPlayerSkin.get(uuid)`（`:61-65`），而 `DefaultPlayerSkin:43-49` 是用 `uuid.hashCode()` 取模选皮肤的 → **自己的皮肤/手臂模型会变成默认 Steve/Alex，甚至可能粗细手臂互换**。
- `ClientPacketListener.java:2039` 的 `UPDATE_GAME_MODE` 判断 `minecraft.player.getUUID().equals(entry.profileId())` 不再成立 → 本地游戏模式回调被跳过。
- `Minecraft.java:2400-2406` 的 `isBlocked` 不再豁免自己 → **可能把自己的聊天屏蔽掉**；`ChatListener.java:243-250` 在集成服务器下不再把自己的话判为 SECURE。
- 标签页头像翻转、`PlayerSocialManager.discoveredNamesToUUID` 的 `<@名字>` 高亮等都会指向旧 UUID。

要修复，只能**重连**（重新走一次 `ClientboundLoginFinishedPacket`）。

### 1.8 运维代价

- 必须 Mixin 进登录链路 + 可能还要 Mixin 权限解析 → 版本更新时维护成本高。
- 需要自己持久化 `玩家名 → 当前世 UUID` 的映射；这份映射丢失/损坏 = **玩家当前世数据永久不可达**。
- **卸载 mod = 所有玩家数据变成随机 UUID 的孤儿文件**（对玩家是灾难）。
- 每世遗留一套 `.dat` / stats / advancements 文件，磁盘无限增长，且与"彻底清理"的诉求完全相反。
- 跨服/多服身份、`usercache.json`、Realms/社交等仍以 Mojang UUID 为准，会出现"同一个账号在不同系统里是两个人"。

---

## 2. 方案 2 的正确实现方式

方案 2 的方向是对的：**保持身份稳定，把"数据"当数据来处理。** 但"把一个活着的 `ServerPlayer` 逐字段清空"这种做法在 MC 里是行不通的，原因有二：

1. **`stats` / `advancements` / `recipeBook` 是 `ServerPlayer` 的 `private final` 字段**，而且在构造时就从 `PlayerList` 的 **private Map** 里取：

```java
// ServerPlayer.java:394-402
public ServerPlayer(MinecraftServer server, ServerLevel level, GameProfile gameProfile, ClientInformation clientInformation) {
    ...
    this.recipeBook = new ServerRecipeBook(...);
    this.stats = server.getPlayerList().getPlayerStats(this);          // Map<UUID, ServerStatsCounter>
    this.advancements = server.getPlayerList().getPlayerAdvancements(this);  // Map<UUID, PlayerAdvancements>
```

`PlayerList.stats` / `PlayerList.advancements` 是 `private final Map`（`PlayerList.java:122-123`），**没有公开访问器**。想换掉这两个对象，要么 AT/Mixin，要么走 `PlayerList.remove()`——那是唯一会清理它们的公开入口（`:342-343`）。而 `ServerStatsCounter.stats` 是 `StatsCounter` 的 `protected` 字段（`StatsCounter.java:9`），`PlayerAdvancements` 也没有 `revokeAll`（只有 `revoke(holder, criterion)` / `forEach` / `stopListening`）。

2. **`Entity` / `LivingEntity` / `Player` 上有大量 `private` / `protected` 无 setter 的持久化状态**（`foodData`、`abilities`、`cooldowns`、`lastDeathLocation`、`lastHurtBy*`、`persistentData`…）。逐个用反射去清是脆弱且必然漏项的。

**正确做法：让引擎自己重建玩家对象。**

### 2.1 复用原版 `PlayerList.respawn` 的"销毁-重建"生命周期

```java
// PlayerList.java:397-472（节选）
public ServerPlayer respawn(ServerPlayer serverPlayer, boolean keepAllPlayerData, Entity.RemovalReason removalReason) {
    TeleportTransition respawnInfo = serverPlayer.findRespawnPositionAndUseSpawnBlock(!keepAllPlayerData, TeleportTransition.DO_NOTHING);
    this.players.remove(serverPlayer);
    serverPlayer.level().removePlayerImmediately(serverPlayer, removalReason);

    // Neo: 允许 mod 修改重生目标
    var event = net.neoforged.neoforge.event.EventHooks.firePlayerRespawnPositionEvent(serverPlayer, respawnInfo, keepAllPlayerData);
    respawnInfo = event.getTeleportTransition();

    ServerLevel level = respawnInfo.newLevel();
    ServerPlayer player = new ServerPlayer(this.server, level, serverPlayer.getGameProfile(), serverPlayer.clientInformation());
    player.connection = serverPlayer.connection;                 // 复用连接，玩家无感
    player.restoreFrom(serverPlayer, keepAllPlayerData);
    player.setId(serverPlayer.getId());                          // 保留实体 id → 跟踪/发包不乱
    player.setMainArm(serverPlayer.getMainArm());
    if (event.copyOriginalSpawnPosition()) player.copyRespawnPosition(serverPlayer);
    for (String tag : serverPlayer.entityTags()) player.addTag(tag);

    ... // 发 ClientboundRespawnPacket / teleport / difficulty / xp / effects / level info / permission level
    level.addRespawnedPlayer(player);            // 重新注册进 EntityLookup / 区块跟踪 / waypoint
    this.players.add(player);
    this.playersByUUID.put(player.getUUID(), player);
    player.initInventoryMenu();
    player.setHealth(player.getHealth());
    net.neoforged.neoforge.attachment.AttachmentSync.syncInitialPlayerAttachments(player);
    net.neoforged.neoforge.event.EventHooks.firePlayerRespawnEvent(player, keepAllPlayerData);
    ...
}
```

它顺手做掉的事情，正是方案 2 最怕"清不干净"的地方：

- 旧实体从 `EntityLookup` / `PersistentEntitySectionManager` / `ChunkMap` 跟踪中**正确注销**（因为它按"当时的 UUID"移除，且始终没变）；
- 新实体**正确注册**，保留实体 id，客户端收到规范的 `ClientboundRespawnPacket` 全量重建（**不存在 §1.7 的客户端身份错位**）；
- `ServerWaypointManager`（定位条/路径点）由 `ServerLevel` 的 `onAddedToLevel` / `onRemovedFromLevel` 自动增删（`ServerLevel.java:1944,1978`）；
- 自定义 Boss 栏、标签页、`ClientboundPlayerInfoRemovePacket` 等由调用方/引擎侧处理。

而且 NeoForge 已经给了**官方重定向入口**：`PlayerRespawnPositionEvent`（`_nfsrc/.../PlayerRespawnPositionEvent.java`）

```java
public void setTeleportTransition(TeleportTransition t)   // :55
public void setRespawnLevel(ResourceKey<Level> key)       // :64
public void setCopyOriginalSpawnPosition(boolean copy)    // :100  ← 转生时置 false，别把床点带过来
```

**所以"转生"在实现层面 = 一次被精心定制过的 `respawn(player, keepAllPlayerData = false, ...)`。**

### 2.2 `restoreFrom(old, false)` 依然会拷贝的东西（必须显式覆盖）

这是方案 2 最容易漏的一环：即使 `restoreAll = false`，`ServerPlayer.restoreFrom`（`:1702-1750`）仍会**无条件**搬运：

| 被搬运的东西 | 行号 | 转生时应如何处理 |
|---|---|---|
| `wardenSpawnTracker`（监守者生成计时） | `:1703` | 需要重置（`new WardenSpawnTracker()`，但字段私有 → 需 AT/Mixin，或干脆在重建前把旧对象重置） |
| `chatSession` | `:1704` | 保留（否则聊天签名断） |
| `gameMode` | `:1705` | 按转生规则决定（通常保留） |
| `getAttributes().assignBaseValues(old)` | `:1707` | **需要重置**为默认属性表（`AttributeMap` / `DefaultAttributes`），否则 mod 属性成长会带过去 |
| `enchantmentSeed` | `:1726` | 需重置（否则附魔种子可预测/沿用） |
| **`enderChestInventory`** | `:1727` | **必须清空**——末影箱是最容易漏的"跨世走私通道" |
| `DATA_PLAYER_MODE_CUSTOMISATION`（皮肤部件显隐） | `:1728` | 可保留 |
| `recipeBook.copyOverData` | `:1732` | **需要清空**（否则配方书带过世） |
| `seenCredits` | `:1733` | 需重置 |
| `enteredNetherPosition` | `:1734` | 需重置 |
| `chunkTrackingView` / `requestedDebugSubscriptions` | `:1735-1736` | 保留（技术状态） |
| `shoulderEntityLeft/Right`（肩上海龟/鹦鹉） | `:1737-1738` | 需清空 |
| `lastDeathLocation` | `:1739` | 需清空（别让玩家看到"上辈子的死点"） |
| **`waypointIcon()`**（定位条图标样式） | `:1740` | 需重置（`LivingEntity.waypointIcon()`，`LivingEntity.java:4113`） |
| `PlayerPersisted` 子标签 | `:1744-1746` | **需清空**（这是原版刻意跨重生保留的 NBT 区） |
| **`EventHooks.onPlayerClone(...)`** | `:1747` | ⚠️ **触发 `PlayerEvent.Clone`，其它 mod 会在这里把数据拷进新玩家**。我们的"清空"必须发生在**这之后**。 |
| `tabListHeader/Footer` | `:1748-1749` | 可保留 |

另外 NeoForge 会自动搬运**数据附件**（Data Attachments）：

```java
// AttachmentInternals.java:56-59
public static void onPlayerClone(PlayerEvent.Clone event) {
    event.getEntity().copyAttachmentsFrom(event.getOriginal(), event.isWasDeath());
}
```

所以"重建 + 清空"的**顺序**必须是：先 `respawn()`（内部触发 clone 事件，mod 把数据拷进新玩家）→ **然后**再执行我们的清空清单（把拷贝过来的东西清掉）。

附件复制并非无条件全量：`.99 AttachmentInternals.java:53` 在死亡克隆时只复制 `copyOnDeath` 类型，非死亡克隆则复制全部可复制类型；`restoreFrom(old,false)` 传出的 Clone `wasDeath` 为 true（`ServerPlayer.java:1747`）。重置仍应在 Clone 之后执行。其他模组若在事件里产生外部副作用，不能靠事后删除附件自动撤销，需它们的适配器参与。

### 2.3 stats / advancements 的重置：推荐定点清缓存，完整登出仅作备选

```java
// PlayerList.java:314-348（节选）
public void remove(ServerPlayer player) {
    net.neoforged.neoforge.event.EventHooks.firePlayerLoggedOut(player);
    ...
    player.awardStat(Stats.LEAVE_GAME);
    this.save(player);                       // 落盘 <uuid>.dat
    ...
    level.removePlayerImmediately(player, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
    player.getAdvancements().stopListening();  // ← 关键：否则旧对象继续接事件
    this.players.remove(player);
    ...
    this.playersByUUID.remove(uuid);
    this.stats.remove(uuid);                 // ← 唯一会清这两张表的公开路径
    this.advancements.remove(uuid);
}
```

`remove()` 是公开入口，但同时执行完整登出语义，不适合作为转生主流程。**推荐小范围 Accessor/AT 暴露已有的 `stats`、`advancements` 两张表**，由我们自己的服务停止旧成就监听并移除该玩家的两个缓存项，再走正常 `respawn()`。AT 只能开放已有成员，不能凭空新增一个引擎方法。

推荐序列（自定义服务方法均为设计接口）：

```java
// 准备阶段先将内存状态归档并写事务日志，详见 §3.2。
old.getAdvancements().stopListening();
evictProgressCaches(playerList, old.getUUID());  // Accessor：仅清 stats / advancements
seedNewProgressFiles(transaction);              // 最新内存已归档；写空 UUID JSON 阻断旧数据/名字回退
old.setRespawnPosition(null, false);             // 避免消耗旧重生锚
ServerPlayer fresh = playerList.respawn(old, false, Entity.RemovalReason.CHANGED_DIMENSION);
fresh.connection.player = fresh;                // 必须由调用者更新，respawn() 本身不赋值！
resetAndSynchronize(fresh, transaction);         // 覆盖 Clone 拷贝，完成 §2.2 的重置与客户端同步
```

完整 `remove()` + `respawn()` 方案有以下额外副作用；若保留为原型路径，必须单独验证：

1. **标签页条目会消失。** `remove()` 最后会 `broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())))`（`:347`），而 `respawn()`（`:397-472`）**不会**重新广播 `ClientboundPlayerInfoUpdatePacket`（原版死亡重生时玩家从未离开，所以不需要）。因此要自己补一条：
   ```java
   playerList.broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fresh)));
   ```
   否则所有人（包括玩家自己）的标签页里都会少一个人。
2. **双重移除不能直接宣称安全。** `remove()` 与 `respawn()` 都会调用移除流程；原版部分操作有守卫，但第三方的注销、挂载和登出监听未必幂等。完整登出还会移除独占玩家载具及末影珍珠（`.99 PlayerList.java:319-332`）。推荐路径避免这次额外登出。
3. **`respawn(player, false, ...)` 会顺带消耗玩家的重生锚。** 传 `keepAllPlayerData=false` 时，`PlayerList.respawn` 内部调用 `findRespawnPositionAndUseSpawnBlock(!keepAllPlayerData /* = true */, ...)`（`PlayerList.java:398`），第一个参数是 `consumeSpawnBlock`：

   ```java
   // ServerPlayer.findRespawnAndUseSpawnBlock
   if (!forced && consumeSpawnBlock && standUpPosition.isPresent()) {
       level.setBlock(pos, blockState.setValue(RespawnAnchorBlock.CHARGE,
           blockState.getValue(RespawnAnchorBlock.CHARGE) - 1), 3);   // ← 扣一格充能
   }
   ```
   这个副作用发生在 `PlayerRespawnPositionEvent` **之前**（事件在 `PlayerList.java:403` 才触发），所以即使你在事件里把目标改成转生点，**重生锚的充能已经被扣掉了**。转生前先 `setRespawnPosition(null, false)` 清掉，或走自己的重建流程。
4. `remove()` 会触发 `PlayerLoggedOutEvent` 与服务器通知（`notificationManager().playerLeft`），并对自定义 Boss 栏执行断开处理。定点清缓存路径保留在线玩家索引，正常 `respawn()` 负责替换玩家，不需要重新广播 PlayerInfo 初始化。

**两条路径都必须处理的事项**：

- `ServerGamePacketListenerImpl.java:1904,1913` 的原版调用者会接收 `respawn()` 返回值并写回 `this.player`。自定义调用同样必须立即设置 `fresh.connection.player = fresh`，否则后续输入包仍操作旧玩家。
- `restoreFrom(old,false)` 在 `keepInventory=true` 或旧玩家为旁观者时仍复制背包、经验与分数（`.99 ServerPlayer.java:1721-1722`）；不能把 `false` 当作完整清空。
- 重建会先发送部分初始状态，重置后必须再次同步。统计包是逐键更新，发空包不会清掉客户端旧值（`.99 ClientPacketListener.java:1737-1743`）；需对旧统计键发送 0，或使用本模组的客户端清理包。配方书应调用 `sendInitialRecipeBook`，其 Add 包带替换标志（`ServerRecipeBook.java:114-122`）。
- `.dat_old` 也在原版读档回退链中（`.99 PlayerDataStorage.java:77-87`），不得留下可被当作新世数据读取的旧备份。统计的按名字回退文件也应纳入事务迁移。

### 2.4 引擎之外的引用：可枚举的 vs 清不到的

> 重要版本校正：**26.1.2 里已经不存在 `ownerUUID` / `getOwnerUUID()`**（`world/entity` 下 0 处命中），现代主人引用统一是 `EntityReference<T>`（`EntityReference.java:24`，语义是 `Either<UUID, T>`）。`Projectile` 实现的是 `TraceableEntity`（`getOwner()`），**不是** `OwnableEntity`（`Projectile.java:39`）。真正的 `OwnableEntity` 只有两个实现：`TamableAnimal.java:35` 和 `animal/equine/AbstractHorse.java:80`。

**（a）有公开清理 API 的（遍历 `ServerLevel.getAllEntities()`，`ServerLevel.java:1683`）**

| 引用 | 位置 | 清理 API |
|---|---|---|
| 驯服动物主人（狼/猫/鹦鹉/鹦鹉螺） | `TamableAnimal.java:42`（`EntityDataAccessor<Optional<EntityReference<LivingEntity>>>`），NBT `"Owner"`（`:59-73`）；读写 `:159-168` | `setOwner(null)` `:163` / `setOwnerReference(null)` `:167` |
| 马/驴/骡/骆驼/羊驼/骷髅马 | `AbstractHorse.java:126`（NBT `"Owner"` `:794/:804`） | `setOwner(null)` `:182` |
| 中立生物仇恨（蜂/狼/北极熊/铁傀儡/僵尸猪灵/末影人） | `NeutralMob.java:26-28`；字段 `Bee:133`、`Wolf:111`、`PolarBear:64`、`IronGolem:59`、`ZombifiedPiglin:60`、`EnderMan:85`；NBT `"angry_at"` | `stopBeingAngry()` `NeutralMob.java:134` |
| **攻击记忆（量最大的一类）** | `LivingEntity.lastHurtByPlayer` `:234`（NBT `"last_hurt_by_player"` `:759`）、`lastHurtByMob` `:247` | `setLastHurtByPlayer((Player)null, 0)` `:629`、`setLastHurtByMob(null)` `:642`（**注意：这两个是有公开 setter 的**） |
| 弹射物发射者 | `Projectile.owner` `:42`（NBT `"Owner"`），`AreaEffectCloud.owner` `:58`，`EvokerFangs.owner` `:28` | `setOwner(null)` |
| 掉落物 | `ItemEntity.thrower` `:51`（`setThrower` `:423`）、`ItemEntity.target` `:52`（**裸 `UUID`**，"允许拾取的玩家"，`setTarget(UUID)` `:410`） | 有 setter |
| 拴绳 | `Leashable.java:383-384`（NBT `"leash"`） | `setLeashData(null)` / `dropLeash`（**原版唯一会自愈的主人引用**） |
| Allay 的 `LIKED_PLAYER` | `MemoryModuleType.java:137`（走 Brain NBT） | `getBrain().eraseMemory(LIKED_PLAYER)` `Allay.java:305` |
| 猪灵/猪灵蛮兵/鹦鹉螺的 `ANGRY_AT` | `MemoryModuleType.java:100` | `eraseMemory` `PiglinAi.java:775` |
| 村民流言/好感 | `Villager.java:121` + `GossipContainer.java:33`（`Map<UUID, EntityGossips>`，NBT `"Gossips"`） | `getGossips()` `:902` → `remove(UUID, GossipType)` `:130` / `remove(GossipType)` `:140` / `clear()` `:152`（**按村民实例遍历**） |
| 计分板分数 / 队伍 | `Scoreboard.java:35, 38`（**键是玩家名**） | `resetAllPlayerScores(holder)` `:193`、`removePlayerFromTeam(String)` `:285` |
| 自定义 Boss 栏 | `CustomBossEvent.java:23`（`Set<UUID>`，SavedData `"custom_boss_events"`） | `removePlayer(ServerPlayer)` `:48`（**需要在线对象**）；离线 UUID 只能 `removeAllPlayers()` `:56-62` 或反射 |
| 交互方块记录 | `Interaction.java:37-38`（`PlayerAction(UUID, long)`，NBT `"attack"`/`"interaction"`） | ❌ **无任何 getter/setter/移除 API** → AT/Mixin |
| 保险箱（Vault）已领奖名单 | `VaultServerData.rewardedPlayers` `:30`（`Set<UUID>`，NBT `"rewarded_players"`） | ❌ **无移除 API**，只有追加 + 128 条 FIFO |
| 试炼刷怪笼 | `TrialSpawnerStateData.detectedPlayers` `:45`（NBT `"registered_players"`） | `resetStatistics()` `:87-92`（**注意它会持续参与难度缩放** `countAdditionalPlayers` `:111-117`） |
| 玩家头颅 | `SkullBlockEntity.owner` `:25`（`ResolvableProfile` = 名字 + UUID） | 需改写方块实体 |
| 海龟/鹦鹉肩上的实体 | `ServerPlayer.shoulderEntityLeft/Right` `:272-273` | getter 返回活的 `CompoundTag` → 清键；但 setter 是 `protected`，且**必须再调** `Player.setShoulderParrotLeft/Right(Optional.empty())` `:1857,1865` 才能同步渲染 |
| 末影珍珠"发射者"列表 | `ServerPlayer.enderPearls` `:270`，**会被序列化进玩家存档** `:500-510` | `getEnderPearls()` `:2423` 返回活 Set → 清空 |
| 狐狸信任列表 | `Fox.java:118-132`（NBT `"Trusted"`） | ❌ `clearTrusted()` `:452` **是 private** → AT/Mixin |
| 繁殖"父母" | `Animal.loveCause` `:46`（NBT `"LoveCause"`，**`EntityReference<ServerPlayer>`**） | ❌ **`resetLove()` `:197` 不会清它，且字段 private** → AT/Mixin |
| 僵尸村民"治愈者" | `ZombieVillager.java:74`（NBT `"ConversionPlayer"`，裸 UUID） | ❌ `startConverting` `:200` 是 private |
| 监守者愤怒 | `AngerManagement.angerByUuid` `:49`（`Object2IntMap<UUID>`，NBT `"anger"` → `"suspects"`） | ❌⚠️ **`Warden.clearAnger(Entity)` `:439` → `AngerManagement.clearAnger` `:157-161` 只清内存映射，永远不清 `angerByUuid`**；重启后 `convertFromUuids` `:128-141` 会把旧 UUID 重新绑上 → 这是原版自身的一个缺口 |
| 袭击"村庄英雄" | `Raid.java:108`（`Set<UUID>`，NBT `"heroes_of_the_village"`） | ❌ **没有 getter、没有 remover** → AT/Mixin |
| TNT 点燃者 | `PrimedTnt.owner` `:51`（NBT `"owner"`） | ❌ 无公开 setter |
| 蜜蜂蜂巢 | `BeehiveBlockEntity.java:87`（存放整只蜜蜂 NBT） | ⚠️ 见下方"跨容器搬运" |
| 末影龙 | `EnderDragon.java:159` `unlimitedLastHurtByPlayer` | 不序列化，仅内存；`aiStep()` `:165-170` 自清 → 可不管 |

**（b）两个必须在设计里显式处理的"隐藏通道"**

1. **蜂巢会把玩家 UUID 搬进物品栏。** `BeehiveBlockEntity.Occupant.of()` 会剥掉 `IGNORED_BEE_TAGS`（`BeehiveBlockEntity.java:55-82`），其中包含 `"Brain"`（`:59`）和蜜蜂自己的 `"UUID"`（`:81`），**但不包含 `"angry_at"`、`"last_hurt_by_player"`、`"LoveCause"`**。也就是说：**玩家打过的蜜蜂被收进蜂巢后，玩家 UUID 会随蜂巢 NBT 一起被塞进 `minecraft:bees` 物品组件，跟着潜影盒/箱子跨维度、跨基地流动。** 单纯清扫"世界里的实体"是抓不到它的。
2. **区域文件里睡着的实体 / 方块实体。** 玩家没有指向全部历史关系的反向索引，无法从玩家枚举它们。若要求转生当刻物理删除全部旧记录，需要离线重写或加载它们；若目标是“旧关系以后不能生效”，则可在 `EntityReference` 内保存世代，在解析时拒绝旧世引用，无需先加载全部区域。裸 UUID 集合仍需单独适配。

**（c）真正清不到的（关键盲区）**

`getAllEntities()` 只覆盖**已加载区块**的实体。**未加载区块里睡着的宠物、马、狐狸，你根本枚举不到。** 这正是用户提到的"清理不干净，比如宠物"问题的真正根源——不是清单不够长，而是**扫描范围有洞**。

第 2.7、3.3 节分别说明统一引用失效与延迟清理的覆盖边界。

### 2.5 NeoForge 数据附件的清空

`AttachmentHolder` 的存储是个包私有字段：

```java
// AttachmentHolder.java:40-51
@Nullable
Map<AttachmentType<?>, Object> attachments = null;      // package-private，无访问器
```

**没有 clear-all API**（`IAttachmentHolder` 只有按类型的 `removeData`）。但好消息是：`removeData(AttachmentType)` 是 **public** 的（`AttachmentHolder.java:105-115`），而类型可以枚举：

```java
// NeoForgeRegistries.java:43
public static final Registry<AttachmentType<?>> ATTACHMENT_TYPES = ...;
```

所以**用公开 API 可以枚举并移除附件**。正式实现先按策略筛选，不能把“有能力全删”当成默认兼容策略：

```java
for (AttachmentType<?> type : NeoForgeRegistries.ATTACHMENT_TYPES) {
    if (resetPolicy.shouldReset(type) && player.hasData(type)) {
        player.removeData(type);
    }
}
```

副作用：`removeData` 会调 `syncData(type)`（`Entity.java:3975-3978` → `AttachmentSync.syncEntityUpdate`），若无差别地对上百个类型调用会发一批同步包。建议：先用 `hasData(type)`（`:68-71`，public）过滤。

**`getPersistentData()` / `NeoForgeData`** 也没有 clear API，但返回的是**活的** `CompoundTag`：

```java
// Entity.java:3926-3934
private net.minecraft.nbt.@Nullable CompoundTag persistentData;
public CompoundTag getPersistentData() { if (persistentData == null) persistentData = new CompoundTag(); return persistentData; }
// 存 :2023  output.storeNullable("NeoForgeData", CompoundTag.CODEC, persistentData);
// 读 :2087
```

清空方式：先复制待删除键集合，再逐个 `remove`（26.1.2 的 `CompoundTag` 没有 `clear()`），避免边遍历活视图边修改。`PlayerPersisted` 子标签也需按键归档、重置或保留，不能因为原版跨死亡复制就默认跨转生保留；账号配置和第三方必要标记则由处理器明确保留。权威世代和事务日志放在世界级存储，不能被玩家附件重置删除。

### 2.6 必须接受"要写 AT/Mixin"的现实：硬骨头清单

下面是 26.1.2 里**没有公开 API、只能靠 AccessTransformer / Mixin 解决**的项（子代理逐条核实，行号可直接查）。它们决定了这个 mod 的"侵入度下限"：

| # | 项 | 位置 | 为什么必须 AT/Mixin |
|---|---|---|---|
| H1 | `Player.gameProfile` | `Player.java:168` | `private final`，全库无 `setGameProfile`（这也是方案 1 死掉的根因） |
| H5 | 属性全量重置 | `LivingEntity.java:211` + `AttributeMap.java:17` | 无 `getAllAttributes()`；只能外部遍历 `BuiltInRegistries.ATTRIBUTE`（`BuiltInRegistries.java:201`）配 `resetBaseValue` `AttributeMap.java:119` + `removeModifiers` `AttributeInstance.java:132` |
| H7 | `CombatTracker` | `CombatTracker.java:25-31` | 全 private；`recheckStatus()` `:150` 要等 100/300 tick 才清 |
| H8 | `FoodData.exhaustionLevel`/`tickTimer` | `FoodData.java:16-17` | private，只有加法 `addExhaustion` `:100`（`Player.foodData` 是 `protected` 非 final，同包/子类才能整体替换） |
| H9 | `ItemCooldowns` | `ItemCooldowns.java:14,63`；`Player.java:171` | 只有 `removeCooldown(Identifier)`；变通：遍历 `BuiltInRegistries.ITEM` 逐个 `removeCooldown` |
| H11 | `ServerPlayerGameMode` 的 `previousGameModeForPlayer` 与"正在挖的方块"进度 | `ServerPlayerGameMode.java:39-48,79` | 全 private，无 setter |
| H12 | `ServerPlayer.enteredNetherPosition` | `ServerPlayer.java:257` | private，无 getter/setter（只在 `:1167` 自设、`:1212` 自清） |
| H13 | 配方书批量清空 | `ServerRecipeBook.java:125,133,141` | `apply` 是 private、`loadUntrusted` 不清空；变通：`copyOverData(new ServerRecipeBook((id,out)->{}))` |
| H14 | 肩上实体（海龟/鹦鹉） | `ServerPlayer.java:2431,2440` | setter 是 `protected`，且需配合 `Player.setShoulderParrotLeft/Right` 才同步 |
| H15 | 统计数据全量清空 | `StatsCounter.java:9,20-23`；`ServerStatsCounter.java:107-112` | 无 clear；`setValue(...,0)` 是"写 0"不是"删除"；重新 parse 空文档也不会清（`putAll`） |
| H16 | 成就全量清空 | `PlayerAdvancements.java:54-59,83,92,111-126` | `reload()` 会**立刻把 JSON 读回来**；正确顺序必须是"先删/改名 JSON，再 reload" |
| H17 | `chatSession` | `ServerPlayer.java:2302,2306` | `setChatSession` 不接受 null，无 `clearChatSession`（转生时**应当保留**，否则聊天签名链断） |
| H19 | `PlayerList.stats` / `advancements` 缓存替换 | `PlayerList.java:122-123,773,804` | `private final Map`，只有 `computeIfAbsent`；**唯一公开清理点是 `PlayerList.remove()` `:342-343`** |
| H20 | 玩家存档没有删除 API | `PlayerDataStorage.java:33,77,89` | 只有 `save`/`load`/`getPlayerDir()`；删文件得自己来（`MinecraftServer.getWorldPath(...)` `:2058` / `PlayerList.getPlayerIo().getPlayerDir()` `:882`） |
| H21 | `ScoreAccess.reset()` 不会移除条目 | `ScoreAccess.java:22-24` | 它写 0 但保留条目；要真删必须用 `Scoreboard.resetAllPlayerScores` `:193` |
| — | `Raid.heroesOfTheVillage` | `Raid.java:108` | 只读字段、无 remover |
| — | `AngerManagement.angerByUuid` | `AngerManagement.java:49,157-161` | `clearAnger` 不清它 |
| — | `Interaction.attack/.interaction` | `Interaction.java:37-38,180` | 私有 record，无 API |
| — | `BeehiveBlockEntity.stored` | `BeehiveBlockEntity.java:87,337` | private，无 API |
| — | `Fox.clearTrusted()` | `Fox.java:452` | private |
| — | `PrimedTnt.owner` | `PrimedTnt.java:51` | 无 setter |
| — | `Animal.loveCause` | `Animal.java:46` | private，`resetLove()` 不清 |
| — | `ZombieVillager` 治愈者 | `ZombieVillager.java:74,200` | `startConverting` private |
| — | `SavedDataStorage.cache` | `SavedDataStorage.java:43` | private → **无法通用枚举 SavedData**，必须预先知道每个 `SavedDataType` |

**实现范围**：推荐接受集中式 Mixin/Accessor：`EntityReference` 扩展与 Codec 桥接、NBT 标记复制与完整载荷打包/恢复、同步数据的引用替换，以及玩家进度缓存访问。确实需要清理的裸 UUID 私有字段另按策略开放。新建玩家已经重置的临时字段不必逐个开放；`getEntity()` 返回 null 能阻止旧关系解析，也不代表必须物理清除它所在的私有字段。H1/H17 属于应保留的账号/会话状态，不是转生所需的侵入项。

### 2.7 直接扩展 `EntityReference`：世代字段、Codec 与失效校验

#### 2.7.1 原版行为与可修改位置

`.99 EntityReference.java:20-24` 的类为 `final`，内部是 `Either<UUID,T>`；`codec()` / `streamCodec()` 分别返回 UUID Codec / 网络 Codec（`:26-31`）。可用 Mixin 添加字段、实现本模组接口，**不需要继承它，也不必反射修改 `private static final CODEC`**。

原版缓存的对象被移除后，会退回 UUID 并重新查找（`:55-75`）。因此稳定 UUID 下，普通 `EntityReference` 会把旧关系接到重建后的玩家上；增加字段后还必须增加校验。

设计一个 `EntityReferenceExtension` 接口，保存可选的非负 `long playerGeneration`。`OptionalLong.empty()` 表示没有显式玩家世代，不能用 0 表示“没字段”，因为 0 是有效的初始世代。字段只在创建关系或反序列化时设置，**不得随解析刷新为当前世代**。非玩家引用保持原版行为，例如末影龙战斗中的末影水晶引用。

| 入口 | `.99` 证据 | 必须做的改动 |
|---|---|---|
| `codec()` | `EntityReference.java:26-27` | 包装原版 UUID 编解码，在 NBT 节点上标记/读取世代；仍输出原 UUID 形状。叶子没有父级路径，路径由 §2.8 的完整树遍历自动发现 |
| 实例 `store(ValueOutput,String)` | `:92-93` | 原版直接调用 UUID Codec，须转用统一引用桥接 Codec；不为每个 key 单独写世代字段 |
| 静态 `store(reference,output,key)` | `:96-99` | 它委托实例 `store`；实例路径修正后无需再独立实现一套 |
| `read(ValueInput,String)` | `:120-121` | 原版已调用引用 Codec；完整输入边界先恢复 NBT 节点标记，该路径即可统一读回世代 |
| `readWithOldOwnerConversion` | `:124-133` | UUID 分支改走桥接 Codec，保留旧玩家名转换；旧名字转换不属于新关系绑定 |
| 直接 Codec 调用方 | `Fox.java:132`；`NeutralMob.java:36,53` | 用于验收组合覆盖；缓存的列表/字段 Codec 自动组合桥接叶子，无需逐个调用方改写 |
| `of(T)` / `of(UUID)` | `:42-48` | `of(ServerPlayer)` 捕获当前世代；只有 UUID 的新绑定需要显式服务器上下文，见下文 |
| `getEntity(Level,Class)` 与 `getEntity(UUIDLookup,Class)` | `:55-81` | 两个重载都要覆盖失效语义，校验必须包含缓存命中分支 |
| `matches(T)` | `:88-89` | 对玩家增加世代检查，否则绕过解析的仇恨、信任判断仍按 UUID 命中 |

只改 `CODEC`、只改 `getEntity()`，或只在“从 UUID 查到实体”时校验，都会漏掉上表中的路径。

#### 2.7.2 存档格式候选：逐字段旁存与结构模式

**决策更新**：本节记录两种已研究、运行过独立 Codec 检查的布局。A 说明未知键兼容性，但需要字段/容器适配；B 改变原字段类型。为满足通用性要求，当前实施主线改用 §2.8，两者均不作为默认原版引用布局。

**A. 逐字段兼容模式：原字段保持合规形状，扩展字段旁存。** Minecraft 常用的 `RecordCodecBuilder` / `MapCodec` 按声明字段读取对象，通常不会对未消费键做全量拒绝校验；普通实体的 ValueInput 读取也只访问所需键。因而可以利用“原版忽略未知键”实现兼容，而不必把增加世代等同于破坏存档。具体 Codec 如果主动检查键集合，则需另行处理。

这里需区分两种改动：**给对象增加未知键**可以被原版忽略；**把已知字段由 UUID 数组改成对象**仍会违反原版 `UUIDUtil.CODEC` 的输入类型。`EntityReference` 原本是裸 UUID，因此 A 模式把世代写在包含引用的父级中，读入后仍恢复到引用对象内部的扩展字段。

```snbt
{
    Owner: [I; 123, 456, 789, 1011],
    "reincarnation:Owner": {
        uuid: [I; 123, 456, 789, 1011],
        generation: 3L
    }
}
```

原版照常读取 `Owner`，忽略 `reincarnation:Owner`；本模组读取两者，校验元数据 UUID 与原字段一致后，把 generation 写入 `EntityReferenceExtension`。对于 `angry_at`、`LoveCause` 等字段使用同一规则，避免改变原字段语义。元数据缺失按旧档规则处理；元数据存在但损坏/不匹配不能补成今世，需记录并隔离该关系或拒绝恢复。

对象级兼容 Codec 的示例：

```java
public record ReferenceStamp(UUID uuid, long generation) {
    public static final Codec<ReferenceStamp> CODEC = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.CODEC.fieldOf("uuid").forGetter(ReferenceStamp::uuid),
        Codec.LONG.validate(g -> g >= 0L
            ? DataResult.success(g)
            : DataResult.error(() -> "Negative player generation"))
            .fieldOf("generation").forGetter(ReferenceStamp::generation)
    ).apply(i, ReferenceStamp::new));
}

public record CompatibleOwnerData(UUID owner, Optional<ReferenceStamp> stamp) {
    public static final Codec<CompatibleOwnerData> CODEC = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.CODEC.fieldOf("Owner").forGetter(CompatibleOwnerData::owner),
        ReferenceStamp.CODEC.optionalFieldOf("reincarnation:Owner")
            .forGetter(CompatibleOwnerData::stamp)
    ).apply(i, CompatibleOwnerData::new));
}
```

示例说明父级字段的布局，不是替代整只实体的 Codec。实际 `store/read` 适配器只向当前 ValueOutput/ValueInput 读写这两个字段，不重写父对象的其他数据；通用字段 MapCodec 可将 `Owner` 参数化。世代元数据也使用 Codec 序列化，引用对象的运行时世代与外部存储布局不必一一同形。

在 A 模式下，狐狸 `Trusted` 等 UUID 列表保留原列表，新添 `reincarnation:Trusted` 元数据列表；每项记录原索引、UUID、世代，加载时三者关联校验，不依赖实体位置，也不按 UUID 一对一覆盖不同世代的重复项。只修改叶子 `EntityReference.codec()` 无法获得父级/列表索引，因而该候选方式必须覆盖这些直接 Codec 调用点。清除关系或替换 UUID 时，同时清除/更新它的旁存数据。**这正是 A 不再作为主线的原因**；§2.8 用完整树遍历取代逐字段约定。

**B. 可选结构模式：新结构和旧 UUID 双读。** 以下保留上一版的结构方案与验证结论，供本模组自有数据或已约定新格式的接口使用；它不是默认原版兼容布局。

原有未带戳引用仍写原版 UUID；显式带戳的玩家引用写成结构：

```snbt
// 旧格式，也继续用于不带世代的非玩家引用
Owner: [I; 123, 456, 789, 1011]

// 新的玩家关系：世代存在 EntityReference 的扩展字段中
Owner: {
    uuid: [I; 123, 456, 789, 1011],
    "mortal_cultivation:generation": 3L
}
```

下面是独立的序列化载体骨架；Mixin 桥接层负责在它与 `EntityReference<?>` 的 UUID、扩展世代字段之间转换。这里的 UUID 常量编码器仍用 `UUIDUtil.CODEC`，**不要在扩展 Codec 的初始化中再次调用 `EntityReference.codec()`，否则会递归初始化**。

```java
public record ReferenceData(UUID uuid, Optional<Long> generation) {
    private static final Codec<ReferenceData> STAMPED = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.CODEC.fieldOf("uuid").forGetter(ReferenceData::uuid),
        Codec.LONG.validate(g -> g >= 0L
            ? DataResult.success(g)
            : DataResult.error(() -> "Negative player generation"))
            .fieldOf("mortal_cultivation:generation")
            .forGetter(data -> data.generation().orElseThrow())
    ).apply(i, (uuid, generation) -> new ReferenceData(uuid, Optional.of(generation))));

    public static final Codec<ReferenceData> CODEC = Codec.either(STAMPED, UUIDUtil.CODEC).xmap(
        either -> either.map(data -> data, uuid -> new ReferenceData(uuid, Optional.empty())),
        data -> data.generation().isPresent() ? Either.left(data) : Either.right(data.uuid())
    );
}
```

所需类型来自 `java.util`、`com.mojang.serialization`、`com.mojang.serialization.codecs.RecordCodecBuilder`、`com.mojang.datafixers.util.Either` 和 `net.minecraft.core.UUIDUtil`。结构格式要求世代字段存在且非负；损坏的新结构不能悄悄当作“今世”补值。桥接解码创建 UUID 引用后，只恢复存档里的世代，不查询当前世代。

如果采用 B 模式，可以 `@Inject(..., at=@At("HEAD"), cancellable=true)` 替换 `codec()` 返回值；泛型强转集中在一个桥接点，类型参数仍满足 `UniquelyIdentifyable` 上界。具体重载使用完整方法描述符区分。狐狸等类会把 `codec().listOf()` 缓存在静态字段中，因此 Mixin 必须在这些类初始化前生效，不能等服务器启动后再替换常量。A 在字段/父级读写处接入旁存 Codec；§2.8 同样需要包装 `codec()`，但其 UUID 编码形状保持原样。

```java
// EntityReferenceMixin 中的替换入口；LifeReferenceCodecs.CODEC 为自定义桥接 Codec
@Inject(method = "codec", at = @At("HEAD"), cancellable = true)
private static <T extends UniquelyIdentifyable> void mc$codec(
    CallbackInfoReturnable<Codec<EntityReference<T>>> cir
) {
    cir.setReturnValue((Codec<EntityReference<T>>) (Codec<?>) LifeReferenceCodecs.CODEC);
}
```

B 模式的桥接 Codec 通过 `ReferenceData.CODEC.xmap(decodeReference, encodeReference)` 构造：`decodeReference` 创建 UUID 引用并恢复扩展字段；`encodeReference` 读取扩展字段。补齐实例 `store` 和旧主人读取后，引用中的世代才真正能存进宠物等实体 NBT。上面的注入骨架还需要在实际启动时验证目标匹配与 Mixin 应用，不能以 Codec 往返测试替代。A 模式的检查仅证明原版和扩展解码器可读取同一父对象，不证明 §2.8 的实际 Mixin 已生效。

#### 2.7.3 绑定和校验：世代不能在读档时“续期”

1. **新绑定**：`EntityReference.of(serverPlayer)` 捕获服务器权威的当前世代。`of(UUID)` 没有 Level/服务器参数，不能安全推导当前世代；为确实新建的 UUID 关系提供 `LifeReferences.bind(ServerLevel, UUID)`，并审计第三方这类写入。反序列化与旧名字转换不是新绑定，不调用这个工厂。
2. **解析**：带戳玩家引用先比较 `boundGeneration == currentGeneration`；不同即返回 null。缓存里即使已经有同 UUID 的活玩家，也必须拒绝。`Level` 重载可先查世界级世代索引；底层 `UUIDLookup` 重载没有服务器参数，至少在返回缓存/解析出的 `ServerPlayer` 前，通过该玩家所属服务器再检查，不能只拦截 `Level` 重载。
3. **匹配**：`matches(player)` 同样检查 UUID 与世代。狐狸 `Fox.java:698`、中立生物 `NeutralMob.java:99-108` 直接依赖 `matches`，只拦截 `getEntity()` 不足以让它们遗忘前世。
4. **离线与卸载**：本世玩家不在线时，返回 null 只是暂时不可解析，不能删除关系。内部应区分 `CURRENT`、`STALE`、`UNKNOWN`；只有索引完整且世代不匹配才能认定过期。检查不强制加载区块。
5. **旧档迁移**：在启用此机制的世界中，把原有无戳玩家关系解释为初始世代 0；当账号首次推进到 1 后，离线保存的旧关系也会过期。未带戳引用首次解析为玩家时，只能保持/补写 0，**不能补写该玩家当前世代**。同一世界启用后新建的 UUID 关系必须经过有上下文的绑定工厂；无法判断来源的第三方引用列为兼容缺口。

世代索引按服务器/存档隔离；客户端不做权威世代判定，也不能使用跨服务器的静态“当前服务器”变量。已有世代索引损坏时应从事务日志恢复，不得整表默认回到 0。普通死亡、重登、换维度不增加世代，因此本世宠物仍能重新解析到新的玩家实体。

#### 2.7.4 网络、相等性与存档兼容

- **磁盘 Codec 与 `streamCodec()` 独立。** 第一阶段保留原版 UUID 网络格式，世代只在服务端判定和保存。`EntityDataSerializers.java:99-101` 的宠物主人同步使用 `streamCodec()`，所以客户端引用不会自动得到世代；需要展示世代时发送独立自定义包。以后若修改基础网络 Codec，必须让通信两端使用相同协议，并覆盖缓存该 StreamCodec 的静态初始化。
- 保留 `getUUID()`、`equals()`、`hashCode()` 的原版 UUID 语义；新增 `sameLife` 比较业务身份，不全局改变第三方集合的含义。原版 `matches` 的玩家世代检查属于显式行为变更。若同 UUID 的引用换世代，`SynchedEntityData.set` 可能因相等而连服务端值都不替换，应在这个基础更新入口统一识别引用及 Optional 引用的世代变化，强制替换/同步；不能只补发客户端包，也不逐个宠物 setter 修复。第三方自定义包装类型需遵守元数据变化通知契约。引用发布后不原地改戳，显式重绑创建新引用。
- 解码、复制、实体转换、蜂巢/物品收纳必须保留原戳；将引用拆成 `getUUID()` 再 `of(UUID)` 会丢戳。原版读取新结构失败时不能静默重建为当前世代。
- **当前通用方案及候选 A 均保留原版字段类型**：普通对象解码忽略扩展区中的未知键，宠物主人无需先导出成 UUID 才能被原版加载；B 把 `Owner` 改成对象，才有原版 UUID 解码不兼容的问题。兼容不等于保存未知字段：卸载本模组后不会执行世代隔离，其他程序再次保存也不保证保留元数据。重新启用后的迁移规则与未来 DataFixer 仍需验证。

#### 2.7.5 覆盖面与剩余适配

完成 §2.8 的引用桥接与 NBT 基础边界后，宠物/马主人、狐狸信任、持久愤怒、攻击归属、繁殖归属、投射物/TNT/区域效果所有者等使用 `EntityReference` 的标准 NBT 路径可以统一保存，无需逐字段持久化适配。它提供的是**引用解析和匹配失效**，不自动重置 `tamed`、坐下状态、攻击计时、珍珠票据等附属状态；这些仍由具体系统的清理策略决定。

村民 `GossipContainer`、监守者 `angerByUuid`、Brain 的 UUID 记忆、袭击英雄、掉落物拾取目标、Vault 领奖集合、计分板等不经过这个类，不能宣称统一覆盖。第三方直接比较 `getUUID()` 的调用也需单独审计。

### 2.8 通用持久化桥接（当前推荐）

详细研究与原型见 [05-通用EntityReference持久化桥接](转生系统调研/05-通用EntityReference持久化桥接.md)。核心是把叶子 Codec 不知道的路径信息，延后到完整 NBT 树组装后自动取得，不要求各关系字段提供父级上下文。

```text
保存：带戳 EntityReference → 原版 UUID 编码 + NBT 节点内存戳
    → 完整载荷扫描 → 扩展区统一保存 path / target UUID / generation
读取：完整载荷恢复节点戳 → 原版组合 Codec → 带戳 EntityReference
    → getEntity / matches 统一执行世代校验
```

实体等完整对象使用 `NeoForgeData["reincarnation:reference_metadata"]` 存表；SavedData 使用其外层存档封装，不能往任意业务 Map 中插键。路径由有类型的 `{key:…}` / `{index:…}` 分段构成，目标 UUID 用于校验。同 UUID 的不同位置可以属于不同世代，不能按 UUID 合并。移除本模组元数据后，原有字段树应与原版编码相等。

集中接入 `EntityReference.codec()`、绕过它的 `store` 和旧主人 UUID 读取分支，给 `IntArrayTag` 增加仅内存的戳字段，并在 `copy()`、NBT→NBT 转换时传递。标记不参与 NBT write、equals 或 hashCode。完整输入边界必须先恢复标记，再运行任何引用解码；完整输出在组装完成后收集标记。因此原版 `listOf`、Map 和 RecordCodecBuilder 组合可直接使用同一桥接叶子。

优先验证 `TagValueOutput.buildResult()` / `TagValueInput.create()`，并为不经过它们的基础存储接同一个 helper，例如 `SavedDataStorage.encodeUnchecked` 及其读取端。打包后还可能复制、嵌套或移动子树，最终持久化边界应重新生成路径；读取要恢复嵌套载荷。损坏或不匹配的元数据不得补成当前世代，扫描与后台存盘应使用私有快照。

原始载荷的表只在修改前恢复，消费后的旧表不能再次覆盖当前节点戳；同 UUID 换世代无法仅靠 UUID 校验发现误贴。嵌套表冲突、未知版本和损坏戳需传播无效状态或隔离完整载荷，不能让 ValueInput 忽略解码错误后再保存成无戳旧档。完整契约见 05，当前原型尚未完整实现这些保护。

通用范围限于走这些 `EntityReference` 和 NBT 基础路径的引用。裸 UUID、`of(UUID)` 新绑定缺少上下文、第三方自行拆装数组或过滤元数据、DataFixer 移结构及任意 JSON 重编码，不能凭相同 UUID 自动恢复历史。JSON 需要另一个统一后端，不能由 NBT 原型推定已支持。

**验证状态**：研究阶段的通用算法原型 **21 项检查通过**，使用身份表和复制 helper 模拟 Mixin。2026-11-02 已接入实际节点/引用字段、NBT copy/转换、完整输入输出、最终二进制边界和同步替换，首轮 **7 项专服 GameTest 全部通过**，包含真实狼存档和 SavedData 落盘重读。恢复先整树验证后消费旧表，损坏输入以异常拒绝；完整隔离恢复、客户端及其他搬运链路仍待验证，见 06。

---

## 3. 方案 3（推荐）：稳定 UUID + 转生世代模型

### 3.1 数据模型

```
Account = Minecraft UUID（永不变）
LifeId  = (accountUuid, long generation)    // 权威业务身份；generation >= 0，单调递增
EntityReference = 原有 UUID/实体缓存 + 可选的绑定时 generation
LifeToken = 对带命名空间的 LifeId 按固定 UTF-8 编码生成的 UUID（仅作兼容键）
```

- **绝不改账号 UUID。** 登录和权限不需要改造，也不产生随机 UUID 玩家文件。通用桥接保留原版关系字段形状，世代统一放入完整载荷的扩展元数据区；卸载后重存不保证保留世代信息，详见 §2.7.4、§2.8。
- **"清空"变成两个动作**：(a) 把玩家对象通过引擎生命周期**重建**；(b) 按清单**清空**引擎与 mod 的遗留状态。
- **"历史"天然可达**：因为键（UUID）永远稳定，`world/mortal_cultivation/history/<uuid>/<generation>.…` 随时可查、可回放、可做转生剧情（展示上一世背包/成就/死因）。

### 3.2 转生流程：持久化状态机与向前恢复

```
① VALIDATING：主线程校验、账号级互斥、Pre 可取消
   - 确定 txId、fromGeneration、toGeneration、目标维度、重置策略
   - 在服务端拦截交互和新增关系；客户端界面只负责呈现，不能代替冻结

② PREPARED：生成最新归档和事务清单，成功持久化后才允许推进
   - 从当前内存序列化玩家 NBT、stats、advancements、模组快照
   - 不能直接复制可能尚未保存的旧 JSON 当作最新快照
   - 记录处理器 ID/版本、策略、文件清单、校验和、归档路径
   - 临时文件写完并强制落盘，再原子替换提交清单；检查写入结果

③ COMMITTING：持久化不可撤销的提交意图，然后将权威世代设为 toGeneration
   - 重放时执行“设为指定值”，不能再次 generation++
   - 世代发布与已加载旧关系处理在同一主线程阶段完成，不让玩家插入交互
   - 不保留的带戳关系立即在解析/匹配时失效；保留规则见 §3.3

④ REBUILDING：§2.3 的定点清缓存 + 原版重生
   - 先停旧成就监听，隔离旧进度文件及回退文件
   - 清旧重生点；PlayerRespawnPositionEvent 设置转生位置和禁止复制旧出生点
   - fresh = playerList.respawn(old, false, CHANGED_DIMENSION)
   - fresh.connection.player = fresh

⑤ RESETTING：覆盖 Clone 拷贝、应用已保存的策略、重置关系附属状态
   - 处理器从归档/事务上下文恢复，不能依赖旧玩家对象一直存在
   - 清空基础状态后，按白名单恢复跨世保留项，重算派生属性和资源
   - 重发背包、能力、属性、效果、经验、配方、成就、统计和本模组客户端状态

⑥ COMMITTED：保存新玩家及进度，回读验证 generation/txId，再写完成记录
   - 写入“新世已初始化”标记；标记不能在重置前提前写好
   - 最后发 Post / 解冻；失败则保持待恢复状态，不把半完成结果交给玩家
```

**持久化边界**：`SavedData.setDirty()` 只表示需要保存，并不代表已经落盘。`.99 SavedDataStorage.java:243-244` 有 `saveAndJoin()`，但底层写入会捕获并记录 IO 错误（`:228-239`），不能仅凭方法返回就认定事务成功；`PlayerDataStorage.save()` 也会捕获异常。事务提交清单需使用能报告失败的独立写入流程，并验证归档与新数据。`mtime` 不能充当世代或提交证据。

**恢复规则**：`PREPARED` 且没有提交意图时可中止；已有 `COMMITTING` 意图则根据同一个 `txId/toGeneration` 向前恢复，不回退世代、不再次转生。启动时读取未完成事务；玩家可暂时离线，待登录前完成恢复并校验初始化标记。只在新世尚未完成时重放重置，已完成的新世正常游玩进度不能被重复清空。外部数据库与第三方 Post 副作用须由适配器按 txId 去重，不能承诺一次文件操作就提供跨模组事务。

### 3.3 未加载实体：引用内世代校验为主，加载清理为辅

采用 §2.7、§2.8 后，狼的运行时主人引用保存 `(玩家 UUID, 旧世代)`；磁盘 `Owner` 仍是原 UUID，世代在完整载荷的统一元数据表中。玩家在别处转生，索引推进到新世代；狼以后在任意区块、任意维度加载，其引用仍带旧戳，`getOwner()` / `matches()` 就不能指向新世玩家。新驯服关系捕获新世代，因此卸载再加载也不会误清。判断依据附着在引用上，实体移动不会改变结果。

**撤回原有的“一次性区块清扫即可完整解决”结论**：区块加载后实体仍可能从其他区块移动进来，旧实体也可能经蜂巢、肩上 NBT、物品或第三方存储重新释放。标记区块已清扫不等于其中所有关系都属于今世。另外 `ChunkEvent.Load` 在区块达到 FULL 之前触发，且不能推断实体已全部加载（`.99 ChunkEvent.java:38-45`）。

补充清理分三层：

1. 转生提交时处理已加载关系的附属状态，例如宠物坐下/驯服标志、旧攻击目标、末影珍珠及票据；已过期引用无需能解析到玩家，也可以用它保留的 UUID/世代定位。
2. 服务端 `EntityJoinLevelEvent` 处理后续进入世界的实体，区分磁盘加载和新生成；只根据关系戳判定。此事件也可能早于 FULL（`EntityJoinLevelEvent.java:23-25`），事件内只做不加载世界的检查，依赖区块的修改延迟到安全 tick；在此期间引用校验已经阻止旧关系生效。
3. 蜂巢/物品释放、实体转换等边界验证引用戳没有丢失。块实体与裸 UUID 集合按类型适配。区块批处理可作性能优化和物理清理手段，不能承担正确性依据。

**保留宠物的语义**：世代递增会使全部旧世玩家引用过期，不能仅靠 `purgePets=false` 跳过扫描来保留。需先将保留规则持久化到事务/关系策略中，按“关系种类、UUID、绑定世代”允许解析，并在实体加载时显式重新绑定到新世代；未加载实体也要读到同一策略。若不实现这种按种类保留，首版应固定断开前世宠物关系，删除这个配置项。全局 `getEntity()` 不知道“这是宠物还是投射物”，种类保留需在拥有关系的适配器处处理，不能只在全局引用中放一个豁免开关。

默认保留账号级事实（OP、白名单、封禁、Vault 已领奖、头颅/书籍署名）；是否清分数、队伍、村民好感由明确策略决定。尤其不能为了“遗忘前世”清空整个 Vault/试炼参与集合，造成重复领奖或影响其他玩家。

### 3.4 API 设计

```java
package com.iafenvoy.mortalcultivation.api;

public interface ReincarnationApi {
    /** 当前世代，0 = 从未转生 */
    long generation(ServerPlayer player);
    long generation(UUID playerId);
    /** 这一世的 token，别的 mod 可以用它当"本世数据"的键 */
    UUID lifeToken(UUID playerId, long generation);
    /** 新关系绑定；内部使用带世代的 EntityReference */
    EntityReference<ServerPlayer> bindLife(ServerPlayer player);

    boolean isReincarnating(ServerPlayer player);
    ReincarnationResult reincarnate(ServerPlayer player, ReincarnationRequest request);

    /** 历史记录（跨会话可读） */
    List<SoulRecord> history(UUID playerId);
    Optional<SoulRecord> record(UUID playerId, long generation);
}
```

**请求对象**：

```java
public record ReincarnationRequest(
    ResourceKey<Level> targetDimension, // 可持久化；执行前解析并校验维度存在
    Vec3 targetPos,
    float yRot, float xRot,
    Reason reason,              // CULTIVATION_FAILURE / VOLUNTARY / ADMIN / ...
    boolean keepGameMode,       // 是否保留游戏模式
    Identifier resetPolicy,    // 已注册的重置/保留规则，快照进事务
    CompoundTag extra           // 调用方自定义
) {}
```

**事件（给别的 mod 参与的机会）**：

```java
public abstract class ReincarnationEvent extends Event {
    // 事件字段/构造器略：accountId、txId、from/to(long)、request
    // Pre extends ReincarnationEvent implements ICancellableEvent：提交前可取消
    // Reset extends ReincarnationEvent：重建后的处理通知，不可取消
    // Post extends ReincarnationEvent：新世已保存完成后的通知，不可取消
}
```

**扩展契约（这是"保留历史 + 清得干净"的核心）**：

```java
public interface ReincarnationHandler {
    Identifier id();
    /** 执行顺序：数值小的先跑。引擎内置的用 -1000..0，mod 用正数 */
    int priority();

    /** ② 归档：把属于你的数据抄进档案 */
    default void archive(ServerPlayer player, LifeArchive archive) {}

    /** 重置/恢复必须幂等；上下文持有 txId、世代、策略快照、旧世归档 */
    default void reset(ServerPlayer fresh, ReincarnationContext context) {}

    /** 恢复时复用相同上下文；不能要求上次进程的 old Player 仍然存在 */
    default void recover(ServerPlayer fresh, ReincarnationContext context) {
        reset(fresh, context);
    }
}
```

处理器通过本模组专用的 `RegisterReincarnationHandlersEvent` 注册；除非实际建立 NeoForge 注册表，否则不使用通用 `RegisterEvent`。重复 ID 拒绝注册，启动后冻结顺序，事务记录参与的 ID/版本。需要的处理器缺失时保留待恢复状态，不能跳过后宣布完成。跨世保留项应在提交完成前由处理器恢复，Post 只作通知，避免崩溃后漏恢复。

建议内置模块（覆盖范围以已接入适配器为准）：

| 模块 | 负责 | 公开 API？ |
|---|---|---|
| `PlayerAttributeReset` | 遍历 `BuiltInRegistries.ATTRIBUTE` 逐个 `resetBaseValue` + `removeModifiers`；用 `assignBaseValues` 回默认表 | 部分（H5 需 AT 才能枚举已存在的属性实例） |
| `PlayerContainerReset` | 背包 `Inventory.clearContent()`、末影箱 `clearContent()`、选中槽、物品冷却（遍历 `BuiltInRegistries.ITEM` → `removeCooldown`） | ✅ |
| `PlayerProgressReset` | 经验三件套、附魔种子、配方书（`copyOverData(new ServerRecipeBook(...))`）、成就（**先删 JSON 再 `reload()`**）、统计（`resetStat` 全量 / 删 JSON + 重建） | 部分（H15/H16） |
| `PlayerBodyReset` | 生命/饥饿/饱和度/效果（`removeAllEffects`）/吸收/着火/氧气/姿势/骑乘/`resetCurrentImpulseContext`/睡眠 | ✅ |
| `PlayerLocationReset` | 重生点 `setRespawnPosition(null,false)`、`setLastDeathLocation(Optional.empty())`、传送门冷却、`getEnderPearls().clear()` + 撤销对应区块票据 | ✅ |
| `PlayerIdentityExtrasReset` | 自定义名字/可见/发光/静默/无重力/无敌、`entityTags().clear()`、`CustomData.EMPTY`、`NeoForgeData` 逐键清除、**`PlayerPersisted` 也要清**、`prefixes`/`suffixes`/`forcedPose` | ✅ |
| `AttachmentReset` | 归档后按类型策略 RESET / KEEP / HANDLER；在 Clone 复制后执行，并恢复保留项、同步客户端。不能无差别删除账号数据 | ✅ |
| `ScoreboardReset` | 按策略 `resetAllPlayerScores(holder)` + `removePlayerFromTeam(name)`；只删除已登记属于该玩家的伪玩家键，不能按 `#` 前缀清全服数据 | ✅ |
| `VillageReset` | 遍历村民 `getGossips().remove(uuid, type)`；`Raid` 的 `heroesOfTheVillage`（需 AT） | 部分 |
| `BossBarReset` | 按策略只移除该玩家；不能以 `removeAllPlayers()` 代替单玩家清理 | 在线玩家 ✅ |
| `MapReset` | `MapItemSavedData` 的 `carriedByPlayers` 陈旧条目；玩家装饰是**按名字**且**只存内存不入存档**（`MapItemSavedData.java:49-63,73,75`），重登即消失 | ✅ |
| `WaypointReset` | `waypointIcon().copyFrom(new Waypoint.Icon())`；`ServerWaypointManager.removePlayer/addPlayer` 由 `ServerLevel` 生命周期自动处理（`ServerLevel.java:1944,1978`） | ✅ |
| `EntityReferenceLifecycle` | §2.7 的扩展字段、Codec、旧主人转换、绑定、解析和 matches；使用原版缓存机制，统一拒绝旧世引用 | 集中 Mixin |
| `ReferenceNbtBridge` | §2.8 的节点内存戳、copy/转换传播、完整载荷统一打包/恢复；自动覆盖字段、列表和 Map，不逐关系登记 | 集中 Mixin + 通用 helper |
| `ReferenceDataSync` | 在 `SynchedEntityData.set` 集中识别引用/Optional 引用的世代变化，保证同 UUID 重绑实际替换服务端值 | 集中 Mixin |
| `RelationStateReset` | 驯服/坐下、愤怒与 AI 当前目标、珍珠及票据等附属状态；按具体实体处理。`OwnableEntity` 只有读取接口，写入需匹配具体实现，不能调用不存在的 `OwnableEntity.setOwner` | 部分 |
| `RawUuidReferenceReset` | Brain、拾取目标、治愈者、流言、袭击/监守者 UUID 集合等，按玩家和策略精确处理 | 部分 |
| `BlockEntityReset` | 蜂巢等存储边界验证引用戳；Vault 领奖与署名默认保留。试炼统计不能为了一个玩家整表 reset | 部分 |
| `DeferredRelationCleanup` | `EntityJoinLevelEvent` / 转换与物品释放适配；`ChunkEvent.Load` 只作辅助调度 | 事件 + 边界适配 |
| `PlayerStateResync` | 更新 `connection.player` 后同步所有重置状态；仅完整登出备选路径需要额外补 PlayerInfo | ✅ |

### 3.5 历史记录存储

```
<world>/
  data/mortal_cultivation/reincarnation_index.dat  # 主世界 SavedData，ID mortal_cultivation:reincarnation_index
  mortal_cultivation/transactions/<txId>.dat       # 独立持久化状态/归档清单/完成标记
  mortal_cultivation/history/<uuid>/
      0000/player.dat            # 原样归档的 NBT
      0000/stats.json
      0000/advancements.json
      0000/soul.dat              # SoulRecord：开始/结束时间、维度、死因、游玩时长、XP…
      0000/mods/<modid>.dat      # 各 ReincarnationHandler.archive() 的产物
      0001/...
```

- 账号 UUID 稳定；`SavedData` 用 `server.overworld().getDataStorage()` 挂载，各维度共享。路径由 SavedData ID 解析，不是随意指定在历史目录里的 `index.dat`（`.99 SavedDataStorage.java:65-66`、`Identifier.java:159-160`）。
- 归档写入是**纯追加**，永不覆盖历史（`generation` 单调递增）。
- 可选保留最近 N 世或压缩旧档；未完成事务依赖的归档不得清理。删除历史不能删除当前世代索引，否则旧世引用可能重新生效。每份归档记录格式版本、Minecraft/NeoForge/模组版本、数据版本和校验和。

### 3.6 与其它 mod 的兼容策略

- 按 UUID、玩家名、独立文件或数据库保存的状态不会因为扩展 `EntityReference` 自动清空；通过 `ReincarnationHandler` 明确登记归档、重置与保留规则。附件/NBT 未知键默认保留并列入覆盖报告；严格清除模式可以另外配置，不能承诺兼容所有模组。
- 第三方采用 `EntityReference` 及受支持的标准 NBT 基础路径时，引用持久化直接走通用桥接，不要求为每个字段注册适配器。`ReincarnationHandler` 用于定义业务状态如何归档、清空或保留，不能替代基础持久化层的通用性。
- 适配本世业务时优先使用 `LifeId` 或带戳引用；只接受 UUID 键的扩展可以使用 `lifeToken`，它不是可交给原版查找玩家实体的 UUID。
- `getEntity() == null` 不足以判断关系过期；不要让第三方把离线主人、暂时未加载的实体当作该删除的旧世关系。直接用 `getUUID()` 比较、自己缓存 `ServerPlayer`、重新 `of(UUID)` 的路径仍需适配。

**原内容工程的接入线索**：以下来自 `E:\Java\MortalCultivation` 及其相邻 MXT 源码，供后续集成适配参考；这些类不在当前独立 Reincarnation 模板中：

- 相邻 MXT 源码的 `LifeSpanService.reincarnate(LivingEntity)`（`E:\Java\MiXianTu\src\main\java\com\iafenvoy\mxt\runtime\cultivation\LifeSpanService.java:166`）当前原地重置修为等模块；私有重置流程在 `:248` 起。玩家的命令、脚本、寿元耗尽入口要统一交给转生协调器，避免先原地清空再归档或重复推进世代；普通非玩家生物的行为可维持原流程。该相邻源码仅作为接入线索，实施前还需对照当前依赖 JAR。
- 原 MortalCultivation 工程 `MCAttachments.java:36-40` 的 `RESOURCE_BONUS` 带 `copyOnDeath()`。资源值和已应用加成账本必须一起重置或一起保留，再重新计算；只删账本却保留已加成资源会重复加成（`ResourceBonusService.java:145-166`）。
- 转生策略应明确修为、小境界、天劫、寿元、灵根、体质、功法、魂魄、技能来源、Curios 容器的保留规则，不能只用一个“删全部附件”代替。`keepSoul` 等已有规则应在归档与 Clone 后由适配器应用；权威世代只由协调器修改。

### 3.7 可选的"真·UUID 轮换"实验模式（明确不推荐）

如果将来确实需要"连 mod 都绝对找不到"的强隔离，可以在方案 3 之上加一个**实验开关**，但要如实记录代价：需要 Mixin 登录链路、需要维护 名字→当前世 UUID 映射、封禁/白名单/OP 语义需要一并接管、卸载 mod 会导致数据孤儿化、客户端会话中途不可变更、文件按世堆积。**结论：把方案 3 做好，就不需要它。**

---

## 4. 落地路线图

| 阶段 | 内容 | 风险 |
|---|---|---|
| **A. 通用引用桥接** | 已实现集中式桥接、世代索引与 API；实际引用测试见 06；07 补上输出快照和抽取分支前消费完整元数据表的共享边界修正 | 客户端、蜂巢完整搬运、版本升级仍需环境验收 |
| **B. 转生主流程** | 已实现状态机、内存归档、清缓存、原版 respawn、连接替换、重置/同步和恢复；真实服务端流程测试见 07 | 已初始化判据防重复清空；真实断电/每阶段强杀仍须验收 |
| **C. 关系与模组适配** | 已实现已加载及后加载宠物/马/持久愤怒清理、珍珠处理和版本化处理器 API；未知数据默认保留，裸 UUID 默认按账号事实保留 | MXT/Saturn/Curios 的具体语义由所属工程接入处理器 |
| **D. 历史与体验** | 已实现不可变 SoulRecord 历史、当前世摘要、在线/离线名字/UUID 文字查询、分页/详情及管理员转生命令 | 图形界面、归档导出和历史裁剪属于可选后续功能 |

**测试重点**：
1. 转生后**重启服务器再登录**，确认回到"新世"而不是旧世（这是 §1.2 那个坑的验收点）。
2. 前世宠物在已加载、未加载、跨区块和跨容器情况下都不能绑定新世玩家；新驯服宠物及普通死亡后的本世宠物仍保留。失效表示断开关系，不表示杀死实体。
3. 转生前后 OP / 白名单 / 封禁行为完全一致（§1.3 的验收点）。
4. 转生后标签页、皮肤、聊天、计分板、Boss 栏、地图标记均正常。
5. 转生瞬间断电（杀掉进程）后能恢复，不出现"数据既没归档也没清空"。

---

## 5. 关键证据索引（速查）

| 主题 | 位置 |
|---|---|
| UUID 字段与 setter | `Entity.java:280-281`（字段）、`:3183-3186`（`setUUID`）、`:3197-3200`（`getScoreboardName`） |
| 构造时/读档时强制回写 UUID | `Player.java:180-183`、`Player.java:646-649` |
| `gameProfile` 不可变 | `Player.java:168` |
| 登录链路 | `ServerLoginPacketListenerImpl.java:118-133, 199-207, 243-251`；`PrepareSpawnTask.java:178-201` |
| 玩家数据读写路径 | `PlayerDataStorage.java:33-48`（写用实体 UUID）、`:64-87`（读用 profile UUID）；`LevelResource.java:4-7` |
| `PlayerList` 的三张表 | `PlayerList.java:117, 122-123`；`:216`；`:338-345`；`:441` |
| 唯一的重建入口 | `PlayerList.java:397-472` |
| `restoreFrom` 的拷贝清单 | `ServerPlayer.java:1702-1750` |
| stats/advancements 键不一致 | `PlayerList.java:773-778` vs `:804-805` |
| 实体索引 | `EntityLookup.java:17, 32-45`；`PersistentEntitySectionManager.java:35, 62-64, 476` |
| 玩家注册去重 | `ServerLevel.java:1000-1011` |
| 权限键 | `ServerOpList.java:64-66`；`UserWhiteList.java:59-61`；`UserBanList.java:27-29` |
| 计分板按名字 | `Player.java:1761-1762`；`Scoreboard.java:193, 285` |
| 村庄/袭击 | `GossipContainer.java:33, 130, 152`；`Raid.java:108, 807` |
| 主人引用 | `OwnableEntity.java:9`；`TamableAnimal.java:42, 59-73, 159-168`；`AbstractHorse.java:126, 178`；`NeoForge` 无 |
| 附件 | `AttachmentHolder.java:40-51, 105-115`；`NeoForgeRegistries.java:43`；`AttachmentInternals.java:56-59` |
| `NeoForgeData` | `Entity.java:3926-3934, 2023, 2087` |
| 客户端身份 | `ClientHandshakePacketListenerImpl.java:174-185`；`ClientPacketListener.java:390, 432-434, 1290-1301, 2039`；`LocalPlayer.java:172`；`AbstractClientPlayer.java:38-65`；`DefaultPlayerSkin.java:43-49` |
| 客户端签名/聊天 | `Minecraft.java:2606-2608, 2400-2406`；`ChatListener.java:198-200, 243-250` |
| 可用事件 | `PlayerRespawnPositionEvent.java:55, 64, 100`；`PlayerEvent.Clone`（`PlayerEvent.java:245-266`）；`ChunkEvent.Load`（`ChunkEvent.java:46`） |
| 本次复核：引用缓存/匹配 | `.99 EntityReference.java:55-89`；`Fox.java:698`；`NeutralMob.java:99-108` |
| 本次复核：Codec 读写分流 | `.99 EntityReference.java:20-31,92-99,120-133`；`TamableAnimal.java:59-73`；`AbstractHorse.java:794,804` |
| 本次复核：网络 Codec | `.99 EntityDataSerializers.java:99-101`，独立于磁盘 Codec |
| 本次复核：调用者更新玩家 | `.99 ServerGamePacketListenerImpl.java:1904,1913` |
| 本次复核：持久化与回退 | `.99 PlayerDataStorage.java:33-49,77-87`；`SavedDataStorage.java:228-244` |
| 本次复核：加载事件时机 | `.99 ChunkEvent.java:38-45`；`EntityJoinLevelEvent.java:23-25` |
| 通用桥接：NBT 复制与转换 | `.99 IntArrayTag.java:87-90`；`CompoundTag.java:418-421,459-465`；`NbtOps.java:123-124` |
| 通用桥接：完整载荷边界 | `.99 TagValueOutput.java:159`；`TagValueInput.java:40-45`；`SavedDataStorage.java:95-105,220-225` |

---

## 6. 方案选择与 EntityReference 的答复

1. **方案 1 更好吗？** 不。它在 MC 26.1.2 里**技术上不成立**（UUID 由 GameProfile 每次登录重推，无处可插且不可持久），而且**语义错误**（权限、封禁、存档、客户端都是按"账号 UUID"记账的）。更糟的是"只换实体 UUID"这个最省事的变体会让**旧世数据在下次登录复活**（§1.2）。
2. **方案 2 更好吗？** 方向正确，但"复制出来再原地清空活着的玩家"不能成立（大量 `private final` 状态 + `PlayerList` 的 private Map）。**正确做法是复用 `PlayerList.respawn` 的销毁-重建生命周期**，再按显式清单清空——这样"清不干净"的问题会从"必然漏项"变成"一份可枚举、可扩展、可测试的清单"。
3. **完善后的方案？** 稳定账号 UUID，在 `EntityReference` 中保存绑定时的玩家世代，使用 §2.8 的通用 NBT 桥接持久化；在解析及 matches 时使旧世关系失效。以引擎重建和可恢复事务重置玩家，裸 UUID 与玩法附属状态按策略处理；加载事件辅助物理清理，历史按账号与世代追加归档。
4. **能修改 CODEC 并兼容原版吗？** 能包装 Codec 并保留原 UUID 值，世代先附在 NBT 节点的内存字段，再在完整载荷扩展区统一保存。原版忽略未知扩展键；把 UUID 数组改成对象则仍不兼容原版 UUID 解码。单独修改叶子不足以持久化，还要集中接入复制、转换与完整输入/输出边界。
5. **是否仍需逐实体、逐字段兼容？** 标准 `EntityReference` 的字段、列表和 Map 持久化无需逐个适配，路径由树遍历自动发现；`store` 与旧主人读取旁路在基础类中一次修正。裸 UUID、任意第三方自定义序列化和具体玩法副作用不在此自动覆盖范围。通用算法原型通过 21 项检查，实际 Mixin 首轮通过 7 项专服 GameTest，更多客户端/搬运链路见 06 的剩余范围。

---

## 附录 A：清空清单速查（按存储位置分组）

### A.1 存进玩家 `.dat`（`<world>/players/data/<uuid>.dat`）——**全部按 UUID 键**

`Entity` 层：位置 / 朝向 / 动量 / `fallDistance` / 着火 tick / 氧气 / `onGround` / 无敌 / 传送门冷却 / UUID / 自定义名 / 静默 / 无重力 / 发光 / `ticksFrozen` / **计分板 tag 集合**（`entityTags()` 返回活集合）/ `CustomData` / **NeoForge attachments** / **`NeoForgeData`** / 乘客 / 载具(`RootVehicle`)。

`LivingEntity` 层：生命 / 吸收 / `hurtTime` / `deathTime` / `lastHurtByMob`+时间戳 / **`lastHurtByPlayer`+记忆时间** / `lastHurtMob` / **药水效果** / **属性基础值+修饰符** / 装备 / 鞘翅飞行 / 睡床位置 / **定位条图标 `locator_bar_icon`** / `current_explosion_impact_pos`。

`Player` 层：背包 / 选中槽 / **末影箱** / 睡眠计时 / **经验三件套** / **`XpSeed`(附魔种子)** / `Score` / **食物四件套** / **abilities** / **`LastDeathLocation`**。

`ServerPlayer` 层：**`warden_spawn_tracker`** / 游戏模式(含 `previousGameType`) / `seenCredits` / **`enteredNetherPosition`** / **`recipeBook`** / `Dimension` / **`respawn` 重生点(含 forced)** / `spawn_extra_particles_on_fall` / **`raid_omen_position`** / **`ender_pearls`（含每颗珍珠的维度）** / **`ShoulderEntityLeft/Right`** / 载具(`RootVehicle`)。

### A.2 不在 `.dat` 里

| 状态 | 位置 | 键 |
|---|---|---|
| 统计数据 + `<uuid>.json` | `StatsCounter.java:9`；`ServerStatsCounter.java:52-53` | UUID（**有按名字的旧文件回退迁移**，见 `PlayerList.java:781-802`） |
| 成就 + `<uuid>.json` | `PlayerAdvancements.java:52-54` | UUID |
| `PlayerList` 两张缓存 | `PlayerList.java:122-123` | UUID（**private Map**） |
| 计分板分数 / 队伍 | `Scoreboard.java:35,38`；`PlayerTeam.java:25` | **玩家名** |
| 计分板存档 | `data/scoreboard.dat`（`ScoreboardSaveData.java:13-18`） | 名字字符串 |
| 自定义 Boss 栏 | `CustomBossEvent.java:23`；`data/custom_boss_events.dat` | UUID（Set） |
| 袭击"村庄英雄" | `Raid.java:108`（`Raids` SavedData，**每维度一份**） | UUID（Set，无 remover） |
| 村民流言 | `GossipContainer.java:33`（在村民 NBT 里） | UUID（Map） |
| 监守者愤怒 | `AngerManagement.java:49`（在监守者 NBT 里） | UUID（Map，`clearAnger` 不清） |
| 保险箱/试炼刷怪笼 | `VaultServerData.java:30` / `TrialSpawnerStateData.java:45` | UUID（Set） |
| 玩家头颅 | `SkullBlockEntity.java:25` | 名字 + UUID（`ResolvableProfile`） |
| 地图玩家标记 | `MapItemSavedData.java:75` | **名字**（且**只存内存，不入存档**） |
| 定位条连接 | `ServerWaypointManager.java:19-21` | 活对象（不落盘） |
| 单机房主 UUID | `PrimaryLevelData.java:39`（`level.dat` 里**唯一**的玩家 UUID，`"singleplayer_uuid"`） | 无公开 setter |

### A.3 三条"其它 mod 数据"的通道（你的 `ReincarnationHandler` 必须覆盖）

1. **`Entity#getPersistentData()`（NBT 键 `NeoForgeData`）** —— 按已登记策略归档/保留/删除；遍历待删键的快照再修改（**注意：如果没有数据，调用它反而会创建一个空 tag 并被写进存档**）。
2. **Data Attachments（NBT 键 `neoforge:attachments`）** —— 遍历类型 + `hasData` + `removeData`，有能力按策略删除；`PlayerEvent.Clone` 时会按复制策略搬运，所以顺序为“先重建、后应用重置/保留规则”，不保证任意第三方外部状态也被清理。
3. **mod 自己的 `SavedData` / 独立文件** —— `SavedDataStorage.cache` 是 private（`SavedDataStorage.java:43`），**无法通用枚举**。所以必须靠 `ReincarnationHandler` 让 mod 主动登记；对没登记的 mod，只能靠它是否把数据放在上面两条通道里。

---

## 附录 B：建议的验收测试（每一条都对应上面某个具体坑）

| # | 测试 | 对应坑 |
|---|---|---|
| 1 | 转生 → **重启服务器** → 再登录，确认是新世而不是旧世 | §1.2（写/读身份不一致会让旧世复活） |
| 2 | 转生前后 `/op`、白名单、封禁行为完全一致 | §1.3（权限身份分裂） |
| 3 | 转生后 OP 仍在、封禁仍生效、`usercache.json` 正常 | §1.3 |
| 4 | 已加载宠物旧关系立即失效；未加载宠物在首次使用关系时也不能解析到新世玩家 | §2.7、§3.3 |
| 5 | 转生后新驯服的宠物在卸载/加载后仍属于本世；宠物移动到曾清理过的区块也按引用戳判定 | §3.3，证明正确性不依赖区块标记 |
| 6 | 转生后标签页里玩家仍在（自己 + 其他人视角） | §2.3 坑 1（`remove()` 删条目、`respawn()` 不补） |
| 7 | 重启后计分板/Boss 栏/袭击数据按策略处理，其他玩家记录保留，Vault 不能因转生重复领奖 | §3.3、附录 A.2 |
| 8 | 转生后蜂巢（含潜影盒里的蜂巢）里那只被玩家打过的蜜蜂不再把玩家视为愤怒目标 | §2.4(b) 隐藏通道 1 |
| 9 | 在各事务阶段强杀进程，重启按同 txId 向前恢复，不重复推进世代或抹掉已完成新世进度 | §3.2 持久化状态机 |
| 10 | 转生前把物品放进末影箱 / 把东西挂在肩上 → 转生后都没了 | §2.2（`restoreFrom` 无条件拷贝项） |
| 11 | 转生后成就、统计、配方书确实空了（含重启后） | H15/H16 |
| 12 | 转生后客户端皮肤、聊天、游戏模式回调、自己发的消息不被屏蔽 | §1.7 |
| 13 | 通用 NBT 桥接真实二进制往返保留 UUID＋世代，原 UUID 字段形状不变；0 与无戳严格区分，负世代/损坏元数据拒绝 | §2.8；JSON 后端另行验收 |
| 14 | 旧 UUID、旧主人名分别加载；狐狸 TRUSTED 列表和 neutral angry_at 的直接 Codec 路径自动保留世代，无字段专用代码 | §2.7.1、§2.8，覆盖不止 store/read |
| 15 | 宠物/马 Owner、投射物 Owner、TNT owner 经 store→read/旧主人转换后戳不丢失 | §2.7.1 的旁路入口 |
| 16 | 引用缓存命中活玩家时推进世代，两个 getEntity 重载和 matches 均拒绝旧世 | §2.7.3，覆盖缓存快路径 |
| 17 | 本世玩家暂时离线时关系保留；重登/普通死亡/跨维度后本世引用仍可重新解析 | 不把未解析等同于过期 |
| 18 | 创世代 0 的未加载旧宠物，在玩家已转到 1 后首次加载时失效，不能被自动补戳“续期” | 旧档迁移规则 |
| 19 | 末影水晶等非玩家引用保持原版 UUID 编码、解析和匹配；客户端不读取服务端权威索引 | 泛型与逻辑侧边界 |
| 20 | 同 UUID 新世代重新绑定后服务端实际替换旧引用、客户端同步正确；不依赖 equals 自动判脏 | §2.7.4 |
| 21 | 蜂巢放入容器、再跨维度释放蜜蜂；转换/复制实体时保持引用戳 | 搬运与复制边界 |
| 22 | UUID-only 新绑定显式捕获今世；反序列化和旧名迁移保留原有/初始世代 | 防止绑定与读取混淆 |
| 23 | 转生后移动/挖掘/交互实际作用于 fresh，旧玩家不能继续被连接处理 | §2.3，connection.player |
| 24 | keepInventory 开启、旁观者、末影珍珠在飞行中、正在骑乘时转生，按规则重置且无旧票据残留 | 生命周期附加路径 |
| 25 | 注入 IO 失败/处理器异常，事务不错误标成完成；归档包含未自动保存的最新进度 | §3.2，不能只看 mtime/setDirty |
| 26 | MXT 保留灵根/功法/魂魄后，技能与资源重新派生且不重复加成；Curios 与额外容器有独立规则 | §3.6 |
| 27 | 连续转生两次、预提交取消、重复提交同 txId、多玩家同时转生，互不污染世代和归档 | 幂等与账号级互斥 |
| 28 | 若提供保留宠物策略，未加载宠物同样按种类显式重绑；投射物不能因此获得豁免 | §3.3，可选功能门槛 |
| 29 | 最小专服和客户端实际加载 Mixin，确认静态 Codec 缓存、目标重载、网络同步均生效 | 编译与纯 Codec 测试不覆盖注入 |
| 30 | 同一份含统一元数据表的完整 NBT，桥接恢复世代、原版字段 Codec 读出相同 UUID；旧档无扩展键仍能读取 | §2.8；A 模式 NBT/JSON 为历史候选验证 |
| 31 | 未知扩展键值损坏时原版仍能读取原字段，扩展解码器按契约拒绝损坏元数据；把 Owner 改成对象时原版 UUID Codec 仍拒绝 | 额外键兼容与已知字段类型校验的区别 |
| 32 | 删除/替换引用、列表重排、嵌套子树移动后统一重建路径；同 UUID 不同世代不合并，旧表不能覆盖新节点戳 | 通用元数据一致性，无字段专用清理 |
| 33 | 原版 NBT copy/merge 和 NBT→NBT 转换保留节点戳；嵌套载荷打包/恢复幂等，后台保存使用私有快照 | §2.8 的实际基础钩子，算法原型不能替代 |
| 34 | 任意新字段、列表和嵌套业务 Map 使用引用 Codec，无须注册字段名即可往返；业务 Map 内不出现额外元数据条目 | §2.8 的通用覆盖与根封装限制 |
| 35 | SavedData 等绕过 ValueInput/Output 的基础存储使用同一 helper，首次引用解码前恢复戳；损坏/未知版本表不能续为今世 | 基础存储输入/输出必须成对覆盖 |
| 36 | 同 UUID 换世代后，重复输入边界不应用旧表；嵌套表冲突及解码错误不会在另存后变成无戳旧档 | 原始表消费、冲突隔离与错误状态传播 |

---

## 附录 C：调研产物与证据来源

- 主文档（本文）：`转生系统-MC源码调研与设计方案.md`
- 原始调研记录（逐行引用、可直接检索）：
  - `docs/转生系统调研/01-身份与登录追踪.md` —— 登录链路、UUID 推导、权限/封禁键、`usercache`
  - `docs/转生系统调研/02-实体UUID索引.md` —— 30+ 个按 UUID 索引的结构、`EntityLookup`/`knownUuids` 失效机制、发包面
  - `docs/转生系统调研/03-玩家引用审计.md` —— 宠物/弹射物/村民/方块实体/命令层的"指向玩家的引用"全表
- 后续研究与当前决策：
  - [04-原版结构保留与引用世代存储](转生系统调研/04-原版结构保留与引用世代存储.md) —— 附件/槽位账本与外部旁库候选，保存顺序、复制与搬运证据。
  - [05-通用EntityReference持久化桥接](转生系统调研/05-通用EntityReference持久化桥接.md) —— **当前推荐主线**，替代逐字段持久化适配；集中入口、支持契约与 21 项原型验证。
  - [06-通用引用桥接实现与验证](转生系统调研/06-通用引用桥接实现与验证.md) —— 实际 Mixin、世代索引、API、专服 GameTest 及后续实现范围。
  - [07-转生流程与历史查询实现](转生系统调研/07-转生流程与历史查询实现.md) —— 当前独立模组的转生事务、恢复判据、默认策略、处理器契约、文字查询和服务端验证。
  - [08-Saturn玩家视图与信息栏接口设计](转生系统调研/08-Saturn玩家视图与信息栏接口设计.md) —— 通用只读玩家视图、信息文档、模块扩展及三个现有界面的接入方案；Saturn 基础解析与展示已实现，业务接入待完成。
- 原报告基线为 `.114`；本次复核基线是工程实际使用的 `minecraft-patched-26.1.2.99-sources.jar` + `neoforge-26.1.2.99-sources.jar`。新增源码证据见 §5。
- 上一轮在 MortalCultivation 工程中提取 §2.7.2 **B 模式**的 `ReferenceData` 代码，使用 `.99` 依赖及 Java 25 运行独立验证，**9 项检查通过**：NBT/JSON 往返、旧 UUID 读取、无戳仍写原版格式、世代 0 区分、负世代读写拒绝及缺字段结构拒绝。脚本/日志仍位于 `E:\Java\MortalCultivation\.gradle\reincarnation-source-review\`，下列命令需在该旧工程执行；它不证明 A 或当前通用方案的原版兼容性，也不代表 Mixin/游戏验证完成。

  ```powershell
  .\gradlew.bat --offline --no-configuration-cache -I .gradle/reincarnation-source-review/codec-probe.init.gradle verifyReincarnationCodecProbe
  ```

- 本轮在 **Reincarnation** 工程中提取 §2.7.2 **A 模式**的 `ReferenceStamp` / `CompatibleOwnerData`，使用当前 `.99` 依赖与 Java 25 编译运行，**13 项检查通过**：原 UUID 字段形状、旁存结构、原版与扩展 NBT/JSON 读取、旧档缺字段、无关未知键、原版忽略损坏的未知值、扩展拒绝损坏/负世代、原版拒绝 Owner 类型变化，以及原版重编码不保留未知键。代码和日志位于新工程 `.gradle/reincarnation-codec-compat-review/`；尚未接入 Mixin 或游戏验证。

  ```powershell
  .\gradlew.bat --offline --no-configuration-cache -I .gradle/reincarnation-codec-compat-review/compat-probe.init.gradle verifyCompatibleCodecProbe
  ```

- 当前推荐方案的独立原型位于 `.gradle/generic-ref-nbt-probe/`，在 Reincarnation 工程使用真实原版 EntityReference Codec 委托编解码，**21 项检查通过**；自动发现六条标量/列表/Map 引用，验证同 UUID 不同世代、压缩 NBT 往返、复制传播、移动后重建路径、幂等与目标校验。身份表模拟 Mixin 字段，通用复制 helper 模拟 copy 钩子；实际 Mixin、NbtOps 转换接线和专服/客户端尚未验证。

  ```powershell
  .\gradlew.bat --offline --no-configuration-cache -I .gradle/generic-ref-nbt-probe/generic-probe.init.gradle verifyGenericReferenceNbtProbe
  ```

- 版本注意：26.1.2 的存档路径已改为 `players/data`、`players/stats`、`players/advancements`（`LevelResource.java:4-7`）；1.20 时代的 `world/playerdata`、`world/stats`、`world/advancements` 是**旧路径**，网上大部分教程已经过时。
- 已确认**不存在**的旧 API（别去追）：`ownerUUID`/`getOwnerUUID()`、`Player.xCloak/yCloak/zCloak`、`Player.itemInHandTick`、`Player.leashData`（`Player` 不是 `Leashable`，`Player.java:126`）、`Entity.wasOnGround`、`Entity.walkDist`（改叫 `moveDist`）、`Entity.setNoPhysics`、`ServerPlayer.setGameProfile`、`ServerPlayer.spawnChunkRadius`、`getPlayerForLogin`、`GameProfileCache`（改叫 `CachedUserNameToIdResolver`）、`Raid.getHeroesOfTheVillage()`、`SculkShriekerBlockEntity.warnedPlayers`。

