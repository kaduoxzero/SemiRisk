package com.semirisk.api;

import org.junit.jupiter.api.Test;
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

@SpringBootTest
@AutoConfigureMockMvc
class SemiRiskAuthApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publicDashboardCanBeReadWithoutLogin() throws Exception {
        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void protectedAlertsRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("请先登录"));
    }

    @Test
    void loginCreatesSessionAndUnlocksProtectedApi() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("ADMIN"))
                .andReturn();

        mockMvc.perform(get("/api/alerts").session((org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void wrongPasswordReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }
}
