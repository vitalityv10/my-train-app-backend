package org.ipz.backend.service.mapper;

import org.ipz.backend.model.User;
import org.ipz.backend.model.entity.UserEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserServiceMapper {

    @Mapping(target = "tickets", ignore = true)
    User toDomain(UserEntity userEntity);

}
