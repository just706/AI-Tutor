package com.aitutor.service.impl;

import com.aitutor.entity.User;
import com.aitutor.exception.UnauthorizedException;
import com.aitutor.mapper.UserMapper;
import com.aitutor.security.UserContext;
import com.aitutor.service.UserService;
import com.aitutor.vo.UserVO;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    private static final int STATUS_ENABLED = 1;

    private final UserMapper userMapper;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserVO getCurrentUser() {
        Long userId = UserContext.getRequired().getId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new UnauthorizedException("未登录或 Token 无效");
        }
        if (user.getStatus() == null || user.getStatus() != STATUS_ENABLED) {
            throw new UnauthorizedException("账号已禁用");
        }
        return UserVO.from(user);
    }
}
