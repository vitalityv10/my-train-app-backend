package org.ipz.backend.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "train_seat_occupancy",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_occupancy_train_date_carriage_seat",
                        columnNames = {"train_id", "departure_date", "carriage", "seat_number"}
                )
        },
        indexes = {
                @Index(name = "idx_occupancy_train_date", columnList = "train_id, departure_date")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainSeatOccupancyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "train_id", referencedColumnName = "id", nullable = false)
    private TrainEntity train;

    @Column(name = "departure_date", nullable = false)
    private LocalDate departureDate;

    @Column(name = "carriage", nullable = false)
    private Integer carriage;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @Column(name = "seat_key", length = 16, nullable = false)
    private String seatKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", referencedColumnName = "ticket_id")
    private TicketEntity ticket;

    @Column(name = "occupied_at", updatable = false)
    private OffsetDateTime occupiedAt;

    @PrePersist
    protected void onCreate() {
        if (seatKey == null && carriage != null && seatNumber != null) {
            seatKey = carriage + "_" + seatNumber;
        }
        if (occupiedAt == null) {
            occupiedAt = OffsetDateTime.now();
        }
    }
}
