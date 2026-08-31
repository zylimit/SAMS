package com.sams.auth;

import com.sams.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record LoginBody(String username, String password) {}
    public record ChangePasswordBody(String old_password, String new_password) {}

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginBody body, HttpServletRequest request) {
        if (body.username() == null || body.password() == null) {
            throw ApiException.of(400, "username/password required");
        }
        return auth.login(body.username(), body.password(), request);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        auth.logout(AuthContext.get(), request);
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return Map.of("user", auth.publicUser(AuthContext.require()));
    }

    @PostMapping("/change-password")
    public Map<String, Object> change(@RequestBody ChangePasswordBody body) {
        auth.changePassword(AuthContext.require(), body.old_password(), body.new_password());
        return Map.of("ok", true);
    }
}
