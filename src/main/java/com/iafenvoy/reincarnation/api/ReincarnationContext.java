package com.iafenvoy.reincarnation.api;

import java.util.UUID;

public record ReincarnationContext(UUID transactionId, UUID account, long fromGeneration, long toGeneration,
                                   ReincarnationRequest request, boolean recovering) {
}
