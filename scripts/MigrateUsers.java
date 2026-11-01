import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/** Run with administrator DB_* credentials, after backup and while writers are stopped. */
class MigrateUsers {
    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    public static void main(String[] args) throws Exception {
        String[] names = {"alice", "bob", "agent1", "agent2"};
        long[] ids = {1001, 1002, 2001, 2002};
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        try (Connection db = DriverManager.getConnection(required("DB_URL"), required("DB_USER"), required("DB_PASSWORD"))) {
            String catalog = db.getCatalog();
            if (catalog == null || catalog.isBlank()) throw new IllegalArgumentException("Select a database in DB_URL");
            // Reject unknown references before any DDL; never silently rewrite existing data.
            try (var statement = db.createStatement(); var rows = statement.executeQuery(
                    "SELECT creator_id FROM tickets UNION SELECT assignee_id FROM tickets WHERE assignee_id IS NOT NULL UNION SELECT actor_id FROM ticket_events")) {
                while (rows.next()) {
                    long id = rows.getLong(1);
                    if (Arrays.stream(ids).noneMatch(expected -> expected == id))
                        throw new IllegalStateException("Unmapped existing user id: " + id);
                }
            }
            String[] hashes = new String[4];
            for (int i = 0; i < 4; i++) hashes[i] = encoder.encode(required("LAB_" + names[i].toUpperCase(Locale.ROOT) + "_PASSWORD"));
            try (var statement = db.createStatement()) {
                statement.execute(Files.readString(Path.of("sql/02-users.sql")));
            }
            db.setAutoCommit(false);
            try {
                for (int i = 0; i < 4; i++) {
                    try (var query = db.prepareStatement("SELECT id,username,role FROM users WHERE id=? OR username=?")) {
                        query.setLong(1, ids[i]); query.setString(2, names[i]);
                        try (var rows = query.executeQuery()) {
                            if (rows.next()) {
                                if (rows.getLong(1) != ids[i] || !names[i].equals(rows.getString(2))
                                        || !(i < 2 ? "USER" : "AGENT").equals(rows.getString(3)) || rows.next())
                                    throw new IllegalStateException("Existing account mapping conflicts: " + names[i]);
                                continue; // Reruns must not reset passwords or re-enable accounts.
                            }
                        }
                    }
                    try (var insert = db.prepareStatement("INSERT INTO users(id,username,password_hash,role) VALUES (?,?,?,?)")) {
                        insert.setLong(1, ids[i]); insert.setString(2, names[i]);
                        insert.setString(3, hashes[i]); insert.setString(4, i < 2 ? "USER" : "AGENT");
                        insert.executeUpdate();
                    }
                }
                db.commit();
            } catch (Exception failure) {
                db.rollback(); throw failure;
            }
            db.setAutoCommit(true);
            String[][] constraints = {{"tickets", "creator_id", "fk_ticket_creator"},
                    {"tickets", "assignee_id", "fk_ticket_assignee"}, {"ticket_events", "actor_id", "fk_event_actor"}};
            for (String[] key : constraints) {
                boolean present = false;
                try (var rows = db.getMetaData().getImportedKeys(catalog, null, key[0])) {
                    while (rows.next()) {
                        if (key[1].equals(rows.getString("FKCOLUMN_NAME"))) {
                            if (!"users".equals(rows.getString("PKTABLE_NAME")) || !"id".equals(rows.getString("PKCOLUMN_NAME")))
                                throw new IllegalStateException("Conflicting foreign key: " + key[1]);
                            present = true;
                        }
                    }
                }
                if (!present) try (var statement = db.createStatement()) {
                    statement.execute("ALTER TABLE " + key[0] + " ADD CONSTRAINT " + key[2]
                            + " FOREIGN KEY (" + key[1] + ") REFERENCES users(id)");
                }
            }
            System.out.println("User migration complete for " + catalog + "; existing passwords and enabled flags preserved.");
        }
    }
}
