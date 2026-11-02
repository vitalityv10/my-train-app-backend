package org.ipz.backend.controller.mapper;

import org.ipz.backend.model.CheckoutPurchase;
import org.ipz.backend.model.Ticket;
import org.ipz.backend.model.dto.CheckoutPurchaseRequestDto;
import org.ipz.backend.model.dto.CheckoutPurchaseResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface CheckoutControllerMapper {

    CheckoutPurchase toDomain(CheckoutPurchaseRequestDto requestDto);

    CheckoutPurchase.SeatBookingItem toItemDomain(CheckoutPurchaseRequestDto.CheckoutTicketRequestDto dto);

    @Mapping(target = "status", source = "paymentStatus")
    @Mapping(target = "method", source = "paymentMethod")
    CheckoutPurchaseResponseDto.PurchasedTicketDto toPurchasedTicketDto(Ticket ticket);

    List<CheckoutPurchaseResponseDto.PurchasedTicketDto> toPurchasedTicketDtoList(List<Ticket> tickets);
}
