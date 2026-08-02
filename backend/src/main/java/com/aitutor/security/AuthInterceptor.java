package com.aitutor.security;

import com.aitutor.entity.User;
import com.aitutor.exception.UnauthorizedException;
import com.aitutor.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int STATUS_ENABLED = 1;

    private final JwtTokenProvider jwtTokenProvider;
    private final UserMapper userMapper;

    public AuthInterceptor(JwtTokenProvider jwtTokenProvider, UserMapper userMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("未登录或 Token 无效");
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        CurrentUser tokenUser = jwtTokenProvider.parseToken(token);
        User user = userMapper.selectById(tokenUser.getId());
        if (user == null) {
            throw new UnauthorizedException("未登录或 Token 无效");
        }
        if (user.getStatus() == null || user.getStatus() != STATUS_ENABLED) {
            throw new UnauthorizedException("账号已禁用");
        }

        UserContext.set(new CurrentUser(user.getId(), user.getUsername(), user.getRole()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
