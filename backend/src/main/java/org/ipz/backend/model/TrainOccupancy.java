package org.ipz.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainOccupancy {
    private String occupancyId;
    private Integer trainId;
    private LocalDate date;
    private Integer totalCarriages;
    private Integer seatsPerCarriage;
    private List<String> occupiedSeats;
}
