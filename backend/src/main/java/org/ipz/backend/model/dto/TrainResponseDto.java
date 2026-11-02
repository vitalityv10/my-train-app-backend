package org.ipz.backend.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import org.ipz.backend.model.enums.TrainType;

import java.math.BigDecimal;

@Builder
public record TrainResponseDto(
        Integer id,
        String number,
        String title,
        String description,
        TrainType type,
        String imageUrl,
        Boolean hasWifi,
        Boolean hasAirConditioning,
        RouteDto route,
        SeatDto seat,
        PaymentDto payment
) {

    @Builder
    public record RouteDto(
            DepartureEndpointDto from,
            ArrivalEndpointDto to,
            String duration
    ) {
    }

    @Builder
    public record DepartureEndpointDto(
            String city,
            String station,
            String departureTime
    ) {
    }

    @Builder
    public record ArrivalEndpointDto(
            String city,
            String station,
            String arrivalTime
    ) {
    }

    @Builder
    public record SeatDto(
            @JsonProperty("class")
            String seatClass,
            Integer carriage,
            Integer number
    ) {
    }

    @Builder
    public record PaymentDto(
            BigDecimal basePrice
    ) {
    }
}
