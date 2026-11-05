package com.iafenvoy.reincarnation.storage;

import com.iafenvoy.saturn.util.FileAccess;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persisted account generations. Loading failure must never look like a fresh index.
 */
public final class GenerationIndex {
    private static final Codec<Map<UUID, Long>> CODEC = CompoundTag.CODEC.comapFlatMap(root -> {
        try {
            if (root.getInt("version").orElse(-1) != 1)
                throw new IllegalArgumentException("Unsupported generation index version");
            Map<UUID, Long> values = new HashMap<>();
            for (Tag tag : root.getList("accounts").orElseThrow()) {
                if (!(tag instanceof CompoundTag entry)) throw new IllegalArgumentException("Malformed account entry");
                UUID uuid = UUID.fromString(entry.getString("uuid").orElseThrow());
                long generation = entry.getLong("generation").orElseThrow();
                if (generation < 0 || values.putIfAbsent(uuid, generation) != null)
                    throw new IllegalArgumentException("Invalid or duplicate account generation");
            }
            return DataResult.success(values);
        } catch (RuntimeException failure) {
            return DataResult.error(() -> "Invalid generation index: " + failure.getMessage());
        }
    }, values -> {
        CompoundTag root = new CompoundTag();
        root.putInt("version", 1);
        ListTag entries = new ListTag();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(value -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("uuid", value.getKey().toString());
            entry.putLong("generation", value.getValue());
            entries.add(entry);
        });
        root.put("accounts", entries);
        return root;
    });
    private final Path file;
    private Map<UUID, Long> generations;

    private GenerationIndex(Path file, Map<UUID, Long> generations) {
        this.file = file;
        this.generations = generations;
    }

    public static GenerationIndex open(Path file) {
        try {
            Path marker = file.resolveSibling(file.getFileName() + ".initialized");
            if (!Files.exists(file)) {
                if (Files.exists(marker)) throw new IOException("Initialized generation index is missing");
                GenerationIndex index = new GenerationIndex(file, new HashMap<>());
                index.write(index.generations);
                Files.writeString(marker, "Reincarnation generation index v1\n", StandardOpenOption.CREATE_NEW);
                return index;
            }
            Map<UUID, Long> values = FileAccess.NBT.load(CODEC, file)
                    .orElseThrow(() -> new IllegalStateException("Cannot decode generation index"));
            if (!Files.exists(marker))
                Files.writeString(marker, "Reincarnation generation index v1\n", StandardOpenOption.CREATE_NEW);
            return new GenerationIndex(file, values);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot load generation index " + file + "; restore it before continuing", e);
        }
    }

    public synchronized long current(UUID account) {
        return this.generations.getOrDefault(account, 0L);
    }

    /**
     * Called by a transaction coordinator AFTER saving its commit intent, never as a standalone reset.
     */
    public synchronized void commit(UUID account, long expected, long next) {
        if (expected < 0 || expected == Long.MAX_VALUE || next != expected + 1)
            throw new IllegalArgumentException("Generation must advance by exactly one");
        long current = this.current(account);
        if (current == next) return; // replay of the same generation transition
        if (current != expected) throw new IllegalStateException("Generation changed during transaction");
        Map<UUID, Long> updated = new HashMap<>(this.generations);
        updated.put(account, next);
        try {
            this.write(updated); // Publish only after FileAccess reports a successful save.
            this.generations = updated;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot commit generation index " + this.file, e);
        }
    }

    private void write(Map<UUID, Long> values) throws IOException {
        Files.createDirectories(this.file.toAbsolutePath().getParent());
        if (!FileAccess.NBT.save(CODEC, this.file, values))
            throw new IOException("Cannot save generation index: " + this.file);
    }
}
