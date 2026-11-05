package com.iafenvoy.reincarnation.reference;

/**
 * Fail the enclosing load/save instead of silently converting corrupt metadata to an unstamped reference.
 */
public final class ReferenceMetadataException extends IllegalStateException {
    public ReferenceMetadataException(String message) {
        super(message);
    }
}
