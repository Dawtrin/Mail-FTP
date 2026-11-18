package vn.edu.vku.mailserver.server.database;

/**
 * Cấu hình kết nối cơ sở dữ liệu MySQL.
 * Các giá trị có thể ghi đè qua System Properties hoặc biến môi trường (Environment Variables).
 */
public class DatabaseConfig {

    private String host;
    private int port;
    private String database;
    private String username;
    private String password;

    public DatabaseConfig() {
        this.host = getEnvOrProp("DB_HOST", "db.host", "localhost");
        this.port = Integer.parseInt(getEnvOrProp("DB_PORT", "db.port", "3306"));
        this.database = getEnvOrProp("DB_NAME", "db.name", "vku_mail_server");
        this.username = getEnvOrProp("DB_USER", "db.user", "root");
        this.password = getEnvOrProp("DB_PASS", "db.password", "");
    }

    public DatabaseConfig(String host, int port, String database, String username, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    public String getJdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&createDatabaseIfNotExist=true&characterEncoding=UTF-8";
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDatabase() {
        return database;
    }

    private static String getEnvOrProp(String envKey, String propKey, String defaultValue) {
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) return env.trim();
        String prop = System.getProperty(propKey);
        if (prop != null && !prop.isBlank()) return prop.trim();
        return defaultValue;
    }
}
