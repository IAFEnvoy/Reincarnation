package com.iafenvoy.reincarnation.gametest;

import com.iafenvoy.reincarnation.Reincarnation;
import com.iafenvoy.reincarnation.api.LifeReferences;
import com.iafenvoy.reincarnation.reference.*;
import com.iafenvoy.reincarnation.storage.GenerationIndex;
import com.iafenvoy.reincarnation.storage.Generations;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

public final class ReferenceTests {
    static final String MOD_ID = Reincarnation.MOD_ID;
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.of(
            "persistence", ReferenceTests::persistence,
            "codec_composition", ReferenceTests::codecComposition,
            "copy_and_move", ReferenceTests::copyAndMove,
            "invalid_metadata", ReferenceTests::invalidMetadata,
            "generation_and_cache", ReferenceTests::generationAndCache,
            "synced_owner_and_reload", ReferenceTests::syncedOwner,
            "saved_data", ReferenceTests::savedData,
            "index_recovery", ReferenceTests::indexRecovery);

    private ReferenceTests() {
    }

    public static void register(IEventBus bus) {
        if (!GameTestHooks.isGametestEnabled()) return;
        LifecycleTests.registerHandlers(bus);
        AttachmentTests.register(bus);
        LinkedHashMap<String, Consumer<GameTestHelper>> tests = new LinkedHashMap<>(TESTS);
        tests.putAll(LifecycleTests.tests());
        tests.putAll(AttachmentTests.tests());
        tests.putAll(PlayerViewTests.tests());
        tests.putAll(ReasonTests.tests());
        tests.putAll(LifespanTests.tests());
        tests.putAll(DeathDamageTests.tests());
        DeferredRegister<Consumer<GameTestHelper>> functions = DeferredRegister.create(Registries.TEST_FUNCTION, MOD_ID);
        tests.forEach((name, test) -> functions.register("gametest/" + name, () -> test));
        functions.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> tests.forEach((name, test) -> {
            Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(id(name), new TestEnvironmentDefinition.AllOf(List.of()));
            event.registerTest(id(name), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id(name)),
                    new TestData<>(environment, id("empty"), 200, 0, true)));
        }));
    }

    private record Payload(EntityReference<LivingEntity> owner, List<EntityReference<LivingEntity>> trusted,
                           Map<String, List<EntityReference<LivingEntity>>> arbitrary) {
        static final Codec<Payload> CODEC = RecordCodecBuilder.create(i -> i.group(
                EntityReference.<LivingEntity>codec().fieldOf("Owner").forGetter(Payload::owner),
                EntityReference.<LivingEntity>codec().listOf().fieldOf("Trusted").forGetter(Payload::trusted),
                Codec.unboundedMap(Codec.STRING, EntityReference.<LivingEntity>codec().listOf())
                        .fieldOf("arbitrary").forGetter(Payload::arbitrary)
        ).apply(i, Payload::new));
    }

    private static void persistence(GameTestHelper helper) {
        UUID target = UUID.randomUUID();
        Payload payload = new Payload(LifeReferences.restore(target, 2), List.of(LifeReferences.restore(target, 3)),
                Map.of("a/b", List.of(LifeReferences.restore(target, 0)), "0", List.of(LifeReferences.restore(target, 4))));
        CompoundTag tree = (CompoundTag) Payload.CODEC.encodeStart(NbtOps.INSTANCE, payload).getOrThrow();
        helper.assertTrue(tree.get("Owner") instanceof IntArrayTag owner && owner.size() == 4, "UUID shape stays vanilla");
        CompoundTag business = tree.copy();
        tree = (CompoundTag) ReferencePersistence.pack(NbtOps.INSTANCE, tree);
        helper.assertValueEqual(entries(tree).size(), 4, "automatically discovered references");
        helper.assertValueEqual(UUIDUtil.CODEC.parse(NbtOps.INSTANCE, tree.get("Owner")).getOrThrow(), target, "vanilla UUID decode");
        CompoundTag stripped = tree.copy();
        stripped.remove(ReferencePersistence.CARRIER);
        helper.assertValueEqual(stripped, business, "original entire field tree");
        Payload restored = Payload.CODEC.parse(NbtOps.INSTANCE, roundTrip(tree)).getOrThrow();
        generation(helper, restored.owner(), 2);
        generation(helper, restored.trusted().getFirst(), 3);
        generation(helper, restored.arbitrary().get("a/b").getFirst(), 0);
        generation(helper, restored.arbitrary().get("0").getFirst(), 4);
        helper.assertTrue(payload.owner().equals(payload.trusted().getFirst()), "vanilla UUID equality retained");
        helper.assertTrue(!LifeReferences.sameLife(payload.owner(), payload.trusted().getFirst()), "explicit life comparison");
        helper.assertTrue(EntityReference.<LivingEntity>codec().encodeStart(JsonOps.INSTANCE, payload.owner()).isError(), "unsupported JSON cannot silently lose stamps");
        ByteBuf buffer = Unpooled.buffer();
        try {
            EntityReference.<LivingEntity>streamCodec().encode(buffer, payload.owner());
            helper.assertValueEqual(buffer.readableBytes(), 16, "vanilla UUID network size");
            EntityReference<LivingEntity> received = EntityReference.<LivingEntity>streamCodec().decode(buffer);
            helper.assertValueEqual(received.getUUID(), target, "vanilla UUID network value");
            helper.assertTrue(LifeReferences.generation(received).isEmpty(), "server-only generation stays off vanilla network");
        } finally {
            buffer.release();
        }
        helper.assertTrue(LifeReferences.generation(EntityReference.codec().parse(NbtOps.INSTANCE,
                UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, target).getOrThrow()).getOrThrow()).isEmpty(), "legacy UUID stays unstamped");

        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        EntityReference.store(payload.owner(), output, "custom_owner");
        output.store("custom_list", EntityReference.<LivingEntity>codec().listOf(), payload.trusted());
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), roundTrip(output.buildResult()));
        generation(helper, EntityReference.read(input, "custom_owner"), 2);
        generation(helper, EntityReference.readWithOldOwnerConversion(input, "custom_owner", helper.getLevel()), 2);
        generation(helper, input.read("custom_list", EntityReference.<LivingEntity>codec().listOf()).orElseThrow().getFirst(), 3);
        CompoundTag originallyEmptyExtension = new CompoundTag();
        originallyEmptyExtension.put(ReferencePersistence.CARRIER, new CompoundTag());
        originallyEmptyExtension.put("Owner", encode(target, 2));
        CompoundTag originalShape = originallyEmptyExtension.copy();
        originallyEmptyExtension = (CompoundTag) ReferencePersistence.pack(NbtOps.INSTANCE, originallyEmptyExtension);
        helper.assertValueEqual(roundTrip(originallyEmptyExtension), originalShape, "preexisting empty extension preserved");
        helper.succeed();
    }

    private static void codecComposition(GameTestHelper helper) {
        UUID target = UUID.randomUUID();
        Codec<Payload> codec = ReferencePersistence.wrap(Payload.CODEC);
        Payload payload = new Payload(LifeReferences.restore(target, 3), List.of(LifeReferences.restore(target, 1)),
                Map.of("a/b", List.of(LifeReferences.restore(target, 2))));
        CompoundTag prefix = new CompoundTag();
        prefix.putString("existing", "keep");
        CompoundTag extension = new CompoundTag();
        extension.putString("othermod:field", "keep extension");
        prefix.put(ReferencePersistence.CARRIER, extension);
        CompoundTag before = prefix.copy();
        helper.assertTrue(UUIDUtil.CODEC.encode(target, NbtOps.INSTANCE, prefix).isError(), "primitive UUID codecs cannot merge an object prefix");
        CompoundTag encoded = (CompoundTag) codec.encode(payload, NbtOps.INSTANCE, prefix).getOrThrow();
        helper.assertValueEqual(prefix, before, "joint encoding does not mutate the caller's prefix");
        helper.assertValueEqual(encoded.getString("existing").orElseThrow(), "keep", "joint encoding retains existing root fields");
        helper.assertValueEqual(encoded.getCompound(ReferencePersistence.CARRIER).orElseThrow().getString("othermod:field").orElseThrow(),
                "keep extension", "joint encoding retains existing extension fields");
        helper.assertTrue(encoded.get("Owner") instanceof IntArrayTag owner && owner.size() == 4, "joint encoding keeps the vanilla UUID shape");
        ReferenceMetadata metadata = ReferenceMetadata.CODEC.parse(NbtOps.INSTANCE,
                encoded.getCompound(ReferencePersistence.CARRIER).orElseThrow().get(ReferencePersistence.METADATA)).getOrThrow();
        helper.assertValueEqual(ReferenceMetadata.CODEC.parse(JsonOps.INSTANCE,
                        ReferenceMetadata.CODEC.encodeStart(JsonOps.INSTANCE, metadata).getOrThrow()).getOrThrow(), metadata,
                "the metadata schema supports ordinary Codec JSON round trips");
        CompoundTag raw = rawRoundTrip(encoded);
        CompoundTag rawBefore = raw.copy();
        Payload decoded = codec.parse(NbtOps.INSTANCE, raw).getOrThrow();
        helper.assertValueEqual(raw, rawBefore, "decoding consumes tables in a returned view without editing serialized input");
        generation(helper, decoded.owner(), 3);
        generation(helper, decoded.trusted().getFirst(), 1);
        generation(helper, decoded.arbitrary().get("a/b").getFirst(), 2);

        Payload updated = new Payload(LifeReferences.restore(target, 4), List.of(), Map.of());
        CompoundTag replaced = (CompoundTag) codec.encode(updated, NbtOps.INSTANCE, encoded).getOrThrow();
        generation(helper, codec.parse(NbtOps.INSTANCE, rawRoundTrip(replaced)).getOrThrow().owner(), 4);
        helper.assertValueEqual(entries(replaced).size(), 1, "reencoding onto a known prefix rebuilds stale metadata through codecs");
        helper.assertValueEqual(codec.parse(NbtOps.INSTANCE, rawRoundTrip(encoded)).getOrThrow().owner().getUUID(), target,
                "prefix composition leaves the previous payload readable");
        helper.succeed();
    }

    private static void copyAndMove(GameTestHelper helper) {
        UUID target = UUID.randomUUID();
        CompoundTag root = new CompoundTag();
        root.put("source", encode(target, 1));
        root = (CompoundTag) ReferencePersistence.pack(NbtOps.INSTANCE, root);
        CompoundTag copy = root.copy();
        helper.assertTrue(((ReferenceCarrier) (Object) copy).reincarnation$isCarrier(), "complete-payload marker copied");
        copy.put("destination", copy.get("source"));
        copy.remove("source");
        copy.put("destination", encode(target, 2)); // same UUID, newer generation; old table is now stale
        copy = (CompoundTag) ReferencePersistence.restore(NbtOps.INSTANCE, copy);
        generation(helper, decode(copy.get("destination")), 2);
        CompoundTag moved = roundTrip(copy);
        generation(helper, decode(moved.get("destination")), 2);
        helper.assertTrue(!moved.contains("source"), "old path removed");
        CompoundTag converted = (CompoundTag) NbtOps.INSTANCE.convertTo(NbtOps.INSTANCE, copy);
        generation(helper, decode(converted.get("destination")), 2);
        generation(helper, decode(roundTrip(converted).get("destination")), 2);
        CompoundTag merged = new CompoundTag();
        merged.merge(copy);
        merged = (CompoundTag) ReferencePersistence.pack(NbtOps.INSTANCE, merged);
        generation(helper, decode(roundTrip(merged).get("destination")), 2);

        CompoundTag container = new CompoundTag();
        ListTag list = new ListTag();
        list.add(copy.copy());
        container.put("arbitrary_storage", list);
        CompoundTag nested = roundTrip(container);
        generation(helper, decode(((CompoundTag) nested.getList("arbitrary_storage").orElseThrow().getFirst()).get("destination")), 2);
        FriendlyByteBuf network = new FriendlyByteBuf(Unpooled.buffer());
        try {
            network.writeNbt(container);
            CompoundTag received = network.readNbt();
            generation(helper, decode(((CompoundTag) received.getList("arbitrary_storage").orElseThrow().getFirst()).get("destination")), 2);
        } finally {
            network.release();
        }
        CompoundTag similarlyNamedBusinessField = new CompoundTag();
        similarlyNamedBusinessField.put(ReferencePersistence.METADATA, copy.copy());
        generation(helper, decode(roundTrip(similarlyNamedBusinessField).getCompound(ReferencePersistence.METADATA)
                .orElseThrow().get("destination")), 2);
        copy = (CompoundTag) ReferencePersistence.pack(NbtOps.INSTANCE, copy);
        CompoundTag packed = copy.copy();
        copy = (CompoundTag) ReferencePersistence.pack(NbtOps.INSTANCE, copy);
        helper.assertValueEqual(copy, packed, "repeat pack is idempotent");
        copy.remove("destination");
        copy = (CompoundTag) ReferencePersistence.pack(NbtOps.INSTANCE, copy);
        helper.assertTrue(!copy.contains(ReferencePersistence.CARRIER), "last reference removal drops empty metadata");
        helper.succeed();
    }

    private static void invalidMetadata(GameTestHelper helper) {
        UUID target = UUID.randomUUID();
        CompoundTag packed = new CompoundTag();
        packed.put("Owner", encode(target, 5));
        packed = (CompoundTag) ReferencePersistence.pack(NbtOps.INSTANCE, packed);
        CompoundTag wrongTarget = rawRoundTrip(packed);
        wrongTarget.put("Owner", UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, UUID.randomUUID()).getOrThrow());
        rejected(helper, () -> ReferencePersistence.restore(NbtOps.INSTANCE, wrongTarget), "target mismatch");
        CompoundTag negative = rawRoundTrip(packed);
        ((CompoundTag) entries(negative).getFirst()).putLong("generation", -1);
        rejected(helper, () -> ReferencePersistence.restore(NbtOps.INSTANCE, negative), "negative generation");
        CompoundTag unknown = rawRoundTrip(packed);
        unknown.getCompound(ReferencePersistence.CARRIER).orElseThrow().getCompound(ReferencePersistence.METADATA).orElseThrow().putInt("version", 99);
        rejected(helper, () -> ReferencePersistence.restore(NbtOps.INSTANCE, unknown), "unknown schema");
        CompoundTag duplicate = rawRoundTrip(packed);
        entries(duplicate).add(entries(duplicate).getFirst().copy());
        rejected(helper, () -> ReferencePersistence.restore(NbtOps.INSTANCE, duplicate), "duplicate path");
        CompoundTag mutated = packed.copy();
        ((IntArrayTag) mutated.get("Owner")).getAsIntArray()[0] ^= 1;
        rejected(helper, () -> ReferencePersistence.pack(NbtOps.INSTANCE, mutated), "in-place UUID mutation");
        CompoundTag unwrapped = new CompoundTag();
        unwrapped.put("Owner", encode(target, 1));
        rejected(helper, () -> bytes(unwrapped), "unwrapped reference cannot be silently lost");

        // Nested tables can agree, but conflicting generations must never depend on traversal order.
        CompoundTag outer = rawRoundTrip(packed);
        CompoundTag inner = rawRoundTrip(packed);
        outer.put("nested", inner);
        CompoundTag cross = ((CompoundTag) entries(outer).getFirst()).copy();
        ListTag crossPath = new ListTag();
        CompoundTag segment = new CompoundTag();
        segment.putString("key", "nested");
        crossPath.add(segment);
        for (Tag step : cross.getList("path").orElseThrow()) crossPath.add(step.copy());
        cross.put("path", crossPath);
        cross.putLong("generation", 6);
        entries(outer).add(cross);
        rejected(helper, () -> ReferencePersistence.restore(NbtOps.INSTANCE, outer), "conflicting nested table");
        // Validation is atomic: no node receives a partial stamp when another entry is invalid.
        helper.assertTrue(((StampedTag) (Object) outer.get("Owner")).reincarnation$stamp() == null, "invalid tree was not partially restored");
        helper.succeed();
    }

    private static void generationAndCache(GameTestHelper helper) {
        ServerPlayer player = player(helper, UUID.randomUUID());
        EntityReference<ServerPlayer> old = EntityReference.of(player);
        generation(helper, old, 0);
        helper.assertTrue(old.getEntity(uuid -> player, ServerPlayer.class) == player, "cache initially resolves");
        helper.assertTrue(old.getEntity(helper.getLevel(), ServerPlayer.class) == player, "Level overload initially resolves");
        Generations.commit(helper.getLevel().getServer(), player.getUUID(), 0, 1);
        helper.assertTrue(old.getEntity(uuid -> player, ServerPlayer.class) == null, "live cached old player rejected");
        helper.assertTrue(old.getEntity(helper.getLevel(), ServerPlayer.class) == null, "Level overload rejects old life");
        helper.assertTrue(!old.matches(player), "matches rejects old life");
        EntityReference<ServerPlayer> fresh = EntityReference.of(player);
        generation(helper, fresh, 1);
        helper.assertTrue(fresh.matches(player), "new binding matches");
        helper.assertTrue(!EntityReference.<ServerPlayer>of(player.getUUID()).matches(player), "unstamped old UUID is generation zero");
        EntityReference<ServerPlayer> offline = LifeReferences.bind(helper.getLevel(), player.getUUID());
        generation(helper, offline, 1);
        helper.assertTrue(offline.getEntity(uuid -> null, ServerPlayer.class) == null, "offline temporarily unresolved");
        helper.assertTrue(offline.getEntity(uuid -> player, ServerPlayer.class) == player, "same life re-resolves without renewing stamp");
        player.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
        ServerPlayer replacement = player(helper, player.getUUID());
        helper.assertTrue(fresh.getEntity(uuid -> replacement, ServerPlayer.class) == replacement, "normal same-life entity reconstruction");
        helper.assertTrue(old.getEntity(uuid -> replacement, ServerPlayer.class) == null, "old reference cannot attach to replacement");
        helper.succeed();
    }

    private static void syncedOwner(GameTestHelper helper) {
        Wolf wolf = EntityType.WOLF.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        ServerPlayer player = player(helper, UUID.randomUUID());
        EntityReference<LivingEntity> old = EntityReference.of(player);
        wolf.setOwnerReference(old);
        wolf.getEntityData().packDirty();
        Generations.commit(helper.getLevel().getServer(), player.getUUID(), 0, 1);
        EntityReference<LivingEntity> replacement = EntityReference.of(player);
        wolf.setOwnerReference(replacement);
        helper.assertTrue(wolf.getOwnerReference() == replacement, "same UUID new stamp replaces server value");
        helper.assertTrue(wolf.getEntityData().packDirty() != null, "same UUID new stamp marks synchronization dirty");
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        wolf.saveWithoutId(output);
        CompoundTag stored = roundTrip(output.buildResult());
        helper.assertTrue(stored.get("Owner") instanceof IntArrayTag, "real wolf keeps vanilla Owner shape");
        Wolf loaded = EntityType.WOLF.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), stored));
        generation(helper, loaded.getOwnerReference(), 1);
        helper.assertTrue(loaded.getOwnerReference().matches(player), "real wolf owner restored");
        helper.succeed();
    }

    private static final class ReferenceData extends SavedData {
        static final Codec<ReferenceData> CODEC = EntityReference.<LivingEntity>codec().listOf().xmap(ReferenceData::new, data -> data.values);
        final List<EntityReference<LivingEntity>> values;

        ReferenceData(List<EntityReference<LivingEntity>> values) {
            this.values = values;
        }
    }

    private static void savedData(GameTestHelper helper) {
        try {
            Path directory = Files.createTempDirectory("reincarnation-saved-data-");
            SavedDataType<ReferenceData> type = new SavedDataType<>(id("references"), () -> new ReferenceData(List.of()), ReferenceData.CODEC);
            UUID target = UUID.randomUUID();
            try (SavedDataStorage storage = new SavedDataStorage(helper.getLevel(), directory, helper.getLevel().getServer().getFixerUpper(), helper.getLevel().registryAccess())) {
                storage.set(type, new ReferenceData(List.of(LifeReferences.restore(target, 7), LifeReferences.restore(target, 8))));
                storage.saveAndJoin();
            }
            Path file = id("references").withSuffix(".dat").resolveAgainst(directory);
            helper.assertTrue(Files.exists(file), "SavedData actually written to disk");
            try (SavedDataStorage storage = new SavedDataStorage(helper.getLevel(), directory, helper.getLevel().getServer().getFixerUpper(), helper.getLevel().registryAccess())) {
                ReferenceData restored = storage.get(type);
                helper.assertTrue(restored != null, "SavedData restored through factory codec");
                generation(helper, restored.values.get(0), 7);
                generation(helper, restored.values.get(1), 8);
            }
            helper.succeed();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void indexRecovery(GameTestHelper helper) {
        try {
            Path file = Files.createTempDirectory("reincarnation-generations-").resolve("generations.dat");
            UUID target = UUID.randomUUID();
            GenerationIndex index = GenerationIndex.open(file);
            helper.assertValueEqual(index.current(target), 0L, "initial generation");
            index.commit(target, 0, 1);
            index.commit(target, 0, 1);
            helper.assertValueEqual(index.current(target), 1L, "idempotent transition");
            helper.assertValueEqual(GenerationIndex.open(file).current(target), 1L, "durable generation after reopening");
            try {
                index.commit(target, 0, 2);
                helper.fail("generation skip accepted");
            } catch (IllegalArgumentException _) {
            }
            Files.writeString(file, "corrupt index");
            try {
                GenerationIndex.open(file);
                helper.fail("corrupt index reset to zero");
            } catch (IllegalStateException _) {
            }
            Files.delete(file);
            try {
                GenerationIndex.open(file);
                helper.fail("missing initialized index reset to zero");
            } catch (IllegalStateException _) {
            }
            helper.succeed();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ServerPlayer player(GameTestHelper helper, UUID uuid) {
        return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), new GameProfile(uuid, "LifeTest"), ClientInformation.createDefault());
    }

    private static void rejected(GameTestHelper helper, Runnable operation, String label) {
        try {
            operation.run();
            helper.fail("Accepted invalid metadata: " + label);
        } catch (ReferenceMetadataException _) {
        }
    }

    private static Tag encode(UUID target, long generation) {
        return EntityReference.codec().encodeStart(NbtOps.INSTANCE, LifeReferences.restore(target, generation)).getOrThrow();
    }

    private static EntityReference<LivingEntity> decode(Tag tag) {
        return EntityReference.<LivingEntity>codec().parse(NbtOps.INSTANCE, tag).getOrThrow();
    }

    private static void generation(GameTestHelper helper, EntityReference<?> reference, long expected) {
        helper.assertTrue(reference != null, "reference exists");
        helper.assertValueEqual(LifeReferences.generation(reference).orElse(-1L), expected, "bound generation");
    }

    private static ListTag entries(CompoundTag root) {
        return root.getCompound(ReferencePersistence.CARRIER).orElseThrow().getCompound(ReferencePersistence.METADATA).orElseThrow().getList("entries").orElseThrow();
    }

    private static byte[] bytes(CompoundTag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CompoundTag roundTrip(CompoundTag tag) {
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(bytes(tag)), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CompoundTag rawRoundTrip(CompoundTag tag) {
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes(tag))))) {
            // Inspect bytes below the complete-input hook to build intentionally corrupt fixtures.
            if (input.readByte() != 10) throw new IllegalStateException("Expected compound fixture");
            input.readUTF();
            return CompoundTag.TYPE.load(input, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, "gametest/" + path);
    }
}
