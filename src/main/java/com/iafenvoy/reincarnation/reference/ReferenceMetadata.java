package com.iafenvoy.reincarnation.reference;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

public record ReferenceMetadata(int version, boolean createdExtension, List<Entry> entries) {
    public static final int MAX_DEPTH = 512;
    public static final int MAX_ENTRIES = 100_000;
    public static final Codec<Either<String, Integer>> STEP_CODEC = Codec.either(
            Codec.STRING.fieldOf("key").codec(), Codec.intRange(0, Integer.MAX_VALUE).fieldOf("index").codec());
    public static final Codec<ReferenceMetadata> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(1, 1).fieldOf("version").forGetter(ReferenceMetadata::version),
            Codec.BOOL.optionalFieldOf("created_extension", false).forGetter(ReferenceMetadata::createdExtension),
            Codec.list(Entry.CODEC, 0, MAX_ENTRIES).fieldOf("entries").forGetter(ReferenceMetadata::entries)
    ).apply(i, ReferenceMetadata::new));

    public ReferenceMetadata {
        entries = List.copyOf(entries);
    }

    public record Entry(List<Either<String, Integer>> path, ReferenceStamp stamp) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.list(STEP_CODEC, 1, MAX_DEPTH).fieldOf("path").forGetter(Entry::path),
                ReferenceStamp.MAP_CODEC.forGetter(Entry::stamp)
        ).apply(i, Entry::new));

        public Entry {
            path = List.copyOf(path);
        }
    }
}
