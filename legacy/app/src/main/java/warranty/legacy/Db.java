package warranty.legacy;

import java.sql.Connection;
import java.sql.SQLException;
import oracle.jdbc.pool.OracleDataSource;

final class Db {
    private Db() {}

    static Connection open() throws SQLException {
        // DriverManager does not see a driver that is packed inside the WAR.
        OracleDataSource ds = new OracleDataSource();
        ds.setURL(env("LEGACY_DB_URL", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1"));
        ds.setUser(env("LEGACY_DB_USER", "claims"));
        ds.setPassword(env("LEGACY_DB_PASSWORD", "claims"));
        Connection c = ds.getConnection();
        c.setAutoCommit(false);
        return c;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null ? fallback : value;
    }
}
