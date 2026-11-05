package com.iafenvoy.reincarnation.reference;

/**
 * Marks a complete, extensible payload. Arbitrary business maps must not become carriers.
 */
public interface ReferenceCarrier {
    boolean reincarnation$isCarrier();

    void reincarnation$markCarrier();
}
