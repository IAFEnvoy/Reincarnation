package com.iafenvoy.reincarnation.api;

import java.math.BigInteger;

/**
 * Player-facing life numbers start at one; persisted generation IDs remain zero-based.
 */
public final class LifeNumbers {
    private LifeNumbers() {
    }

    public static String display(long generation) {
        if (generation < 0) throw new IllegalArgumentException("Negative generation");
        // The last valid generation displays as Long.MAX_VALUE + 1.
        return BigInteger.valueOf(generation).add(BigInteger.ONE).toString();
    }

    public static long generation(String lifeNumber) {
        BigInteger generation = new BigInteger(lifeNumber).subtract(BigInteger.ONE);
        if (generation.signum() < 0 || generation.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)
            throw new NumberFormatException("Life number out of range");
        return generation.longValueExact();
    }
}
