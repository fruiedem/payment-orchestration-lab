package lab.payment.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InfrastructureIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate http;

    @Test
    void postgresIsReachableAndInitialMigrationWasApplied() {
        String databaseVersion = jdbcTemplate.queryForObject("SELECT version()", String.class);
        Boolean schemaExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.schemata
                    WHERE schema_name = 'payment_orchestration'
                )
                """, Boolean.class);
        Boolean migrationSucceeded = jdbcTemplate.queryForObject("""
                SELECT success
                FROM flyway_schema_history
                WHERE version = '1'
                """, Boolean.class);

        assertThat(databaseVersion).startsWith("PostgreSQL");
        assertThat(schemaExists).isTrue();
        assertThat(migrationSucceeded).isTrue();
    }

    @Test
    void applicationHealthEndpointIsUp() {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = http.getForObject("/actuator/health", Map.class);

        assertThat(body).containsEntry("status", "UP");
    }
}
