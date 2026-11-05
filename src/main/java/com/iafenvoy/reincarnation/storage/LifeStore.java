package com.iafenvoy.reincarnation.storage;

import com.iafenvoy.reincarnation.lifecycle.LifeTransaction;
import com.iafenvoy.reincarnation.lifecycle.SoulRecord;
import com.iafenvoy.saturn.util.FileAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

public final class LifeStore {
    private final Path root;

    public LifeStore(MinecraftServer server) {
        this.root = directory(server.getWorldPath(LevelResource.ROOT).resolve("reincarnation"));
    }

    public Path transactionFile(UUID id) {
        return this.root.resolve("transactions/" + id + "/transaction.dat");
    }

    public Path archiveFile(UUID id) {
        return this.root.resolve("transactions/" + id + "/archive.dat");
    }

    public Path initializedRecordFile(UUID id) {
        return this.root.resolve("transactions/" + id + "/initialized.dat");
    }

    public Path historyFile(UUID account, long generation) {
        if (generation < 0) throw new IllegalArgumentException("Negative generation");
        return this.root.resolve("history/" + account + "/" + generation + ".dat");
    }

    public String archive(UUID id, CompoundTag snapshot) {
        Path file = this.archiveFile(id);
        if (Files.exists(file)) throw new IllegalStateException("Archive already exists: " + id);
        directory(file.getParent());
        if (!FileAccess.NBT.save(CompoundTag.CODEC, file, snapshot))
            throw new IllegalStateException("Cannot save life archive: " + file);
        return this.archiveHash(id);
    }

    public String archiveHash(UUID id) {
        Path file = this.archiveFile(id);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot hash life archive: " + file, failure);
        }
    }

    public CompoundTag archive(LifeTransaction tx) {
        if (!this.archiveHash(tx.id()).equals(tx.archiveHash()))
            throw new IllegalStateException("Archive checksum mismatch: " + tx.id());
        CompoundTag archive = FileAccess.NBT.load(CompoundTag.CODEC, this.archiveFile(tx.id())).orElseThrow(() -> new IllegalStateException("Cannot load life archive: " + tx.id()));
        if (archive.getInt("version").orElse(-1) != 1)
            throw new IllegalStateException("Unsupported life archive version");
        if (!archive.getString("transaction").orElseThrow().equals(tx.id().toString()))
            throw new IllegalStateException("Archive transaction mismatch");
        return archive;
    }

    public Optional<CompoundTag> archive(SoulRecord record) {
        if (record.transactionId().isEmpty()) return Optional.empty();
        UUID id = UUID.fromString(record.transactionId());
        if (!Files.exists(this.transactionFile(id)) && !Files.exists(this.archiveFile(id))) return Optional.empty();
        LifeTransaction tx = FileAccess.NBT.load(LifeTransaction.CODEC, this.transactionFile(id)).orElseThrow(() -> new IllegalStateException("Cannot load life transaction: " + id));
        if (!tx.id().equals(id) || !tx.account().equals(record.account()) || tx.from() != record.generation()
                || tx.state() != LifeTransaction.State.COMMITTED)
            throw new IllegalStateException("History transaction mismatch");
        CompoundTag archive = this.archive(tx);
        SoulRecord archived = SoulRecord.load(archive.getCompound("record").orElseThrow());
        if (!archived.equals(record)) throw new IllegalStateException("History summary mismatch");
        return Optional.of(archive);
    }

    public void transaction(LifeTransaction tx) {
        Path file = this.transactionFile(tx.id());
        directory(file.getParent());
        if (!FileAccess.NBT.save(LifeTransaction.CODEC, file, tx))
            throw new IllegalStateException("Cannot save life transaction: " + file);
    }

    public List<LifeTransaction> transactions() {
        List<LifeTransaction> result = new ArrayList<>();
        Path directory = this.root.resolve("transactions");
        if (!Files.exists(directory)) return result;
        try (Stream<@NotNull Path> files = Files.list(directory)) {
            for (Path child : files.filter(Files::isDirectory).toList()) {
                Path file = child.resolve("transaction.dat");
                if (!Files.exists(file)) continue; // archive-only prepare interrupted before creating its manifest
                LifeTransaction tx = FileAccess.NBT.load(LifeTransaction.CODEC, file).orElseThrow(() -> new IllegalStateException("Cannot load life transaction: " + file));
                if (!child.getFileName().toString().equals(tx.id().toString()))
                    throw new IllegalStateException("Transaction filename mismatch");
                result.add(tx);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot enumerate reincarnation transactions", e);
        }
        result.sort(Comparator.comparingLong(LifeTransaction::createdAt));
        return result;
    }

    public void history(SoulRecord record) {
        Path file = this.historyFile(record.account(), record.generation());
        if (Files.exists(file)) {
            if (!FileAccess.NBT.load(SoulRecord.CODEC, file)
                    .orElseThrow(() -> new IllegalStateException("Cannot load life history: " + file))
                    .transactionId().equals(record.transactionId()))
                throw new IllegalStateException("Refusing to replace an existing life archive");
            return;
        }
        directory(file.getParent());
        if (!FileAccess.NBT.save(SoulRecord.CODEC, file, record))
            throw new IllegalStateException("Cannot save life history: " + file);
    }

    public Optional<SoulRecord> history(UUID account, long generation) {
        Path file = this.historyFile(account, generation);
        return Files.exists(file) ? Optional.of(FileAccess.NBT.load(SoulRecord.CODEC, file)
                .orElseThrow(() -> new IllegalStateException("Cannot load life history: " + file))) : Optional.empty();
    }

    public List<SoulRecord> history(UUID account) {
        Path directory = this.root.resolve("history/" + account);
        if (!Files.exists(directory)) return List.of();
        List<SoulRecord> records = new ArrayList<>();
        try (Stream<@NotNull Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".dat")).toList())
                records.add(FileAccess.NBT.load(SoulRecord.CODEC, file)
                        .orElseThrow(() -> new IllegalStateException("Cannot load life history: " + file)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read life history", e);
        }
        records.sort(Comparator.comparingLong(SoulRecord::generation).reversed());
        return List.copyOf(records);
    }

    public void current(SoulRecord record) {
        Path file = directory(this.root.resolve("accounts")).resolve(record.account() + ".dat");
        if (!FileAccess.NBT.save(SoulRecord.CODEC, file, record))
            throw new IllegalStateException("Cannot save current life: " + file);
    }

    public Optional<SoulRecord> current(UUID account) {
        Path file = this.root.resolve("accounts/" + account + ".dat");
        return Files.exists(file) ? Optional.of(FileAccess.NBT.load(SoulRecord.CODEC, file)
                .orElseThrow(() -> new IllegalStateException("Cannot load current life: " + file))) : Optional.empty();
    }

    public List<SoulRecord> accounts() {
        Path directory = this.root.resolve("accounts");
        if (!Files.exists(directory)) return List.of();
        List<SoulRecord> result = new ArrayList<>();
        try (Stream<@NotNull Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".dat")).toList())
                result.add(FileAccess.NBT.load(SoulRecord.CODEC, file)
                        .orElseThrow(() -> new IllegalStateException("Cannot load current life: " + file)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot enumerate accounts", e);
        }
        return result;
    }

    private static Path directory(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot create life data directory: " + path, failure);
        }
    }
}
