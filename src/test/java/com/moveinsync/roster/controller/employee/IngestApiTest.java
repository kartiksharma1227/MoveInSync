package com.moveinsync.roster.controller.employee;

import static com.moveinsync.roster.SyntheticRosterGenerator.createThenUpdateFeed;
import static com.moveinsync.roster.SyntheticRosterGenerator.event;
import static com.moveinsync.roster.SyntheticRosterGenerator.hr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.moveinsync.roster.IntegrationTest;
import com.moveinsync.roster.service.employee.IngestService.EventRequest;
import com.moveinsync.roster.service.employee.IngestService.HrRequest;

/** The ingest and employee APIs end to end: HTTP, JWT roles, tenant isolation and the database. */
class IngestApiTest extends IntegrationTest {

    static final Instant T = Instant.parse("2026-10-01T09:00:00Z");

    String hrSystem;
    String transportAdmin;

    @BeforeEach
    void tokens() throws Exception {
        hrSystem = token("acme-hr", "ACME", "HR_SYSTEM");
        transportAdmin = token("acme-admin", "ACME", "TRANSPORT_ADMIN");
    }

    ResultActions send(String token, List<EventRequest> events) throws Exception {
        return mockMvc.perform(post("/api/v1/events")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(events)));
    }

    ResultActions read(String token, String employeeId) throws Exception {
        return mockMvc.perform(get("/api/v1/employees/{id}", employeeId).header("Authorization", token));
    }

    void createEmployee(String id) throws Exception {
        send(hrSystem, List.of(event("CREATE", id, 1, T, hr("BLR-01", "S0900"))))
                .andExpect(jsonPath("$.applied").value(1));
    }

    // ---- H1: validation at the boundary ----

    @Test
    void badRecordsAreRejectedOneByOneAndTheGoodOnesStillApply() throws Exception {
        String ok1 = uniqueId("OK"), badPhone = uniqueId("BP"), badOffice = uniqueId("BO"), ok2 = uniqueId("OK");
        HrRequest withBadPhone = new HrRequest("Test Person", "t@acme.test", "98-BAD-PHONE", null, "BLR-01", "S0900");
        HrRequest withBadOffice = new HrRequest("Test Person", "t@acme.test", "+919811112222", null, "XXX-99", "S0900");

        String body = send(hrSystem, List.of(
                event("CREATE", ok1, 1, T, hr("BLR-01", "S0900")),
                event("CREATE", badPhone, 1, T, withBadPhone),
                event("CREATE", badOffice, 1, T, withBadOffice),
                event("CREATE", ok2, 1, T, hr("HYD-01", "S1400"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(4))
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.rejected").value(2))
                .andExpect(jsonPath("$.errors[0].index").value(1))
                .andExpect(jsonPath("$.errors[0].employeeId").value(badPhone))
                .andExpect(jsonPath("$.errors[0].errors[0].field").value("phone"))
                .andExpect(jsonPath("$.errors[0].errors[0].code").value("INVALID_FORMAT"))
                .andExpect(jsonPath("$.errors[1].index").value(2))
                .andExpect(jsonPath("$.errors[1].errors[0].code").value("UNKNOWN_OFFICE"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("98-BAD-PHONE"); // no personal data in error reports
        assertThat(countEmployees(ok1, ok2, badPhone, badOffice)).isEqualTo(2);
    }

    // ---- H3: idempotency ----

    @Test
    void replayingTheSameFeedTwiceChangesNothing() throws Exception {
        String prefix = uniqueId("RP");
        List<EventRequest> feed = createThenUpdateFeed(prefix, 100); // 200 events

        send(hrSystem, feed).andExpect(jsonPath("$.applied").value(200));
        List<Map<String, Object>> before = snapshot(prefix);

        send(hrSystem, feed)
                .andExpect(jsonPath("$.applied").value(0))
                .andExpect(jsonPath("$.stale").value(100))      // the v1 CREATEs: v2 is already stored
                .andExpect(jsonPath("$.duplicate").value(100))  // the v2 UPDATEs
                .andExpect(jsonPath("$.rejected").value(0));
        List<Map<String, Object>> after = snapshot(prefix);

        assertThat(before).hasSize(100);
        assertThat(after).isEqualTo(before); // same versions, hashes and even updated_at: no row was written
    }

    // ---- H2: ordering, through the real API ----

    @Test
    void outOfOrder_v7BeforeV6_staleV6IsIgnored() throws Exception {
        String id = uniqueId("OO");
        send(hrSystem, List.of(event("CREATE", id, 5, T, hr("BLR-01", "S0900"))));
        send(hrSystem, List.of(event("UPDATE", id, 7, T.plusSeconds(20), hr("HYD-01", "S2200")))); // transfer
        send(hrSystem, List.of(event("UPDATE", id, 6, T.plusSeconds(10), hr("BLR-01", "S1400")))) // shift change
                .andExpect(jsonPath("$.stale").value(1));

        read(transportAdmin, id)
                .andExpect(jsonPath("$.officeCode").value("HYD-01"))
                .andExpect(jsonPath("$.shift").value("S2200"))
                .andExpect(jsonPath("$.hrVersion").value(7));
    }

    @Test
    void updateForUnknownEmployeeIsRejected() throws Exception {
        send(hrSystem, List.of(event("UPDATE", uniqueId("UK"), 2, T, hr("BLR-01", "S0900"))))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.errors[0].errors[0].code").value("UNKNOWN_EMPLOYEE"));
    }

    @Test
    void sameVersionWithDifferentDataIsReportedAsAConflict() throws Exception {
        String id = uniqueId("VC");
        createEmployee(id);

        send(hrSystem, List.of(event("UPDATE", id, 1, T, hr("HYD-01", "S2200"))))
                .andExpect(jsonPath("$.versionConflict").value(1))
                .andExpect(jsonPath("$.errors[0].errors[0].code").value("VERSION_CONFLICT"));
    }

    @Test
    void batchOverTheLimitIsRefused() throws Exception {
        List<EventRequest> tooMany = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            tooMany.add(event("DEACTIVATE", "X-" + i, 1, T, null));
        }

        send(hrSystem, tooMany).andExpect(status().isContentTooLarge());
    }

    // ---- Authentication and roles ----

    @Test
    void requestWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutTenantIsUnauthorized() throws Exception {
        read(token("someone", null, "TRANSPORT_ADMIN"), "E1001").andExpect(status().isUnauthorized());
    }

    @Test
    void transportAdminCannotPushHrEvents() throws Exception {
        send(transportAdmin, List.of()).andExpect(status().isForbidden());
    }

    // ---- H9: tenant isolation ----

    @Test
    void anotherTenantCannotSeeTheEmployee() throws Exception {
        String id = uniqueId("TI");
        createEmployee(id);

        read(transportAdmin, id).andExpect(status().isOk());
        read(token("globex-admin", "GLOBEX", "TRANSPORT_ADMIN"), id).andExpect(status().isNotFound());
    }

    @Test
    void employeeCanReadOnlyTheirOwnRecord() throws Exception {
        String me = uniqueId("ME"), colleague = uniqueId("CO");
        createEmployee(me);
        createEmployee(colleague);
        String myToken = token(me, "ACME", "EMPLOYEE");

        read(myToken, me).andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("+919811112222"));
        read(myToken, colleague).andExpect(status().isNotFound());
    }

    @Test
    void operatorDoesNotSeeContactDetails() throws Exception {
        String id = uniqueId("OP");
        createEmployee(id);

        read(token("ops", "ACME", "OPERATOR"), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.officeCode").value("BLR-01"))
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());
        read(transportAdmin, id).andExpect(jsonPath("$.name").value("Test Person"));
    }

    // ---- H4: field ownership end to end ----

    @Test
    void hrOfficeChangeKeepsTheAdminsPickupPointAndFlagsIt() throws Exception {
        String id = uniqueId("OW");
        createEmployee(id);
        mockMvc.perform(put("/api/v1/employees/{id}/transport", id)
                        .header("Authorization", transportAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupPoint\":\"Gate 9\",\"specialAssistance\":\"Front seat\"}"))
                .andExpect(status().isOk());

        send(hrSystem, List.of(event("UPDATE", id, 2, T.plusSeconds(60), hr("HYD-01", "S0900"))))
                .andExpect(jsonPath("$.applied").value(1));

        read(transportAdmin, id)
                .andExpect(jsonPath("$.officeCode").value("HYD-01"))
                .andExpect(jsonPath("$.pickupPoint").value("Gate 9"))
                .andExpect(jsonPath("$.specialAssistance").value("Front seat"))
                .andExpect(jsonPath("$.pickupNeedsReview").value(true));
    }

    @Test
    void overlongTransportFieldIsABadRequest() throws Exception {
        String id = uniqueId("TL");
        createEmployee(id);

        mockMvc.perform(put("/api/v1/employees/{id}/transport", id)
                        .header("Authorization", transportAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupPoint\":\"" + "x".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    private int countEmployees(String... ids) {
        return jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id = 'ACME' AND employee_id = ANY(?)",
                Integer.class, (Object) ids);
    }

    private List<Map<String, Object>> snapshot(String prefix) {
        return jdbc.queryForList("SELECT employee_id, hr_version, row_hash, updated_at, opt_lock FROM employee "
                + "WHERE tenant_id = 'ACME' AND employee_id LIKE ? ORDER BY employee_id", prefix + "-%");
    }
}
