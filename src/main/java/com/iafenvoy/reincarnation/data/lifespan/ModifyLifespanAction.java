package com.iafenvoy.reincarnation.data.lifespan;

import com.iafenvoy.reincarnation.api.LifespanApi;
import com.iafenvoy.saturn.data.action.EntityAction;
import com.iafenvoy.saturn.data.context.action.EntityActionContext;
import com.iafenvoy.saturn.util.formula.NumberProvider;
import com.iafenvoy.saturn.util.formula.number.Constant;
import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import java.util.Locale;

public record ModifyLifespanAction(Mode mode, NumberProvider amount) implements EntityAction {
    public static final MapCodec<ModifyLifespanAction> CODEC = RecordCodecBuilder.<ModifyLifespanAction>mapCodec(i -> i.group(
            Mode.CODEC.optionalFieldOf("mode", Mode.ADD).forGetter(ModifyLifespanAction::mode),
            NumberProvider.CODEC.fieldOf("amount").forGetter(ModifyLifespanAction::amount)
    ).apply(i, ModifyLifespanAction::new)).validate(action -> action.mode == Mode.SET && action.amount instanceof Constant(double value) && value < 0
            ? DataResult.error(() -> "Setting lifespan requires a non-negative amount") : DataResult.success(action));

    @Override
    public void execute(@NotNull EntityActionContext context) {
        if (!(context.entity() instanceof LivingEntity living)) return;
        double amount = this.amount.evaluate(context.formula());
        if (!Double.isFinite(amount)) return;
        if (this.mode == Mode.SET) LifespanApi.set(living, Math.round(amount));
        else LifespanApi.add(living, Math.round(amount));
    }

    @Override
    public @NotNull MapCodec<ModifyLifespanAction> codec() {
        return CODEC;
    }

    public enum Mode implements StringRepresentable {
        ADD, SET;
        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);
        @Override
        public @NotNull String getSerializedName() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }
}
