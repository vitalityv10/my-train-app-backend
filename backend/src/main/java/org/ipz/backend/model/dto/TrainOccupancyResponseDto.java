package org.ipz.backend.model.dto;

import lombok.Builder;

import java.time.LocalDate;
import java.util.List;

@Builder
public record TrainOccupancyResponseDto(
        String occupancyId,
        Integer trainId,
        LocalDate date,
        Integer totalCarriages,
        Integer seatsPerCarriage,
        List<String> occupiedSeats
) {
}
