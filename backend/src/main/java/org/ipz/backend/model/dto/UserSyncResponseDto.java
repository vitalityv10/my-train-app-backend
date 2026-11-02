package org.ipz.backend.model.dto;

import lombok.Builder;

import java.time.OffsetDateTime;

@Builder
public record UserSyncResponseDto(
        String uid,
        String name,
        String email,
        String photo,
        OffsetDateTime createdAt
) {
}
