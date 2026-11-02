package org.ipz.backend.service.mapper;

import org.ipz.backend.model.Ticket;
import org.ipz.backend.model.entity.TicketEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TicketServiceMapper {

    @Mapping(target = "userUid", source = "user.firebaseUid")
    @Mapping(target = "trainId", source = "train.id")
    @Mapping(target = "trainNumber", source = "train.number")
    @Mapping(target = "trainTitle", source = "train.title")
    @Mapping(target = "fromCity", source = "train.fromCity")
    @Mapping(target = "fromStation", source = "train.fromStation")
    @Mapping(target = "departureTime", source = "train.departureTime")
    @Mapping(target = "toCity", source = "train.toCity")
    @Mapping(target = "toStation", source = "train.toStation")
    @Mapping(target = "arrivalTime", source = "train.arrivalTime")
    @Mapping(target = "duration", source = "train.duration")
    Ticket toDomain(TicketEntity entity);

    List<Ticket> toDomainList(List<TicketEntity> entities);
}
