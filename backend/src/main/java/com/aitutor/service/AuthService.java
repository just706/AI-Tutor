package com.aitutor.service;

import com.aitutor.dto.LoginRequest;
import com.aitutor.dto.RegisterRequest;
import com.aitutor.vo.LoginVO;
import com.aitutor.vo.RegisterVO;

public interface AuthService {

    RegisterVO register(RegisterRequest request);

    LoginVO login(LoginRequest request);
}
