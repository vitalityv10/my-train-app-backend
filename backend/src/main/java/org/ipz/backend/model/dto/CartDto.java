package org.ipz.backend.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import org.ipz.backend.model.enums.TrainType;

import java.util.List;

@Builder
public record CartDto(
        @NotNull(message = "items cannot be null")
        @Valid
        List<CartItemDto> items
) {

    @Builder
    public record CartItemDto(
            @NotNull(message = "train id is required")
            Integer id,
            String number,
            String title,
            TrainType type,
            Boolean hasWifi,
            Boolean hasAirConditioning,
            TrainResponseDto.RouteDto route,
            TrainResponseDto.SeatDto seat,
            TrainResponseDto.PaymentDto payment,
            @NotNull(message = "quantity is required")
            @Min(value = 1, message = "quantity must be at least 1")
            Integer quantity
    ) {
    }
}
