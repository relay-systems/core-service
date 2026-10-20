package com.relay.core.db.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.relay.core.CoreServiceApplication;
import com.relay.core.db.LocalLiquibaseRecovery;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class LocalLiquibaseRecoveryStartupTest extends RecoverySupport {

    @BeforeEach
    void db() throws Exception {
        freshDb();
    }

    /** v1 applied, v2 (changed earliest changeset) written; returns the DB state before startup. */
    List<String> v1AppliedV2Written(String v2Rollback) throws Exception {
        write(auditCs(), tbl("a", "id int"), tbl("b", "id int"));
        recovery(pool()).afterPropertiesSet();
        List<String> before = new ArrayList<>(history());
        before.addAll(audit());
        before.addAll(tables());
        write(auditCs(), cs("a", "create table a(id int, x int);", v2Rollback), tbl("b", "id int"));
        return before;
    }

    List<String> state() throws Exception {
        List<String> s = new ArrayList<>(history());
        s.addAll(audit());
        s.addAll(tables());
        return s;
    }

    ConfigurableApplicationContext start(boolean localProfile, boolean optIn, String url, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + PG.getUsername(),
                "--spring.datasource.password=" + PG.getPassword(),
                "--spring.liquibase.change-log=file:" + changelog(),
                "--relay.liquibase.auto-recovery-enabled=" + optIn
        ));

        if (localProfile) {
            args.add("--spring.profiles.active=local");
        }

        args.addAll(List.of(extra));
        return SpringApplication.run(CoreServiceApplication.class, args.toArray(String[]::new));
    }

    @Test
    void localProfileWithoutOptInKeepsNormalChecksumFailure() throws Exception {
        List<String> before = v1AppliedV2Written("insert into audit(msg) values ('rb-a'); drop table a;");
        assertThat(chain(catchThrowable(() -> start(true, false, url())))).contains("ValidationFailedException");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void optInWithoutLocalProfileKeepsNormalChecksumFailure() throws Exception {
        List<String> before = v1AppliedV2Written("insert into audit(msg) values ('rb-a'); drop table a;");
        assertThat(chain(catchThrowable(() -> start(false, true, url())))).contains("ValidationFailedException");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void nonAllowlistedHostRefusesStartup() throws Exception {
        v1AppliedV2Written("insert into audit(msg) values ('rb-a'); drop table a;");
        String badUrl = url().replaceFirst("//[^/:]+:\\d+", "//postgres.invalid:5432"); // unresolvable, never reached
        assertThat(chain(catchThrowable(() -> start(true, true, badUrl, "--spring.datasource.hikari.initialization-fail-timeout=-1"))))
                .contains("Local Liquibase recovery refused");
    }

    @Test
    void localProfileWithOptInRecoversAndAppStarts() throws Exception {
        v1AppliedV2Written("insert into audit(msg) values ('rb-a'); insert into audit(msg) values ('rb-b'); drop table a;");
        try (ConfigurableApplicationContext ctx = start(true, true, url())) {
            assertThat(q("select id from databasechangelog order by orderexecuted")).containsExactly("audit", "a", "b");
            assertThat(q("select column_name from information_schema.columns where table_name='a' order by 1"))
                    .containsExactly("id", "x");
            assertThat(lockHeld()).isFalse();
            String port = ctx.getEnvironment().getProperty("local.server.port");
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).contains("UP");
        }
    }

    @Test
    void failingRecoveryFailsContextStartup() throws Exception {
        List<String> before = v1AppliedV2Written("drop table a; drop table nonexistent_zzz;");
        assertThat(chain(catchThrowable(() -> start(true, true, url())))).contains("nonexistent_zzz");
        assertThat(state()).isEqualTo(before);
        assertThat(lockHeld()).isFalse();
    }

    @Test
    void liquibaseDisabledAlsoDisablesRecoveryAndLeavesDbUntouched() throws Exception {
        List<String> before = v1AppliedV2Written("insert into audit(msg) values ('rb-a'); drop table a;");
        try (ConfigurableApplicationContext ctx = start(true, true, url(), "--spring.liquibase.enabled=false")) {
            assertThat(ctx.getBeansOfType(LocalLiquibaseRecovery.class)).isEmpty();
        }
        assertThat(state()).isEqualTo(before);
        assertThat(lockHeld()).isFalse();
    }
}
