package org.ipz.backend.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ipz.backend.exception.NotFoundException;
import org.ipz.backend.model.Ticket;
import org.ipz.backend.model.User;
import org.ipz.backend.model.entity.TicketEntity;
import org.ipz.backend.model.entity.UserEntity;
import org.ipz.backend.repository.TicketRepository;
import org.ipz.backend.repository.UserRepository;
import org.ipz.backend.service.UserService;
import org.ipz.backend.service.mapper.TicketServiceMapper;
import org.ipz.backend.service.mapper.UserServiceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

@Transactional
@RequiredArgsConstructor
@Service
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final UserServiceMapper userMapper;
    private final TicketServiceMapper ticketMapper;

    @Override
    public User syncUser(String firebaseUid, String tokenEmail, String tokenName, String tokenPicture, User requestUser) {
        String email = resolveValue(requestUser != null ? requestUser.getEmail() : null, tokenEmail);
        String name = resolveValue(requestUser != null ? requestUser.getName() : null, tokenName);
        String photoUrl = resolveValue(requestUser != null ? requestUser.getPhotoUrl() : null, tokenPicture);
        String phone = requestUser != null ? requestUser.getPhone() : null;

        UserEntity entity = userRepository.findById(firebaseUid)
                .map(existing -> {
                    if (StringUtils.hasText(email)) {
                        existing.setEmail(email);
                    }
                    if (StringUtils.hasText(name)) {
                        existing.setName(name);
                    }
                    if (StringUtils.hasText(photoUrl)) {
                        existing.setPhotoUrl(photoUrl);
                    }
                    if (StringUtils.hasText(phone)) {
                        existing.setPhone(phone);
                    }
                    existing.setUpdatedAt(OffsetDateTime.now());
                    return existing;
                })
                .orElseGet(() -> UserEntity.builder()
                        .firebaseUid(firebaseUid)
                        .email(StringUtils.hasText(email) ? email : firebaseUid + "@firebase.local")
                        .name(name)
                        .photoUrl(photoUrl)
                        .phone(phone)
                        .build());

        UserEntity saved = userRepository.save(entity);
        log.info("Synchronized user profile for Firebase UID: {}", firebaseUid);
        return userMapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public User getUserWithTickets(String firebaseUid) {
        UserEntity userEntity = userRepository.findById(firebaseUid)
                .orElseThrow(() -> new NotFoundException("User with uid " + firebaseUid + " not found"));

        List<TicketEntity> ticketEntities = ticketRepository.findAllByUserFirebaseUidOrderByCreatedAtDesc(firebaseUid);
        List<Ticket> tickets = ticketMapper.toDomainList(ticketEntities);

        User user = userMapper.toDomain(userEntity);
        user.setTickets(tickets);
        return user;
    }

    private String resolveValue(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }
}
