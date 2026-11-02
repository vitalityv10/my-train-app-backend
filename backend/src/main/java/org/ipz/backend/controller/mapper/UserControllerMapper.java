package org.ipz.backend.controller.mapper;

import org.ipz.backend.model.Ticket;
import org.ipz.backend.model.User;
import org.ipz.backend.model.dto.UserResponseDto;
import org.ipz.backend.model.dto.UserSyncRequestDto;
import org.ipz.backend.model.dto.UserSyncResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Mapper(componentModel = "spring")
public interface UserControllerMapper {

    DateTimeFormatter LOCAL_ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Mapping(target = "photoUrl", source = "photo")
    @Mapping(target = "firebaseUid", ignore = true)
    @Mapping(target = "password", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "tickets", ignore = true)
    User toDomain(UserSyncRequestDto request);

    @Mapping(target = "uid", source = "firebaseUid")
    @Mapping(target = "photo", source = "photoUrl")
    UserSyncResponseDto toSyncDto(User user);

    @Mapping(target = "uid", source = "firebaseUid")
    @Mapping(target = "photo", source = "photoUrl")
    @Mapping(target = "tickets", source = "tickets")
    UserResponseDto toDto(User user);

    @Mapping(target = "number", source = "trainNumber")
    @Mapping(target = "title", source = "trainTitle")
    @Mapping(target = "route.from.city", source = "fromCity")
    @Mapping(target = "route.from.station", source = "fromStation")
    @Mapping(target = "route.from.departureTime", source = "departureTime", qualifiedByName = "formatDateTime")
    @Mapping(target = "route.to.city", source = "toCity")
    @Mapping(target = "route.to.station", source = "toStation")
    @Mapping(target = "route.to.arrivalTime", source = "arrivalTime", qualifiedByName = "formatDateTime")
    @Mapping(target = "route.duration", source = "duration")
    @Mapping(target = "seat.seatClass", source = "seatClass")
    @Mapping(target = "seat.carriage", source = "carriage")
    @Mapping(target = "seat.number", source = "seatNumber")
    @Mapping(target = "payment.basePrice", source = "price")
    @Mapping(target = "payment.status", source = "paymentStatus")
    @Mapping(target = "payment.method", source = "paymentMethod")
    @Mapping(target = "payment.paidAt", source = "paidAt")
    UserResponseDto.UserTicketDto toTicketDto(Ticket ticket);

    List<UserResponseDto.UserTicketDto> toTicketDtoList(List<Ticket> tickets);

    @Named("formatDateTime")
    default String formatDateTime(OffsetDateTime dateTime) {
        return dateTime != null ? dateTime.format(LOCAL_ISO_FORMATTER) : null;
    }
}
