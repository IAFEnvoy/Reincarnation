package com.iafenvoy.reincarnation.reference;

/**
 * Implemented by EntityReference. -1 means an unstamped legacy/non-player reference.
 */
public interface GenerationReference {
    long reincarnation$generation();

    /**
     * Set once, before publishing a newly constructed reference.
     */
    void reincarnation$generation(long generation);
}
