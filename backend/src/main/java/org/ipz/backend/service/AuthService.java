package org.ipz.backend.service;

import org.ipz.backend.model.AuthResult;
import org.ipz.backend.model.dto.UserLoginRequestDto;
import org.ipz.backend.model.dto.UserRegisterRequestDto;

public interface AuthService {

    AuthResult register(UserRegisterRequestDto requestDto);

    AuthResult login(UserLoginRequestDto requestDto);
}
