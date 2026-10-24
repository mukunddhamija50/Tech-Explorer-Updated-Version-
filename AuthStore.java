import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class AuthStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final long OTP_LIFETIME_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final long SESSION_LIFETIME_MILLIS = Duration.ofDays(30).toMillis();
    private static final String EMAIL_PATTERN = "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$";
    private static String jdbcUrl;
    private static byte[] secret;

    private AuthStore() {}

    static synchronized void initialize() throws IOException {
        try {
            Class.forName("org.sqlite.JDBC");
            Path database = Path.of(System.getenv().getOrDefault(
                    "SKILLMATCH_DB", Path.of("data", "skillmatch.db").toAbsolutePath().toString()))
                    .toAbsolutePath().normalize();
            Files.createDirectories(database.getParent());
            jdbcUrl = "jdbc:sqlite:" + database;
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS users (
                          id INTEGER PRIMARY KEY,
                          identifier TEXT NOT NULL UNIQUE,
                          email TEXT UNIQUE,
                          phone TEXT UNIQUE,
                          display_name TEXT NOT NULL,
                          password_salt TEXT,
                          password_hash TEXT,
                          google_sub TEXT UNIQUE,
                          created_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS otp_challenges (
                          identifier TEXT PRIMARY KEY,
                          code_hash TEXT NOT NULL,
                          expires_at INTEGER NOT NULL,
                          sent_at INTEGER NOT NULL,
                          attempts INTEGER NOT NULL DEFAULT 0,
                          verified_at INTEGER,
                          verification_hash TEXT
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS sessions (
                          token_hash TEXT PRIMARY KEY,
                          user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                          expires_at INTEGER NOT NULL,
                          created_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS user_progress (
                          user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                          last_section TEXT NOT NULL,
                          state_json TEXT NOT NULL,
                          updated_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS user_history (
                          id INTEGER PRIMARY KEY,
                          user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                          event_type TEXT NOT NULL,
                          title TEXT NOT NULL,
                          details_json TEXT NOT NULL,
                          created_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS user_history_owner_time ON user_history(user_id, created_at DESC)");
                statement.execute("CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at)");
            }
            String configuredSecret = System.getenv("SKILLMATCH_AUTH_SECRET");
            if (configuredSecret != null && configuredSecret.length() >= 32) {
                secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
            } else if (configuredSecret != null) {
                throw new IOException("SKILLMATCH_AUTH_SECRET must contain at least 32 characters.");
            } else {
                Path keyFile = database.getParent().resolve("auth.secret");
                if (!Files.exists(keyFile)) {
                    byte[] generated = new byte[48];
                    RANDOM.nextBytes(generated);
                    Files.writeString(keyFile, Base64.getEncoder().encodeToString(generated),
                            StandardCharsets.US_ASCII);
                }
                secret = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
            }
        } catch (ClassNotFoundException | SQLException | IllegalArgumentException ex) {
            throw new IOException("Could not initialize SQLite authentication storage: " + ex.getMessage(), ex);
        }
    }

    static Map<String, Object> config() {
        return Map.of("google_client_id", env("GOOGLE_CLIENT_ID"));
    }

    static Map<String, Object> requestOtp(Map<String, Object> input) {
        String identifier = normalizeIdentifier(text(input.get("identifier")));
        String purpose = text(input.get("purpose"));
        if (!purpose.equals("signup")) throw new AuthException(400, "Unsupported OTP purpose.");
        long now = System.currentTimeMillis();
        try (Connection connection = connect()) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT sent_at FROM otp_challenges WHERE identifier=?")) {
                query.setString(1, identifier);
                try (ResultSet row = query.executeQuery()) {
                    if (row.next() && now - row.getLong(1) < Duration.ofSeconds(60).toMillis())
                        throw new AuthException(429, "Please wait one minute before requesting another code.");
                }
            }
            try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM users WHERE identifier=?")) {
                query.setString(1, identifier);
                try (ResultSet row = query.executeQuery()) {
                    if (row.next()) throw new AuthException(409, "An account already exists. Please log in instead.");
                }
            }
            String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
            boolean localDevelopmentCode = false;
            try {
                deliverOtp(identifier, code);
            } catch (AuthException ex) {
                if (ex.status != 503 || !Boolean.parseBoolean(env("SKILLMATCH_DEV_OTP"))) throw ex;
                localDevelopmentCode = true;
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO otp_challenges(identifier, code_hash, expires_at, sent_at, attempts, verified_at, verification_hash)
                    VALUES(?, ?, ?, ?, 0, NULL, NULL)
                    ON CONFLICT(identifier) DO UPDATE SET
                      code_hash=excluded.code_hash, expires_at=excluded.expires_at, sent_at=excluded.sent_at,
                      attempts=0, verified_at=NULL, verification_hash=NULL
                    """)) {
                insert.setString(1, identifier);
                insert.setString(2, hmac(identifier + ":" + code));
                insert.setLong(3, now + OTP_LIFETIME_MILLIS);
                insert.setLong(4, now);
                insert.executeUpdate();
            }
            if (localDevelopmentCode) {
                return Map.of("message", "Email/SMS delivery is not configured. For local testing only, use the code below. It expires in 10 minutes.",
                        "development_code", code);
            }
            return Map.of("message", "A verification code was sent to your "
                    + (identifier.startsWith("phone:") ? "phone" : "email") + ".");
        } catch (SQLException ex) {
            throw storageError(ex);
        }
    }

    static Map<String, Object> verifyOtp(Map<String, Object> input) {
        String identifier = normalizeIdentifier(text(input.get("identifier")));
        String code = text(input.get("code")).trim();
        if (!code.matches("\\d{6}")) throw new AuthException(400, "Enter the six-digit verification code.");
        long now = System.currentTimeMillis();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT code_hash, expires_at, attempts FROM otp_challenges WHERE identifier=?
                    """)) {
                query.setString(1, identifier);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) throw new AuthException(400, "Request a new verification code first.");
                    if (row.getLong("expires_at") < now) throw new AuthException(400, "The verification code expired. Request a new one.");
                    if (row.getInt("attempts") >= 5) throw new AuthException(429, "Too many incorrect codes. Request a new one.");
                    if (!constantTimeEquals(row.getString("code_hash"), hmac(identifier + ":" + code))) {
                        try (PreparedStatement update = connection.prepareStatement(
                                "UPDATE otp_challenges SET attempts=attempts+1 WHERE identifier=?")) {
                            update.setString(1, identifier);
                            update.executeUpdate();
                        }
                        connection.commit();
                        throw new AuthException(400, "That verification code is incorrect.");
                    }
                }
            }
            String verificationToken = randomToken();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE otp_challenges SET verified_at=?, verification_hash=? WHERE identifier=?
                    """)) {
                update.setLong(1, now);
                update.setString(2, sha256(verificationToken));
                update.setString(3, identifier);
                update.executeUpdate();
            }
            connection.commit();
            return Map.of("verified", true, "verification_token", verificationToken);
        } catch (SQLException ex) {
            throw storageError(ex);
        }
    }

    static Map<String, Object> completeSignup(Map<String, Object> input) {
        String identifier = normalizeIdentifier(text(input.get("identifier")));
        String verificationToken = text(input.get("verification_token"));
        String password = text(input.get("password"));
        String name = text(input.get("name")).trim();
        if (password.length() < 10 || password.length() > 256)
            throw new AuthException(400, "Password must be between 10 and 256 characters.");
        if (name.isEmpty() || name.length() > 120) throw new AuthException(400, "Enter your name.");
        long now = System.currentTimeMillis();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT verification_hash, verified_at, expires_at FROM otp_challenges WHERE identifier=?
                    """)) {
                query.setString(1, identifier);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next() || row.getObject("verified_at") == null
                            || row.getLong("expires_at") < now
                            || !constantTimeEquals(row.getString("verification_hash"), sha256(verificationToken)))
                        throw new AuthException(400, "Verify your email or phone number before creating an account.");
                }
            }
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            String email = identifier.startsWith("email:") ? identifier.substring(6) : null;
            String phone = identifier.startsWith("phone:") ? identifier.substring(6) : null;
            long userId;
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO users(identifier,email,phone,display_name,password_salt,password_hash,created_at)
                    VALUES(?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, identifier);
                insert.setString(2, email);
                insert.setString(3, phone);
                insert.setString(4, name);
                insert.setString(5, Base64.getEncoder().encodeToString(salt));
                insert.setString(6, passwordHash(password, salt));
                insert.setLong(7, now);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("SQLite did not return the new account ID.");
                    userId = keys.getLong(1);
                }
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM otp_challenges WHERE identifier=?")) {
                delete.setString(1, identifier);
                delete.executeUpdate();
            }
            String token = createSession(connection, userId, now);
            connection.commit();
            return sessionResponse(userId, token);
        } catch (SQLException ex) {
            if (isUniqueViolation(ex)) throw new AuthException(409, "An account already exists for this email or phone.");
            throw storageError(ex);
        }
    }

    static Map<String, Object> login(Map<String, Object> input) {
        String identifier = normalizeIdentifier(text(input.get("identifier")));
        String password = text(input.get("password"));
        long now = System.currentTimeMillis();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement("""
                SELECT id, password_salt, password_hash FROM users WHERE identifier=?
                """)) {
            query.setString(1, identifier);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || row.getString("password_salt") == null
                        || !constantTimeEquals(row.getString("password_hash"),
                        passwordHash(password, Base64.getDecoder().decode(row.getString("password_salt")))))
                    throw new AuthException(401, "Email/phone or password is incorrect.");
                long userId = row.getLong("id");
                String token = createSession(connection, userId, now);
                return sessionResponse(userId, token);
            }
        } catch (SQLException ex) {
            throw storageError(ex);
        }
    }

    static Map<String, Object> loginWithGoogle(Map<String, Object> input) {
        String clientId = env("GOOGLE_CLIENT_ID");
        if (clientId.isEmpty()) throw new AuthException(503, "Google sign-in is not configured yet.");
        String idToken = text(input.get("credential"));
        if (idToken.isBlank()) throw new AuthException(400, "Google did not return a sign-in token.");
        try {
            URI uri = URI.create("https://oauth2.googleapis.com/tokeninfo?id_token="
                    + URLEncoder.encode(idToken, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response = index.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new AuthException(401, "Google sign-in token is invalid or expired.");
            Map<String, Object> claims = index.object(index.Json.parse(response.body()));
            if (!clientId.equals(text(claims.get("aud")))
                    || !text(claims.get("email_verified")).equalsIgnoreCase("true"))
                throw new AuthException(401, "Google account could not be verified for this application.");
            String subject = text(claims.get("sub"));
            String email = normalizeIdentifier(text(claims.get("email")));
            String displayName = text(claims.get("name")).trim();
            if (subject.isBlank()) throw new AuthException(401, "Google account did not provide a verified identity.");
            long now = System.currentTimeMillis();
            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                long userId;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT id FROM users WHERE google_sub=? OR identifier=?")) {
                    query.setString(1, subject);
                    query.setString(2, email);
                    try (ResultSet row = query.executeQuery()) {
                        if (row.next()) {
                            userId = row.getLong("id");
                            try (PreparedStatement update = connection.prepareStatement(
                                    "UPDATE users SET google_sub=?, email=?, display_name=? WHERE id=?")) {
                                update.setString(1, subject);
                                update.setString(2, email.substring(6));
                                update.setString(3, displayName.isEmpty() ? email.substring(6) : displayName);
                                update.setLong(4, userId);
                                update.executeUpdate();
                            }
                        } else {
                            try (PreparedStatement insert = connection.prepareStatement("""
                                    INSERT INTO users(identifier,email,display_name,google_sub,created_at)
                                    VALUES(?,?,?,?,?)
                                    """, Statement.RETURN_GENERATED_KEYS)) {
                                insert.setString(1, email);
                                insert.setString(2, email.substring(6));
                                insert.setString(3, displayName.isEmpty() ? email.substring(6) : displayName);
                                insert.setString(4, subject);
                                insert.setLong(5, now);
                                insert.executeUpdate();
                                try (ResultSet keys = insert.getGeneratedKeys()) {
                                    if (!keys.next()) throw new SQLException("SQLite did not return the Google account ID.");
                                    userId = keys.getLong(1);
                                }
                            }
                        }
                    }
                }
                String token = createSession(connection, userId, now);
                connection.commit();
                return sessionResponse(userId, token);
            }
        } catch (IOException ex) {
            throw new AuthException(502, "Could not verify your Google sign-in. Try again.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AuthException(502, "Google sign-in was interrupted. Try again.");
        } catch (SQLException ex) {
            if (isUniqueViolation(ex)) throw new AuthException(409, "This Google account is linked to another user.");
            throw storageError(ex);
        }
    }

    static long authenticatedUser(String authorization) {
        return authenticatedUser(authorization, "");
    }

    static long authenticatedUser(String authorization, String cookieHeader) {
        String token = bearerToken(authorization);
        if (token.isEmpty()) token = cookieToken(cookieHeader);
        if (token.isEmpty())
            throw new AuthException(401, "Please log in to use Skill Match.");
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement("""
                SELECT user_id FROM sessions WHERE token_hash=? AND expires_at>?
                """)) {
            query.setString(1, sha256(token));
            query.setLong(2, System.currentTimeMillis());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new AuthException(401, "Your session expired. Please log in again.");
                return row.getLong(1);
            }
        } catch (SQLException ex) {
            throw storageError(ex);
        }
    }

    static void logout(String authorization) {
        logout(authorization, "");
    }

    static void logout(String authorization, String cookieHeader) {
        String token = bearerToken(authorization);
        if (token.isEmpty()) token = cookieToken(cookieHeader);
        if (token.isEmpty()) return;
        try (Connection connection = connect(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM sessions WHERE token_hash=?")) {
            delete.setString(1, sha256(token));
            delete.executeUpdate();
        } catch (SQLException ex) {
            throw storageError(ex);
        }
    }

    static long sessionCookieMaxAgeSeconds() {
        return SESSION_LIFETIME_MILLIS / 1000;
    }

    static Map<String, Object> state(long userId) {
        try (Connection connection = connect()) {
            Map<String, Object> user;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT display_name,email,phone,created_at FROM users WHERE id=?")) {
                query.setLong(1, userId);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) throw new AuthException(401, "Account no longer exists.");
                    user = new LinkedHashMap<>();
                    user.put("name", row.getString("display_name"));
                    user.put("email", row.getString("email"));
                    user.put("phone", row.getString("phone"));
                    user.put("created_at", row.getLong("created_at"));
                }
            }
            Map<String, Object> progress = Map.of("last_section", "home", "state", Map.of(), "updated_at", 0);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT last_section,state_json,updated_at FROM user_progress WHERE user_id=?")) {
                query.setLong(1, userId);
                try (ResultSet row = query.executeQuery()) {
                    if (row.next()) progress = Map.of(
                            "last_section", row.getString("last_section"),
                            "state", index.object(index.Json.parse(row.getString("state_json"))),
                            "updated_at", row.getLong("updated_at"));
                }
            }
            List<Map<String, Object>> history = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT id,event_type,title,details_json,created_at
                    FROM user_history WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 50
                    """)) {
                query.setLong(1, userId);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) history.add(Map.of(
                            "id", rows.getLong("id"),
                            "type", rows.getString("event_type"),
                            "title", rows.getString("title"),
                            "details", index.Json.parse(rows.getString("details_json")),
                            "created_at", rows.getLong("created_at")));
                }
            }
            return Map.of("user", user, "progress", progress, "history", history);
        } catch (SQLException ex) {
            throw storageError(ex);
        }
    }

    static void saveProgress(long userId, Map<String, Object> input) {
        String section = text(input.get("section"));
        if (!List.of("home", "maker", "scan", "jobs", "match", "tests").contains(section))
            throw new AuthException(400, "Unsupported Skill Match location.");
        Map<String, Object> state = index.object(input.get("state"));
        if (state.size() > 30) throw new AuthException(400, "Saved progress is too large.");
        String json = index.Json.stringify(state);
        if (json.length() > 20_000) throw new AuthException(400, "Saved progress is too large.");
        try (Connection connection = connect(); PreparedStatement save = connection.prepareStatement("""
                INSERT INTO user_progress(user_id,last_section,state_json,updated_at) VALUES(?,?,?,?)
                ON CONFLICT(user_id) DO UPDATE SET
                  last_section=excluded.last_section,state_json=excluded.state_json,updated_at=excluded.updated_at
                """)) {
            save.setLong(1, userId);
            save.setString(2, section);
            save.setString(3, json);
            save.setLong(4, System.currentTimeMillis());
            save.executeUpdate();
        } catch (SQLException ex) {
            throw storageError(ex);
        }
    }

    static void addHistory(long userId, Map<String, Object> input) {
        String type = text(input.get("type")).trim();
        String title = text(input.get("title")).trim();
        if (!List.of("resume", "assessment", "scan", "company_match", "roadmap").contains(type))
            throw new AuthException(400, "Unsupported history item.");
        if (title.isEmpty() || title.length() > 160) throw new AuthException(400, "History title is required.");
        Object details = input.getOrDefault("details", Map.of());
        String json = index.Json.stringify(details);
        if (json.length() > 500_000) throw new AuthException(400, "History item is too large.");
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO user_history(user_id,event_type,title,details_json,created_at) VALUES(?,?,?,?,?)
                """)) {
            insert.setLong(1, userId);
            insert.setString(2, type);
            insert.setString(3, title);
            insert.setString(4, json);
            insert.setLong(5, System.currentTimeMillis());
            insert.executeUpdate();
        } catch (SQLException ex) {
            throw storageError(ex);
        }
    }

    private static Map<String, Object> sessionResponse(long userId, String token) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("access_token", token);
        result.putAll(state(userId));
        return result;
    }

    private static String createSession(Connection connection, long userId, long now) throws SQLException {
        String token = randomToken();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO sessions(token_hash,user_id,expires_at,created_at) VALUES(?,?,?,?)")) {
            insert.setString(1, sha256(token));
            insert.setLong(2, userId);
            insert.setLong(3, now + SESSION_LIFETIME_MILLIS);
            insert.setLong(4, now);
            insert.executeUpdate();
        }
        return token;
    }

    private static String normalizeIdentifier(String value) {
        String identifier = value.trim();
        if (identifier.matches("(?i)" + EMAIL_PATTERN)) return "email:" + identifier.toLowerCase(Locale.ROOT);
        String phone = identifier.replaceAll("[\\s().-]", "");
        if (phone.matches("\\+?[1-9]\\d{7,14}")) {
            if (!phone.startsWith("+")) throw new AuthException(400, "Enter the phone number with its country code, such as +14155550123.");
            return "phone:" + phone;
        }
        throw new AuthException(400, "Enter a valid email address or phone number with country code.");
    }

    private static String deliverOtp(String identifier, String code) {
        String message = "Your Skill Match verification code is " + code + ". It expires in 10 minutes.";
        if (identifier.startsWith("phone:")) {
            sendSms(identifier.substring(6), message);
            return "phone";
        }
        sendEmail(identifier.substring(6), message);
        return "email";
    }

    private static void sendSms(String phone, String message) {
        String account = env("TWILIO_ACCOUNT_SID");
        String token = env("TWILIO_AUTH_TOKEN");
        String from = env("TWILIO_FROM_NUMBER");
        if (account.isEmpty() || token.isEmpty() || from.isEmpty())
            throw new AuthException(503, "SMS verification is not configured. Add TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, and TWILIO_FROM_NUMBER to the server environment.");
        try {
            String form = "To=" + formEncode(phone) + "&From=" + formEncode(from) + "&Body=" + formEncode(message);
            String credentials = Base64.getEncoder().encodeToString(
                    (account + ":" + token).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "https://api.twilio.com/2010-04-01/Accounts/" + account + "/Messages.json"))
                    .timeout(Duration.ofSeconds(12)).header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> response = index.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new AuthException(502, "The SMS provider could not deliver the code. Check your Twilio configuration and phone number.");
        } catch (IOException ex) {
            throw new AuthException(502, "Could not reach the SMS provider. Try again.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AuthException(502, "SMS delivery was interrupted. Try again.");
        }
    }

    private static void sendEmail(String address, String message) {
        String host = env("SMTP_HOST");
        String username = env("SMTP_USERNAME");
        String password = env("SMTP_PASSWORD");
        String from = env("SMTP_FROM");
        int port;
        try {
            port = Integer.parseInt(System.getenv().getOrDefault("SMTP_PORT", "587"));
        } catch (NumberFormatException ex) {
            throw new AuthException(503, "SMTP_PORT must be a valid port number.");
        }
        if (host.isEmpty() || username.isEmpty() || password.isEmpty() || from.isEmpty())
            throw new AuthException(503, "Email verification is not configured. Set SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD, and SMTP_FROM on the server.");
        try (Socket plainSocket = new Socket()) {
            plainSocket.connect(new InetSocketAddress(host, port), 8_000);
            plainSocket.setSoTimeout(10_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(plainSocket.getInputStream(), StandardCharsets.US_ASCII));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(plainSocket.getOutputStream(), StandardCharsets.US_ASCII));
            expectSmtp(reader, 220);
            smtpCommand(reader, writer, "EHLO skillmatch.local", 250);
            smtpCommand(reader, writer, "STARTTLS", 220);
            try (javax.net.ssl.SSLSocket tlsSocket = (javax.net.ssl.SSLSocket)
                    ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                            .createSocket(plainSocket, host, port, true)) {
                javax.net.ssl.SSLParameters parameters = tlsSocket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tlsSocket.setSSLParameters(parameters);
                tlsSocket.startHandshake();
                reader = new BufferedReader(new InputStreamReader(tlsSocket.getInputStream(), StandardCharsets.US_ASCII));
                writer = new BufferedWriter(new OutputStreamWriter(tlsSocket.getOutputStream(), StandardCharsets.US_ASCII));
                smtpCommand(reader, writer, "EHLO skillmatch.local", 250);
                smtpCommand(reader, writer, "AUTH LOGIN", 334);
                smtpCommand(reader, writer, Base64.getEncoder().encodeToString(username.getBytes(StandardCharsets.UTF_8)), 334);
                smtpCommand(reader, writer, Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)), 235);
                smtpCommand(reader, writer, "MAIL FROM:<" + cleanEmail(from) + ">", 250);
                smtpCommand(reader, writer, "RCPT TO:<" + cleanEmail(address) + ">", 250, 251);
                smtpCommand(reader, writer, "DATA", 354);
                String body = "From: <" + cleanEmail(from) + ">\r\nTo: <" + cleanEmail(address)
                        + ">\r\nSubject: Your Skill Match verification code\r\nMIME-Version: 1.0\r\n"
                        + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                        + Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(message.getBytes(StandardCharsets.UTF_8)).replace(".", "=2E")
                        + "\r\n.";
                writer.write(body + "\r\n");
                writer.flush();
                expectSmtp(reader, 250);
                smtpCommand(reader, writer, "QUIT", 221);
            }
        } catch (IOException ex) {
            throw new AuthException(502, "Could not deliver the email verification code. Check SMTP settings and try again.");
        }
    }

    private static void smtpCommand(BufferedReader reader, BufferedWriter writer, String command,
                                    int expected, int... alternatives) throws IOException {
        writer.write(command + "\r\n");
        writer.flush();
        int response = expectSmtp(reader, expected, alternatives);
        if (response != expected && java.util.Arrays.stream(alternatives).noneMatch(code -> code == response))
            throw new IOException("Unexpected SMTP response.");
    }

    private static int expectSmtp(BufferedReader reader, int expected, int... alternatives) throws IOException {
        String line = reader.readLine();
        if (line == null || line.length() < 3) throw new IOException("SMTP server closed the connection.");
        int code;
        try {
            code = Integer.parseInt(line.substring(0, 3));
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid SMTP server response.", ex);
        }
        while (line.length() > 3 && line.charAt(3) == '-') {
            line = reader.readLine();
            if (line == null) throw new IOException("SMTP server closed the connection.");
        }
        if (code != expected && java.util.Arrays.stream(alternatives).noneMatch(candidate -> candidate == code))
            throw new IOException("SMTP server rejected a command.");
        return code;
    }

    private static String cleanEmail(String value) throws IOException {
        if (!value.matches(EMAIL_PATTERN)) throw new IOException("Invalid SMTP email address.");
        return value;
    }

    private static String passwordHash(String password, byte[] salt) {
        try {
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password.toCharArray(),
                    salt, PASSWORD_ITERATIONS, 256);
            try {
                return Base64.getEncoder().encodeToString(
                        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                                .generateSecret(spec).getEncoded());
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException ex) {
            throw new AuthException(500, "Password security service is unavailable.");
        }
    }

    private static String hmac(String value) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new AuthException(500, "Authentication security service is unavailable.");
        }
    }

    private static String sha256(String value) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new AuthException(500, "Authentication security service is unavailable.");
        }
    }

    private static boolean constantTimeEquals(String first, String second) {
        if (first == null || second == null) return false;
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String bearerToken(String authorization) {
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim() : "";
    }

    private static String cookieToken(String cookieHeader) {
        if (cookieHeader == null) return "";
        for (String part : cookieHeader.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals("skillmatch_session")) return pair[1].trim();
        }
        return "";
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String env(String name) {
        return System.getenv().getOrDefault(name, "").trim();
    }

    private static Connection connect() throws SQLException {
        if (jdbcUrl == null) throw new SQLException("SQLite authentication storage is not initialized.");
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private static AuthException storageError(SQLException ex) {
        ex.printStackTrace(System.err);
        return new AuthException(500, "SQL account storage failed. Check the SQLite database file and server logs.");
    }

    private static boolean isUniqueViolation(SQLException ex) {
        return ex.getErrorCode() == 19 || (ex.getMessage() != null && ex.getMessage().contains("UNIQUE constraint failed"));
    }

    static final class AuthException extends RuntimeException {
        final int status;
        AuthException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
