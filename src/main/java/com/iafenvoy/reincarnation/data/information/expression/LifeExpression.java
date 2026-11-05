package com.iafenvoy.reincarnation.data.information.expression;

import com.iafenvoy.reincarnation.api.LifeNumbers;
import com.iafenvoy.reincarnation.data.information.getter.LifeGetter;
import com.iafenvoy.reincarnation.data.information.getter.LifeSourceGetter;
import com.iafenvoy.reincarnation.lifecycle.SoulRecord;
import com.iafenvoy.saturn.data.information.expression.ExpressionValue;
import com.iafenvoy.saturn.data.information.expression.InformationEvaluation;
import com.iafenvoy.saturn.data.information.expression.InformationExpression;
import com.iafenvoy.saturn.data.information.getter.EntityValueGetter;
import com.iafenvoy.saturn.data.information.model.InformationValue;
import com.iafenvoy.saturn.data.information.model.MissingReason;
import com.iafenvoy.saturn.data.information.model.PlayerViewMeta;
import com.iafenvoy.saturn.util.codec.ExtraEnumCodecs;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record LifeExpression(Value value) implements InformationExpression {
    public static final MapCodec<LifeExpression> CODEC = Value.CODEC.fieldOf("value").xmap(LifeExpression::new, LifeExpression::value);

    @Override
    public MapCodec<LifeExpression> codec() {
        return CODEC;
    }

    @Override
    public ExpressionValue evaluate(InformationEvaluation context) {
        SoulRecord record = context.getter(LifeGetter.INSTANCE, List.of()).decode(SoulRecord.CODEC);
        InformationValue result = switch (this.value) {
            case SOURCE -> translated("info.reincarnation.source." + source(context).name().toLowerCase(Locale.ROOT));
            case GENERATION -> text(LifeNumbers.display(record.generation()));
            case STATE ->
                    translated(record.endedAt() == 0 ? "command.reincarnation.state.current" : "command.reincarnation.state.ended");
            case STARTED -> time(record.startedAt());
            case ENDED ->
                    record.endedAt() == 0 ? new InformationValue.Missing(MissingReason.NOT_APPLICABLE) : time(record.endedAt());
            case OBSERVED -> time(record.observedAt());
            case REASON -> record.endedAt() == 0 ? translated("command.reincarnation.state.current")
                    : new InformationValue.Text(record.reason());
            case LEVEL -> new InformationValue.Number(record.level());
            case TOTAL_EXPERIENCE -> new InformationValue.Number(record.totalExperience());
            case INVENTORY_ITEMS -> new InformationValue.Number(record.inventoryItems());
            case ADVANCEMENTS -> new InformationValue.Number(record.completedAdvancements());
            case DEATHS -> new InformationValue.Number(record.deaths());
            case PLAYED_SECONDS -> text(Long.toString(record.playedTicks() / 20));
            case TRANSACTION ->
                    record.transactionId().isEmpty() ? new InformationValue.Missing(MissingReason.NOT_APPLICABLE) : text(record.transactionId());
            case SUMMARY_POSITION -> source(context) == PlayerViewMeta.SourceKind.SUMMARY
                    ? text(String.format(Locale.ROOT, "%s (%.1f, %.1f, %.1f)", record.dimension(), record.position().x, record.position().y, record.position().z))
                    : new InformationValue.Missing(MissingReason.NOT_APPLICABLE);
        };
        return new ExpressionValue.Display(result);
    }

    @Override
    public Set<EntityValueGetter<?>> getters() {
        return switch (this.value) {
            case SOURCE, SUMMARY_POSITION -> Set.of(LifeGetter.INSTANCE, LifeSourceGetter.INSTANCE);
            default -> Set.of(LifeGetter.INSTANCE);
        };
    }

    private static PlayerViewMeta.SourceKind source(InformationEvaluation context) {
        return context.getter(LifeSourceGetter.INSTANCE, List.of()).decode(PlayerViewMeta.SourceKind.CODEC);
    }

    private static InformationValue text(String value) {
        return new InformationValue.Text(Component.literal(value));
    }

    private static InformationValue translated(String key) {
        return new InformationValue.Text(Component.translatable(key));
    }

    private static InformationValue time(long epoch) {
        return epoch > 0 ? text(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epoch))) : new InformationValue.Missing(MissingReason.NOT_CAPTURED);
    }

    public enum Value {
        SOURCE, GENERATION, STATE, STARTED, ENDED, OBSERVED, REASON, LEVEL, TOTAL_EXPERIENCE,
        INVENTORY_ITEMS, ADVANCEMENTS, DEATHS, PLAYED_SECONDS, TRANSACTION, SUMMARY_POSITION;

        public static final Codec<Value> CODEC = ExtraEnumCodecs.enumCodec(Value.class);
    }
}
