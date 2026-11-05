# Entity UUID plumbing

Research target: what goes stale when a **live** entity's `uuid` field is mutated at runtime in Minecraft 26.1.2 (decompiled, NeoForge patches applied).

Source trees:
- MC: `E:\Java\_mcsrc_26.1.2\net\minecraft\...`
- NeoForge API: `E:\Java\_nfsrc_26.1.2\net\neoforged\...`

All citations are `path:line` relative to those roots. Paths below are abbreviated as `net/minecraft/...` for MC and `net/neoforged/...` for NF.

**Headline answer.** The UUID is a *pure data field* on `Entity` (`Entity.setUUID` touches nothing else), but it is used as a **hash key / identity token** in at least ten independent server-side registries. Mutating it does not move the entity in any spatial structure (sections, chunk tracking, entity-id maps all survive because they key on `Entity.getId()`, object identity, or `Class`), but every `UUID`-keyed structure is left holding an entry that no longer matches what the entity reports. Four of them are **unremovable leaks**, one is a **crash path**, and the entity remains *reachable by its old UUID* forever. Correct-at-runtime UUID mutation would require unregister + re-register in all of the members of the table in §7.

---

## 1. `Entity.java` — the field, its I/O, and its accessors

### Field and initialization

`net/minecraft/world/entity/Entity.java:280-281`:

```java
    protected UUID uuid = Mth.createInsecureUUID(this.random);
    protected String stringUUID = this.uuid.toString();
```

Two fields, always written together. The initial UUID is **not** assigned by `EntityType.create` — it comes from the instance field initializer, i.e. a random UUID generated at construction time from the entity's `RandomSource` (`net/minecraft/util/Mth.java:320-324`):

```java
    public static UUID createInsecureUUID(RandomSource random) {
        long most = random.nextLong() & -61441L | 16384L;
        long least = random.nextLong() & 4611686018427387903L | Long.MIN_VALUE;
        return new UUID(most, least);
    }
```

Note `EntityType.create(Level, EntitySpawnReason)` does nothing about the UUID — `net/minecraft/world/entity/EntityType.java:1492-1494`:

```java
    public @Nullable T create(Level level, EntitySpawnReason reason) {
        return !this.isEnabled(level.enabledFeatures()) ? null : this.factory.create(this, level);
    }
```

The `Entity` constructor begins at `net/minecraft/world/entity/Entity.java:305` (`public Entity(EntityType<?> type, Level level)`); constructor code does not touch `uuid`. So at construction the UUID is already "live" in the sense that the object is registered anywhere only after `save`/`load` may overwrite it.

### Complete list of `this.uuid` / `this.stringUUID` / accessor reads and writes in `Entity.java`

| Line | Code | Kind |
|---|---|---|
| 280 | `protected UUID uuid = Mth.createInsecureUUID(this.random);` | field init |
| 281 | `protected String stringUUID = this.uuid.toString();` | field init |
| 1985 | `output.store("UUID", UUIDUtil.CODEC, this.getUUID());` | **read** (save) |
| 2070-2073 | `input.read("UUID", UUIDUtil.CODEC).ifPresent(id -> { this.uuid = id; this.stringUUID = this.uuid.toString(); });` | **write** (load) |
| 3183-3186 | `public void setUUID(UUID uuid) { this.uuid = uuid; this.stringUUID = this.uuid.toString(); }` | **write** (public setter) |
| 3189-3191 | `public UUID getUUID() { return this.uuid; }` | read |
| 3193-3195 | `public String getStringUUID() { return this.stringUUID; }` | read |
| 3198-3200 | `public String getScoreboardName() { return this.stringUUID; }` | read (of `stringUUID`) |
| 3363 | `return new HoverEvent.ShowEntity(new HoverEvent.EntityTooltipInfo(this.getType(), this.getUUID(), this.getName()));` | read via `getUUID()` |
| 3796 | `this.setUUID(packet.getUUID());` (inside `recreateFromPacket`) | **write** (client-side packet path) |

That is the **entire** surface: `grep -n "this\.uuid\|this\.stringUUID"` in that file returns exactly 15 matches, all listed above. In particular `setUUID` is the *only* mutator besides the `load` lambda, and it is unguarded — no notification, no `setChanged`, no re-registration.

The two reads outside the obvious accessors matter:
- `getScoreboardName()` (`:3198-3200`) returns `stringUUID`, so for **non-player** entities the scoreboard is keyed by the entity UUID string. (`Player` overrides this: `net/minecraft/world/entity/player/Player.java:1760-1763` returns `this.getGameProfile().name()`, so players are immune here.)
- `createHoverEvent()` (`:3363`) embeds the live UUID in item/NBT hover data.

### NBT: `saveWithoutId` / `load`

`Entity.java:1969` is `public void saveWithoutId(ValueOutput output)`. The UUID is written at `:1985` with the **int-stream codec**, not a string and not `NbtUtils.loadUUID`:

```java
            output.store("UUID", UUIDUtil.CODEC, this.getUUID());
```

`Entity.java:2048` is `public void load(ValueInput input)`. The UUID is read at `:2070-2073`:

```java
            input.read("UUID", UUIDUtil.CODEC).ifPresent(id -> {
                this.uuid = id;
                this.stringUUID = this.uuid.toString();
            });
```

- `TAG_UUID` constant exists at `Entity.java:174`: `public static final String TAG_UUID = "UUID";` — but note `saveWithoutId` uses the **literal** `"UUID"` at `:1985`, not the constant.
- There is **no** `NbtUtils.addCurrentDataVersion` and **no** `NbtUtils.loadUUID` call in this class; `NbtUtils` is not imported. The import is `net/minecraft/core/UUIDUtil` (`Entity.java:45`).
- `UUIDUtil.CODEC` is the four-int stream codec: `net/minecraft/core/UUIDUtil.java:23` (`public static final Codec<UUID> CODEC = Codec.INT_STREAM ...`). `UUIDUtil.STRING_CODEC` (`:27`) and `STREAM_CODEC` (`:42`) are the string / binary-wire variants used elsewhere.
- Because `input.read(...).ifPresent(...)` is optional, an entity NBT blob **without** a `UUID` tag silently keeps the random constructor UUID. That is exactly the "restore" step for legacy/entity data in §5.
- `load` also has a natural guard rail for the command path, `net/minecraft/server/commands/data/EntityDataAccessor.java:53-57`:

```java
            UUID uuid = this.entity.getUUID();

            try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(this.entity.problemPath(), LOGGER)) {
                this.entity.load(TagValueInput.create(reporter, this.entity.registryAccess(), tag));
                this.entity.setUUID(uuid);
```

i.e. vanilla `/data merge entity` **deliberately restores the pre-merge UUID** so that NBT editing cannot perform the mutation we are studying. That is the in-tree confirmation that arbitrary UUID changes are considered unsafe by the vanilla code itself.

---

## 2. Entity lookup by UUID

### `EntityLookup` — the authoritative `UUID -> Entity` map

`net/minecraft/world/level/entity/EntityLookup.java:14-53` (whole class is 58 lines):

```java
public class EntityLookup<T extends EntityAccess> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Int2ObjectMap<T> byId = new Int2ObjectLinkedOpenHashMap<>();
    private final Map<UUID, T> byUuid = Maps.newHashMap();
```

Yes: `byUuid` is `Map<UUID, T>` (a Guava `HashMap`), while `byId` is `Int2ObjectLinkedOpenHashMap`. The two maps are **only** kept in sync inside `add`/`remove`:

```java
    public void add(T entity) {
        UUID uuid = entity.getUUID();
        if (this.byUuid.containsKey(uuid)) {
            LOGGER.warn("Duplicate entity UUID {}: {}", uuid, entity);
        } else {
            this.byUuid.put(uuid, entity);
            this.byId.put(entity.getId(), entity);
        }
    }

    public void remove(T entity) {
        this.byUuid.remove(entity.getUUID());
        this.byId.remove(entity.getId());
    }

    public @Nullable T getEntity(int id)   { return this.byId.get(id); }
    public @Nullable T getEntity(UUID id)  { return this.byUuid.get(id); }
    public int count()                     { return this.byUuid.size(); }
```

Consequences of a post-registration UUID mutation:
- `remove(entity)` at `:42-45` calls `entity.getUUID()` — the **new** UUID — so `byUuid.remove(newUuid)` is a no-op and the **old** key is stranded. `byId.remove(entity.getId())` still succeeds, so the id-keyed half stays consistent while the UUID half does not.
- `getEntity(newUuid)` returns `null` (nothing was ever put under it).
- `getEntity(oldUuid)` still returns the live entity — a *phantom* lookup: `Level.getEntity(UUID)` keeps resolving to an entity that no longer claims that UUID.
- `count()` (`:55-57`) reports `byUuid.size()`, so stats/debug counters are now wrong even though `byId` is right. `PersistentEntitySectionManager.gatherStats()` (`:369-371`) prints this as the second field, and `ServerLevel` surfaces it at `:1721` and `:1865`.
- Because `add` gates on `byUuid.containsKey`, a *new* entity created with the old UUID is warned about (`"Duplicate entity UUID {}: {}"`, `:35`) and then **not registered in either map at all** — the `else` branch skips both puts.
- Deduplication is by UUID only, so two live entities can now share `byId`-distinct entries while the UUID map has a single stale key.

### `PersistentEntitySectionManager` — `knownUuids`

`net/minecraft/world/level/entity/PersistentEntitySectionManager.java:35`:

```java
    private final Set<UUID> knownUuids = Sets.newHashSet();
```

Registration (`:62-69`) and the collision path:

```java
    private boolean addEntityUuid(T entity) {
        if (!this.knownUuids.add(entity.getUUID())) {
            LOGGER.warn("UUID of added entity already exists: {}", entity);
            return false;
        } else {
            return true;
        }
    }
```

`addEntity` / `addEntityWithoutEvent` (`:79-107`) make that check the **first** thing they do, before any section insertion:

```java
    private boolean addEntityWithoutEvent(T entity, boolean loaded) {
        if (!this.addEntityUuid(entity)) {
            return false;
        } else {
            long sectionKey = SectionPos.asLong(entity.blockPosition());
            EntitySection<T> entitySection = this.sectionStorage.getOrCreateSection(sectionKey);
            entitySection.add(entity);
            entity.setLevelCallback(new PersistentEntitySectionManager.Callback(entity, sectionKey, entitySection));
```

So a duplicate UUID means the entity is **silently rejected** (returns `false`; the caller `ServerLevel.addEntity` at `:1018-1023` then just returns `false`). The only log is the warn at `:64`.

The visible-registry add/remove is `startTracking`/`stopTracking` (`:139-147`):

```java
    private void startTracking(T entity) {
        this.visibleEntityStorage.add(entity);
        this.callbacks.onTrackingStart(entity);
    }

    private void stopTracking(T entity) {
        this.callbacks.onTrackingEnd(entity);
        this.visibleEntityStorage.remove(entity);
    }
```

`knownUuids` read sites: `isLoaded(UUID)` at `:308-310` (`return this.knownUuids.contains(uuid);`), `gatherStats` at `:369`. The only removal is in the section `Callback.onRemove` at `:476`:

```java
            PersistentEntitySectionManager.this.knownUuids.remove(this.entity.getUUID());
```

Again keyed on the **current** UUID. After a mutation, `knownUuids` keeps the old UUID forever:
- `ServerLevel.tryAddFreshEntityWithPassengers` (`net/minecraft/server/level/ServerLevel.java:1028`) checks `entity.getSelfAndPassengers().map(Entity::getUUID).anyMatch(this.entityManager::isLoaded)` — this now checks the *new* UUID and finds nothing, so the duplicate guard **fails open**.
- Conversely the old UUID stays "loaded" in the set forever, so any future entity created with that UUID is rejected at `addEntityUuid` (`:63-65`) for the lifetime of the level — a permanent, unrecoverable name collision.

Other `UUID`-relevant log strings in this class (for completeness; these fire on section bookkeeping mismatches, which a UUID change does **not** by itself cause, since sections key on position and identity):
- `:412` `"Entity {} wasn't found in section {} (moving to {})"`
- `:460` `"Entity {} wasn't found in section {} (destroying due to {})"`
- `:224` `"Failed to read chunk {}"`

### `EntitySection` — **not** UUID-keyed (survives)

`net/minecraft/world/level/entity/EntitySection.java:12-28`:

```java
public class EntitySection<T extends EntityAccess> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ClassInstanceMultiMap<T> storage;
...
    public void add(T entity) {
        this.storage.add(entity);
    }

    public boolean remove(T entity) {
        return this.storage.remove(entity);
    }
```

`ClassInstanceMultiMap` (`net/minecraft/util/ClassInstanceMultiMap.java:15-16`) stores `Map<Class<?>, List<T>> byClass` with plain `List.remove(Object)` (`:39-45`) — object identity/`equals`, and `Entity` does not override `equals`, so section membership is **unaffected** by a UUID change. Entity iteration by AABB (`EntitySection.getEntities`, `:30-54`) and `size()` (`:75-77`) stay correct.

### `LevelEntityGetter` / `LevelEntityGetterAdapter`

`net/minecraft/world/level/entity/LevelEntityGetter.java:9-21` declares the interface including `@Nullable T get(final UUID id);`.

`net/minecraft/world/level/entity/LevelEntityGetterAdapter.java:9-31`:

```java
public class LevelEntityGetterAdapter<T extends EntityAccess> implements LevelEntityGetter<T> {
    private final EntityLookup<T> visibleEntities;
    private final EntitySectionStorage<T> sectionStorage;

    public LevelEntityGetterAdapter(EntityLookup<T> visibleEntities, EntitySectionStorage<T> sectionStorage) {
...
    @Override
    public @Nullable T get(int id) {
        return this.visibleEntities.getEntity(id);
    }

    @Override
    public @Nullable T get(UUID id) {
        return this.visibleEntities.getEntity(id);
    }

    @Override
    public Iterable<T> getAll() {
        return this.visibleEntities.getAllEntities();
    }
```

So every UUID lookup that goes through the level is exactly a `byUuid` hit; AABB lookups (`:39-46`) bypass UUIDs entirely and keep working. `getAll()` iterates `byId` (`EntityLookup:28-30`, `Iterables.unmodifiableIterable(this.byId.values())`), so `getAll()` stays correct. **The asymmetry is important**: `getAll()` and AABB/type queries are fine, UUID queries are broken.

The client's copy is the same code: `net/minecraft/world/level/entity/TransientEntitySectionManager.java:17-23` (`private final EntityLookup<T> entityStorage = new EntityLookup<>()`), used by `ClientLevel` (`net/minecraft/client/multiplayer/ClientLevel.java:136`).

### `ServerLevel`

- `entityManager` field: `net/minecraft/server/level/ServerLevel.java:201` (`private final PersistentEntitySectionManager<Entity> entityManager;`), constructed at `:263` (`new PersistentEntitySectionManager<>(Entity.class, new ServerLevel.EntityCallbacks(), entityStorage)`).
- `getEntity(int)`: `:1401-1404`

```java
    @Override
    public @Nullable Entity getEntity(int id) {
        return this.getEntities().get(id);
    }
```
  → `byId`, unaffected.
- `getEntity(UUID)` is **not** overridden by `ServerLevel`; it is inherited from `Level` (`net/minecraft/world/level/Level.java:852-854`):

```java
    public @Nullable Entity getEntity(UUID uuid) {
        return this.getEntities().get(uuid);
    }
```
  → `byUuid`, broken after mutation.
- `getEntityInAnyDimension(UUID)`: `ServerLevel.java:1406-1423` iterates all levels calling the broken `getEntity(uuid)`.
- `getPlayerInAnyDimension(UUID)`: `:1425-1428` delegates to the player list: `return this.getServer().getPlayerList().getPlayer(uuid);` → `PlayerList.playersByUUID` (see §7).
- **Duplicate-UUID handling for players**, `:1000-1011`:

```java
    private void addPlayer(ServerPlayer player) {
        if (net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.EntityJoinLevelEvent(player, this)).isCanceled()) return;
        Entity existing = this.getEntity(player.getUUID());
        if (existing != null) {
            LOGGER.warn("Force-added player with duplicate UUID {}", player.getUUID());
            existing.unRide();
            this.removePlayerImmediately((ServerPlayer)existing, Entity.RemovalReason.DISCARDED);
        }

        this.entityManager.addNewEntityWithoutEvent(player);
```
  This is the only place that *does* clean up a UUID-duplicate (log string `"Force-added player with duplicate UUID {}"`), and it can cast-explode if the stale entry is not a `ServerPlayer`. Under our mutation, a re-added player with the entity's *new* UUID finds `existing == null` (good) but the old UUID remains occupied in `byUuid` and `knownUuids`.
- `addEntity` duplicate path, `:1013-1025` (log `"Tried to add entity {} but it was marked as removed already"` at `:1015`; otherwise returns `false` silently).
- `entityManager` is otherwise closed/saved at `:907-909`, dumped at `:1601`, `:1625`.
- Entity-callback wiring, `:1911` `private final class EntityCallbacks implements LevelCallback<Entity>`: `onCreated` `:1917`, `onDestroyed` `:1923`, `onTickingStart` `:1931`, `onTickingEnd` `:1935`, `onTrackingStart` `:1939-1972`, `onTrackingEnd` `:1974-2004`, `onSectionChange` `:2006-2008`. `onTrackingEnd` is where `debugSynchronizers.dropEntity(entity)` (`:2000`, UUID-keyed, see §7) and `getChunkSource().removeEntity(entity)` (`:1975`, id-keyed) fire.
- `ServerLevel` has **no** other UUID-keyed container: its fields (`:194-224`) are `List<ServerPlayer> players`, `EntityTickList entityTickList`, `ServerWaypointManager waypointManager`, `Set<Mob> navigatingMobs`, `Int2ObjectMap<PartEntity<?>> dragonParts` (`:218`, id-keyed), `LevelDebugSynchronizers debugSynchronizers` (`:222`).

---

## 3. Chunk / entity tracking — keyed by **entity id**, not UUID

This is the main "survives" area, and worth stating explicitly because it is a common wrong assumption.

### `ChunkMap` maps

`net/minecraft/server/level/ChunkMap.java:127-129,145-146`:

```java
    private final Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap = new Long2ObjectLinkedOpenHashMap<>();
    private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap = this.updatingChunkMap.clone();
    private final Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads = new Long2ObjectLinkedOpenHashMap<>();
...
    private final PlayerMap playerMap = new PlayerMap();
    private final Int2ObjectMap<ChunkMap.TrackedEntity> entityMap = new Int2ObjectOpenHashMap<>();
```

- `entityMap` is `Int2ObjectMap<TrackedEntity>` → keyed by `entity.getId()`, **not** UUID.
- `playerMap` is a `PlayerMap` whose inner map is `Object2BooleanMap<ServerPlayer>` (`net/minecraft/server/level/PlayerMap.java:8`), i.e. keyed by the `ServerPlayer` **object** (`ServerPlayer` does not override `hashCode`/`equals` — `grep` for `hashCode|equals(` in `ServerPlayer.java` returns only `java.util.Objects.equals(...)` call sites, no overrides), so UUID-independent.
- `updatingChunkMap`/`visibleChunkMap`/`pendingUnloads` are `long` chunk keys.

Registration/removal (`ChunkMap.java:1157-1196`):

```java
    protected void addEntity(Entity entity) {
        if (!(entity instanceof net.neoforged.neoforge.entity.PartEntity)) {
            EntityType<?> type = entity.getType();
            int range = type.clientTrackingRange() * 16;
            if (range != 0) {
                int updateInterval = type.updateInterval();
                if (this.entityMap.containsKey(entity.getId())) {
                    throw (IllegalStateException)Util.pauseInIde(new IllegalStateException("Entity is already tracked!"));
                } else {
                    ChunkMap.TrackedEntity trackedEntity = new ChunkMap.TrackedEntity(entity, range, updateInterval, type.trackDeltas());
                    this.entityMap.put(entity.getId(), trackedEntity);
                    trackedEntity.updatePlayers(this.level.players());
                    if (entity instanceof ServerPlayer player) {
                        this.updatePlayerStatus(player, true);

                        for (ChunkMap.TrackedEntity e : this.entityMap.values()) {
                            if (e.entity != player) {
                                e.updatePlayer(player);
                            }
                        }
                    }
                }
            }
        }
    }

    protected void removeEntity(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            this.updatePlayerStatus(player, false);

            for (ChunkMap.TrackedEntity trackedEntity : this.entityMap.values()) {
                trackedEntity.removePlayer(player);
            }
        }

        ChunkMap.TrackedEntity trackedEntity = this.entityMap.remove(entity.getId());
```

**Answer to "does it use the entity's UUID at add time?": No.** `addEntity` reads only `entity.getId()`, `entity.getType()`, and `instanceof ServerPlayer`. There is no `UUID` token anywhere in `ChunkMap.java` (`grep -n "UUID"` on that file returns only `Map<`/`Long2ObjectMap` matches, no UUID import at all — see its import block `:3-109`). All the `sendToTrackingPlayers*` helpers (`:1232-1256`), `getPlayersWatching` (`:1317-1322`), `forEachEntityTrackedBy` (`:1258-1264`), and `tick()` (`:1198-1230`) also look entities up by id.

So a live UUID mutation **does not corrupt chunk/entity tracking**, does not despawn anything, and does not re-send add-entity packets. The client keeps its old UUID for that entity id — see §6.

### `ChunkMap.TrackedEntity` and `ServerEntity`

`ChunkMap.java:1353-1378`:

```java
    private class TrackedEntity implements ServerEntity.Synchronizer {
        private final ServerEntity serverEntity;
        private final Entity entity;
        private final int range;
        private SectionPos lastSectionPos;
        private final Set<ServerPlayerConnection> seenBy;

        public TrackedEntity(Entity entity, int range, int updateInterval, boolean trackDelta) {
            ...
            this.seenBy = Sets.newIdentityHashSet();
            this.serverEntity = new ServerEntity(ChunkMap.this.level, entity, updateInterval, trackDelta, this);
...
        @Override
        public boolean equals(Object obj) {
            return obj instanceof ChunkMap.TrackedEntity ? ((ChunkMap.TrackedEntity)obj).entity.getId() == this.entity.getId() : false;
        }

        @Override
        public int hashCode() {
            return this.entity.getId();
        }
```

`seenBy` is an **identity** hash set (`Sets.newIdentityHashSet()`), `equals`/`hashCode` use `entity.getId()`. Fully UUID-independent.

`net/minecraft/server/level/ServerEntity.java`: `grep -n "UUID|uuid"` on this file returns **no matches**. Its fields (`:56-71`) hold `ServerLevel level`, `Entity entity`, `int updateInterval`, `boolean trackDelta`, `VecDeltaCodec positionCodec`, byte rotations, `Vec3 lastSentMovement`, `List<Entity> lastPassengers`. Pairing is by id: `removePairing` sends `new ClientboundRemoveEntitiesPacket(this.entity.getId())` (`:255-259`), `addPairing` (`:261-267`), `sendPairingData` uses `this.entity.getAddEntityPacket(this)` (`:275`) and `new ClientboundSetEntityDataPacket(this.entity.getId(), this.trackedDataValues)` (`:279`). **Not UUID-keyed.**

---

## 4. Chunk-ticket / distance-manager state — **not** keyed by player UUID

This is the second common wrong assumption. The ticket system is keyed by chunk `long`, and NeoForge entity-ticket owners are stored as UUIDs only for *externally force-loaded* chunks, not for the normal per-player loading tickets.

### `DistanceManager`

`net/minecraft/server/level/DistanceManager.java:37-46`:

```java
    private static final int PLAYER_TICKET_LEVEL = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
    private final Long2ObjectMap<ObjectSet<ServerPlayer>> playersPerChunk = new Long2ObjectOpenHashMap<>();
...
    private final TicketStorage ticketStorage;
    private final DistanceManager.FixedPlayerDistanceChunkTracker naturalSpawnChunkCounter = new DistanceManager.FixedPlayerDistanceChunkTracker(8);
    private final DistanceManager.PlayerTicketTracker playerTicketManager = new DistanceManager.PlayerTicketTracker(32);
```

`playersPerChunk` is `long -> ObjectSet<ServerPlayer>` — keyed by chunk, valued by player *objects* (no UUID). Add/remove:

```java
    public void addPlayer(SectionPos pos, ServerPlayer player) {
        ChunkPos chunk = pos.chunk();
        long chunkPos = chunk.pack();
        this.playersPerChunk.computeIfAbsent(chunkPos, k -> new ObjectOpenHashSet<>()).add(player);
        this.naturalSpawnChunkCounter.update(chunkPos, 0, true);
        this.playerTicketManager.update(chunkPos, 0, true);
        this.ticketStorage.addTicket(new Ticket(TicketType.PLAYER_SIMULATION, this.getPlayerTicketLevel()), chunk);
    }

    public void removePlayer(SectionPos pos, ServerPlayer player) {
        ChunkPos chunk = pos.chunk();
        long chunkPos = chunk.pack();
        ObjectSet<ServerPlayer> chunkPlayers = this.playersPerChunk.get(chunkPos);
        chunkPlayers.remove(player);
```

(`:110-130`.) Note `removePlayer` will NPE if `playersPerChunk` lacks the chunk — but nothing here involves the UUID. Trackers are `Long2ByteMap`/`Long2IntMap` (`:198-206`, `:246-256`). `updateSimulationDistance` (`:152-157`) replaces ticket levels by `TicketType`, not by owner.

**Conclusion: player-UUID-keyed ticket state does not exist in vanilla 26.1.2.** A UUID change does not affect chunk loading/ticking at all.

### `TicketType` / `TicketStorage`

`net/minecraft/server/level/TicketType.java:21-29` registers `PLAYER_SPAWN`, `SPAWN_SEARCH`, `DRAGON`, `PLAYER_LOADING`, `PLAYER_SIMULATION`, `FORCED`, `PORTAL`, `ENDER_PEARL`, `UNKNOWN`. None of these carry an owner UUID; a `Ticket` is `(type, level)`.

`net/minecraft/world/level/TicketStorage.java:45-46`:

```java
    private final Long2ObjectOpenHashMap<List<Ticket>> tickets;
    private final Long2ObjectOpenHashMap<List<Ticket>> deactivatedTickets;
```

Keyed by `ChunkPos.pack()`. **However**, NeoForge adds UUID-owned tickets — `TicketStorage.java:424-435`:

```java
    // Neo: Keep track of forced loaded chunks caused by entities or blocks.
    private final net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.TicketTracker<net.minecraft.core.BlockPos> blockForcedChunks = new net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.TicketTracker<>(this, net.neoforged.neoforge.common.NeoForgeMod.BLOCK_TICKET, net.neoforged.neoforge.common.NeoForgeMod.BLOCK_WITH_NATURAL_SPAWNING_TICKET);
    private final net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.TicketTracker<java.util.UUID> entityForcedChunks = new net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.TicketTracker<>(this, net.neoforged.neoforge.common.NeoForgeMod.ENTITY_TICKET, net.neoforged.neoforge.common.NeoForgeMod.ENTITY_WITH_NATURAL_SPAWNING_TICKET);
```

The entity variant is `TicketTracker<UUID>`, and the public API takes the owner straight off the entity — `net/neoforged/neoforge/common/world/chunk/TicketController.java:57-59`:

```java
    public boolean forceChunk(ServerLevel level, Entity owner, int chunkX, int chunkZ, boolean add, boolean forceNaturalSpawning) {
        return forceChunk(level, owner.getUUID(), chunkX, chunkZ, add, forceNaturalSpawning);
    }
```

with the UUID form at `:67-69`. Ownership is compared by value in `net/neoforged/neoforge/common/world/chunk/ForcedChunkManager.java:237-263`:

```java
    static class TicketOwner<T extends Comparable<? super T>> implements Comparable<TicketOwner<T>> {
        private final Identifier id;
        private final T owner;
...
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TicketOwner<?> that = (TicketOwner<?>) o;
            return Objects.equals(id, that.id) && Objects.equals(owner, that.owner);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, owner);
        }
    }
```

Consequence: if any mod/controller force-loads chunks with `forceChunk(level, entity, ...)`, the ticket is owned by the **UUID captured at call time**. After a UUID mutation, `forceChunk(level, entity, ..., false)` computes a *different* `TicketOwner` (`ForcedChunkManager.java:93`, `TicketOwner<T> ticketOwner = new TicketOwner<>(id, owner);`) and `remove(...)` at `:348-365` (`if (sources != null && sources.remove(owner))`) fails to match, so **the chunk stays forced forever** and the entity's old UUID keeps a loading ticket. This is persisted (see `OwnedChunks.java` in §7). Also `forceChunk` with `add==true` calls `level.getChunk(chunkX, chunkZ)` at `:96-97`.

### `ServerLevel.setChunkForced` / `getChunkSource`

`net/minecraft/server/level/ServerLevel.java:1516-1527`:

```java
    public LongSet getForceLoadedChunks() {
        return this.chunkSource.getForceLoadedChunks();
    }

    public boolean setChunkForced(int chunkX, int chunkZ, boolean forced) {
        boolean updated = this.chunkSource.updateChunkForced(new ChunkPos(chunkX, chunkZ), forced);
        if (forced && updated) {
            this.getChunk(chunkX, chunkZ);
        }

        return updated;
    }
```

`getChunkSource()` at `:1228`; `updateChunkForced` lives on `ServerChunkCache`. This path is **(x,z)-only**, no UUID. `TicketStorage.getForceLoadedChunks()` at `:396-398` returns `this.chunksWithForcedTickets` (a `LongSet`).

---

## 5. Entity load path and UUID collisions

Flow when a chunk is loaded:

1. `net/minecraft/world/level/chunk/storage/EntityStorage.java:46-82` — `loadEntities(ChunkPos)` reads the region tag, then `:73-75`:

```java
                        ValueInput.ValueInputList entities = chunkRoot.childrenListOrEmpty("Entities");
                        List<Entity> chunkEntities = EntityType.loadEntitiesRecursive(entities, this.level, EntitySpawnReason.LOAD).toList();
                        var8 = new ChunkEntities<>(pos, chunkEntities);
```

2. `net/minecraft/world/entity/EntityType.java:1577-1582` `loadEntitiesRecursive` → `:1552-1564` `loadEntityRecursive` → `:1584-1591` `loadStaticEntity` → `:1496-1502` `create(ValueInput, Level, EntitySpawnReason)`:

```java
    public static Optional<Entity> create(ValueInput input, Level level, EntitySpawnReason reason) {
        return Util.ifElse(
            by(input).map(type -> type.create(level, reason)),
            entity -> entity.load(input),
            () -> LOGGER.warn("Skipping Entity with id {}", input.getStringOr("id", "[invalid]"))
        );
    }
```

So the UUID is restored *only* via `Entity.load` (`Entity.java:2070-2073`), i.e. inside the `entity.load(input)` consumer above. Failures are swallowed per-entity: `EntityType.java:1587-1589` `LOGGER.warn("Exception loading entity: ", (Throwable)var4);`.

3. The loaded list is delivered through the manager's inbox: `PersistentEntitySectionManager.processPendingLoads()` (`:248-257`):

```java
    public void processPendingLoads() {
        ChunkEntities<T> loadedChunk;
        while ((loadedChunk = this.loadingInbox.poll()) != null) {
            loadedChunk.getEntities().forEach(e -> {
                this.addEntity(e, true);
                if (e instanceof Entity entity) entity.onAddedToLevel();
            });
            this.chunkLoadStatuses.put(loadedChunk.getPos().pack(), PersistentEntitySectionManager.ChunkLoadStatus.LOADED);
        }
    }
```

4. `addEntity` → `addEntityUuid` (`:62-69`) is where a **UUID collision with an already-loaded entity** is detected, and the log string is:

```java
            LOGGER.warn("UUID of added entity already exists: {}", entity);
```
(`PersistentEntitySectionManager.java:64`.) The entity is then **dropped** — not added to a section, not tracked, not ticked. There is **no `LOG.error`** on this path.

5. Legacy chunk path: `SerializableChunkData.java:557-558` calls `level.addLegacyChunkEntities(EntityType.loadEntitiesRecursive(TagValueInput.create(...), level, EntitySpawnReason.LOAD))` → `PersistentEntitySectionManager.addLegacyChunkEntities` (`:117-122`).

Other relevant collision surfaces:
- `EntityLookup.add` warn: `"Duplicate entity UUID {}: {}"` (`EntityLookup.java:35`).
- `ServerLevel.addPlayer` warn: `"Force-added player with duplicate UUID {}"` (`ServerLevel.java:1004`).
- Summon command's own guard: `net/minecraft/server/commands/SummonCommand.java:32` `ERROR_DUPLICATE_UUID = new SimpleCommandExceptionType(Component.translatable("commands.summon.failed.uuid"))` and `:96-97`:

```java
                if (!level.tryAddFreshEntityWithPassengers(entity)) {
                    throw ERROR_DUPLICATE_UUID.create();
```

which routes through `ServerLevel.tryAddFreshEntityWithPassengers` (`:1027-1034`) and the now-unreliable `isLoaded(UUID)` check (§2). **No `LOG.error` anywhere in `net/minecraft/server/level` relates to UUID collisions** — `grep -n "LOG\.error|LOGGER\.error"` there yields only `ChunkMap.java:542,561,800` (chunk save/load) and `ServerChunkCache.java:99` (storage dir).

Additional load-time UUID consumers (break if the UUID changed before a save/load cycle, since the tag will contain the *new* UUID while cross-references still hold the old one):
- `ServerPlayer.saveParentVehicle` / `loadAndSpawnParentVehicle`: `net/minecraft/server/level/ServerPlayer.java:463` `vehicleWrapper.store("Attach", UUIDUtil.CODEC, vehicle.getUUID());` and `:468-489`, where reattachment is `:477` `if (vehicle.getUUID().equals(attachTo))` / `:481` `if (entity.getUUID().equals(attachTo))`; failure logs `"Couldn't reattach entity to player"` at `:489`.
- `net/minecraft/world/entity/EntityReference.java:21-24,51-52,127` — a reference is persisted as a bare UUID (`output.store(key, UUIDUtil.CODEC, this.getUUID())` at `:93`) or as a stored entity type, and resolves through `LevelEntityGetter`/`UUIDLookup`. `EntityReference` implements value `equals`/`hashCode` on the UUID (`:138-143`), so it is itself a UUID-keyed key (see §7).

---

## 6. Packet-facing: where the UUID is serialized, and what the client keys on

### `ClientboundAddEntityPacket`

`net/minecraft/network/protocol/game/ClientboundAddEntityPacket.java:17-31` and `:101`:

```java
public class ClientboundAddEntityPacket implements Packet<ClientGamePacketListener> {
    ...
    private final int id;
    private final UUID uuid;
...
        output.writeUUID(this.uuid);
```

Constructed from the live entity at `:37-51` (`entity.getUUID()` at `:40`) and `:53-67` (`entity.getUUID()` at `:56`). Read side at `:87` `this.uuid = input.readUUID();`, accessor `:126-127` `public UUID getUUID() { return this.uuid; }`.

**When is it sent?** Only on tracking start / chunk entry, via `ServerEntity.sendPairingData` (`ServerEntity.java:269-277`) calling `this.entity.getAddEntityPacket(this)`. There is **no periodic re-send**, so mutating a live UUID produces **no packet** — the client keeps the stale UUID. (The only way to resend is to make the entity stop/start being tracked.)

### Client side: entities are keyed by **int id**

`net/minecraft/client/multiplayer/ClientPacketListener.java:569-591`:

```java
    public void handleAddEntity(ClientboundAddEntityPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.minecraft.packetProcessor());
        if (this.removedPlayerVehicleId.isPresent() && this.removedPlayerVehicleId.getAsInt() == packet.getId()) {
            this.removedPlayerVehicleId = OptionalInt.empty();
        }

        Entity entity = this.createEntityFromPacket(packet);
        if (entity != null) {
            entity.recreateFromPacket(packet);
            this.level.addEntity(entity);
```

`Entity.recreateFromPacket` (`Entity.java:3788-3798`) applies the packet UUID: `this.setUUID(packet.getUUID());` at `:3796`. `ClientLevel.getEntity(int)` (`net/minecraft/client/multiplayer/ClientLevel.java:450`) goes to `entityStorage` (a `TransientEntitySectionManager` wrapping `EntityLookup`, §2), and **every** packet handler resolves entities by id — `ClientPacketListener.java:628, 637, 646, 673, 983-984, 1106, 1128, 1153, 1186, 1231, 1382, 1512, 1804, 1857, 2133, 2358, 2467, 2522`.

The UUID from the packet is used for exactly two things:
1. Player-info correlation — `:585-590` (`PlayerInfo playerInfo = this.playerInfoMap.get(uuid);`) and `createEntityFromPacket` `:594-607`:

```java
        if (type == EntityType.PLAYER) {
            PlayerInfo playerInfo = this.getPlayerInfo(packet.getUUID());
            if (playerInfo == null) {
                LOGGER.warn("Server attempted to add player prior to sending player info (Player id {})", packet.getUUID());
                return null;
            } else {
                return new RemotePlayer(this.level, playerInfo.getProfile());
            }
```

  i.e. for **player** entities the UUID must match a `ClientboundPlayerInfoUpdatePacket` entry or the entity is not created at all.
2. The `seenPlayers` bookkeeping at `:589`.

There is **no** client-side `Map<UUID, Entity>`; `grep` over `net/minecraft/client` for `Map<UUID` yields only `BossHealthOverlay.events` (`net/minecraft/client/gui/components/BossHealthOverlay.java:55`), `ClientWaypointManager.waypoints` (`net/minecraft/client/waypoints/ClientWaypointManager.java:17`), `PlayerTabOverlay.healthStates` (`net/minecraft/client/gui/components/PlayerTabOverlay.java:67`, keyed off `profile.id()` at `:205`), and social/player-list structures. So a server-side UUID mutation is **invisible** to the client's entity registry.

### `ClientboundPlayerInfoUpdatePacket`

`net/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket.java:60` (`EntryBuilder builder = new ClientboundPlayerInfoUpdatePacket.EntryBuilder(buf.readUUID());`), `:73` (`buf.writeUUID(entry.profileId());`), and the entry record at `:148-171`:

```java
    public record Entry(
        UUID profileId,
        @Nullable GameProfile profile,
...
        private Entry(ServerPlayer player) {
            this(
                player.getUUID(),
```

The tab-list key is `profileId` = `player.getUUID()` captured at packet construction. Corresponding removal is `ClientboundPlayerInfoRemovePacket(List<UUID> profileIds)` (`net/minecraft/network/protocol/game/ClientboundPlayerInfoRemovePacket.java:11,17,21`). Client storage: `ClientPacketListener.java:393` `private final Map<UUID, PlayerInfo> playerInfoMap = Maps.newHashMap();` with removal at `:2001-2003` and insertion at `:2016` (`this.playerInfoMap.putIfAbsent(entry.profileId(), playerInfo)`), unknown-entry warn at `:2024` `LOGGER.warn("Ignoring player info update for unknown player {} ({})", entryx.profileId(), packet.actions());`.

So for a **player**, a UUID mutation desynchronizes three things at once: the tab list keeps listing the old UUID, the `playerInfoMap` keeps the old key, and `ClientboundAddEntityPacket` for player entities would no longer find a matching profile entry (`:597-600`) if it were ever resent.

### Other packets carrying a UUID

- `ClientboundBossEventPacket` — `:20` `private final UUID id;`, `:44`, `:77`, `:53` `createRemovePacket(UUID id)`. Client key: `BossHealthOverlay.java:55` `Map<UUID, LerpingBossEvent> events`.
- `ClientboundTrackedWaypointPacket` — `:30-54` all factories take `UUID identifier`.
- `ClientboundPlayerChatPacket` — `:19` `UUID sender`, `:34`, `:46`.
- `ServerboundTeleportToEntityPacket` — `:16` `private final UUID uuid;`; server handler `net/minecraft/server/network/ServerGamePacketListenerImpl.java:1452-1463` resolves `packet.getEntity(level)` through the (broken) UUID lookup, spectator-only.
- Resource-pack packets (`ServerboundResourcePackPacket`, `ClientboundResourcePackPushPacket`, `ClientboundResourcePackPopPacket`) — pack IDs, unrelated to entities.
- `DebugEntityNameGenerator.getEntityName(UUID uuid)` (`net/minecraft/network/protocol/game/DebugEntityNameGenerator.java:84,88`) — shortens the entity UUID for debug output.

---

## 7. Broad sweep: every `UUID -> something` structure and whether a mutation strands it

Search basis: `grep -n "Map<UUID, |Map<UUID|Set<UUID> |List<UUID> |UUID>|Map<java.util.UUID"` over `net/minecraft/server`, `net/minecraft/world`, `net/minecraft/util`, `net/minecraft/core`, `net/minecraft/network`, plus the NeoForge `ForcedChunkManager`/`TicketController`.

| Structure | File:line | Keyed by | Stale after UUID mutation? |
|---|---|---|---|
| `EntityLookup.byUuid` (`Map<UUID,T>`) | `net/minecraft/world/level/entity/EntityLookup.java:17` | entity UUID → entity | **YES — strand + phantom.** `remove` (`:43`) removes the wrong key; `getEntity(old)` still hits; `getEntity(new)` misses; `count()` (`:56`) wrong. Every `Level.getEntity(UUID)` inherits this. |
| `EntityLookup.byId` | `net/minecraft/world/level/entity/EntityLookup.java:16` | entity id | No — id-based. |
| `PersistentEntitySectionManager.knownUuids` (`Set<UUID>`) | `net/minecraft/world/level/entity/PersistentEntitySectionManager.java:35` | entity UUID | **YES — permanent leak.** `remove` at `:476` misses; `isLoaded(old)` stays `true` forever (`:308-310`); `tryAddFreshEntityWithPassengers`'s guard `ServerLevel.java:1028` fails open; the old UUID can never be reused. |
| `visibleEntityStorage` (→ `EntityLookup`) | `net/minecraft/world/level/entity/PersistentEntitySectionManager.java:38,140,146` | as above | **YES** — same object as the first row. |
| `EntitySection.storage` (`ClassInstanceMultiMap`) | `net/minecraft/world/level/entity/EntitySection.java:14` / `net/minecraft/util/ClassInstanceMultiMap.java:16` | `Class` + list identity | No. |
| `TrackingDebugSynchronizer.SourceSynchronizer.entitySources` (`Map<UUID,ValueSource<T>>`) | `net/minecraft/util/debug/TrackingDebugSynchronizer.java:144` | entity UUID (hashed) | **YES — crash + leak.** Registered with `registerEntity(entity.getUUID(), …)` (`:193`, called from `net/minecraft/util/debug/LevelDebugSynchronizers.java:140`); `dropEntity` removes by the new UUID (`:211`) so the entry leaks; the poll loop `:176-181` then does `Objects.requireNonNull(level.getEntity(entryxx.getKey()))` — `getEntity` uses the broken `byUuid`, so this **throws NPE** (debug subscription path). `sendInitialEntity` (`:234`) silently misses. |
| `TrackingDebugSynchronizer.subscribedPlayers` (`Set<UUID>`) | `net/minecraft/util/debug/TrackingDebugSynchronizer.java:30` | **player** UUID | **YES** for a mutated player: stale membership (`:38,44,56,68,77,81,87`); cleanup `removeIf(id -> level.getPlayerByUUID(id) == null)` at `:49` is the only thing that eventually prunes it. |
| `PlayerList.playersByUUID` (`Map<UUID,ServerPlayer>`) | `net/minecraft/server/players/PlayerList.java:117` | player UUID → player | **YES — worst offender.** `getPlayer(UUID)` (`:841-842`) misses for the new UUID and still returns the player for the old one. On removal `:338-345`: `ServerPlayer serverPlayer = this.playersByUUID.get(uuid); if (serverPlayer == player) { this.playersByUUID.remove(uuid); ... }` — with the new UUID the `get` is `null`, so `playersByUUID`, `stats` and `advancements` entries under the **old** UUID are **never removed** (and a subsequent same-UUID login **overwrites** the key, losing the old object). `broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())))` at `:347` tells clients to remove the new UUID, which they never had. |
| `PlayerList.stats` (`Map<UUID,ServerStatsCounter>`) | `net/minecraft/server/players/PlayerList.java:122` | player UUID | **YES** — stranded at `:341-343` (same block); `save(ServerPlayer)` lookup `:303` misses → stats silently not saved. Note the stat-file path is separately UUID-derived (`:795-800`). |
| `PlayerList.advancements` (`Map<UUID,PlayerAdvancements>`) | `net/minecraft/server/players/PlayerList.java:123` | player UUID | **YES** — stranded at `:341-343`; `save` lookup `:308` misses; `getPlayerAdvancements` (`:804-811`) **creates a brand-new `PlayerAdvancements`** under the new UUID backed by a new `<newuuid>.json` (`:808`), i.e. the player's advancement data effectively resets. |
| `ServerLevel.players` (`List<ServerPlayer>`) | `net/minecraft/server/level/ServerLevel.java:194` | list, identity | No — object list; `EntityGetter.getPlayerByUUID` is a linear `uuid.equals(player.getUUID())` scan (`net/minecraft/world/level/EntityGetter.java:113-122`), so it *follows* the mutation. |
| `PlayerMap.players` (`Object2BooleanMap<ServerPlayer>`) | `net/minecraft/server/level/PlayerMap.java:8` | player object | No (no `hashCode`/`equals` override on `ServerPlayer`) — but note it would break if anyone added one. |
| `ChunkMap.entityMap` (`Int2ObjectMap<TrackedEntity>`) | `net/minecraft/server/level/ChunkMap.java:146` | entity **id** | No. |
| `ChunkMap.updatingChunkMap` / `visibleChunkMap` / `pendingUnloads` | `net/minecraft/server/level/ChunkMap.java:127-129` | chunk `long` | No. |
| `DistanceManager.playersPerChunk` (`Long2ObjectMap<ObjectSet<ServerPlayer>>`) | `net/minecraft/server/level/DistanceManager.java:38` | chunk `long` → player objects | No. |
| `TicketStorage.tickets` / `deactivatedTickets` | `net/minecraft/world/level/TicketStorage.java:45-46` | chunk `long` | No. |
| `ForcedChunkManager.TicketTracker<UUID>.sourcesLoading*` (4 maps of `Long2ObjectMap<Set<TicketOwner<UUID>>>`) | `net/neoforged/neoforge/common/world/chunk/ForcedChunkManager.java:270-273` | chunk `long` → `TicketOwner(id, UUID)` (value equality, `:252-263`) | **YES, if used.** `TicketController.forceChunk(level, entity, …)` captures `owner.getUUID()` (`net/neoforged/neoforge/common/world/chunk/TicketController.java:57-59`); the matching `remove` then builds a different `TicketOwner` (`ForcedChunkManager.java:93`) and fails (`:348-365`) → chunk stays force-loaded forever. Persisted via `OwnedChunks` (`ForcedChunkManager.java:173-175`, `ENTITY_CHUNK_CODEC` = `Map<UUID, TicketSet>`). |
| `Scoreboard.playerScores` (`Map<String,PlayerScores>`) | `net/minecraft/world/scores/Scoreboard.java:35` | score-holder **string** = `Entity.getScoreboardName()` = `stringUUID` | **YES for non-player entities.** `entityRemoved` (`:346-351`) calls `resetAllPlayerScores` → `playerScores.remove(player.getScoreboardName())` (`:194`) → misses, and `removePlayerFromTeam(entity.getScoreboardName())` (`:349`) misses; scores/team membership keyed by the old UUID string are orphaned. `teamsByPlayer` (`:38`) likewise. **Players are safe** — `Player.getScoreboardName()` returns the game-profile name (`net/minecraft/world/entity/player/Player.java:1760-1763`). |
| `EntityReference` value identity (`equals`/`hashCode` on UUID) | `net/minecraft/world/entity/EntityReference.java:21-24,51-52,89,93,127,138-143` | embedded UUID (persisted as `UUIDUtil.CODEC`) | **YES.** `getUUID()` reads `Either.left` or the resolved entity; `matches` (`:89`) and `hashCode` (`:143`) use it, so any set/map of references and any resolution via `getEntity(Level, Class)` (`:78-81`) breaks; on save (`:93`) the new UUID is written. |
| `AngerManagement.angerByUuid` (`Object2IntMap<UUID>`) | `net/minecraft/world/entity/monster/warden/AngerManagement.java:49` | target entity UUID → anger | **YES.** `removeInt(entity.getUUID())` (`:147`) misses → the stale UUID entry is re-serialized (`:70`, `:82-85`, `:129-132`) and can never be cleared; the warden treats the mutated target as a stranger. |
| `GossipContainer.gossips` (`Map<UUID,EntityGossips>`) | `net/minecraft/world/entity/ai/gossip/GossipContainer.java:33` | villager UUID → gossip | **YES** — entries keyed by the old UUID orphaned (serialized via `getGossipEntries`, `:43-44`). |
| `Raid.heroesOfTheVillage` (`Set<UUID>`) | `net/minecraft/world/entity/raid/Raid.java:108` | player UUID | **YES for a mutated player** — hero status lost / stale entry persisted (`:148`). |
| `TrialSpawnerStateData.currentMobs` (`Set<UUID>`) | `net/minecraft/world/level/block/entity/trialspawner/TrialSpawnerStateData.java:46` | spawned-mob UUID | **YES.** `currentMobs.stream().map(level::getEntity)` (`:180`) silently skips stale UUIDs (spawner leaks "live mobs"), `isReadyToSpawn`/`isEmpty` checks (`:104,108`) use the stale size, and `clear()` (`:82`, `:195`) never frees the old UUID. Packed as `current_mobs` (`:300,310`). |
| `TrialSpawnerStateData.detectedPlayers` (`Set<UUID>`) | `net/minecraft/world/level/block/entity/trialspawner/TrialSpawnerStateData.java:45` | player UUID | **YES for a mutated player** — `level.getPlayerByUUID(uuid)` (`:163`) misses; detected set / `getAdditionalPlayers` (`:116`) wrong; persisted as `registered_players` (`:299,309`). |
| `VaultSharedData.connectedPlayers` (`Set<UUID>`) | `net/minecraft/world/level/block/entity/vault/VaultSharedData.java:27` | player UUID | **YES for a mutated player** — `VaultBlockEntity.java:178` `level.getPlayerByUUID(uuid)` misses; `VaultBlockEntity.java:173` iterates the stale set. |
| `VaultServerData.rewardedPlayers` (`Set<UUID>`) | `net/minecraft/world/level/block/entity/vault/VaultServerData.java:30` | player UUID | **YES for a mutated player** — `contains`/`add` (`:60,65`) key on the new UUID → the player can be **rewarded twice**; the old entry is never pruned (`:67`). |
| `CustomBossEvent.players` (`Set<UUID>`) | `net/minecraft/server/bossevents/CustomBossEvent.java:23` | player UUID | **YES for a mutated player** — `addPlayer`/`removePlayer` (`:42,50`), reconciliation `setPlayers` (`:93-132`) and `contains` (`:180`) all match on `player.getUUID()`, so the boss-bar recipient set desyncs. Persisted as `Players` (`:204,217`). |
| `CachedUserNameToIdResolver.profilesByUUID` | `net/minecraft/server/players/CachedUserNameToIdResolver.java:44` | profile UUID | **YES for a mutated player** — cache keyed on the auth profile; removal `:107` and `get(UUID)` `:135-136` desync. |
| `LevelDebugSynchronizers` source registration | `net/minecraft/util/debug/LevelDebugSynchronizers.java:140` | entity UUID | **YES** — same object as the `entitySources` row. |
| `Brain` UUID memories (`LIKED_PLAYER`, `ANGRY_AT`, `UNREACHABLE_TONGUE_TARGETS`) | `net/minecraft/world/entity/ai/memory/MemoryModuleType.java:85,100,137` | UUID value | Degraded, not corrupted — memories hold a UUID and resolve via `BehaviorUtils.getLivingEntityFromUUIDMemory` (`net/minecraft/world/entity/ai/behavior/BehaviorUtils.java:138-139`), so they silently stop resolving. |
| `CreakingHeartBlockEntity.creakingInfo` (`Either<Creaking,UUID>`) | `net/minecraft/world/level/block/entity/CreakingHeartBlockEntity.java:59,353` | entity UUID | **YES** — the heart's stored UUID no longer matches the creaking, which de-summons/desyncs the pairing. |
| `EntityPositionSource` (`Either<Entity, Either<UUID,Integer>>`) | `net/minecraft/world/level/gameevent/EntityPositionSource.java:34,67` | entity ref or UUID | **YES** — `Entity::getUUID` at `:67` returns the new UUID while any wireless-redstone source was built from the old one. |
| `EnderDragonFight` dragon UUID | `net/minecraft/world/level/dimension/end/EnderDragonFight.java:133` | dragon entity UUID | **YES for a mutated dragon** — respawn/fight bookkeeping (`Optional<UUID> dragonUUID`) no longer matches. |
| `TrackedWaypoint.identifier` (`Either<UUID,String>`) + `ServerWaypointManager` | `net/minecraft/world/waypoints/TrackedWaypoint.java:23,33,41,51,57-69`; `net/minecraft/world/waypoints/WaypointTransmitter.java:94-188` | player/waypoint UUID | **YES for a mutated player** — locator-bar packets are sent keyed by `source.getUUID()` (`WaypointTransmitter.java:94,99,107,128,133,140,176,181,188`) and `removeWaypoint` then references the new UUID; `ServerWaypointManager` itself keys on objects (`net/minecraft/server/waypoints/ServerWaypointManager.java:19-20`), so only the wire identifier desyncs. |
| `ClientWaypointManager.waypoints` / `BossHealthOverlay.events` / `PlayerTabOverlay.healthStates` | `net/minecraft/client/waypoints/ClientWaypointManager.java:17`; `net/minecraft/client/gui/components/BossHealthOverlay.java:55`; `net/minecraft/client/gui/components/PlayerTabOverlay.java:67` | client-side UUID | **YES as a consequence** — the client keeps the old UUID, so removal/update packets keyed by the new UUID add a *second* entry rather than updating. |
| `EntityTickList` / `navigatingMobs` / `dragonParts` (`Int2ObjectMap<PartEntity<?>>`) | `net/minecraft/server/level/ServerLevel.java:198,210,218` | object identity / entity id | No. |

### What would have to be re-registered (the "escape hatch" list)

Because `Entity.setUUID` is silent, a correct runtime mutation needs an explicit unregister/re-register around it, in this order:

1. `PersistentEntitySectionManager`: remove the old UUID from `knownUuids` (`PersistentEntitySectionManager.java:35`) and re-`addEntityUuid` with the new one; if the entity is currently tracked, `stopTracking`/`startTracking` (`:139-147`) so `EntityLookup.byUuid` is rebuilt.
2. `EntityLookup`: `byUuid.remove(oldUUID)` + `put(newUUID, entity)` (`EntityLookup.java:17,37,43`) — otherwise `Level.getEntity(UUID)`/`ServerLevel.getEntityInAnyDimension` stay wrong.
3. `PlayerList`: `playersByUUID`, `stats`, `advancements` re-key (`PlayerList.java:117,122,123`) — plus the on-disk `<uuid>.json` files these paths derive (`PlayerList.java:795-800,808`), and the `ClientboundPlayerInfoUpdatePacket`/`Remove` flow (`:216,347,441`).
4. `Scoreboard.playerScores` / `teamsByPlayer` for non-player entities (`Scoreboard.java:35,38`), via `resetAllPlayerScores`/`removePlayerFromTeam` before the change and re-add after.
5. `TrackingDebugSynchronizer.SourceSynchronizer.entitySources` (`TrackingDebugSynchronizer.java:144`) and `subscribedPlayers` (`:30`) — and note the NPE at `:179` if the old entry is not dropped.
6. NeoForge forced-chunk tickets owned by the entity UUID: re-issue `TicketController.forceChunk(level, entityOrNewUUID, x, z, false, …)` with the **old** UUID before mutating, then `add` with the new one (`TicketController.java:57-69`, `ForcedChunkManager.java:83-100`).
7. Block-entity / brain / reference state that captured the UUID: `TrialSpawnerStateData` (`:45-46`), `VaultSharedData`/`VaultServerData` (`:27`, `:30`), `Raid.heroesOfTheVillage` (`:108`), `CustomBossEvent.players` (`:23`), `AngerManagement.angerByUuid` (`:49`), `GossipContainer.gossips` (`:33`), `CreakingHeartBlockEntity.creakingInfo` (`:59`), `EntityReference`s (`EntityReference.java:24`), `EntityPositionSource` (`:34`), `EnderDragonFight` (`:133`).
8. Clients: nothing resends `ClientboundAddEntityPacket` on demand — the only supported way to propagate a new UUID is to make tracking restart (`ChunkMap.removeEntity`/`addEntity`, `ChunkMap.java:1157-1196`, which are id-keyed and therefore will *not* fire on their own). Waypoint (`WaypointTransmitter.java:94-188`) and boss-event (`CustomBossEvent`) consumers must be told explicitly.

**Simplest safe alternative in-tree:** the vanilla-consistent pattern is to *not* mutate in place — `ServerLevel.addPlayer` (`ServerLevel.java:1002-1007`) and `SummonCommand` (`:96-97`) both treat a duplicated UUID as an additive conflict: discard the old entity and add a new one, letting load/save restore the UUID from NBT (`Entity.java:2070-2073`) with a fresh registration.

---

## Explicitly undetermined / flagged

- **Behaviour of the `TrackingDebugSynchronizer` NPE**: `net/minecraft/util/debug/TrackingDebugSynchronizer.java:179` `Objects.requireNonNull(level.getEntity(entryxx.getKey()))` will throw if the stale UUID is still in `entitySources` and `byUuid` no longer resolves it. I could not determine from source alone whether the poll loop that reaches it is gated behind an active debug subscription (the class is driven by `SourceSynchronizer.pollAndSendUpdates`, `:158`); I did not trace the subscription lifecycle. Treat "crashes the server" as **conditional on an active debug subscription**.
- **Whether any NeoForge mod in *this* game actually calls `TicketController.forceChunk(level, Entity|UUID, …)`**: I found only the NeoForge API implementation (`net/neoforged/neoforge/common/world/chunk/TicketController.java:57-69`) and the internal JSON-RPC surface. I did **not** locate an in-tree caller that passes a live entity's UUID, so the forced-chunk stranding is **API-reachable but not proven to be exercised** by vanilla/NeoForge core.
- **`NetHandler`/`Connection` state keyed by UUID**: secure-chat/profile paths do use the player UUID — e.g. `net/minecraft/server/network/ServerGamePacketListenerImpl.java:285` `this.signedMessageDecoder = SignedMessageChain.Decoder.unsigned(player.getUUID(), server::enforceSecureProfile)` and `:2190` `this.signedMessageDecoder = chatSession.createMessageDecoder(this.player.getUUID())`. I did **not** fully trace `SignedMessageChain` internals to determine whether a later UUID change invalidates the decoder; flagged as unresolved.
- **`MinecraftServer.getSinglePlayerUUID` / `PrimaryLevelData.singlePlayerUUID`** (`net/minecraft/server/players/PlayerList.java:291-294`, `net/minecraft/world/level/storage/PrimaryLevelData.java:48`) and the singleplayer `setUUID` call at `net/minecraft/client/multiplayer/ClientPacketListener.java:526` are keyed by the stored world UUID; I did not determine whether mutating a live singleplayer player's UUID corrupts ownership checks beyond `isSingleplayerOwner`.
- **Exhaustiveness of the `Map<UUID` sweep**: I searched with several literal patterns (`Map<UUID,`, `Map<UUID`, `Set<UUID>`, `List<UUID>`, `UUID>`, `Map<java.util.UUID`). Structures declared with an import alias, a generic wrapper (e.g. `SomeWrapper<UUID>`), or a type variable bound to `UUID` would be missed. `grep` for `Map<UUID` also misses fastutil declarations spelled `Object2ObjectOpenHashMap<UUID,` on a separate line; I checked the `Object2Object.*UUID` and `UUID,` patterns as a partial mitigation but cannot claim the sweep is provably complete.
- **Client-side `RemotePlayer`/`AbstractClientPlayer` by UUID**: I confirmed there is no `Map<UUID, Entity>` in `net/minecraft/client`, and that `getPlayerByUUID` is a linear scan (`EntityGetter.java:113-122`) rather than a map. I did not audit `PlayerSocialManager` / `RemoteChatSession` UUID handling.
- **Save-format consequences**: a mutated UUID means the entity is written under the new UUID while any cross-references (leashes, `RootVehicle` `Attach`, `EntityReference`, `CreakingHeartBlockEntity`) still hold the old one; I traced the load-side comparisons (`ServerPlayer.java:476-486`, `EntityReference.java:127`) but did not exhaustively enumerate every persisted UUID cross-reference in the tree.
