package com.relay.core.db.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.lockservice.LockService;
import liquibase.lockservice.LockServiceFactory;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.Test;

/** Liquibase 4.31.1 behaviours that LocalLiquibaseRecovery's design depends on; a Liquibase upgrade may break them. */
class LiquibaseBehaviorAssumptionsTest extends RecoverySupport {

    Connection open(boolean autoCommit) throws Exception {
        Connection c = DriverManager.getConnection(url(), PG.getUsername(), PG.getPassword());
        c.setAutoCommit(autoCommit);
        return c;
    }

    Database db(Connection c) throws Exception {
        return DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
    }

    Liquibase lb(Database d) throws Exception {
        return new Liquibase("changelog.xml", new DirectoryResourceAccessor(dir), d);
    }

    @Test
    void changeSetRollbackCommitsInternallyAndKeepsHistoryRow() throws Exception {
        freshDb();
        write(cs("t1", "create table t1(id int);", "drop table t1;"));
        try (Connection a = open(true)) {
            lb(db(a)).update("");
        }
        try (Connection a = open(false)) {
            Database da = db(a);
            Liquibase l = lb(da);
            ChangeSet t1 = l.getDatabaseChangeLog().getChangeSets().get(0);
            Scope.child(Scope.Attr.resourceAccessor.name(), new DirectoryResourceAccessor(dir), () -> t1.rollback(da));
            a.rollback(); // too late: already committed
            assertThat(tables()).isEmpty();
            assertThat(history()).hasSize(1);
        }
    }

    @Test
    void updateCommitsPerChangeset() throws Exception {
        freshDb();
        write(cs("t1", "create table t1(id int);", null), cs("t2", "this is invalid sql", null));
        try (Connection a = open(false)) {
            assertThatThrownBy(() -> lb(db(a)).update("")).isInstanceOf(LiquibaseException.class);
            a.rollback();
            assertThat(tables()).containsExactly("t1");
            assertThat(history()).hasSize(1);
        }
    }

    @Test
    void updateReleasesALockHeldByTheCallerAndLockRowIsVisibleToOthers() throws Exception {
        freshDb();
        write(cs("t1", "create table t1(id int);", null));
        try (Connection a = open(false)) {
            Database da = db(a);
            LockService ls = LockServiceFactory.getInstance().getLockService(da);
            assertThat(ls.acquireLock()).isTrue();
            assertThat(lockHeld()).isTrue(); // committed although A is autoCommit=false
            lb(da).update("");
            assertThat(ls.hasChangeLogLock()).isFalse();
            assertThat(lockHeld()).isFalse();
        }
    }
}
