package com.iafenvoy.reincarnation.reference;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.UUIDUtil;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/** Complete-payload persistence. Codecs own the schema; DynamicOps handles structural navigation. */
public final class ReferencePersistence {
    public static final String CARRIER = "NeoForgeData";
    public static final String METADATA = "reincarnation:reference_metadata";
    private static final Codec<ReferenceMetadata> TABLE_CODEC = ReferenceMetadata.CODEC.fieldOf(METADATA).codec();
    private static final Codec<Dynamic<?>> EXTENSION_CODEC = Codec.PASSTHROUGH.fieldOf(CARRIER).codec();

    private ReferencePersistence() {
    }

    /** Compose complete object codecs while retaining the caller's prefix and existing extension fields. */
    public static <A> Codec<A> wrap(Codec<A> codec) {
        return new Codec<>() {
            @Override
            public <T> DataResult<T> encode(A value, DynamicOps<T> ops, T prefix) {
                return codec.encode(value, ops, prefix).map(encoded -> {
                    copyTransient(prefix, encoded);
                    return pack(ops, encoded);
                });
            }

            @Override
            public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
                return codec.decode(ops, restore(ops, input));
            }
        };
    }

    /** Validate every table before assigning any transient stamps. Serialized input is never edited. */
    public static <T> T restore(DynamicOps<T> ops, T tree) {
        Map<T, ReferenceMetadata> tables = new IdentityHashMap<>();
        Map<StampedTag, ReferenceStamp> pending = new IdentityHashMap<>();
        walk(ops, tree, new ArrayList<>(), false, (node, _) -> {
            field(ops, node, CARRIER).filter(extension -> field(ops, extension, METADATA).isPresent()).ifPresent(extension -> {
                ReferenceMetadata table = TABLE_CODEC.parse(ops, extension).getOrThrow(ReferenceMetadataException::new);
                tables.put(node, table);
                if (!isCarrier(node)) validate(ops, node, table, pending);
            });
            return true;
        }, 0);
        if (tables.isEmpty()) return tree;
        pending.forEach(StampedTag::reincarnation$stamp);
        return transform(ops, tree, false, (original, current) -> {
            ReferenceMetadata table = tables.get(original);
            if (table == null) return current;
            T extension = ops.remove(field(ops, current, CARRIER).orElseThrow(), METADATA);
            T restored = table.createdExtension() && ops.getMap(extension).getOrThrow().entries().findAny().isEmpty()
                    ? ops.remove(current, CARRIER) : mergeExtension(ops, current, extension);
            markCarrier(restored);
            return restored;
        }, 0);
    }

    /** Only complete, extensible objects may own a reference table. */
    public static <T> T pack(DynamicOps<T> ops, T tree) {
        T restored = restore(ops, tree);
        ops.getMap(restored).getOrThrow(ReferenceMetadataException::new);
        markCarrier(restored);
        return rebuild(ops, restored);
    }

    /** Rebuild paths at the final write boundary without changing the supplied payload. */
    public static <T> T snapshotForWrite(DynamicOps<T> ops, T tree) {
        boolean[] needed = {false};
        walk(ops, tree, new ArrayList<>(), false, (node, _) -> {
            if (node instanceof StampedTag stamp && stamp.reincarnation$stamp() != null
                    || field(ops, node, CARRIER).flatMap(extension -> field(ops, extension, METADATA)).isPresent()) {
                needed[0] = true;
                return false;
            }
            return true;
        }, 0);
        if (!needed[0]) return tree;
        T restored = restore(ops, tree);
        walk(ops, restored, new ArrayList<>(), false, (node, _) -> {
            if (isCarrier(node)) return false;
            if (node instanceof StampedTag stamp && stamp.reincarnation$stamp() != null)
                fail("Stamped reference has no complete payload wrapper; compose a ReferencePersistence codec first");
            return true;
        }, 0);
        return rebuild(ops, restored);
    }

    public static void copyTransient(Object source, Object destination) {
        if (source instanceof StampedTag from && destination instanceof StampedTag to)
            to.reincarnation$stamp(from.reincarnation$stamp());
        if (isCarrier(source)) markCarrier(destination);
    }

    private static <T> void validate(DynamicOps<T> ops, T root, ReferenceMetadata table, Map<StampedTag, ReferenceStamp> pending) {
        Set<StampedTag> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ReferenceMetadata.Entry entry : table.entries()) {
            T node = follow(ops, root, entry.path());
            if (!(node instanceof StampedTag)) fail("Reference path does not point to a supported UUID node");
            StampedTag target = (StampedTag) node;
            if (!seen.add(target)) fail("Duplicate reference path in one table");
            if (!entry.stamp().target().equals(UUIDUtil.CODEC.parse(ops, node).getOrThrow(ReferenceMetadataException::new)))
                fail("Reference path target changed");
            ReferenceStamp previous = pending.putIfAbsent(target, entry.stamp());
            if (target.reincarnation$stamp() != null && !target.reincarnation$stamp().equals(entry.stamp())
                    || previous != null && !previous.equals(entry.stamp()))
                fail("Conflicting reference generations in nested tables");
        }
    }

    private static <T> T follow(DynamicOps<T> ops, T root, List<Either<String, Integer>> path) {
        T current = root;
        String previousKey = null;
        for (Either<String, Integer> step : path) {
            if (step.left().isPresent()) {
                String key = step.left().orElseThrow();
                if (CARRIER.equals(previousKey) && METADATA.equals(key)) fail("Path points into reference metadata");
                current = field(ops, current, key).orElseThrow(() -> new ReferenceMetadataException("Reference path is missing"));
                previousKey = key;
            } else {
                current = ops.getStream(current).getOrThrow(ReferenceMetadataException::new).skip(step.right().orElseThrow())
                        .findFirst().orElseThrow(() -> new ReferenceMetadataException("Reference index is missing"));
                previousKey = null;
            }
        }
        return current;
    }

    private static <T> T rebuild(DynamicOps<T> ops, T tree) {
        return transform(ops, tree, false, (_, current) -> {
            if (!isCarrier(current)) return current;
            List<ReferenceMetadata.Entry> entries = new ArrayList<>();
            walk(ops, current, new ArrayList<>(), false, (node, path) -> {
                if (node != current && isCarrier(node)) return false;
                if (node instanceof StampedTag stamped && stamped.reincarnation$stamp() != null) {
                    ReferenceStamp stamp = stamped.reincarnation$stamp();
                    if (!stamp.target().equals(UUIDUtil.CODEC.parse(ops, node).getOrThrow(ReferenceMetadataException::new)))
                        fail("Stamped UUID node was modified");
                    if (entries.size() >= ReferenceMetadata.MAX_ENTRIES) fail("Too many reference entries");
                    entries.add(new ReferenceMetadata.Entry(path, stamp));
                }
                return true;
            }, 0);
            if (entries.isEmpty()) return current;
            Optional<T> existing = field(ops, current, CARRIER);
            T extension = existing.orElseGet(ops::emptyMap);
            // Encode onto the existing extension first, then encode that result onto the complete prefix.
            T encoded = TABLE_CODEC.encode(new ReferenceMetadata(1, existing.isEmpty(), entries), ops, extension)
                    .getOrThrow(ReferenceMetadataException::new);
            return mergeExtension(ops, current, encoded);
        }, 0);
    }

    private static <T> T mergeExtension(DynamicOps<T> ops, T prefix, T extension) {
        T result = EXTENSION_CODEC.encode(new Dynamic<>(ops, extension), ops, prefix).getOrThrow(ReferenceMetadataException::new);
        copyTransient(prefix, result);
        return result;
    }

    private static boolean isCarrier(Object node) {
        return node instanceof ReferenceCarrier carrier && carrier.reincarnation$isCarrier();
    }

    private static void markCarrier(Object node) {
        if (!(node instanceof ReferenceCarrier)) fail("Persistence backend cannot carry complete-payload markers");
        ((ReferenceCarrier) node).reincarnation$markCarrier();
    }

    private static <T> Optional<T> field(DynamicOps<T> ops, T node, String key) {
        return ops.getMap(node).result().flatMap(map -> Optional.ofNullable(map.get(key)));
    }

    private static <T> void walk(DynamicOps<T> ops, T tree, List<Either<String, Integer>> path, boolean extension,
                                 BiPredicate<T, List<Either<String, Integer>>> visitor, int depth) {
        checkDepth(depth);
        if (!visitor.test(tree, path) || tree instanceof StampedTag) return;
        var map = ops.getMap(tree).result();
        if (map.isPresent()) {
            for (Pair<T, T> entry : map.get().entries().toList()) {
                String key = ops.getStringValue(entry.getFirst()).getOrThrow(ReferenceMetadataException::new);
                if (extension && METADATA.equals(key)) continue;
                path.add(Either.left(key));
                walk(ops, entry.getSecond(), path, CARRIER.equals(key), visitor, depth + 1);
                path.removeLast();
            }
        } else {
            var list = ops.getStream(tree).result();
            if (list.isEmpty()) return;
            int index = 0;
            for (T child : list.get().toList()) {
                path.add(Either.right(index++));
                walk(ops, child, path, false, visitor, depth + 1);
                path.removeLast();
            }
        }
    }

    private static <T> T transform(DynamicOps<T> ops, T tree, boolean extension, BiFunction<T, T, T> action, int depth) {
        checkDepth(depth);
        T result = tree;
        var map = ops.getMap(tree).result();
        if (map.isPresent()) {
            List<Pair<T, T>> entries = new ArrayList<>();
            boolean changed = false;
            for (Pair<T, T> entry : map.get().entries().toList()) {
                String key = ops.getStringValue(entry.getFirst()).getOrThrow(ReferenceMetadataException::new);
                T child = extension && METADATA.equals(key) ? entry.getSecond()
                        : transform(ops, entry.getSecond(), CARRIER.equals(key), action, depth + 1);
                changed |= child != entry.getSecond();
                entries.add(Pair.of(entry.getFirst(), child));
            }
            if (changed) result = ops.createMap(entries.stream());
        } else if (!(tree instanceof StampedTag)) {
            var list = ops.getStream(tree).result();
            if (list.isPresent()) {
                List<T> children = new ArrayList<>();
                boolean changed = false;
                for (T child : list.get().toList()) {
                    T replacement = transform(ops, child, false, action, depth + 1);
                    changed |= replacement != child;
                    children.add(replacement);
                }
                if (changed) result = ops.createList(children.stream());
            }
        }
        copyTransient(tree, result);
        return action.apply(tree, result);
    }

    private static void checkDepth(int depth) {
        if (depth > ReferenceMetadata.MAX_DEPTH) fail("Reference payload is too deeply nested");
    }

    private static void fail(String message) {
        throw new ReferenceMetadataException(message);
    }
}
