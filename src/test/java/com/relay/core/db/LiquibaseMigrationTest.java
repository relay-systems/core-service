package com.relay.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.core.TestcontainersConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfig.class)
class LiquibaseMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void liquibaseTablesExist() {
        // Liquibase creates these lowercase on PostgreSQL
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = current_schema()",
                String.class);
        assertThat(tables).contains("databasechangelog", "databasechangeloglock");
    }
}
