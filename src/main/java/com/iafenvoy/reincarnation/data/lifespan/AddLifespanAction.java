package com.iafenvoy.reincarnation.data.lifespan;

import com.iafenvoy.reincarnation.api.LifespanApi;
import com.iafenvoy.saturn.data.action.EntityAction;
import com.iafenvoy.saturn.data.context.action.EntityActionContext;
import com.iafenvoy.saturn.util.formula.NumberProvider;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

public record AddLifespanAction(NumberProvider amount) implements EntityAction {
    public static final MapCodec<AddLifespanAction> CODEC = NumberProvider.CODEC.fieldOf("amount").xmap(AddLifespanAction::new, AddLifespanAction::amount);

    @Override
    public void execute(@NotNull EntityActionContext context) {
        if (!(context.entity() instanceof LivingEntity living)) return;
        double amount = this.amount.evaluate(context.formula());
        if (Double.isFinite(amount) && amount >= 0) LifespanApi.add(living, Math.round(amount));
    }

    @Override
    public @NotNull MapCodec<AddLifespanAction> codec() {
        return CODEC;
    }
}
