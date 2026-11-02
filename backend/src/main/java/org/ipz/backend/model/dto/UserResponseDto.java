package org.ipz.backend.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import org.ipz.backend.model.enums.PaymentMethod;
import org.ipz.backend.model.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Builder
public record UserResponseDto(
        String uid,
        String name,
        String email,
        String photo,
        List<UserTicketDto> tickets
) {

    @Builder
    public record UserTicketDto(
            String ticketId,
            Integer trainId,
            String number,
            String title,
            TrainResponseDto.RouteDto route,
            TicketSeatDto seat,
            TicketPaymentDto payment
    ) {
    }

    @Builder
    public record TicketSeatDto(
            @JsonProperty("class")
            String seatClass,
            Integer carriage,
            Integer number
    ) {
    }

    @Builder
    public record TicketPaymentDto(
            BigDecimal basePrice,
            PaymentStatus status,
            PaymentMethod method,
            OffsetDateTime paidAt
    ) {
    }
}
