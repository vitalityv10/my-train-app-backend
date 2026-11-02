package org.ipz.backend.model.dto;

import jakarta.validation.constraints.Email;
import lombok.Builder;

@Builder
public record UserSyncRequestDto(
        String name,
        @Email(message = "Invalid email format")
        String email,
        String photo,
        String phone
) {
}
