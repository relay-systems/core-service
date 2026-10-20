package com.relay.core.db.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.relay.core.db.LocalLiquibaseRecovery;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import liquibase.ChecksumVersion;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.ThreadLocalScopeManager;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalLiquibaseRecoveryTest extends RecoverySupport {

    @BeforeEach
    void db() throws Exception {
        freshDb();
    }

    void apply() throws Exception {
        recovery(pool()).afterPropertiesSet();
    }

    Throwable failure() {
        return catchThrowable(this::apply);
    }

    String md5(String id) throws Exception {
        return q("select md5sum from databasechangelog where id='" + id + "'").get(0);
    }

    /** Checksum Liquibase computes for the CURRENT changelog files. */
    String expectedMd5(String id) throws Exception {
        try (Connection c = DriverManager.getConnection(url(), PG.getUsername(), PG.getPassword())) {
            DirectoryResourceAccessor accessor = new DirectoryResourceAccessor(dir);
            return Scope.child(Map.<String, Object>of(Scope.Attr.resourceAccessor.name(), accessor), () -> {
                Liquibase l = new Liquibase("changelog.xml", accessor,
                        DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c)));
                return l.getDatabaseChangeLog().getChangeSets().stream().filter(s -> s.getId().equals(id)).findFirst().get()
                        .generateCheckSum(ChecksumVersion.latest()).toString();
            });
        }
    }

    long count(String msg) throws Exception {
        return audit().stream().filter(msg::equals).count();
    }

    @Test
    void noChecksumChangeDoesNothing() throws Exception {
        write(auditCs(), tbl("a", "id int"));
        apply();
        List<String> history = history();
        List<String> audit = audit();
        apply();
        assertThat(history()).isEqualTo(history);
        assertThat(audit()).isEqualTo(audit).containsExactly("fw-a");
    }

    @Test
    void changedForwardSqlIsRolledBackAndReapplied() throws Exception {
        write(auditCs(), tbl("a", "id int"));
        apply();
        String oldMd5 = md5("a");
        write(auditCs(), tbl("a", "id int, x int"));
        apply();
        assertThat(q("select column_name from information_schema.columns where table_name='a' order by 1"))
                .containsExactly("id", "x");
        assertThat(md5("a")).isNotEqualTo(oldMd5).isEqualTo(expectedMd5("a"));
        assertThat(audit()).containsExactly("fw-a", "rb-a", "fw-a");
        assertThat(lockHeld()).isFalse();
    }

    @Test
    void dependentChangesetsAreRolledBackInReverseOrderAndReappliedInOrder() throws Exception {
        Cs con = cs("con", "alter table p add constraint p_chk check (id > 0); insert into audit(msg) values ('fw-con');",
                "insert into audit(msg) values ('rb-con'); alter table p drop constraint p_chk;");
        Cs idx = cs("idx", "create index p_ix on p(id); insert into audit(msg) values ('fw-idx');",
                "insert into audit(msg) values ('rb-idx'); drop index p_ix;");
        write(auditCs(), tbl("p", "id int"), con, idx);
        apply();
        write(auditCs(), tbl("p", "id int, x int"), con, idx); // only the earliest changes
        apply();
        assertThat(audit()).containsExactly("fw-p", "fw-con", "fw-idx", "rb-idx", "rb-con", "rb-p", "fw-p", "fw-con", "fw-idx");
        assertThat(q("select id from databasechangelog order by orderexecuted")).containsExactly("audit", "p", "con", "idx");
        assertThat(q("select indexname from pg_indexes where tablename='p' order by 1")).contains("p_ix");
        assertThat(md5("p")).isEqualTo(expectedMd5("p"));
        assertThat(md5("con")).isEqualTo(expectedMd5("con"));
    }

    @Test
    void failingMultiStatementRollbackChangesNothing() throws Exception {
        Cs v1 = cs("a", "create table a(id int); insert into audit(msg) values ('fw-a');",
                "insert into audit(msg) values ('rb-a'); drop table a; drop table nonexistent_zzz;");
        write(auditCs(), v1);
        apply();
        List<String> history = history();
        write(auditCs(), new Cs("a", "create table a(id int, x int);", v1.rb(), true));
        assertThat(chain(failure())).contains("nonexistent_zzz").contains("unchanged");
        assertThat(tables()).containsExactly("a", "audit");
        assertThat(audit()).containsExactly("fw-a");
        assertThat(history()).isEqualTo(history);
        assertThat(lockHeld()).isFalse();
    }

    @Test
    void failedReapplyKeepsHistoryMatchingSchema() throws Exception {
        write(auditCs(), tbl("t1", "id int"), tbl("t2", "id int"));
        apply();
        write(auditCs(), tbl("t1", "id int, x int"), cs("t2", "create tabel t2(id int);", "drop table t2;"));
        assertThat(chain(failure())).containsIgnoringCase("tabel");
        assertThat(tables()).containsExactly("audit", "t1");
        assertThat(q("select id from databasechangelog order by orderexecuted")).containsExactly("audit", "t1");
        assertThat(md5("t1")).isEqualTo(expectedMd5("t1"));
        assertThat(lockHeld()).isFalse();
    }

    @Test
    void persistentChecksumMismatchStopsAfterThreeAttempts() throws Exception {
        write(auditCs(), tbl("a", "id int"));
        apply();
        AtomicInteger n = new AtomicInteger();
        write(auditCs(), tbl("a", "id int, x0 int"));
        HikariDataSource ds = pool();
        LocalLiquibaseRecovery r = new LocalLiquibaseRecovery() {
            @Override
            protected void reapply(liquibase.Liquibase lb, liquibase.database.Database db) throws Exception {
                super.reapply(lb, db);
                write(auditCs(), tbl("a", "id int, x" + n.incrementAndGet() + " int")); // checksum changes again
            }
        };
        configure(r, ds);
        assertThat(chain(catchThrowable(r::afterPropertiesSet))).contains("persists after 3 recovery attempts");
        assertThat(count("rb-a")).isEqualTo(3);
        assertThat(lockHeld()).isFalse();
    }

    @Test
    void nonChecksumErrorsAreNotRetried() throws Exception {
        write(auditCs(), tbl("a", "id int"));
        apply();
        write(auditCs(), cs("a", "create tabel a(id int);", "insert into audit(msg) values ('rb-a'); drop table a;"));
        assertThat(failure()).isNotNull();
        assertThat(count("rb-a")).isEqualTo(1);
        assertThat(lockHeld()).isFalse();
    }

    @Test
    void orphanedHistoryRecordFailsWithoutChanges() throws Exception {
        write(auditCs(), tbl("a", "id int"), tbl("b", "id int"));
        apply();
        List<String> history = history();
        write(auditCs(), tbl("a", "id int, x int")); // b removed from the changelog
        assertThat(chain(failure())).contains("missing from the changelog").contains("b::a");
        assertThat(tables()).containsExactly("a", "audit", "b");
        assertThat(history()).isEqualTo(history);
        assertThat(audit()).containsExactly("fw-a", "fw-b");
    }

    @Test
    void nonTransactionalChangesetsAreRejectedBeforeAnyChange() throws Exception {
        // (a) runInTransaction=false
        write(auditCs(), new Cs("a", "create table a(id int);", "drop table a;", false));
        apply();
        List<String> history = history();
        write(auditCs(), new Cs("a", "create table a(id int, x int);", "drop table a;", false));
        assertThat(chain(failure())).contains("runInTransaction=false");
        assertThat(history()).isEqualTo(history);
        assertThat(tables()).containsExactly("a", "audit");
    }

    @Test
    void concurrentIndexInRollbackIsRejectedBeforeAnyChange() throws Exception {
        write(auditCs(), cs("a", "create table a(id int); create index a_ix on a(id);",
                "drop index concurrently a_ix; drop table a;"));
        apply();
        List<String> history = history();
        write(auditCs(), cs("a", "create table a(id int, x int); create index a_ix on a(id);",
                "drop index concurrently a_ix; drop table a;"));
        assertThat(chain(failure())).contains("non-transactional");
        assertThat(history()).isEqualTo(history);
        assertThat(q("select indexname from pg_indexes where tablename='a' order by 1")).contains("a_ix");
    }

    @Test
    void changesetWithoutRollbackBlockIsRejected() throws Exception {
        write(auditCs(), cs("a", "create table a(id int);", null));
        apply();
        List<String> history = history();
        write(auditCs(), cs("a", "create table a(id int, x int);", null));
        assertThat(chain(failure())).contains("no rollback block");
        assertThat(history()).isEqualTo(history);
        assertThat(tables()).containsExactly("a", "audit");
    }

    /** Adds raw attributes (e.g. context="x") to one changeSet of the changelog written by write(). */
    void attr(String id, String attrs) throws Exception {
        Path f = changelog();
        Files.writeString(f, Files.readString(f).replace("<changeSet id=\"" + id + "\" ", "<changeSet id=\"" + id + "\" " + attrs + " "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"context=\"x\"", "labels=\"x\"", "dbms=\"h2\""})
    void dependentExcludedByFilterFailsPreflightWithoutChanges(String filteredAttr) throws Exception {
        write(auditCs(), tbl("a", "id int"), tbl("b", "id int"));
        apply(); // b runs unfiltered; the filter attribute is added later (not part of the checksum)
        List<String> history = history();
        write(auditCs(), tbl("a", "id int, x int"), tbl("b", "id int"));
        attr("b", filteredAttr);
        LocalLiquibaseRecovery r = recovery(pool());
        r.setContexts("y");
        r.setLabelFilter("y");
        String msg = chain(catchThrowable(r::afterPropertiesSet));
        assertThat(msg).contains("b::a");
        // a dbms mismatch drops the changeset at parse time, so it is reported by the orphan check instead
        if (filteredAttr.startsWith("dbms")) assertThat(msg).contains("missing from the changelog");
        else assertThat(msg).contains("excluded on reapply").containsPattern("(context|label) filter");
        assertThat(history()).isEqualTo(history);
        assertThat(tables()).containsExactly("a", "audit", "b");
        assertThat(audit()).containsExactly("fw-a", "fw-b");
        assertThat(lockHeld()).isFalse();
    }

    void writeRoRa(Cs a, Cs ro, Cs ra) throws Exception {
        write(auditCs(), a, ro, ra);
        attr("ro", "runOnChange=\"true\"");
        attr("ra", "runAlways=\"true\"");
    }

    @Test
    void runOnChangeAndRunAlwaysDoNotTriggerRecoveryButNormalChangesDo() throws Exception {
        Cs ro = cs("ro", "insert into audit(msg) values ('fw-ro');", "insert into audit(msg) values ('rb-ro');");
        Cs ra = cs("ra", "insert into audit(msg) values ('fw-ra');", "insert into audit(msg) values ('rb-ra');");
        writeRoRa(tbl("a", "id int"), ro, ra);
        apply();
        List<String> historyV1 = history();
        // both SQL files change: treated as ordinary changesets this would be a checksum mismatch and roll back "a" too
        Cs ro2 = cs("ro", "insert into audit(msg) values ('fw-ro2');", ro.rb());
        Cs ra2 = cs("ra", "insert into audit(msg) values ('fw-ra2');", ra.rb());
        writeRoRa(tbl("a", "id int"), ro2, ra2);
        apply(); // standard Liquibase: ro re-runs (changed), ra runs again, nothing rolled back
        assertThat(audit()).containsExactly("fw-a", "fw-ro", "fw-ra", "fw-ro2", "fw-ra2");
        assertThat(audit()).doesNotContain("rb-a", "rb-ro", "rb-ra");
        assertThat(tables()).containsExactly("a", "audit");
        // history rows for audit and a untouched (same md5 and execution order); ro/ra rows updated in place by Liquibase
        assertThat(q("select id, md5sum, orderexecuted from databasechangelog where id in ('audit','a') order by orderexecuted"))
                .isEqualTo(historyV1.stream().filter(h -> h.startsWith("audit|") || h.startsWith("a|"))
                        .map(h -> String.join("|", java.util.Arrays.asList(h.split("\\|")).subList(0, 3))).toList());
        assertThat(q("select count(*) from databasechangelog")).containsExactly("4");
        assertThat(md5("ro")).isEqualTo(expectedMd5("ro"));
        apply(); // unchanged: runAlways runs once more, runOnChange not
        assertThat(audit()).containsExactly("fw-a", "fw-ro", "fw-ra", "fw-ro2", "fw-ra2", "fw-ra2");

        writeRoRa(tbl("a", "id int, x int"), ro2, ra2); // genuinely changed normal changeset
        apply();
        assertThat(audit()).contains("rb-a", "rb-ro", "rb-ra"); // later runOnChange/runAlways are still dependents
        assertThat(q("select column_name from information_schema.columns where table_name='a' order by 1"))
                .containsExactly("id", "x");
    }

    @Test
    void releaseLockFailureAfterFailedRecoveryIsSuppressedNotMasking() throws Exception {
        Cs v1 = cs("a", "create table a(id int);", "drop table a; drop table nonexistent_zzz;");
        write(auditCs(), v1);
        apply();
        write(auditCs(), cs("a", "create table a(id int, x int);", v1.rb()));
        LocalLiquibaseRecovery r = failingRelease(pool());
        Throwable t = catchThrowable(r::afterPropertiesSet);
        assertThat(chain(t)).contains("nonexistent_zzz");
        assertThat(t.getSuppressed()).hasSize(1);
        assertThat(t.getSuppressed()[0]).hasMessage("release boom");
        assertThat(lockHeld()).isFalse(); // the real release still ran first
    }

    @Test
    void releaseLockFailureAloneIsPropagated() throws Exception {
        write(auditCs(), tbl("a", "id int"));
        apply();
        write(auditCs(), tbl("a", "id int, x int"));
        assertThatThrownBy(failingRelease(pool())::afterPropertiesSet).hasMessage("release boom");
    }

    LocalLiquibaseRecovery failingRelease(HikariDataSource ds) {
        LocalLiquibaseRecovery r = new LocalLiquibaseRecovery() {
            @Override
            protected void releaseLock(liquibase.lockservice.LockService lock) throws Exception {
                super.releaseLock(lock);
                throw new IllegalStateException("release boom");
            }
        };
        configure(r, ds);
        return r;
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://postgres.invalid:5432/x", "jdbc:postgresql://localhost.evil.invalid:5432/x",
            "jdbc:postgresql:x"})
    void nonAllowlistedHostRefusesToStartWithoutConnecting(String url) {
        HikariDataSource ds = new HikariDataSource() {
            @Override
            public Connection getConnection() {
                throw new AssertionError("must not connect before the host guard passes");
            }
        };
        ds.setInitializationFailTimeout(-1); // never connect eagerly
        ds.setJdbcUrl(url);
        LocalLiquibaseRecovery r = recovery(ds);
        assertThatThrownBy(r::afterPropertiesSet).isInstanceOf(IllegalStateException.class).hasMessageContaining("refused");
    }

    @Test
    void realTestcontainersUrlIsAccepted() {
        assertThat(LocalLiquibaseRecovery.hostOf(url())).isEqualTo("localhost");
    }

    @Test
    void racingRecoveriesAreSerializedByTheLock() throws Exception {
        Cs v1 = cs("a", "create table a(id int); insert into audit(msg) values ('fw-a');",
                "insert into audit(msg) values ('rb-a'); select pg_sleep(2); drop table a;");
        write(auditCs(), v1);
        apply();
        write(auditCs(), new Cs("a", "create table a(id int, x int); insert into audit(msg) values ('fw-a');", v1.rb(), true));
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        List<Future<?>> done = List.of(race(ex, start), race(ex, start));
        for (Future<?> f : done) f.get(60, TimeUnit.SECONDS);
        ex.shutdown();
        assertThat(count("rb-a")).isEqualTo(1);
        assertThat(tables()).containsExactly("a", "audit");
        assertThat(q("select id from databasechangelog order by orderexecuted")).containsExactly("audit", "a");
        assertThat(md5("a")).isEqualTo(expectedMd5("a"));
        assertThat(lockHeld()).isFalse();
    }

    Future<?> race(ExecutorService ex, CyclicBarrier start) {
        LocalLiquibaseRecovery r = recovery(pool());
        return ex.submit(() -> {
            Scope.setScopeManager(new ThreadLocalScopeManager()); // two Liquibase runs in one JVM only happen in tests
            start.await();
            r.afterPropertiesSet();
            return null;
        });
    }
}
