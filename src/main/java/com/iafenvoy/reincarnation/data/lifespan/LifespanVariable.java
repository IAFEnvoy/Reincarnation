package com.iafenvoy.reincarnation.data.lifespan;

import com.iafenvoy.reincarnation.api.LifespanApi;
import com.iafenvoy.saturn.util.formula.FormulaContext;
import com.iafenvoy.saturn.util.formula.FormulaVariable;
import net.minecraft.world.entity.Entity;
import java.util.Set;

public final class LifespanVariable implements FormulaVariable {
    @Override
    public Set<String> names() {
        return Set.of("lifespan_remaining", "lifespan_total");
    }

    @Override
    public double value(String key, String suffix, FormulaContext context) {
        if (!suffix.isEmpty()) return Double.NaN;
        Entity entity = context.caster() != null ? context.caster() : context.player();
        if (entity == null) return Double.NaN;
        var value = LifespanApi.get(entity);
        long result = key.equals("lifespan_remaining") ? value.remaining() : value.total();
        return result < 0 ? Double.NaN : result;
    }
}
