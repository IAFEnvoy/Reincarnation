package com.iafenvoy.reincarnation.storage;

import com.iafenvoy.saturn.util.FileAccess;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.ExtraCodecs;

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
    private record Account(UUID uuid, long generation) {
        private static final Codec<Account> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.STRING_CODEC.fieldOf("uuid").forGetter(Account::uuid),
                ExtraCodecs.NON_NEGATIVE_LONG.fieldOf("generation").forGetter(Account::generation)
        ).apply(i, Account::new));
    }

    private static final Codec<Map<UUID, Long>> CODEC = Account.CODEC.listOf().comapFlatMap(accounts -> {
        Map<UUID, Long> values = new HashMap<>();
        for (Account account : accounts)
            if (values.putIfAbsent(account.uuid(), account.generation()) != null)
                return DataResult.error(() -> "Duplicate account generation: " + account.uuid());
        return DataResult.success(values);
    }, values -> values.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(entry -> new Account(entry.getKey(), entry.getValue())).toList());
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
            Map<UUID, Long> values = FileAccess.NBT.load(CODEC, file).orElseThrow(() -> new IllegalStateException("Cannot decode generation index"));
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
