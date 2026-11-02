package org.ipz.backend.controller.mapper;

import org.ipz.backend.model.TrainOccupancy;
import org.ipz.backend.model.dto.TrainOccupancyResponseDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OccupancyControllerMapper {

    TrainOccupancyResponseDto toDto(TrainOccupancy occupancy);
}
