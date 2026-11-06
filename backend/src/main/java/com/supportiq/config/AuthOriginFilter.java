package com.supportiq.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AuthOriginFilter extends OncePerRequestFilter {
    @Value("${app.cors.origin}") String allowedOrigin;

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String origin = request.getHeader("Origin");
        if (path.startsWith("/api/auth/") && origin != null && !allowedOrigin.equals(origin)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Request origin is not allowed.");
            return;
        }
        chain.doFilter(request, response);
    }
}
