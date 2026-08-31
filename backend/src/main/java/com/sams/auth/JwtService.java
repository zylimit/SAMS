package com.sams.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final Key key;
    private final int expireDays;

    public JwtService(
            @Value("${sams.jwt.secret}") String secret,
            @Value("${sams.jwt.expire-days}") int expireDays) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            bytes = java.util.Arrays.copyOf(bytes, 32);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireDays = expireDays;
    }

    public record Issued(String token, String jti) {}

    public Issued issue(long userId, String username) {
        String jti = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expireDays * 86400L);
        String token = Jwts.builder()
                .setSubject(String.valueOf(userId))
                .setId(jti)
                .claim("username", username)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        return new Issued(token, jti);
    }

    public Claims parse(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
    }
}
