package com.moveinsync.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.moveinsync.roster.core.RowHasher;
import com.moveinsync.roster.entity.Employee;
import com.moveinsync.roster.repository.EmployeeRepository;

/** Smoke test: the app starts on a real Postgres, migrations and seed data load, and security is on. */
class RosterSyncApplicationTests extends IntegrationTest {

	static final List<String> SEEDED_ACME = List.of("E1001", "E1002", "E1003", "E1004", "E1005");

	@Autowired
	EmployeeRepository employeeRepository;

	@Test
	void healthIsPublicAndUp() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void everythingElseNeedsAToken() throws Exception {
		mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
	}

	@Test
	void migrationsAndSeedDataAreLoaded() {
		// Other tests add employees to the same database, so count only the seeded ones.
		Integer seeded = jdbc.queryForObject(
			"SELECT count(*) FROM employee WHERE employee_id IN ('E1001','E1002','E1003','E1004','E1005','G2001','G2002')",
			Integer.class);
		Integer badHashes = jdbc.queryForObject("SELECT count(*) FROM employee WHERE length(row_hash) <> 64", Integer.class);

		assertThat(seeded).isEqualTo(7);
		assertThat(badHashes).isZero();
	}

	/** Proves the JPA mapping matches the schema, and that the Java hash agrees with the SQL seed hash. */
	@Test
	void seededEmployeesLoadAndJavaHashMatchesStoredHash() {
		for (String id : SEEDED_ACME) {
			Employee e = employeeRepository.findByTenantIdAndEmployeeId("ACME", id).orElseThrow();
			assertThat(RowHasher.hash(e.hr())).as(id).isEqualTo(e.getRowHash());
		}
		Employee e1002 = employeeRepository.findByTenantIdAndEmployeeId("ACME", "E1002").orElseThrow();
		assertThat(e1002.transport().specialAssistance()).isEqualTo("Wheelchair access");
	}

}
