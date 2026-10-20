package com.relay.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.core.TestcontainersConfig;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfig.class)
class SchemaConstraintsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private UUID newUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)", id, email);
        return id;
    }

    private void newIdentity(UUID userId, String issuer, String subject) {
        jdbc.update("insert into user_identities (user_id, issuer, subject) values (?, ?, ?)",
                userId, issuer, subject);
    }

    @Test
    void tablesAndChangesetsExist() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = current_schema()",
                String.class);
        assertThat(tables).contains("users", "user_identities");
        List<String> ids = jdbc.queryForList("select id from databasechangelog", String.class);
        assertThat(ids).containsExactly("init-users-schema");
    }

    @Test
    void constraintAndIndexNamesPreserved() {
        List<String> constraints = jdbc.queryForList(
                "select conname from pg_constraint where conrelid = 'user_identities'::regclass", String.class);
        assertThat(constraints).contains("user_identities_user_id_fkey", "user_identities_issuer_check",
                "user_identities_subject_check", "uq_user_identities_issuer_subject");
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where tablename = 'user_identities'", String.class);
        assertThat(indexes).contains("ix_user_identities_user_id");
    }

    @Test
    void duplicateIssuerSubjectRejected() {
        UUID u = newUser(null);
        newIdentity(u, "iss-dup", "sub");
        assertThatThrownBy(() -> newIdentity(u, "iss-dup", "sub"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameSubjectDifferentIssuerAllowed() {
        UUID u = newUser(null);
        newIdentity(u, "iss-a", "same-sub");
        newIdentity(u, "iss-b", "same-sub");
    }

    @Test
    void unknownUserRejectedByForeignKey() {
        assertThatThrownBy(() -> newIdentity(UUID.randomUUID(), "iss-fk", "sub"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emptyIssuerOrSubjectRejected() {
        UUID u = newUser(null);
        assertThatThrownBy(() -> newIdentity(u, "", "sub"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> newIdentity(u, "iss-empty", ""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void usersHasNoSecretColumns() {
        List<String> cols = jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = current_schema() and table_name in ('users', 'user_identities')",
                String.class);
        assertThat(cols).isNotEmpty().noneMatch(c -> c.contains("password") || c.contains("token"));
    }

    @Test
    void emailIsNotUnique() {
        newUser("dup@example.com");
        newUser("dup@example.com");
    }

    @Test
    void rerunningLiquibaseAppliesNothingNew() throws Exception {
        Integer before = jdbc.queryForObject("select count(*) from databasechangelog", Integer.class);
        try (var conn = dataSource.getConnection()) {
            var db = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            new Liquibase("db/changelog/master.changelog.xml", new ClassLoaderResourceAccessor(), db)
                    .update("");
        }
        Integer after = jdbc.queryForObject("select count(*) from databasechangelog", Integer.class);
        assertThat(after).isEqualTo(before);
    }
}
