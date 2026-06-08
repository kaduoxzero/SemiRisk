package com.semirisk.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/semirisk",
        "spring.datasource.hikari.connection-timeout=250"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SemiRiskAuthApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Order(1)
    void directSpaRouteServesIndexHtml() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @Order(2)
    void publicDashboardCanBeReadWithoutLogin() throws Exception {
        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(3)
    void protectedAlertsRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("请先登录"));
    }

    @Test
    @Order(4)
    void unsafeRequestWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"csrf_user\",\"email\":\"123456@qq.com\",\"displayName\":\"测试用户\",\"password\":\"Password123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF Token 无效或缺失"));
    }

    @Test
    @Order(5)
    void bootstrapAdminCanLogin() throws Exception {
        Csrf csrf = csrf();
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .header("X-CSRF-Token", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"kaduoxli\",\"password\":\"123qwe123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.user.modules").isArray())
                .andExpect(jsonPath("$.data.token").exists())
                .andReturn();
        String token = JsonPath.read(login.getResponse().getContentAsString(), "$.data.token");

        mockMvc.perform(get("/api/alerts").param("access_token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(6)
    void registerCreatesTokenAndUnlocksProtectedApi() throws Exception {
        Csrf csrf = csrf();
        MvcResult register = mockMvc.perform(post("/api/auth/register")
                        .header("X-CSRF-Token", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"first_admin\",\"email\":\"1234567@qq.com\",\"displayName\":\"首个用户\",\"password\":\"Password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("OPERATOR"))
                .andExpect(jsonPath("$.data.token").exists())
                .andReturn();
        String token = JsonPath.read(register.getResponse().getContentAsString(), "$.data.token");

        mockMvc.perform(get("/api/alerts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/system/overview").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无权访问模块：system"));
    }

    @Test
    @Order(7)
    void wrongPasswordReturnsUnauthorized() throws Exception {
        Csrf registerCsrf = csrf();
        mockMvc.perform(post("/api/auth/register")
                        .header("X-CSRF-Token", registerCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wrong_password_user\",\"email\":\"2345678@qq.com\",\"displayName\":\"错误密码用户\",\"password\":\"Password123\"}"))
                .andExpect(status().isOk());

        Csrf loginCsrf = csrf();
        mockMvc.perform(post("/api/auth/login")
                        .header("X-CSRF-Token", loginCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wrong_password_user\",\"password\":\"WrongPassword123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    private Csrf csrf() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.data.token");
        return new Csrf(token);
    }

    private record Csrf(String token) {
    }
}
