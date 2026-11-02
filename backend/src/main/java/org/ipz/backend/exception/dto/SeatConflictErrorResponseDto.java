package org.ipz.backend.exception.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SeatConflictErrorResponseDto {
    private String error;
    private String message;
    private List<String> conflictingSeats;
}
