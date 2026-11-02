package org.ipz.backend.model.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record OccupancyStreamEventDto(
        List<String> occupiedSeats
) {
}
