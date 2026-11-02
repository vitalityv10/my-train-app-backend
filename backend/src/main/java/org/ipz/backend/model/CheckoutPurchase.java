package org.ipz.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ipz.backend.model.enums.PaymentMethod;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutPurchase {
    private PaymentMethod paymentMethod;
    private String contactPhone;
    private List<SeatBookingItem> tickets;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatBookingItem {
        private Integer trainId;
        private LocalDate departureDate;
        private Integer carriage;
        private Integer seatNumber;
    }
}
