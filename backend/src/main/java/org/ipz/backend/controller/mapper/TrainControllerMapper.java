package org.ipz.backend.controller.mapper;

import org.ipz.backend.model.Train;
import org.ipz.backend.model.dto.TrainFilter;
import org.ipz.backend.model.dto.TrainFilterRequestDto;
import org.ipz.backend.model.dto.TrainResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Mapper(componentModel = "spring")
public interface TrainControllerMapper {

    DateTimeFormatter LOCAL_ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    TrainFilter toDomainFilter(TrainFilterRequestDto requestDto);

    @Mapping(target = "route.from.city", source = "fromCity")
    @Mapping(target = "route.from.station", source = "fromStation")
    @Mapping(target = "route.from.departureTime", source = "departureTime", qualifiedByName = "formatDateTime")
    @Mapping(target = "route.to.city", source = "toCity")
    @Mapping(target = "route.to.station", source = "toStation")
    @Mapping(target = "route.to.arrivalTime", source = "arrivalTime", qualifiedByName = "formatDateTime")
    @Mapping(target = "route.duration", source = "duration")
    @Mapping(target = "seat.seatClass", source = "seatClass")
    @Mapping(target = "seat.carriage", source = "defaultCarriage")
    @Mapping(target = "seat.number", source = "defaultSeatNumber")
    @Mapping(target = "payment.basePrice", source = "basePrice")
    TrainResponseDto toDto(Train train);

    List<TrainResponseDto> toDtoList(List<Train> trains);

    @Named("formatDateTime")
    default String formatDateTime(OffsetDateTime dateTime) {
        return dateTime != null ? dateTime.format(LOCAL_ISO_FORMATTER) : null;
    }
}
