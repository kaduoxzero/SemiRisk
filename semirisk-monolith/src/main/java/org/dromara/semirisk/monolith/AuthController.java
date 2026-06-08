package org.dromara.semirisk.monolith;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/prod-api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(authService.login(body.get("username"), body.get("password")));
    }

    @PostMapping("/register")
    public ApiResponse<AuthUser> register(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(authService.register(body.get("username"), body.get("email"), body.get("password")));
    }

    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@RequestBody Map<String, String> body) {
        authService.resetPassword(body.get("account"), body.get("newPassword"));
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<AuthUser> me(HttpServletRequest request) {
        return ApiResponse.ok((AuthUser) request.getAttribute("authUser"));
    }
}
