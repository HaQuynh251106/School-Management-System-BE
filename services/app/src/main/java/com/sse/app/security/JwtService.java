package com.sse.app.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {

    private final JwtProperties props;
    private final SecretKey key;

    public JwtService(JwtProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public long accessTtlSeconds()  { return props.getAccessTtlSeconds(); }
    public long refreshTtlSeconds() { return props.getRefreshTtlSeconds(); }

    public String createAccessToken(String userId, String username, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .claims(Map.of("username", username, "role", role, "type", "access"))
                .issuedAt(new Date(now))
                .expiration(new Date(now + props.getAccessTtlSeconds() * 1000))
                .signWith(key)
                .compact();
    }

    public String createRefreshToken(String userId, String tokenId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(tokenId)
                .subject(userId)
                .claims(Map.of("type", "refresh"))
                .issuedAt(new Date(now))
                .expiration(new Date(now + props.getRefreshTtlSeconds() * 1000))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
