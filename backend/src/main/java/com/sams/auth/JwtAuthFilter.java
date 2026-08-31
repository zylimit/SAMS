package com.sams.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtAuthFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)
                || !path.startsWith("/api")
                || path.equals("/health")
                || ("POST".equals(method) && path.equals("/api/auth/login"))) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        String token = null;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7).trim();
            if (token.isEmpty()) token = null;
        }
        CurrentUser user = token == null ? null : auth.resolve(token);
        if (user == null) {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), Map.of("detail", "unauthenticated"));
            return;
        }
        AuthContext.set(user);
        try {
            chain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }
}
