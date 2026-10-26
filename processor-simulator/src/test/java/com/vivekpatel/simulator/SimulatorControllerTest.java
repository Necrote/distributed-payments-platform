package com.vivekpatel.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Every lever pulled once, through the same curl-shaped calls a demo would make. Latencies are
 * shrunk so the suite runs in seconds, and the decline rate is zero so NORMAL is deterministic.
 */
@SpringBootTest(properties = {
        "simulator.base-latency=0ms",
        "simulator.slow-latency=300ms",
        "simulator.timeout-latency=600ms",
        "simulator.decline-rate=0"
})
@AutoConfigureMockMvc
class SimulatorControllerTest {

    private static final String BODY = """
            {"requestId":"req-1","paymentMethodToken":"tok_test_123","amount":12500,"currency":"INR"}
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SimulatorProperties properties;

    @AfterEach
    void backToNormal() {
        properties.setMode(FailureMode.NORMAL);
    }

    @Test
    void normalApprovesAndEchoesTheRequestId() throws Exception {
        authorize()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.authorizationId").isNotEmpty());
    }

    @Test
    void slowStillSucceedsButHoldsTheCallerForTheConfiguredLatency() throws Exception {
        setMode("SLOW");
        long started = System.nanoTime();
        authorize().andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(300);
    }

    @Test
    void timeoutAnswersOnlyAfterTheTimeoutLatency() throws Exception {
        setMode("TIMEOUT");
        long started = System.nanoTime();
        authorize().andExpect(status().isOk());
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(600);
    }

    @Test
    void http500IsAServerError() throws Exception {
        setMode("HTTP_500");
        authorize()
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.requestId").value("req-1"));
    }

    @Test
    void http429CarriesRetryAfter() throws Exception {
        setMode("HTTP_429");
        authorize()
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void modesNotBuiltYetSay501InsteadOfPretending() throws Exception {
        setMode("OUTAGE");
        authorize().andExpect(status().isNotImplemented());
    }

    @Test
    void aTypoInTheModeIsA400NotASilentNormal() throws Exception {
        mockMvc.perform(put("/control/mode").param("mode", "HTTP_5OO"))
                .andExpect(status().isBadRequest());
        assertThat(properties.getMode()).isEqualTo(FailureMode.NORMAL);
    }

    private void setMode(String mode) throws Exception {
        mockMvc.perform(put("/control/mode").param("mode", mode)).andExpect(status().isOk());
    }

    private ResultActions authorize() throws Exception {
        return mockMvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON).content(BODY));
    }
}
