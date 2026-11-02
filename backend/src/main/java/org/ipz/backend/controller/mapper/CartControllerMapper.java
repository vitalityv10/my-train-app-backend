package org.ipz.backend.controller.mapper;

import org.ipz.backend.model.Cart;
import org.ipz.backend.model.CartItem;
import org.ipz.backend.model.dto.CartDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Mapper(componentModel = "spring")
public interface CartControllerMapper {

    DateTimeFormatter LOCAL_ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    CartDto toDto(Cart cart);

    Cart toDomain(CartDto cartDto);

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
    @Mapping(target = "payment.basePrice", source = "basePrice")
    CartDto.CartItemDto toItemDto(CartItem item);

    @Mapping(target = "fromCity", source = "route.from.city")
    @Mapping(target = "fromStation", source = "route.from.station")
    @Mapping(target = "departureTime", source = "route.from.departureTime", qualifiedByName = "parseDateTime")
    @Mapping(target = "toCity", source = "route.to.city")
    @Mapping(target = "toStation", source = "route.to.station")
    @Mapping(target = "arrivalTime", source = "route.to.arrivalTime", qualifiedByName = "parseDateTime")
    @Mapping(target = "duration", source = "route.duration")
    @Mapping(target = "seatClass", source = "seat.seatClass")
    @Mapping(target = "carriage", source = "seat.carriage")
    @Mapping(target = "seatNumber", source = "seat.number")
    @Mapping(target = "basePrice", source = "payment.basePrice")
    CartItem toItemDomain(CartDto.CartItemDto dto);

    List<CartDto.CartItemDto> toItemDtoList(List<CartItem> items);

    List<CartItem> toItemDomainList(List<CartDto.CartItemDto> dtos);

    @Named("formatDateTime")
    default String formatDateTime(OffsetDateTime dateTime) {
        return dateTime != null ? dateTime.format(LOCAL_ISO_FORMATTER) : null;
    }

    @Named("parseDateTime")
    default OffsetDateTime parseDateTime(String dateTimeStr) {
        if (!StringUtils.hasText(dateTimeStr)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(dateTimeStr);
        } catch (Exception ignored) {
            return LocalDateTime.parse(dateTimeStr).atOffset(ZoneOffset.UTC);
        }
    }
}
