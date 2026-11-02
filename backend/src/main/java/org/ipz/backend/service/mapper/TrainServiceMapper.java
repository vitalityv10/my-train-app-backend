package org.ipz.backend.service.mapper;

import org.ipz.backend.model.Train;
import org.ipz.backend.model.entity.TrainEntity;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TrainServiceMapper {

    Train toDomain(TrainEntity entity);

    List<Train> toDomainList(List<TrainEntity> entities);
}
