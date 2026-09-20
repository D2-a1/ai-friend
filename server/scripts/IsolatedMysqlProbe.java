import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Explicit read-only remote test database preflight; password arrives on stdin, never arguments. */
class IsolatedMysqlProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].matches("[a-zA-Z0-9.-]+")
                || !args[1].matches("[a-zA-Z0-9_]+") || !args[2].matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Expected host database username");
        }
        System.out.println("WAITING_FOR_STDIN_SECRET");
        var console = System.console();
        char[] hidden = console == null ? null : console.readPassword("Test database password: ");
        var secret = console == null ? new BufferedReader(new InputStreamReader(System.in)).readLine()
                : hidden == null ? null : new String(hidden);
        if (hidden != null) java.util.Arrays.fill(hidden, '\0');
        if (secret == null || secret.isEmpty()) throw new IllegalArgumentException("Missing secret");
        var properties = new Properties();
        properties.setProperty("user", args[2]);
        properties.setProperty("password", secret);
        properties.setProperty("sslMode", "REQUIRED");
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "10000");
        try (var connection = DriverManager.getConnection(
                "jdbc:mysql://" + args[0] + ":3306/" + args[1], properties)) {
            properties.remove("password");
            secret = null;
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery("SELECT VERSION(), DATABASE()")) {
                    if (!rows.next() || !args[1].equals(rows.getString(2))) throw new IllegalStateException("Wrong database");
                    System.out.println("VERSION=" + rows.getString(1));
                    System.out.println("DATABASE_MATCH=true");
                }
                try (var rows = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
                    if (!rows.next() || rows.getString(2).isBlank()) throw new IllegalStateException("TLS missing");
                    System.out.println("TLS_ACTIVE=true");
                }
                try (var rows = statement.executeQuery(
                        "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME")) {
                    int count = 0;
                    while (rows.next()) { count++; System.out.println("TABLE=" + rows.getString(1)); }
                    System.out.println("TABLE_COUNT=" + count);
                }
            }
        } catch (SQLException failure) {
            System.out.println("SQL_STATE=" + failure.getSQLState());
            System.out.println("SQL_ERROR_CODE=" + failure.getErrorCode());
            Throwable cause = failure.getCause();
            for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
                System.out.println("CAUSE_TYPE=" + cause.getClass().getSimpleName());
            }
            System.exit(2);
        } finally { properties.remove("password"); }
    }
}
