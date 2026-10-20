package com.relay.core.db.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.core.db.LocalLiquibaseRecovery;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGProperty;

/** Pure parsing only: no test here creates a connection to anything (Hikari is lazy and getConnection() throws). */
class HostGuardCharacterizationTest {

    /** Accepted URLs: the guard's host must equal the host pgjdbc itself parses (pure parse, no connection). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "jdbc:postgresql://localhost/db|localhost", "jdbc:postgresql://LOCALHOST/db|localhost",
            "jdbc:postgresql://LocalHost:5432/db|localhost", "jdbc:postgresql://127.0.0.1:5432/db|127.0.0.1",
            "jdbc:postgresql://127.0.0.1/db|127.0.0.1", "jdbc:postgresql://[::1]/db|[::1]",
            "jdbc:postgresql://[::1]:5432/db|[::1]", "jdbc:postgresql://localhost:55123/test?loggerLevel=OFF|localhost",
            "jdbc:postgresql://localhost/db?user=u&password=p&ssl=false|localhost"})
    void acceptedUrlsMatchDriverHost(String url, String expected) {
        assertThat(LocalLiquibaseRecovery.hostOf(url)).isEqualTo(expected);
        Properties p = org.postgresql.Driver.parseURL(url, new Properties());
        assertThat(p.getProperty("PGHOST").toLowerCase()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // no explicit //host: the driver would take PGHOST from data-source properties
            "jdbc:postgresql:db", "jdbc:postgresql:/db", "jdbc:postgresql:///db", "jdbc:postgresql://localhost", "jdbc:postgresql://localhost/",
            "jdbc:postgresql://localhost?/x",
            // query-parameter redirects, incl. case variants and encoded keys
            "jdbc:postgresql://localhost/db?PGHOST=evil.invalid", "jdbc:postgresql://localhost/db?host=evil.invalid",
            "jdbc:postgresql://localhost/db?pghost=evil.invalid", "jdbc:postgresql://localhost/db?Host=evil.invalid",
            "jdbc:postgresql://localhost/db?PGPORT=1", "jdbc:postgresql://localhost/db?port=1",
            "jdbc:postgresql://localhost/db?hostaddr=203.0.113.9", "jdbc:postgresql://localhost/db?serverName=evil.invalid",
            "jdbc:postgresql://localhost/db?socketFactory=x.Y", "jdbc:postgresql://localhost/db?socketFactoryArg=evil.invalid",
            "jdbc:postgresql://localhost/db?loggerLevel=OFF&PGHOST=evil.invalid", "jdbc:postgresql://localhost/db?service=svc",
            "jdbc:postgresql://localhost/db?url=jdbc:postgresql://evil.invalid/x", "jdbc:postgresql://localhost/db?PG%48OST=evil.invalid",
            "jdbc:postgresql://localhost/db?targetServerType=primary", "jdbc:postgresql://localhost/db?a+host=x",
            // multi-host, userinfo, encoding, IPv6 and other host spellings
            "jdbc:postgresql://localhost,evil.invalid:5432/db", "jdbc:postgresql://localhost@evil.invalid/db",
            "jdbc:postgresql://evil.invalid@localhost/db", "jdbc:postgresql://u:p@localhost/db", "jdbc:postgresql://localhost./db",
            "jdbc:postgresql://127.1/db", "jdbc:postgresql://2130706433/db", "jdbc:postgresql://[0:0:0:0:0:0:0:1]/db",
            "jdbc:postgresql://[::ffff:127.0.0.1]/db", "jdbc:postgresql://::1/db", "jdbc:postgresql://::1:5432/db",
            "jdbc:postgresql://local%68ost/db", "jdbc:postgresql://localhost%2Cevil.invalid/db",
            "jdbc:postgresql://localhost#@evil.invalid/db", "jdbc:postgresql://localhost\\@evil.invalid/db",
            "jdbc:postgresql://localhost:5432:evil/db", "jdbc:postgresql://localhost:/db", "jdbc:postgresql://localhost:123456/db",
            "jdbc:postgresql://:5432/db", "jdbc:postgresql:////db", "jdbc:postgresql://evil.invalid/db",
            "jdbc:postgresql://postgres:5432/db", "jdbc:postgresql://localhost.evil.example/db",
            // scheme oddities, garbage
            "JDBC:postgresql://localhost/db", "jdbc:POSTGRESQL://localhost/db", "jdbc:mysql://localhost/db",
            "postgresql://localhost/db", " jdbc:postgresql://localhost/db", "jdbc:postgresql://localhost/db ", "not a url", ""})
    void everythingElseIsRejected(String url) {
        assertThat(LocalLiquibaseRecovery.hostOf(url)).isNull();
    }

    @Test
    void nullUrlIsRejected() {
        assertThat(LocalLiquibaseRecovery.hostOf(null)).isNull();
    }

    /** Every pgjdbc property whose NAME concerns host/port/socket/factory/address/service/server/target is rejected. */
    @Test
    void everyTargetRelatedPgjdbcPropertyNameIsRejected() {
        List<String> related = Arrays.stream(PGProperty.values()).map(PGProperty::getName)
                .filter(n -> n.toLowerCase().matches(".*(host|port|socket|factory|address|service|server|target).*")).toList();
        assertThat(related).contains("PGHOST", "PGPORT", "socketFactory", "socketFactoryArg", "localSocketAddress", "service");
        for (String name : related) {
            assertThat(LocalLiquibaseRecovery.hostOf("jdbc:postgresql://localhost/db?" + name + "=x")).as(name).isNull();
            assertThat(LocalLiquibaseRecovery.hostOf("jdbc:postgresql://localhost/db?" + name.toUpperCase() + "=x")).as(name).isNull();
        }
    }

    /**
     * Properties whose DESCRIPTION mentions a target-ish word but which are accepted. Reviewed: none selects host,
     * port, socket class or service; if pgjdbc adds one, this snapshot fails and must be re-reviewed.
     */
    @Test
    void acceptedPgjdbcPropertiesWithTargetishDescriptionsAreReviewed() {
        Set<String> accepted = Arrays.stream(PGProperty.values())
                .filter(p -> p.getDescription() != null
                        && p.getDescription().toLowerCase().matches("(?s).*(host|port|socket|factory|address|service|server|connect).*"))
                .map(PGProperty::getName)
                .filter(n -> LocalLiquibaseRecovery.hostOf("jdbc:postgresql://localhost/db?" + n + "=x") != null)
                .collect(Collectors.toCollection(TreeSet::new));
        // reviewed: PGDBNAME (database name only), none redirects host/port/socket
        assertThat(accepted).isSubsetOf(
                "assumeMinServerVersion", "binaryTransfer", "channelBinding", "connectTimeout", "databaseMetadataCacheFields",
                "databaseMetadataCacheFieldsMiB", "groupStartupParameters", "gssResponseTimeout", "loginTimeout",
                "logServerErrorDetail", "options", "preparedStatementCacheQueries", "preparedStatementCacheSizeMiB",
                "protocolVersion", "readOnly", "readOnlyMode", "receiveBufferSize", "replication", "sendBufferSize",
                "sslResponseTimeout", "sslmode", "sslnegotiation", "sslrootcert", "xmlFactoryFactory", "user", "PGDBNAME", "logUnclosedConnections", "sslNegotiation");
    }

    // ---- Hikari-level guard, via afterPropertiesSet; getConnection() throws so any connect attempt fails the test ----

    static HikariDataSource neverConnects(String url) {
        HikariDataSource ds = new HikariDataSource() {
            @Override
            public Connection getConnection() {
                throw new AssertionError("must not connect before the host guard passes");
            }
        };
        ds.setInitializationFailTimeout(-1);
        if (url != null) ds.setJdbcUrl(url);
        return ds;
    }

    static void assertRefused(HikariDataSource ds) {
        LocalLiquibaseRecovery r = new LocalLiquibaseRecovery();
        r.setDataSource(ds);
        assertThatThrownBy(r::afterPropertiesSet).isInstanceOf(IllegalStateException.class).hasMessageContaining("refused");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PGHOST", "pghost", "host", "Host", "serverName", "hostaddr", "PGPORT", "port", "socketFactory",
            "socketFactoryArg", "url", "URL", "jdbcUrl", "service"})
    void dataSourcePropertiesThatCouldRedirectAreRefused(String key) {
        for (String url : new String[] {"jdbc:postgresql://localhost/db", "jdbc:postgresql:db", null}) {
            HikariDataSource ds = neverConnects(url);
            ds.addDataSourceProperty(key, "evil.invalid");
            assertRefused(ds);
        }
    }

    @Test
    void nullOrBlankJdbcUrlIsRefused() {
        assertRefused(neverConnects(null));
        assertRefused(neverConnects(" "));
    }

    @Test
    void dataSourceClassNameIsRefused() {
        HikariDataSource ds = neverConnects("jdbc:postgresql://localhost/db");
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        assertRefused(ds);
        HikariDataSource ds2 = neverConnects(null);
        ds2.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds2.addDataSourceProperty("serverName", "evil.invalid");
        assertRefused(ds2);
    }

    @Test
    void dataSourceInstanceIsRefused() {
        HikariDataSource ds = neverConnects("jdbc:postgresql://localhost/db");
        ds.setDataSource(new org.postgresql.ds.PGSimpleDataSource());
        assertRefused(ds);
    }

    public static class OtherDriver extends org.postgresql.Driver {}

    @Test
    void unexpectedDriverClassNameIsRefused() {
        HikariDataSource ds = neverConnects("jdbc:postgresql://localhost/db");
        ds.setDriverClassName(OtherDriver.class.getName());
        assertRefused(ds);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql:db", "jdbc:postgresql://evil.invalid/db", "jdbc:postgresql://localhost,evil.invalid/db",
            "jdbc:postgresql://localhost/db?PGHOST=evil.invalid", "jdbc:postgresql://postgres:5432/db",
            "jdbc:postgresql://localhost.evil.invalid:5432/db"})
    void badUrlsAreRefusedBeforeAnyConnection(String url) {
        assertRefused(neverConnects(url));
    }
}
