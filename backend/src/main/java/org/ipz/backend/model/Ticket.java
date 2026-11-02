package org.ipz.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ipz.backend.model.enums.PaymentMethod;
import org.ipz.backend.model.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Ticket {
    private String ticketId;
    private String userUid;
    private Integer trainId;
    private String trainNumber;
    private String trainTitle;
    private String fromCity;
    private String fromStation;
    private OffsetDateTime departureTime;
    private String toCity;
    private String toStation;
    private OffsetDateTime arrivalTime;
    private String duration;

    private LocalDate departureDate;
    private Integer carriage;
    private Integer seatNumber;
    private String seatClass;
    private BigDecimal price;
    private PaymentMethod paymentMethod;
    private PaymentStatus paymentStatus;
    private String contactPhone;
    private OffsetDateTime paidAt;
    private OffsetDateTime createdAt;
}
