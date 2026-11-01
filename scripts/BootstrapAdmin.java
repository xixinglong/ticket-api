import java.nio.charset.StandardCharsets;
import java.sql.*;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/** Run after migration 04 with maintenance DB credentials. Never prints secrets. */
class BootstrapAdmin {
    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    public static void main(String[] args) throws Exception {
        String username = required("ADMIN_USERNAME");
        String password = required("ADMIN_PASSWORD");
        if (!username.matches("[A-Za-z0-9][A-Za-z0-9_.-]{2,63}") || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Invalid admin username or password length");
        try (var db = DriverManager.getConnection(required("DB_URL"), required("DB_USER"), required("DB_PASSWORD"))) {
            try (var query = db.prepareStatement("SELECT role,enabled FROM users WHERE username=?")) {
                query.setString(1, username);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        if (!"ADMIN".equals(rows.getString(1)) || !rows.getBoolean(2))
                            throw new IllegalStateException("Existing username is not an enabled administrator");
                        System.out.println("Administrator already exists; credentials unchanged.");
                        return;
                    }
                }
            }
            try (var insert = db.prepareStatement("INSERT INTO users(username,password_hash,role) VALUES (?,?,'ADMIN')")) {
                insert.setString(1, username);
                insert.setString(2, PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(password));
                insert.executeUpdate();
            }
        }
        System.out.println("Administrator created.");
    }
}
