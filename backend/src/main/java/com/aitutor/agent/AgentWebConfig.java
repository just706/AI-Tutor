package com.aitutor.agent;

import com.aitutor.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AgentWebConfig implements WebMvcConfigurer {
    private final PythonAgentGateway gateway;
    public AgentWebConfig(PythonAgentGateway gateway) { this.gateway=gateway; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                gateway.authenticate(request.getHeader("Authorization"));
                if (!"POST".equals(request.getMethod())) throw new BusinessException(405,"内部工具仅支持POST");
                if (request.getContentLengthLong()>65536) throw new BusinessException(413,"内部工具请求过大");
                return true;
            }
        }).addPathPatterns("/internal/agent/v1/**");
    }
}
