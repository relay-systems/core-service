package com.relay.core.db;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.GlobalConfiguration;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.RuntimeEnvironment;
import liquibase.Scope;
import liquibase.change.Change;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.changelog.ChangeLogIterator;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.RanChangeSet;
import liquibase.changelog.filter.ChangeSetFilter;
import liquibase.changelog.filter.ContextChangeSetFilter;
import liquibase.changelog.filter.DbmsChangeSetFilter;
import liquibase.changelog.filter.LabelChangeSetFilter;
import liquibase.changelog.filter.ShouldRunChangeSetFilter;
import liquibase.changelog.visitor.UpdateVisitor;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.integration.spring.SpringResourceAccessor;
import liquibase.lockservice.LockService;
import liquibase.lockservice.LockServiceFactory;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

/**
 * LOCAL DEVELOPMENT ONLY. Replaces Boot's Liquibase when a changed, already-applied changeset fails checksum
*/
public class LocalLiquibaseRecovery extends SpringLiquibase {

    static final int MAX_ATTEMPTS = 3;
    private static final Pattern LOCAL_URL = Pattern.compile(
            "jdbc:postgresql://((?i:localhost)|127\\.0\\.0\\.1|\\[::1\\])(?::\\d{1,5})?/[^/?#\\s]+(?:\\?(.*))?", Pattern.DOTALL);
    private static final Pattern TARGET_KEY = Pattern.compile(
            "host|port|socket|factory|address|service|server|target|url|\\+");
    private static final Pattern NON_TRANSACTIONAL = Pattern.compile(
            "(?i)\\b(concurrently|vacuum|create\\s+database|drop\\s+database|alter\\s+system)\\b");

    @Configuration(proxyBeanMethods = false)
    @Profile("local")
    @ConditionalOnProperty(name = "relay.liquibase.auto-recovery-enabled", havingValue = "true")
    @EnableConfigurationProperties(LiquibaseProperties.class)
    static class Config {

        // Defining this bean makes Boot's LiquibaseAutoConfiguration back off (@ConditionalOnMissingBean).
        @Bean
        @ConditionalOnProperty(name = "spring.liquibase.enabled", matchIfMissing = true) // honour =false like Boot
        LocalLiquibaseRecovery liquibase(DataSource dataSource, LiquibaseProperties p) {
            LocalLiquibaseRecovery r = new LocalLiquibaseRecovery();
            r.setDataSource(dataSource);
            r.setChangeLog(p.getChangeLog());
            r.setContexts(StringUtils.collectionToCommaDelimitedString(p.getContexts()));
            r.setLabelFilter(StringUtils.collectionToCommaDelimitedString(p.getLabelFilter()));
            r.setDefaultSchema(p.getDefaultSchema());
            return r;
        }
    }

    @Override
    public void afterPropertiesSet() throws LiquibaseException {
        requireLocalHost(); // before any connection is opened
        try {
            Database db = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(dataSource.getConnection()));
            try {
                if (defaultSchema != null) db.setDefaultSchemaName(defaultSchema);
                // Scope is thread-bound; wait time is in minutes, poll rate in seconds.
                Scope.child(Map.<String, Object>of(
                        Scope.Attr.resourceAccessor.name(), new SpringResourceAccessor(resourceLoader),
                        GlobalConfiguration.CHANGELOGLOCK_WAIT_TIME.getKey(), 1L,
                        GlobalConfiguration.CHANGELOGLOCK_POLL_RATE.getKey(), 1L), () -> recoverOrUpdate(db));
            } finally {
                db.close();
            }
        } catch (LiquibaseException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Local Liquibase recovery failed: " + e.getMessage(), e);
        }
    }

    private void requireLocalHost() {
        HikariDataSource ds;
        try {
            ds = dataSource.unwrap(HikariDataSource.class);
        } catch (SQLException e) {
            throw new IllegalStateException("Local Liquibase recovery refused: cannot determine the JDBC host", e);
        }
        // Fail closed: only a plain jdbcUrl may define the target; nothing else may redirect it.
        if (ds.getDataSourceClassName() != null || ds.getDataSource() != null) {
            throw refused("a dataSourceClassName/dataSource is configured instead of a plain jdbcUrl");
        }
        if (ds.getDriverClassName() != null && !"org.postgresql.Driver".equals(ds.getDriverClassName())) {
            throw refused("unexpected driverClassName");
        }
        for (Object key : ds.getDataSourceProperties().keySet()) {
            if (isTargetKey(String.valueOf(key))) throw refused("data-source property '" + key + "' could redirect the connection");
        }
        if (hostOf(ds.getJdbcUrl()) == null) {
            throw refused("the JDBC URL must be jdbc:postgresql://<localhost|127.0.0.1|[::1]>[:port]/<db> with no "
                    + "host/port/socket-affecting query parameters");
        }
    }

    private static IllegalStateException refused(String why) {
        return new IllegalStateException("Local Liquibase recovery refused: " + why);
    }

    /** Names (case-insensitive) that can change where the driver connects. Deliberately broad: fail closed. */
    private static boolean isTargetKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return TARGET_KEY.matcher(k).find() || k.contains("%");
    }

    /**
     * The host of a strictly formed local URL, else null. Own strict parse, no pgjdbc: accepts only
     * jdbc:postgresql://{localhost|127.0.0.1|[::1]}[:port]/db[?params] without target-affecting params.
     */
    public static String hostOf(String jdbcUrl) {
        Matcher m = jdbcUrl == null ? null : LOCAL_URL.matcher(jdbcUrl);
        if (m == null || !m.matches()) return null;
        if (m.group(2) != null) {
            for (String param : m.group(2).split("&", -1)) {
                int eq = param.indexOf('=');
                if (isTargetKey(eq < 0 ? param : param.substring(0, eq))) return null;
            }
        }
        return m.group(1).toLowerCase(Locale.ROOT);
    }

    private Liquibase liquibase(Database db) {
        return new Liquibase(changeLog, Scope.getCurrentScope().getResourceAccessor(), db);
    }

    private void recoverOrUpdate(Database db) throws Exception {
        if (plan(liquibase(db), db).isEmpty()) {
            liquibase(db).update(new Contexts(contexts), new LabelExpression(labelFilter));
            return;
        }
        db.commit();
        LockService lock = LockServiceFactory.getInstance().getLockService(db);
        lock.waitForLock();
        try {
            recoverUnderLock(db);
        } catch (Throwable t) { // a failing release must not mask the original failure
            try {
                releaseLock(lock);
            } catch (Exception releaseFailure) {
                t.addSuppressed(releaseFailure);
            }
            throw t;
        }
        releaseLock(lock);
    }

    protected void releaseLock(LockService lock) throws Exception {
        lock.releaseLock();
    }

    private void recoverUnderLock(Database db) throws Exception {
        for (int attempt = 1;; attempt++) {
            Liquibase lb = liquibase(db); // fresh parse every attempt
            Map<RanChangeSet, ChangeSet> plan = plan(lb, db); // re-detect: another instance may have recovered
            db.commit(); // release read locks; the rollback runs on another connection
            if (plan.isEmpty()) {
                if (attempt == 1) reapply(lb, db);
                lb.validate();
                return;
            }
            if (attempt > MAX_ATTEMPTS) {
                throw new IllegalStateException(
                        "Checksum mismatch persists after " + MAX_ATTEMPTS + " recovery attempts: " + ids(plan));
            }
            preflight(plan, db);
            rollback(plan, db);
            reapply(lb, db);
        }
    }

    /** Changed changesets plus every later-executed one, newest first. Empty when no checksum differs. */
    private Map<RanChangeSet, ChangeSet> plan(Liquibase lb, Database db) throws Exception {
        refreshHistory(db);
        List<RanChangeSet> ran = new ArrayList<>(db.getRanChangeSetList());
        ran.sort(Comparator.comparing(RanChangeSet::getOrderExecuted));
        List<ChangeSet> current = lb.getDatabaseChangeLog().getChangeSets();
        Map<RanChangeSet, ChangeSet> matched = new LinkedHashMap<>();
        List<String> orphans = new ArrayList<>();
        int firstChanged = -1;
        for (RanChangeSet r : ran) {
            ChangeSet cs = current.stream().filter(r::isSameAs).findFirst().orElse(null);
            if (cs == null) {
                orphans.add(r.getId() + "::" + r.getAuthor() + "::" + r.getStoredChangeLog());
                continue;
            }
            // runOnChange/runAlways changesets are re-run by Liquibase's own update, not a mismatch
            if (firstChanged < 0 && !cs.shouldRunOnChange() && !cs.shouldAlwaysRun()
                    && !cs.isCheckSumValid(r.getLastCheckSum())) firstChanged = matched.size();
            matched.put(r, cs);
        }
        if (firstChanged < 0) return Map.of();
        if (!orphans.isEmpty()) {
            throw new IllegalStateException("Cannot auto-recover: DATABASECHANGELOG has records missing from the "
                    + "changelog " + orphans + ". Reset the local database manually.");
        }
        List<Map.Entry<RanChangeSet, ChangeSet>> tail =
                new ArrayList<>(matched.entrySet()).subList(firstChanged, matched.size());
        Collections.reverse(tail);
        Map<RanChangeSet, ChangeSet> result = new LinkedHashMap<>();
        tail.forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private void preflight(Map<RanChangeSet, ChangeSet> plan, Database db) throws Exception {
        // Never roll back what reapply() would skip: same filters as reapply().
        Map<String, ChangeSetFilter> filters = new LinkedHashMap<>();
        filters.put("context", new ContextChangeSetFilter(new Contexts(contexts)));
        filters.put("label", new LabelChangeSetFilter(new LabelExpression(labelFilter)));
        filters.put("dbms", new DbmsChangeSetFilter(db));
        for (ChangeSet cs : plan.values()) {
            for (Map.Entry<String, ChangeSetFilter> f : filters.entrySet()) {
                if (!f.getValue().accepts(cs).isAccepted()) {
                    throw new IllegalStateException("Cannot auto-recover: changeset " + cs.toString(false)
                            + " would be excluded on reapply by the " + f.getKey() + " filter");
                }
            }
            if (cs.getRollback().getChanges().isEmpty()) {
                throw new IllegalStateException("Cannot auto-recover: changeset " + cs.toString(false) + " has no rollback block");
            }
            if (!cs.isRunInTransaction()) {
                throw new IllegalStateException("Cannot auto-recover: changeset " + cs.toString(false) + " has runInTransaction=false");
            }
            List<String> all = sql(cs.getChanges(), db);
            all.addAll(sql(cs.getRollback().getChanges(), db));
            for (String s : all) {
                if (NON_TRANSACTIONAL.matcher(s).find()) {
                    throw new IllegalStateException("Cannot auto-recover: changeset " + cs.toString(false)
                            + " contains a non-transactional statement: " + s);
                }
            }
        }
        log.info("Local Liquibase recovery: rolling back (in this order) then reapplying " + ids(plan));
    }

    private void rollback(Map<RanChangeSet, ChangeSet> plan, Database db) throws Exception {
        String table = db.escapeTableName(
                db.getLiquibaseCatalogName(), db.getLiquibaseSchemaName(), db.getDatabaseChangeLogTableName());
        String step = "";
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (Map.Entry<RanChangeSet, ChangeSet> e : plan.entrySet()) {
                    RanChangeSet ran = e.getKey();
                    for (String sql : sql(e.getValue().getRollback().getChanges(), db)) {
                        step = ran.getId() + ", rollback statement: " + sql;
                        try (Statement s = c.createStatement()) {
                            s.execute(sql);
                        }
                    }
                    step = ran.getId() + ", deleting its DATABASECHANGELOG row";
                    try (PreparedStatement ps = c.prepareStatement(
                            "DELETE FROM " + table + " WHERE ID = ? AND AUTHOR = ? AND FILENAME = ?")) {
                        ps.setString(1, ran.getId());
                        ps.setString(2, ran.getAuthor());
                        ps.setString(3, ran.getStoredChangeLog());
                        if (ps.executeUpdate() != 1) throw new SQLException("expected exactly one history row");
                    }
                }
                c.commit(); // once, for the whole set
            } catch (Exception ex) {
                c.rollback();
                throw new IllegalStateException("Local Liquibase recovery failed at changeset " + step
                        + "; the rollback was reverted, schema and history are unchanged", ex);
            }
        }
    }

    // Lower-level than Liquibase.update(), which would release our lock in its finally block.
    protected void reapply(Liquibase lb, Database db) throws Exception {
        refreshHistory(db); // the rollback ran on another connection
        Contexts ctx = new Contexts(contexts);
        LabelExpression labels = new LabelExpression(labelFilter);
        new ChangeLogIterator(lb.getDatabaseChangeLog(), new ShouldRunChangeSetFilter(db),
                new ContextChangeSetFilter(ctx), new LabelChangeSetFilter(labels), new DbmsChangeSetFilter(db))
                .run(new UpdateVisitor(db, null), new RuntimeEnvironment(db, ctx, labels));
    }

    private static void refreshHistory(Database db) throws LiquibaseException {
        ChangeLogHistoryServiceFactory.getInstance().getChangeLogService(db).reset();
    }

    private static List<String> sql(List<Change> changes, Database db) throws Exception {
        List<String> out = new ArrayList<>();
        for (Change change : changes) {
            for (SqlStatement st : change.generateStatements(db)) {
                for (Sql s : SqlGeneratorFactory.getInstance().generateSql(st, db)) out.add(s.toSql());
            }
        }
        return out;
    }

    private static List<String> ids(Map<RanChangeSet, ChangeSet> plan) {
        return plan.keySet().stream().map(r -> r.getId() + "::" + r.getAuthor()).toList();
    }
}
