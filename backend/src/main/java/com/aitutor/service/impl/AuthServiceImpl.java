package com.aitutor.service.impl;

import com.aitutor.dto.LoginRequest;
import com.aitutor.dto.RegisterRequest;
import com.aitutor.entity.User;
import com.aitutor.exception.BusinessException;
import com.aitutor.mapper.UserMapper;
import com.aitutor.security.JwtTokenProvider;
import com.aitutor.service.AuthService;
import com.aitutor.vo.LoginUserVO;
import com.aitutor.vo.LoginVO;
import com.aitutor.vo.RegisterVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_ROLE = "student";
    private static final int STATUS_ENABLED = 1;

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthServiceImpl(UserMapper userMapper,
                           BCryptPasswordEncoder passwordEncoder,
                           JwtTokenProvider jwtTokenProvider) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    @Transactional
    public RegisterVO register(RegisterRequest request) {
        String username = request.getUsername().trim();

        Long existingCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (existingCount > 0) {
            throw new BusinessException(400, "用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(username);
        user.setRole(DEFAULT_ROLE);
        user.setStatus(STATUS_ENABLED);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(400, "用户名已存在");
        }

        return new RegisterVO(user.getId(), user.getUsername());
    }

    @Override
    public LoginVO login(LoginRequest request) {
        String username = request.getUsername().trim();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .last("LIMIT 1"));

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "用户名或密码错误");
        }

        if (user.getStatus() == null || user.getStatus() != STATUS_ENABLED) {
            throw new BusinessException(400, "账号已禁用");
        }

        String token = jwtTokenProvider.generateToken(user);
        return new LoginVO(token, LoginUserVO.from(user));
    }
}
