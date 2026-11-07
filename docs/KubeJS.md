# KubeJS 寿元与转世

安装 KubeJS 后，Reincarnation 提供 `ReincarnationLifespan` 和 `ReincarnationEvents`，不需要安装 MiXianTu。KubeJS 是可选依赖，脚本写在 `kubejs/server_scripts/`。

## 寿元绑定

所有数值均为游戏刻，读操作不创建账本，写操作要求服务端主线程和活体目标。自然老化关闭时仍可显式写入与转世。

| 方法 | 行为 |
| --- | --- |
| `ReincarnationLifespan.remaining(entity)` | 剩余刻数；未记账或无限寿元为 -1 |
| `ReincarnationLifespan.total(entity)` | 本世累计获得刻数；未记账为 -1，关闭账本为 0 |
| `ReincarnationLifespan.set(entity, ticks)` | 同时设置剩余与总额，拒绝负数 |
| `ReincarnationLifespan.add(entity, ticks)` | 正数同时增加剩余和总额，负数仅扣剩余 |
| `ReincarnationLifespan.unlimited(entity)` | 关闭账本，直到显式写入有限寿元 |
| `ReincarnationLifespan.reincarnate(entity)` | 使用配置的模式排队转世 |

客户端读操作返回 -1，写操作返回 `SERVER_ONLY`。写操作返回 Saturn 的 `Result`，`getFailure()` 为 Optional，空值表示成功。排队转世的成功只表示请求已接受，最终结果由事件通知；完整换世会替换玩家对象。

```js
PlayerEvents.loggedIn(event => {
  const player = event.player
  if (ReincarnationLifespan.total(player) < 0) {
    ReincarnationLifespan.set(player, 60 * 24000)
  }
})
```

## 事件

两个服务端事件均有 `getType()`、`getPhase()`、`isCancellable()`、`getEvent()`；`getEvent()` 返回原生事件。`event.cancel()` 立即停止当前脚本监听链，仅 Pre 会取消底层操作。

| 事件 | 阶段 | 原生事件与语义 |
| --- | --- | --- |
| `ReincarnationEvents.lifespanEnd` | `Pre`、`Post` | `getEntity()`、`getSpirit()` 属于耗尽的旧世。Pre 可取消并续命；只取消但未续命则关闭账本。Post 另有 `getOutcome()`。显式转世不发布此事件。 |
| `ReincarnationEvents.lifespanRebirth` | `Pre`、`Post`、`Failed` | `getEntity()`、`getSpirit()`、`getMode()`。Pre 可取消；Post 给出新实体和新账本；排队请求失败或取消时 Failed 提供 `getFailure()`。 |

```js
ReincarnationEvents.lifespanEnd(event => {
  if (event.getPhase() !== 'Pre') return
  const entity = event.getEvent().getEntity()
  if (!entity.getTags().contains('example:immortal')) return
  ReincarnationLifespan.unlimited(entity)
  event.cancel()
})

ReincarnationEvents.lifespanRebirth(event => {
  if (event.getPhase() !== 'Post') return
  const entity = event.getEvent().getEntity()
  // Continue with the replacement entity after a full player rebirth.
})
```

`lifespanRebirth` 覆盖寿尽直接转世和寿元 API 的显式转世。`/reincarnation rebirth` 与死亡自动转生使用完整转生事务，不发布这组寿元事件。

## MiXianTu 脚本迁移

`MxtLifespan` 改为 `ReincarnationLifespan`，原有五个方法的参数与返回值保持一致。`MxtEvents.lifespanEnd`、`MxtEvents.lifespanRebirth` 改为对应的 `ReincarnationEvents` 事件，原有 Pre/Post/Failed 阶段与包装方法保持一致。MiXianTu 不再重复注册寿元绑定或事件。
