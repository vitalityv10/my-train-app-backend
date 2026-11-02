package org.ipz.backend.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.ipz.backend.model.enums.TrainType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "trains",
        indexes = {
                @Index(name = "idx_trains_cities", columnList = "from_city, to_city"),
                @Index(name = "idx_trains_departure", columnList = "departure_time"),
                @Index(name = "idx_trains_price", columnList = "base_price")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "number", length = 20, nullable = false)
    private String number;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20, nullable = false)
    private TrainType type;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Builder.Default
    @Column(name = "has_wifi")
    private Boolean hasWifi = false;

    @Builder.Default
    @Column(name = "has_air_conditioning")
    private Boolean hasAirConditioning = false;

    @Column(name = "from_city", length = 100, nullable = false)
    private String fromCity;

    @Column(name = "from_station", length = 150, nullable = false)
    private String fromStation;

    @Column(name = "departure_time", nullable = false)
    private OffsetDateTime departureTime;

    @Column(name = "to_city", length = 100, nullable = false)
    private String toCity;

    @Column(name = "to_station", length = 150, nullable = false)
    private String toStation;

    @Column(name = "arrival_time", nullable = false)
    private OffsetDateTime arrivalTime;

    @Column(name = "duration", length = 50, nullable = false)
    private String duration;

    @Column(name = "seat_class", length = 50, nullable = false)
    private String seatClass;

    @Builder.Default
    @Column(name = "default_carriage", nullable = false)
    private Integer defaultCarriage = 1;

    @Builder.Default
    @Column(name = "default_seat_number", nullable = false)
    private Integer defaultSeatNumber = 1;

    @Builder.Default
    @Column(name = "total_carriages", nullable = false)
    private Integer totalCarriages = 3;

    @Builder.Default
    @Column(name = "seats_per_carriage", nullable = false)
    private Integer seatsPerCarriage = 36;

    @Column(name = "base_price", precision = 10, scale = 2, nullable = false)
    private BigDecimal basePrice;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
