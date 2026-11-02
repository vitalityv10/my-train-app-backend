package org.ipz.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ipz.backend.model.enums.TrainType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItem {
    private Integer id;
    private String number;
    private String title;
    private TrainType type;
    private Boolean hasWifi;
    private Boolean hasAirConditioning;

    private String fromCity;
    private String fromStation;
    private OffsetDateTime departureTime;
    private String toCity;
    private String toStation;
    private OffsetDateTime arrivalTime;
    private String duration;

    private String seatClass;
    private Integer carriage;
    private Integer seatNumber;
    private BigDecimal basePrice;
    private Integer quantity;
}
