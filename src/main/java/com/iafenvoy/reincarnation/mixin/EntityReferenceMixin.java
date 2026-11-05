package com.iafenvoy.reincarnation.mixin;

import com.iafenvoy.reincarnation.api.LifeReferences;
import com.iafenvoy.reincarnation.reference.GenerationReference;
import com.iafenvoy.reincarnation.reference.ReferenceCodecs;
import com.iafenvoy.reincarnation.storage.Generations;
import com.mojang.serialization.Codec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.UUIDLookup;
import net.minecraft.world.level.entity.UniquelyIdentifyable;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(EntityReference.class)
public abstract class EntityReferenceMixin implements GenerationReference {
    @Unique
    private long reincarnation$generation = -1L;
    @Unique
    private static Codec<?> reincarnation$codec;

    @Unique
    private EntityReference<?> reincarnation$self() {
        return (EntityReference<?>) (Object) this;
    }

    @Override
    public long reincarnation$generation() {
        return this.reincarnation$generation;
    }

    @Override
    public void reincarnation$generation(long generation) {
        if (generation < 0 || this.reincarnation$generation >= 0 && this.reincarnation$generation != generation)
            throw new IllegalArgumentException("Reference generation must be non-negative and set only once");
        this.reincarnation$generation = generation;
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "codec", at = @At("RETURN"), cancellable = true)
    private static <T extends UniquelyIdentifyable> void reincarnation$codec(CallbackInfoReturnable<Codec<EntityReference<T>>> cir) {
        if (reincarnation$codec == null) reincarnation$codec = ReferenceCodecs.wrap(cir.getReturnValue());
        cir.setReturnValue((Codec<EntityReference<T>>) reincarnation$codec);
    }

    @Inject(method = "of(Lnet/minecraft/world/level/entity/UniquelyIdentifyable;)Lnet/minecraft/world/entity/EntityReference;", at = @At("RETURN"))
    private static <T extends UniquelyIdentifyable> void reincarnation$capture(T entity, CallbackInfoReturnable<EntityReference<T>> cir) {
        if (entity instanceof ServerPlayer player)
            ((GenerationReference) (Object) cir.getReturnValue()).reincarnation$generation(Generations.current(player.level().getServer(), player.getUUID()));
    }

    @Inject(method = "getEntity(Lnet/minecraft/world/level/entity/UUIDLookup;Ljava/lang/Class;)Lnet/minecraft/world/level/entity/UniquelyIdentifyable;", at = @At("RETURN"), cancellable = true)
    private void reincarnation$validate(UUIDLookup<?> lookup, Class<?> clazz, CallbackInfoReturnable<UniquelyIdentifyable> cir) {
        if (cir.getReturnValue() != null && !LifeReferences.isCurrent(this.reincarnation$self(), cir.getReturnValue()))
            cir.setReturnValue(null);
    }

    @Inject(method = "matches", at = @At("RETURN"), cancellable = true)
    private void reincarnation$matches(UniquelyIdentifyable entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !LifeReferences.isCurrent(this.reincarnation$self(), entity))
            cir.setReturnValue(false);
    }

    @Inject(method = "store(Lnet/minecraft/world/level/storage/ValueOutput;Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void reincarnation$store(ValueOutput output, String key, CallbackInfo ci) {
        output.store(key, EntityReference.codec(), this.reincarnation$self());
        ci.cancel();
    }

    @Inject(method = "readWithOldOwnerConversion", at = @At("HEAD"), cancellable = true)
    private static <T extends UniquelyIdentifyable> void reincarnation$readOwner(ValueInput input, String key, Level level, CallbackInfoReturnable<EntityReference<T>> cir) {
        // Legacy names still use vanilla conversion. UUID data goes through the common leaf bridge.
        if (input.getString(key).isPresent()) return;
        Optional<EntityReference<T>> reference = input.read(key, EntityReference.codec());
        reference.ifPresent(cir::setReturnValue);
    }
}
