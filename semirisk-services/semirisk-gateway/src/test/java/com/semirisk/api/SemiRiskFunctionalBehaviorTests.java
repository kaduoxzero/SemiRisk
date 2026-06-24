package com.semirisk.api;

import com.jayway.jsonpath.JsonPath;
import com.semirisk.common.SemiriskConstants;
import com.semirisk.model.CrawlerSignal;
import com.semirisk.model.UserAccount;
import com.semirisk.service.AlertService;
import com.semirisk.service.SemiRiskStore;
import com.semirisk.security.TokenAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/semirisk",
        "spring.datasource.hikari.connection-timeout=250"
})
@AutoConfigureMockMvc(addFilters = false)
class SemiRiskFunctionalBehaviorTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SemiRiskStore store;

    @Autowired
    private TokenAuthService tokenAuthService;

    @Test
    void ignoredAlertCanBeRestoredToUnhandled() throws Exception {
        String csrfToken = csrfToken();
        String authorization = authHeader();
        store.refreshDailyRiskRecords(List.of(new CrawlerSignal(
                "TEST-RESTORE-ALERT",
                "JUnitSource",
                "High risk supplier disruption test signal",
                "supply_chain",
                91,
                Instant.now(),
                "https://example.test/restore-alert",
                "OK"
        )));

        mockMvc.perform(put("/api/alerts/TEST-RESTORE-ALERT/ignore")
                        .header("Authorization", authorization)
                        .header("X-CSRF-Token", csrfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(AlertService.STATUS_IGNORED));

        mockMvc.perform(put("/api/alerts/TEST-RESTORE-ALERT/restore")
                        .header("Authorization", authorization)
                        .header("X-CSRF-Token", csrfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(AlertService.STATUS_UNHANDLED));
    }

    @Test
    void systemOverviewReturnsUsersSortedByRolePriority() throws Exception {
        String csrfToken = csrfToken();
        String authorization = authHeader();
        mockMvc.perform(post("/api/system/users/login")
                        .header("Authorization", authorization)
                        .header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"zz_operator\",\"email\":\"3456789@qq.com\",\"displayName\":\"Operator User\",\"password\":\"Password123\",\"role\":\"OPERATOR\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/system/users/login")
                        .header("Authorization", authorization)
                        .header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"aa_analyst\",\"email\":\"4567890@qq.com\",\"displayName\":\"Analyst User\",\"password\":\"Password123\",\"role\":\"ANALYST\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/system/users/login")
                        .header("Authorization", authorization)
                        .header("X-CSRF-Token", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mm_admin\",\"email\":\"5678901@qq.com\",\"displayName\":\"Admin User\",\"password\":\"Password123\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/system/overview")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.users[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.data.users[1].role").value("ANALYST"))
                .andExpect(jsonPath("$.data.users[2].role").value("OPERATOR"));
    }

    private String csrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.token");
    }

    private String authHeader() {
        UserAccount account = new UserAccount("test_admin", "", "Test Admin", SemiriskConstants.ROLE_ADMIN, true);
        return "Bearer " + tokenAuthService.issue(account).token();
    }
}
