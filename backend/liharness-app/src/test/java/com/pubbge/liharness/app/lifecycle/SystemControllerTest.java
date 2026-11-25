package com.pubbge.liharness.app.lifecycle;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "liharness.token=test-token")
@AutoConfigureMockMvc
class SystemControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void healthRejectsMissingOrWrongToken() throws Exception {
        mvc.perform(get("/api/health"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health").header("Authorization", "Bearer wrong"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void healthWithTokenIsUp() throws Exception {
        mvc.perform(get("/api/health").header("Authorization", "Bearer test-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }
}
