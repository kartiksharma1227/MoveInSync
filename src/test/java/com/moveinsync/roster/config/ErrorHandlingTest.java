package com.moveinsync.roster.config;

import static com.moveinsync.roster.TestEndpoints.LEAKY_EMAIL;
import static com.moveinsync.roster.TestEndpoints.LEAKY_PHONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import com.moveinsync.roster.IntegrationTest;

/** Every error has the same shape, a stable code and a correlation ID, and never repeats what the caller sent. */
@ExtendWith(OutputCaptureExtension.class)
class ErrorHandlingTest extends IntegrationTest {

    static final String PHONE_IN_INPUT = "+919812345678";

    @Test
    void malformedJsonIsA400ThatDoesNotQuoteTheInput() throws Exception {
        String broken = "[{\"type\":\"CREATE\",\"hr\":{\"phone\":\"" + PHONE_IN_INPUT + "\"";

        String body = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", token("hr", "ACME", "HR_SYSTEM"))
                        .contentType(MediaType.APPLICATION_JSON).content(broken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PHONE_IN_INPUT);
    }

    @Test
    void validationErrorNamesTheFieldAndRuleButNotTheValue() throws Exception {
        String tooLong = "SecretLane".repeat(21); // 210 characters

        String body = mockMvc.perform(put("/api/v1/employees/E1001/transport")
                        .header("Authorization", token("admin", "ACME", "TRANSPORT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupPoint\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("pickupPoint"))
                .andExpect(jsonPath("$.errors[0].code").value("Size"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("SecretLane");
    }

    @Test
    void unknownEmployeeIsA404WithAStableCode() throws Exception {
        mockMvc.perform(get("/api/v1/employees/NO-SUCH-PERSON")
                        .header("Authorization", token("admin", "ACME", "TRANSPORT_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void missingTokenIsA401WithTheSameBodyShape() throws Exception {
        mockMvc.perform(get("/api/v1/employees/E1001"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void wrongRoleIsA403WithTheSameBodyShape() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", token("admin", "ACME", "TRANSPORT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void badParameterIsNamedButNotEchoed() throws Exception {
        String badDate = "yesterday-" + PHONE_IN_INPUT;

        String body = mockMvc.perform(multipart("/api/v1/reconciliations")
                        .file(new MockMultipartFile("file", "f.csv", "text/csv", new byte[0]))
                        .param("snapshotAt", badDate)
                        .header("Authorization", token("hr", "ACME", "HR_SYSTEM")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.errors[0].field").value("snapshotAt"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PHONE_IN_INPUT);
    }

    @Test
    void unexpectedErrorIsAGeneric500AndItsMessageNeverReachesTheResponseOrTheLogs(CapturedOutput output)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/test/boom")
                        .header("Authorization", token("admin", "ACME", "TRANSPORT_ADMIN")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        String correlationId = result.getResponse().getHeader(CorrelationIdFilter.HEADER);

        assertThat(body).doesNotContain(LEAKY_PHONE, LEAKY_EMAIL);
        assertThat(output.getAll())
                .contains("Unhandled error correlationId=" + correlationId) // findable in the logs by its ID
                .contains("IllegalStateException")
                .doesNotContain(LEAKY_PHONE, LEAKY_EMAIL);
    }

    @Test
    void safeCorrelationIdFromTheCallerIsKeptAndReturned() throws Exception {
        mockMvc.perform(get("/api/v1/employees/NO-SUCH-PERSON")
                        .header(CorrelationIdFilter.HEADER, "client-req-42")
                        .header("Authorization", token("admin", "ACME", "TRANSPORT_ADMIN")))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "client-req-42"))
                .andExpect(jsonPath("$.correlationId").value("client-req-42"));
    }

    @Test
    void missingOrUnsafeCorrelationIdIsReplacedWithAGeneratedOne() throws Exception {
        String generated = mockMvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER);
        String replaced = mockMvc.perform(get("/actuator/health")
                        .header(CorrelationIdFilter.HEADER, "evil\nFAKE LOG LINE"))
                .andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER);

        assertThat(generated).hasSize(36); // a UUID
        assertThat(replaced).hasSize(36).doesNotContain("FAKE");
    }
}
