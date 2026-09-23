package com.example.server.config;

import com.example.server.common.ErrorCode;
import com.example.server.common.Result;
import com.example.server.service.AuthService;
import com.example.server.utils.ClientIpUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final boolean trustForwardedFor;

    public AuthInterceptor(AuthService authService,
                           ObjectMapper objectMapper,
                           @Value("${app.security.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        try {
            Long userId = authService.resolveUser(request.getHeader("Authorization"));
            request.setAttribute(AuthService.REQUEST_USER_ID, userId);
            // 来源地址在这里解析一次：IP 维度限流要用它作 Key，且是否信任 X-Forwarded-For 是安全配置，
            // 不应该散落到各个 Controller 里重复判断。
            request.setAttribute(ClientIpUtils.REQUEST_CLIENT_IP,
                    ClientIpUtils.resolve(request, trustForwardedFor));
            return true;
        } catch (SecurityException e) {
            // 拦截器在 DispatcherServlet 之前返回，不会经过 @RestControllerAdvice，
            // 因此这里必须自己输出统一响应体，否则 401 会是唯一一种“裸文本”错误，
            // 破坏“所有错误都是 {code,message,data}”的契约，前端解包层也要为它开特例。
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(
                    response.getWriter(),
                    Result.error(ErrorCode.UNAUTHORIZED, e.getMessage()));
            return false;
        }
    }
}
