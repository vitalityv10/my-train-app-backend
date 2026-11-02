package org.ipz.backend.model.dto;

import lombok.Builder;
import org.ipz.backend.model.enums.PaymentMethod;
import org.ipz.backend.model.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Builder
public record CheckoutPurchaseResponseDto(
        String message,
        List<PurchasedTicketDto> tickets
) {

    @Builder
    public record PurchasedTicketDto(
            String ticketId,
            Integer trainId,
            Integer carriage,
            Integer seatNumber,
            BigDecimal price,
            PaymentStatus status,
            PaymentMethod method,
            OffsetDateTime paidAt
    ) {
    }
}
