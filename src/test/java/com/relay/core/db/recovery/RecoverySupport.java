package com.relay.core.db.recovery;

import com.relay.core.db.LocalLiquibaseRecovery;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResourceLoader;
import org.testcontainers.containers.PostgreSQLContainer;

/** Dedicated disposable postgres (not the shared @ServiceConnection one); one fresh database per test. */
abstract class RecoverySupport {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");
    static final AtomicInteger SEQ = new AtomicInteger();

    static {
        PG.start();
    }

    /** One changeset: forward SQL, rollback SQL (null = no rollback block), runInTransaction flag. */
    record Cs(String id, String fwd, String rb, boolean tx) {}

    static Cs cs(String id, String fwd, String rb) {
        return new Cs(id, fwd, rb, true);
    }

    @TempDir
    Path dir;

    String dbName;
    final List<HikariDataSource> pools = new ArrayList<>();

    void freshDb() throws Exception {
        dbName = "rec" + SEQ.incrementAndGet();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
                Statement s = c.createStatement()) {
            s.execute("create database " + dbName);
        }
    }

    String url() {
        return PG.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + dbName + "$1");
    }

    HikariDataSource pool() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        cfg.setMaximumPoolSize(4);
        HikariDataSource ds = new HikariDataSource(cfg);
        pools.add(ds);
        return ds;
    }

    @AfterEach
    void closePools() {
        pools.forEach(HikariDataSource::close);
    }

    Path changelog() {
        return dir.resolve("changelog.xml");
    }

    /** Rewrites changelog.xml and its sql/ files; the shared logicalFilePath keeps identity across versions. */
    void write(Cs... changesets) throws Exception {
        StringBuilder x = new StringBuilder("<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog "
                + "http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd\" "
                + "logicalFilePath=\"recovery.xml\">\n");
        Files.createDirectories(dir.resolve("sql/rollbacks"));
        for (Cs c : changesets) {
            Files.writeString(dir.resolve("sql/" + c.id() + ".sql"), c.fwd());
            x.append("<changeSet id=\"").append(c.id()).append("\" author=\"a\" runInTransaction=\"").append(c.tx())
                    .append("\"><sqlFile path=\"sql/").append(c.id()).append(".sql\" relativeToChangelogFile=\"true\"/>");
            if (c.rb() != null) {
                Files.writeString(dir.resolve("sql/rollbacks/" + c.id() + ".sql"), c.rb());
                x.append("<rollback><sqlFile path=\"sql/rollbacks/").append(c.id())
                        .append(".sql\" relativeToChangelogFile=\"true\"/></rollback>");
            }
            x.append("</changeSet>\n");
        }
        Files.writeString(changelog(), x.append("</databaseChangeLog>").toString());
    }

    LocalLiquibaseRecovery recovery(HikariDataSource ds) {
        LocalLiquibaseRecovery r = new LocalLiquibaseRecovery();
        configure(r, ds);
        return r;
    }

    void configure(LocalLiquibaseRecovery r, HikariDataSource ds) {
        r.setDataSource(ds);
        r.setChangeLog("file:" + changelog());
        r.setResourceLoader(new FileSystemResourceLoader());
        r.setContexts("");
        r.setLabelFilter("");
    }

    List<String> q(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url(), PG.getUsername(), PG.getPassword());
                Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery(sql)) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    StringBuilder b = new StringBuilder();
                    for (int i = 1; i <= n; i++) b.append(i > 1 ? "|" : "").append(rs.getString(i));
                    out.add(b.toString());
                }
            }
        }
        return out;
    }

    List<String> tables() throws Exception {
        return q("select table_name from information_schema.tables where table_schema='public' "
                + "and table_name not like 'databasechangelog%' order by 1");
    }

    List<String> history() throws Exception {
        return q("select id, md5sum, orderexecuted, dateexecuted from databasechangelog order by orderexecuted");
    }

    List<String> audit() throws Exception {
        return q("select msg from audit order by n");
    }

    boolean lockHeld() throws Exception {
        return q("select locked from databasechangeloglock").contains("t");
    }

    static String chain(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (; t != null; t = t.getCause()) b.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append("\n");
        return b.toString();
    }

    /** Standard v1: audit table (never changed) + users-like table with audit trail on forward and rollback. */
    static Cs auditCs() {
        return cs("audit", "create table audit(n serial primary key, msg text);", "drop table audit;");
    }

    static Cs tbl(String name, String cols) {
        return cs(name, "create table " + name + "(" + cols + "); insert into audit(msg) values ('fw-" + name + "');",
                "insert into audit(msg) values ('rb-" + name + "'); drop table " + name + ";");
    }
}
