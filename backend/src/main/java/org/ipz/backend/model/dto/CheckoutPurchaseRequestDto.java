package org.ipz.backend.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import org.ipz.backend.model.enums.PaymentMethod;

import java.time.LocalDate;
import java.util.List;

@Builder
public record CheckoutPurchaseRequestDto(
        @NotNull(message = "paymentMethod is required")
        PaymentMethod paymentMethod,

        String contactPhone,

        @NotEmpty(message = "tickets list cannot be empty")
        @Valid
        List<CheckoutTicketRequestDto> tickets
) {

    @Builder
    public record CheckoutTicketRequestDto(
            @NotNull(message = "trainId is required")
            Integer trainId,

            @NotNull(message = "departureDate is required")
            LocalDate departureDate,

            @NotNull(message = "carriage is required")
            @Min(value = 1, message = "carriage must be at least 1")
            Integer carriage,

            @NotNull(message = "seatNumber is required")
            @Min(value = 1, message = "seatNumber must be at least 1")
            Integer seatNumber
    ) {
    }
}
