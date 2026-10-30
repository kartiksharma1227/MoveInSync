package com.moveinsync.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.moveinsync.roster.core.RowHasher;
import com.moveinsync.roster.entity.Employee;
import com.moveinsync.roster.repository.EmployeeRepository;

/** Phase 1 smoke test: the app starts on a real Postgres, migrations and seed data load, and security is on. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RosterSyncApplicationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

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
		Integer employees = jdbc.queryForObject("SELECT count(*) FROM employee", Integer.class);
		Integer badHashes = jdbc.queryForObject("SELECT count(*) FROM employee WHERE length(row_hash) <> 64", Integer.class);

		assertThat(employees).isEqualTo(7);
		assertThat(badHashes).isZero();
	}

	/** Proves the JPA mapping matches the schema, and that the Java hash agrees with the SQL seed hash. */
	@Test
	void seededEmployeesLoadAndJavaHashMatchesStoredHash() {
		List<Employee> all = employeeRepository.findAll();

		assertThat(all).hasSize(7);
		for (Employee e : all) {
			assertThat(RowHasher.hash(e.hr())).as(e.getEmployeeId()).isEqualTo(e.getRowHash());
		}
		Employee e1002 = employeeRepository.findByTenantIdAndEmployeeId("ACME", "E1002").orElseThrow();
		assertThat(e1002.transport().specialAssistance()).isEqualTo("Wheelchair access");
	}

}
