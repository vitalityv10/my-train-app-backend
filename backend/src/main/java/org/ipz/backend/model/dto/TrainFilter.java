package org.ipz.backend.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainFilter {
    private String search;
    private String city;
    private LocalDate date;
    private BigDecimal maxPrice;
    private Boolean wifiOnly;
    private Integer page;
    private Integer limit;
}
