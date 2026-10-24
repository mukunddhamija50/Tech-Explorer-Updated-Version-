import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

public class index {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            ".html", ".css", ".js", ".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp",
            ".ico", ".woff", ".woff2", ".ttf", ".otf");
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final List<Map<String, Object>> COMPANIES = maps(Json.parse(AppData.companiesJson()));
    private static final Map<String, Object> TESTS = object(Json.parse(AppData.testsJson()));
    private static final Map<String, List<String>> TAXONOMY = taxonomy();
    private static final Map<String, String> ALIASES = aliases();
    private static final Map<String, Pattern> MARKET_ROLE_PATTERNS = marketRolePatterns();
    private static final Pattern TECH_ROLE_TITLE = Pattern.compile(
            "\\b(software|engineer|developer|programmer|information technology|it|technical|data|cyber|security|cloud|devops|machine learning|artificial intelligence|ai/ml|sre|site reliability|qa|full[- ]?stack|technology|architect|infrastructure|platform)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TECH_ROLE_TAG = Pattern.compile(
            "^(tech|technology|software development|software engineering|engineering|development|it|product & technology)$",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, Double> WEIGHTS = Map.ofEntries(
            Map.entry("System Design", 1.5), Map.entry("Microservices", 1.4),
            Map.entry("Data Structures", 1.4), Map.entry("REST APIs", 1.3),
            Map.entry("AWS", 1.3), Map.entry("Kubernetes", 1.3), Map.entry("Docker", 1.3),
            Map.entry("Machine Learning", 1.4), Map.entry("Go", 1.3),
            Map.entry("Communication", 0.6), Map.entry("Agile", 0.6),
            Map.entry("Problem Solving", 0.7));
    private static final Map<String, List<String>> ROLE_KEYWORDS = roleKeywords();
    private static final Map<String, String> RESOURCES = resources();
    private static final Map<String, Double> WEEK_LENGTH = Map.ofEntries(
            Map.entry("System Design", 4.0), Map.entry("Microservices", 3.0),
            Map.entry("Kubernetes", 3.0), Map.entry("AWS", 3.0),
            Map.entry("Data Structures", 4.0), Map.entry("Machine Learning", 4.0),
            Map.entry("Go", 2.0), Map.entry("SQL", 1.0), Map.entry("Docker", 1.0),
            Map.entry("Git", 0.5), Map.entry("Linux", 1.0));
    private static final List<String> ASSESSMENT_SKILLS = List.of(
            "Java", "JavaScript", "HTML", "CSS", "C", "C++", "Node.js", "Python", "SQL",
            "AI & ML", "Cyber Security");
    private static final long BUILD_TIMESTAMP = Path.of("index.class").toFile().lastModified();
    private static volatile long cacheTime;
    private static volatile List<Map<String, Object>> cachedJobs = List.of();
    private static volatile String cachedJobsQuery = "";

    public static void main(String[] args) throws IOException {
        AuthStore.initialize();
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8000"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", index::handle);
        server.start();
        System.out.println("Java API started at http://127.0.0.1:" + port);

        int legacyPort = Integer.parseInt(System.getenv().getOrDefault("LEGACY_PORT", "8080"));
        try {
            HttpServer legacyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", legacyPort), 0);
            legacyServer.setExecutor(Executors.newCachedThreadPool());
            legacyServer.createContext("/", index::handleLegacy);
            legacyServer.start();
            System.out.println("Tech Explorer Java API started at http://127.0.0.1:" + legacyPort);
        } catch (java.net.BindException ex) {
            System.err.println("Tech Explorer API was not started: port " + legacyPort + " is already in use.");
        }
    }

    private static void handleLegacy(HttpExchange exchange) throws IOException {
        addCors(exchange.getResponseHeaders());
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (method.equals("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!method.equals("GET")) {
            send(exchange, 405, "application/json; charset=UTF-8", Json.stringify(Map.of("detail", "Method Not Allowed")));
            return;
        }
        if (exchange.getRequestURI().getPath().equals("/api/market/skills")) {
            send(exchange, 200, "application/json; charset=UTF-8", Json.stringify(marketSkills()));
            return;
        }
        List<Map<String, Object>> result;
        switch (exchange.getRequestURI().getPath()) {
            case "/api/hello" -> {
                send(exchange, 200, "application/json; charset=UTF-8", "\"Hello from Java backend\"");
                return;
            }
            case "/api/skills" -> result = List.of(
                    legacySkill("Python + SQL", "Advanced"),
                    legacySkill("Java Full Stack", "Intermediate"),
                    legacySkill("Cloud", "Intermediate"),
                    legacySkill("Applied Data Analytics", "Advanced"),
                    legacySkill("Generative AI", "Intermediate"),
                    legacySkill("Machine Learning & Generative AI", "Advanced"));
            case "/api/jobs" -> result = List.of(
                    legacyJob("Full Stack Developer", "Java, Spring Boot, SQL"),
                    legacyJob("Python Developer", "Python, SQL, FastAPI"),
                    legacyJob("Data Analyst", "Python, SQL, Database Management"),
                    legacyJob("Cyber Security", "Networking,Operating Systems,Linux"),
                    legacyJob("AI & ML Engineer", "Python, AI, Machine Learning, SQL, LLM, API"),
                    legacyJob("Cloud Engineer", "Cloud, Linux, AWS, Networking"));
            case "/api/programs" -> result = List.of(
                    legacyProgram("Full Stack Java Development Bootcamp", "Java,Java Spring Boot,Programming Languages", "6 Months"),
                    legacyProgram("Python Programming & Database Management", "Python,SQL", "4 Months"),
                    legacyProgram("Cloud Engineering with AWS & Azure", "Cloud,AWS,Azure", "5 Months"),
                    legacyProgram("Applied Data Analytics", "Data Analytics,SQL,Excel", "5 Months"),
                    legacyProgram("Machine Learning & Generative AI Specialization", "Machine Learning,LLMs,Generative AI", "5 Months"),
                    legacyProgram("Cyber Security Analyst Track", "Networking,Operating Systems,Web & Application Security,Network Security", "10 Months"));
            default -> {
                send(exchange, 404, "application/json; charset=UTF-8", Json.stringify(Map.of("detail", "Not Found")));
                return;
            }
        }
        send(exchange, 200, "application/json; charset=UTF-8", Json.stringify(result));
    }

    private static Map<String, Object> legacySkill(String name, String level) {
        return Map.of("name", name, "demand", "High", "level", level);
    }

    private static Map<String, Object> legacyJob(String title, String skills) {
        return Map.of("title", title, "skills", skills);
    }

    private static Map<String, Object> legacyProgram(String name, String skill, String duration) {
        return Map.of("name", name, "skill", skill, "duration", duration);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        addCors(exchange.getResponseHeaders());
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (method.equals("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        try {
            String path = exchange.getRequestURI().getPath();
            Object result;
            int status = 200;
            String contentType = "application/json; charset=UTF-8";
            if (path.equals("/api/health") && method.equals("GET")) {
                result = Map.of("ok", true, "time", System.currentTimeMillis() / 1000.0);
            } else if (path.equals("/api/runtime") && method.equals("GET")) {
                result = Map.of("runtime", "java", "resume_pdf", true, "auth_sql", true,
                        "auth_dev_otp", true, "build_timestamp", BUILD_TIMESTAMP);
            } else if (path.equals("/api/auth/config") && method.equals("GET")) {
                result = AuthStore.config();
            } else if (path.equals("/api/auth/session") && method.equals("GET")) {
                try {
                    authenticatedUser(exchange);
                    result = Map.of("authenticated", true);
                } catch (AuthStore.AuthException ex) {
                    result = Map.of("authenticated", false);
                }
            } else if (path.equals("/api/auth/otp/request") && method.equals("POST")) {
                result = AuthStore.requestOtp(body(exchange));
            } else if (path.equals("/api/auth/otp/verify") && method.equals("POST")) {
                result = AuthStore.verifyOtp(body(exchange));
            } else if (path.equals("/api/auth/signup/complete") && method.equals("POST")) {
                result = AuthStore.completeSignup(body(exchange));
            } else if (path.equals("/api/auth/login") && method.equals("POST")) {
                result = AuthStore.login(body(exchange));
            } else if (path.equals("/api/auth/google") && method.equals("POST")) {
                result = AuthStore.loginWithGoogle(body(exchange));
            } else if (path.equals("/api/auth/logout") && method.equals("POST")) {
                AuthStore.logout(exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Cookie"));
                exchange.getResponseHeaders().set("Set-Cookie", expiredSessionCookie());
                result = Map.of("ok", true);
            } else if (path.equals("/api/account/state") && method.equals("GET")) {
                result = AuthStore.state(authenticatedUser(exchange));
            } else if (path.equals("/api/account/progress") && method.equals("POST")) {
                AuthStore.saveProgress(authenticatedUser(exchange), body(exchange));
                result = Map.of("ok", true);
            } else if (path.equals("/api/account/activity") && method.equals("POST")) {
                AuthStore.addHistory(authenticatedUser(exchange), body(exchange));
                result = Map.of("ok", true);
            } else if (path.equals("/api/market/skills") && method.equals("GET")) {
                result = marketSkills();
            } else if (path.equals("/api/jobs") && method.equals("GET")) {
                authenticatedUser(exchange);
                Map<String, String> query = queryParams(exchange.getRequestURI());
                result = getJobs(query.getOrDefault("query", ""), boundedInt(query.get("limit"), 100, 1, 500),
                        !query.getOrDefault("live", "true").equalsIgnoreCase("false"));
            } else if (path.equals("/api/companies") && method.equals("GET")) {
                result = Map.of("companies", COMPANIES);
            } else if (path.equals("/api/resume/generate") && method.equals("POST")) {
                long userId = authenticatedUser(exchange);
                Map<String, Object> resume = generateResume(body(exchange));
                AuthStore.addHistory(userId, Map.of("type", "resume", "title",
                        "Resume created" + (string(object(resume.get("header")).get("target_role")).isBlank()
                                ? "" : " · " + string(object(resume.get("header")).get("target_role"))),
                        "details", resume));
                result = Map.of("resume", resume, "download", Map.of("plain_text", resumeToText(resume)));
            } else if (path.equals("/api/resume/generate/pdf") && method.equals("POST")) {
                authenticatedUser(exchange);
                Map<String, Object> resume = generateResume(body(exchange));
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"resume.pdf\"");
                sendBytes(exchange, 200, "application/pdf", resumeToPdf(resume), false);
                return;
            } else if (path.equals("/api/resume/generate/txt") && method.equals("POST")) {
                authenticatedUser(exchange);
                Map<String, Object> resume = generateResume(body(exchange));
                send(exchange, 200, "text/plain; charset=UTF-8", resumeToText(resume));
                return;
            } else if (path.equals("/api/resume/scan") && method.equals("POST")) {
                long userId = authenticatedUser(exchange);
                result = scanResume(string(body(exchange).get("text")), "");
                Map<String, Object> scanned = object(result);
                AuthStore.addHistory(userId, Map.of("type", "scan", "title", "Resume scanned",
                        "details", Map.of("filename", "", "skill_count", scanned.get("skill_count"))));
            } else if (path.equals("/api/resume/scan/file") && method.equals("POST")) {
                long userId = authenticatedUser(exchange);
                Map<String, Object> upload = multipartFile(exchange);
                String filename = string(upload.get("filename"));
                byte[] data = (byte[]) upload.get("data");
                if (filename.isBlank() || !filename.toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    throw new ApiException(400, "please upload a PDF resume");
                if (data.length == 0) throw new ApiException(400, "uploaded PDF is empty");
                String text;
                try {
                    text = extractPdfText(data);
                } catch (RuntimeException ex) {
                    throw new ApiException(400, "could not read PDF: " + ex.getMessage());
                }
                if (text.isBlank())
                    throw new ApiException(400, "No selectable text found in this PDF. Scanned/image-only PDFs require OCR before scanning.");
                result = scanResume(text, filename);
                Map<String, Object> scanned = object(result);
                AuthStore.addHistory(userId, Map.of("type", "scan", "title", "Resume scanned",
                        "details", Map.of("filename", filename, "skill_count", scanned.get("skill_count"))));
            } else if (path.equals("/api/match/companies") && method.equals("POST")) {
                long userId = authenticatedUser(exchange);
                Map<String, Object> payload = body(exchange);
                List<String> skills = strings(payload.get("skills"));
                if (skills.isEmpty()) throw new ApiException(400, "provide at least one skill (use the scanner or the test results)");
                result = matchCompanies(skills, boundedInt(payload.get("limit"), 500, 1, 500));
                Map<String, Object> matchResult = object(result);
                AuthStore.addHistory(userId, Map.of("type", "company_match", "title", "Company matches",
                        "details", Map.of("skills", skills, "total_companies", matchResult.get("total_companies"))));
            } else if (path.equals("/api/match/gap") && method.equals("POST")) {
                long userId = authenticatedUser(exchange);
                Map<String, Object> payload = body(exchange);
                String company = string(payload.get("company")).trim();
                if (company.isEmpty()) throw new ApiException(400, "choose a target company");
                result = gapAnalysis(strings(payload.get("skills")), company);
                Map<String, Object> gapResult = object(result);
                AuthStore.addHistory(userId, Map.of("type", "roadmap", "title", "Roadmap · " + company,
                        "details", Map.of("company", company, "current_fit_percent", gapResult.get("current_fit_percent"))));
            } else if (path.equals("/api/tests") && method.equals("GET")) {
                authenticatedUser(exchange);
                result = Map.of("tests", availableTests());
            } else if (path.startsWith("/api/tests/") && path.endsWith("/submit") && method.equals("POST")) {
                long userId = authenticatedUser(exchange);
                String skill = decode(path.substring("/api/tests/".length(), path.length() - "/submit".length()));
                Map<String, Object> submission = body(exchange);
                Object answers = submission.get("answers");
                if (!(answers instanceof List<?>)) throw new ApiException(400, "answers must be a list (use null for skipped)");
                Map<String, String> query = queryParams(exchange.getRequestURI());
                int level = boundedInt(query.get("level"), 0, 0, 4);
                boolean interviewPractice = isInterviewPractice(query);
                Object responses = submission.getOrDefault("responses", List.of());
                if (!(responses instanceof List<?>))
                    throw new ApiException(400, "responses must be a list of written answers");
                result = level == 0 ? gradeTest(skill, (List<?>) answers)
                        : gradeTest(skill, level, assessmentQuestionCount(query), interviewPractice,
                                (List<?>) answers, (List<?>) responses);
                Map<String, Object> assessmentResult = object(result);
                if (Boolean.TRUE.equals(assessmentResult.get("self_assessment"))) {
                    AuthStore.addHistory(userId, Map.of("type", "assessment",
                            "title", string(assessmentResult.get("skill")) + " · Level 4 interview practice",
                            "details", Map.of("skill", assessmentResult.get("skill"),
                                    "answered", assessmentResult.get("answered"),
                                    "total", assessmentResult.get("total"),
                                    "level", assessmentResult.getOrDefault("level", 0))));
                } else {
                    AuthStore.addHistory(userId, Map.of("type", "assessment",
                            "title", string(assessmentResult.get("skill")) + " - " + assessmentResult.get("percent") + "%",
                            "details", Map.of("skill", assessmentResult.get("skill"), "percent", assessmentResult.get("percent"),
                                    "correct", assessmentResult.get("correct"), "total", assessmentResult.get("total"),
                                    "level", assessmentResult.getOrDefault("level", 0))));
                }
            } else if (path.startsWith("/api/tests/") && method.equals("GET")) {
                authenticatedUser(exchange);
                String skill = decode(path.substring("/api/tests/".length()));
                Map<String, String> query = queryParams(exchange.getRequestURI());
                int level = boundedInt(query.get("level"), 0, 0, 4);
                result = level == 0 ? getTest(skill)
                        : getTest(skill, level, assessmentQuestionCount(query), isInterviewPractice(query));
            } else if ((path.equals("/docs") || path.equals("/docs/")) && method.equals("GET")) {
                send(exchange, 200, "text/html; charset=UTF-8", docsPage());
                return;
            } else if (path.startsWith("/api/")) {
                throw new ApiException(404, "Not Found");
            } else if (method.equals("GET") || method.equals("HEAD")) {
                Path file = staticPath(path);
                if (!Files.isRegularFile(file)) throw new ApiException(404, "Not Found");
                byte[] bytes = Files.readAllBytes(file);
                String type = contentType(file);
                sendBytes(exchange, 200, type, bytes, method.equals("HEAD"));
                return;
            } else {
                throw new ApiException(405, "Method Not Allowed");
            }
            if (result instanceof Map<?, ?> map && map.get("access_token") instanceof String token) {
                exchange.getResponseHeaders().set("Set-Cookie", sessionCookie(token));
                Map<String, Object> publicResult = object(result);
                publicResult.remove("access_token");
                result = publicResult;
            }
            send(exchange, status, contentType, Json.stringify(result));
        } catch (AuthStore.AuthException ex) {
            send(exchange, ex.status, "application/json; charset=UTF-8",
                    Json.stringify(Map.of("detail", ex.getMessage())));
        } catch (ApiException ex) {
            send(exchange, ex.status, "application/json; charset=UTF-8",
                    Json.stringify(Map.of("detail", ex.getMessage())));
        } catch (IllegalArgumentException ex) {
            send(exchange, 400, "application/json; charset=UTF-8",
                    Json.stringify(Map.of("detail", ex.getMessage())));
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            send(exchange, 500, "application/json; charset=UTF-8",
                    Json.stringify(Map.of("detail", "Internal server error")));
        }
    }

    private static long authenticatedUser(HttpExchange exchange) {
        return AuthStore.authenticatedUser(exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Cookie"));
    }

    private static String sessionCookie(String token) {
        return "skillmatch_session=" + token + "; Path=/; Max-Age="
                + AuthStore.sessionCookieMaxAgeSeconds() + "; HttpOnly; SameSite=Lax"
                + (Boolean.parseBoolean(System.getenv().getOrDefault("COOKIE_SECURE", "false")) ? "; Secure" : "");
    }

    private static String expiredSessionCookie() {
        return "skillmatch_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
                + (Boolean.parseBoolean(System.getenv().getOrDefault("COOKIE_SECURE", "false")) ? "; Secure" : "");
    }

    private static void addCors(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        sendBytes(exchange, status, type, body.getBytes(StandardCharsets.UTF_8), false);
    }

    private static void sendBytes(HttpExchange exchange, int status, String type, byte[] bytes, boolean head)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, head ? -1 : bytes.length);
        if (!head) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } else {
            exchange.close();
        }
    }

    private static Path staticPath(String urlPath) {
        String requested = urlPath.equals("/") ? "/index.html" : urlPath;
        Path resolved = ROOT.resolve(requested.substring(1)).normalize();
        if (!resolved.startsWith(ROOT) || resolved.getFileName().toString().startsWith("."))
            throw new ApiException(404, "Not Found");
        String name = resolved.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || !STATIC_EXTENSIONS.contains(name.substring(dot)))
            throw new ApiException(404, "Not Found");
        return resolved;
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=UTF-8";
        if (name.endsWith(".css")) return "text/css; charset=UTF-8";
        if (name.endsWith(".js")) return "text/javascript; charset=UTF-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static String docsPage() {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>SkillMatch API</title>
                <style>body{font:16px system-ui,sans-serif;max-width:900px;margin:40px auto;padding:0 20px;color:#243047}
                h1{color:#5b54d6}code{background:#f0f1f7;padding:3px 6px;border-radius:4px}
                li{margin:12px 0}</style></head><body><h1>SkillMatch API</h1>
                <p>Local Java API routes used by the SkillMatch website.</p><ul>
                <li><code>GET /api/health</code> — health check</li>
                <li><code>GET /api/jobs</code> — live and fallback job listings</li>
                <li><code>GET /api/market/skills</code> — requested-skill demand and role-demand data; role percentages are normalized across matched role counts to total 100% when matches exist</li>
                <li>Market demand requests up to 500 listings from Adzuna when <code>ADZUNA_APP_ID</code> and <code>ADZUNA_APP_KEY</code> are configured. Otherwise it uses the available Remotive sample and reports the smaller returned count.</li>
                <li><code>GET /api/companies</code> — company profiles</li>
                <li><code>POST /api/auth/otp/request</code>, <code>/api/auth/otp/verify</code>, <code>/api/auth/signup/complete</code> — verify an email or phone before password signup</li>
                <li><code>POST /api/auth/login</code>, <code>/api/auth/google</code>, <code>/api/auth/logout</code> — account access and session management</li>
                <li><code>GET /api/account/state</code>, <code>POST /api/account/progress</code> — restore account history and the last visited Skill Match page</li>
                <li>Configure email OTP with <code>SMTP_HOST</code>, <code>SMTP_PORT</code>, <code>SMTP_USERNAME</code>, <code>SMTP_PASSWORD</code>, and <code>SMTP_FROM</code>; configure phone OTP with <code>TWILIO_ACCOUNT_SID</code>, <code>TWILIO_AUTH_TOKEN</code>, and <code>TWILIO_FROM_NUMBER</code>.</li>
                <li>The local Windows launcher enables <code>SKILLMATCH_DEV_OTP=true</code> for development: without a delivery provider, it displays a one-time code in the signup page. This is for local testing only; set it to <code>false</code> and configure a real provider before exposing the server.</li>
                <li>Set <code>GOOGLE_CLIENT_ID</code> to a Google OAuth Web client ID whose allowed JavaScript origin includes this app. Use <code>COOKIE_SECURE=true</code> when served over HTTPS.</li>
                <li><code>POST /api/resume/generate</code> — build a structured resume</li>
                <li><code>POST /api/resume/generate/pdf</code> — download a formatted, link-enabled PDF resume</li>
                <li><code>POST /api/resume/generate/txt</code> — download a plain-text resume</li>
                <li><code>POST /api/resume/scan</code> — scan pasted resume text</li>
                <li><code>POST /api/resume/scan/file</code> — scan an uploaded PDF</li>
                <li><code>POST /api/match/companies</code> — rank companies by skill fit</li>
                <li><code>POST /api/match/gap</code> — build a skill-gap roadmap</li>
                <li><code>GET /api/tests</code>, <code>GET /api/tests/{skill}?level=1..4</code> — available skills and tiered question sets</li>
                <li><code>POST /api/tests/{skill}/submit?level=1..4</code> — grade the selected level; a passing score may be recorded in a resume</li>
                </ul></body></html>
                """;
    }

    private static Map<String, Object> body(HttpExchange exchange) throws IOException {
        byte[] bytes = readAll(exchange.getRequestBody(), 2_000_000);
        Object parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?>)) throw new ApiException(400, "request body must be a JSON object");
        return object(parsed);
    }

    private static byte[] readAll(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > limit) throw new ApiException(413, "request body is too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) return params;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            params.put(decode(parts[0]), parts.length > 1 ? decode(parts[1]) : "");
        }
        return params;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static int boundedInt(Object raw, int fallback, int minimum, int maximum) {
        if (raw == null || raw.toString().isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(raw.toString());
            return Math.max(minimum, Math.min(maximum, parsed));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Map<String, Object> multipartFile(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        Matcher boundaryMatch = Pattern.compile("boundary=(?:\"([^\"]+)\"|([^;]+))").matcher(contentType == null ? "" : contentType);
        if (!boundaryMatch.find()) throw new ApiException(400, "file field is required");
        String boundary = boundaryMatch.group(1) != null ? boundaryMatch.group(1) : boundaryMatch.group(2).trim();
        byte[] raw = readAll(exchange.getRequestBody(), 20_000_000);
        String wire = new String(raw, StandardCharsets.ISO_8859_1);
        String marker = "--" + boundary;
        for (String part : wire.split(Pattern.quote(marker))) {
            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd < 0) continue;
            String headers = part.substring(0, headerEnd);
            Matcher name = Pattern.compile("name=\"file\"").matcher(headers);
            Matcher filename = Pattern.compile("filename=\"([^\"]*)\"").matcher(headers);
            if (!name.find() || !filename.find()) continue;
            int start = headerEnd + 4;
            int end = part.lastIndexOf("\r\n");
            if (end < start) end = part.length();
            byte[] data = part.substring(start, end).getBytes(StandardCharsets.ISO_8859_1);
            return Map.of("filename", filename.group(1), "data", data);
        }
        throw new ApiException(400, "file field is required");
    }

    private static String extractPdfText(byte[] pdf) {
        String source = new String(pdf, StandardCharsets.ISO_8859_1);
        StringBuilder extracted = new StringBuilder();
        Matcher streams = Pattern.compile("stream\\r?\\n(.*?)\\r?\\nendstream", Pattern.DOTALL).matcher(source);
        while (streams.find()) {
            byte[] contents = streams.group(1).getBytes(StandardCharsets.ISO_8859_1);
            String prefix = source.substring(Math.max(0, streams.start() - 250), streams.start());
            if (prefix.contains("FlateDecode")) {
                try (InputStream inflater = new InflaterInputStream(new java.io.ByteArrayInputStream(contents))) {
                    contents = readAll(inflater, 10_000_000);
                } catch (IOException ignored) {
                    continue;
                }
            }
            extractPdfOperators(new String(contents, StandardCharsets.ISO_8859_1), extracted);
        }
        return extracted.toString();
    }

    private static void extractPdfOperators(String stream, StringBuilder out) {
        Matcher textBlocks = Pattern.compile("BT(.*?)ET", Pattern.DOTALL).matcher(stream);
        while (textBlocks.find()) {
            Matcher strings = Pattern.compile("\\((?:\\\\.|[^\\\\)])*\\)\\s*Tj|\\[(.*?)\\]\\s*TJ", Pattern.DOTALL)
                    .matcher(textBlocks.group(1));
            while (strings.find()) {
                String operation = strings.group();
                Matcher literal = Pattern.compile("\\((?:\\\\.|[^\\\\)])*\\)").matcher(operation);
                while (literal.find()) {
                    String value = literal.group().substring(1, literal.group().length() - 1)
                            .replaceAll("\\\\([()\\\\])", "$1")
                            .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
                    out.append(value).append(' ');
                }
            }
            out.append('\n');
        }
    }

    private static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list) result.add(object(item));
        return result;
    }

    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list) if (item != null) result.add(String.valueOf(item));
        return result;
    }

    private static Map<String, List<String>> taxonomy() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("Python", List.of("python3", "py", "python (programming)"));
        map.put("JavaScript", List.of("java script", "js", "es6", "ecmascript", "javascript (programming)"));
        map.put("TypeScript", List.of("ts"));
        map.put("Java", List.of("java (programming)", "core java", "java 8", "java 17"));
        map.put("C++", List.of("cpp", "c plus plus"));
        map.put("C", List.of("c language", "c programming"));
        map.put("Go", List.of("golang"));
        map.put("SQL", List.of("mysql", "postgres", "postgresql", "sqlite", "t-sql", "pl/sql"));
        map.put("NoSQL", List.of("mongodb", "mongo", "cassandra", "dynamodb", "redis"));
        map.put("HTML", List.of("html5"));
        map.put("CSS", List.of("css3", "flexbox", "grid layout"));
        map.put("React", List.of("react.js", "reactjs", "react js"));
        map.put("Angular", List.of("angularjs", "angular 2+"));
        map.put("Vue.js", List.of("vue", "vuejs", "vue js"));
        map.put("Node.js", List.of("node", "nodejs", "node js", "express", "express.js", "expressjs"));
        map.put("Next.js", List.of("nextjs", "next js"));
        map.put("Django", List.of("django rest framework", "drf"));
        map.put("Flask", List.of("flask-restful"));
        map.put("FastAPI", List.of("fast api", "fast-api"));
        map.put("Spring Boot", List.of("spring", "springboot", "spring-boot"));
        map.put("REST APIs", List.of("rest", "restful", "rest api", "api development", "web services"));
        map.put("GraphQL", List.of("apollo"));
        map.put("Docker", List.of("containers", "containerization", "docker-compose"));
        map.put("Kubernetes", List.of("k8s", "eks", "gke"));
        map.put("AWS", List.of("amazon web services", "ec2", "s3", "lambda", "cloudwatch"));
        map.put("Azure", List.of("microsoft azure"));
        map.put("GCP", List.of("google cloud", "google cloud platform"));
        map.put("CI/CD", List.of("jenkins", "github actions", "gitlab ci", "circleci", "continuous integration"));
        map.put("Git", List.of("github", "gitlab", "version control", "bitbucket"));
        map.put("Linux", List.of("unix", "bash", "shell scripting", "ubuntu"));
        map.put("Data Structures", List.of("dsa", "data structures and algorithms", "algorithms"));
        map.put("System Design", List.of("hld", "lld", "distributed systems", "scalability"));
        map.put("Machine Learning", List.of("ml", "scikit-learn", "sklearn", "supervised learning"));
        map.put("AI & ML", List.of("ai/ml", "artificial intelligence", "ai and machine learning"));
        map.put("Deep Learning", List.of("neural networks", "tensorflow", "keras"));
        map.put("NLP", List.of("natural language processing", "spacy", "text processing"));
        map.put("Pandas", List.of("numpy", "dataframes"));
        map.put("Data Analysis", List.of("data analytics", "exploratory data analysis", "eda"));
        map.put("Data Visualization", List.of("matplotlib", "seaborn", "plotly", "power bi", "tableau"));
        map.put("Statistics", List.of("probability", "statistical analysis", "hypothesis testing"));
        map.put("Excel", List.of("advanced excel", "vlookup", "pivot tables", "microsoft excel"));
        map.put("Testing", List.of("unit testing", "pytest", "jest", "selenium", "cypress", "test automation"));
        map.put("Agile", List.of("scrum", "kanban", "jira", "sprint planning"));
        map.put("Communication", List.of("presentation skills", "public speaking", "stakeholder management"));
        map.put("Problem Solving", List.of("analytical thinking", "analytical skills"));
        map.put("Flutter", List.of("dart"));
        map.put("React Native", List.of("rn"));
        map.put("Android", List.of("android development", "android sdk", "jetpack compose"));
        map.put("Kotlin", List.of("kotlin android"));
        map.put("iOS", List.of("swift", "swiftui", "uikit"));
        map.put("MongoDB", List.of());
        map.put("RabbitMQ", List.of("kafka", "message queues", "apache kafka"));
        map.put("Microservices", List.of("microservices architecture", "service oriented architecture", "soa"));
        map.put("Networking", List.of("tcp/ip", "dns", "http protocol", "load balancing"));
        map.put("Cyber Security", List.of("cybersecurity", "security", "information security",
                "network security", "application security", "penetration testing", "ethical hacking",
                "owasp", "infosec", "info sec"));
        return map;
    }

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        TAXONOMY.forEach((canonical, values) -> {
            aliases.put(canonical.toLowerCase(Locale.ROOT), canonical);
            for (String alias : values) aliases.put(alias.toLowerCase(Locale.ROOT), canonical);
        });
        return aliases;
    }

    private static String category(String skill) {
        skill = canonicalSkill(skill);
        if (Set.of("Python", "JavaScript", "TypeScript", "Java", "C++", "C", "Go", "Kotlin", "Dart").contains(skill)) return "Programming Languages";
        if (Set.of("HTML", "CSS", "React", "Angular", "Vue.js", "Next.js").contains(skill)) return "Frontend";
        if (Set.of("Node.js", "Django", "Flask", "FastAPI", "Spring Boot", "REST APIs", "GraphQL", "Microservices", "RabbitMQ").contains(skill)) return "Backend";
        if (Set.of("Flutter", "React Native", "Android", "iOS").contains(skill)) return "Mobile";
        if (Set.of("Machine Learning", "Deep Learning", "NLP", "Pandas", "Data Analysis", "Data Visualization", "Statistics").contains(skill)) return "Data / ML";
        if (Set.of("SQL", "NoSQL", "MongoDB").contains(skill)) return "Database";
        if (Set.of("Docker", "Kubernetes", "AWS", "Azure", "GCP", "CI/CD", "Git", "Linux").contains(skill)) return "DevOps / Cloud";
        if (Set.of("Data Structures", "System Design", "Networking", "Cyber Security").contains(skill)) return "CS Foundations";
        if (Set.of("Excel", "Testing", "Agile").contains(skill)) return "Tools";
        return "Soft Skills";
    }

    private static List<String> extractSkillNames(String text) {
        return new ArrayList<>(extractSkills(text).stream().map(item -> string(item.get("skill"))).toList());
    }

    private static List<Map<String, Object>> extractSkills(String text) {
        if (text == null || text.isEmpty()) return List.of();
        String flat = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#./-]+", " ")
                .replace(".", " ") + " ";
        List<String> keys = new ArrayList<>(ALIASES.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        Map<String, Set<String>> found = new LinkedHashMap<>();
        for (String alias : keys) {
            String normalized = alias.replaceAll("[^a-z0-9+#./-]+", " ").replace(".", " ").trim();
            if (!normalized.isEmpty() && flat.contains(" " + normalized + " "))
                found.computeIfAbsent(ALIASES.get(alias), ignored -> new LinkedHashSet<>()).add(normalized);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        found.forEach((skill, evidence) -> {
            List<String> sorted = new ArrayList<>(evidence);
            Collections.sort(sorted);
            result.add(Map.of("skill", skill, "category", category(skill), "evidence", sorted));
        });
        return result;
    }

    private static Map<String, List<String>> categorize(List<String> skills) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String skill : skills) {
            String canonical = canonicalSkill(skill);
            result.computeIfAbsent(category(canonical), ignored -> new ArrayList<>()).add(canonical);
        }
        return result;
    }

    private static Map<String, Object> scanResume(String text, String filename) {
        if (text.isBlank()) throw new ApiException(400, "resume text is empty");
        List<Map<String, Object>> detected = extractSkills(text);
        List<String> names = detected.stream().map(item -> string(item.get("skill"))).toList();
        List<String> emails = findAll("[\\w.+-]+@[\\w-]+\\.[\\w.]+", text, 3, true);
        List<String> phones = findAll("(?:\\+91[-\\s]?)?[6-9]\\d{9}|\\d{3}[-.\\s]\\d{3}[-.\\s]\\d{4}", text, 2, false);
        List<Map<String, Object>> profileLinks = findProfileLinks(text);
        String level = "Mid-level (2-5 yrs)";
        int rank = 2;
        String[][] seniority = {
                {"\\b(intern|trainee|internship)\\b", "Intern / Trainee", "0"},
                {"\\b(junior|jr\\.?|entry[- ]level|fresher|associate)\\b", "Junior (0-2 yrs)", "1"},
                {"\\b(senior|sr\\.?|lead|staff)\\b", "Senior (5+ yrs)", "3"},
                {"\\b(principal|architect|director|head of)\\b", "Principal / Architect", "4"}};
        for (String[] item : seniority) if (Pattern.compile(item[0], Pattern.CASE_INSENSITIVE).matcher(text).find()
                && Integer.parseInt(item[2]) >= rank) {
            level = item[1]; rank = Integer.parseInt(item[2]);
        }
        List<String> education = new ArrayList<>();
        String[] degrees = {"\\bB\\.?\\s?Tech\\b", "\\bB\\.?E\\.?\\b", "\\bBachelor(?:'s)?\\b", "\\bB\\.?\\s?Sc\\b",
                "\\bM\\.?\\s?Tech\\b", "\\bM\\.?\\s?Sc\\b", "\\bMaster(?:'s)?\\b", "\\bMBA\\b", "\\bPh\\.?D\\b"};
        for (String degree : degrees) {
            Matcher match = Pattern.compile(degree, Pattern.CASE_INSENSITIVE).matcher(text);
            if (match.find() && !education.contains(match.group().trim())) education.add(match.group().trim());
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        int score = 100;
        String lower = text.toLowerCase(Locale.ROOT);
        if (emails.isEmpty()) { score -= 15; finding(findings, "error", "No email found — recruiters cannot contact you."); }
        if (phones.isEmpty()) { score -= 10; finding(findings, "warn", "No phone number found."); }
        if (text.length() < 400) { score -= 15; finding(findings, "warn", "Resume looks very short (< ~400 chars). Add experience/projects with outcomes."); }
        if (text.length() > 6000) { score -= 5; finding(findings, "info", "Very long resume — 1 page (2 max) is ideal in India."); }
        if (!Pattern.compile("\\d+\\s?(%|users|ms|k\\b|x\\b|hours|lakh|crore|₹)", Pattern.CASE_INSENSITIVE).matcher(lower).find()) {
            score -= 15; finding(findings, "warn", "No measurable outcomes found — add numbers (%, users, latency, revenue).");
        }
        if (Pattern.compile("responsible for|worked on|helped with|duties included").matcher(lower).find()) {
            score -= 10; finding(findings, "warn", "Passive phrasing ('responsible for…') — start bullets with strong action verbs.");
        }
        if (profileLinks.isEmpty()) {
            score -= 5; finding(findings, "info", "No clickable professional profiles detected. Add GitHub, LinkedIn, or portfolio URLs.");
        } else {
            finding(findings, "ok", "Detected " + profileLinks.size() + " professional profile link(s).");
        }
        if (names.size() < 4) { score -= 15; finding(findings, "error", "Only " + names.size() + " skills detected. ATS keyword filters will likely drop this resume."); }
        else if (names.size() >= 8) finding(findings, "ok", "Good skill coverage: " + names.size() + " skills detected.");
        if (!Pattern.compile("\\b(education|b\\.?tech|bachelor|degree|university|college)\\b").matcher(lower).find()) {
            score -= 10; finding(findings, "warn", "No education section detected.");
        }
        Map<String, Object> contact = Map.of("emails", emails, "phones", phones, "profile_links", profileLinks);
        return Map.of("filename", filename, "contact", contact, "detected_skills", detected,
                "skill_count", names.size(), "skills_by_category", categorize(names), "seniority", level,
                "education", education.stream().limit(4).toList(), "ats_score", Math.max(score, 0), "findings", findings);
    }

    private static List<Map<String, Object>> findProfileLinks(String text) {
        Matcher matcher = Pattern.compile(
                "(?i)\\b(?:https?://|www\\.)?[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}(?:/[^\\s<>()\\]]*)?")
                .matcher(text);
        Map<String, Map<String, Object>> links = new LinkedHashMap<>();
        while (matcher.find()) {
            String url = matcher.group().replaceAll("[.,;:!?]+$", "");
            String normalized = url.replaceFirst("(?i)^https?://", "").replaceFirst("(?i)^www\\.", "");
            String host = normalized.split("/", 2)[0].toLowerCase(Locale.ROOT);
            String platform;
            if (host.equals("github.com")) platform = "GitHub";
            else if (host.equals("linkedin.com")) platform = "LinkedIn";
            else if (host.equals("gitlab.com")) platform = "GitLab";
            else if (host.equals("bitbucket.org")) platform = "Bitbucket";
            else platform = "Website / Portfolio";
            String href = url.matches("(?i)^https?://.*") ? url : "https://" + url;
            links.putIfAbsent(platform + "|" + href.toLowerCase(Locale.ROOT),
                    Map.of("platform", platform, "url", href));
        }
        return new ArrayList<>(links.values());
    }

    private static List<String> findAll(String regex, String text, int max, boolean insensitive) {
        Matcher matcher = Pattern.compile(regex, insensitive ? Pattern.CASE_INSENSITIVE : 0).matcher(text);
        List<String> out = new ArrayList<>();
        while (matcher.find() && out.size() < max) out.add(matcher.group());
        return out;
    }

    private static void finding(List<Map<String, Object>> findings, String severity, String issue) {
        findings.add(Map.of("severity", severity, "issue", issue));
    }

    private static Map<String, List<String>> roleKeywords() {
        Map<String, List<String>> roles = new LinkedHashMap<>();
        roles.put("backend", List.of("REST APIs", "SQL", "Docker", "Microservices", "System Design", "Testing", "Git"));
        roles.put("frontend", List.of("HTML", "CSS", "JavaScript", "React", "Testing", "Git"));
        roles.put("full stack", List.of("HTML", "CSS", "JavaScript", "React", "Node.js", "REST APIs", "SQL", "Git"));
        roles.put("data", List.of("Python", "SQL", "Pandas", "Data Analysis", "Data Visualization", "Statistics"));
        roles.put("machine learning", List.of("Python", "Machine Learning", "Deep Learning", "Statistics", "Pandas"));
        roles.put("ai & ml", List.of("AI & ML", "Python", "Machine Learning", "Deep Learning", "Statistics", "Pandas"));
        roles.put("cyber security", List.of("Cyber Security", "Networking", "Linux", "Operating Systems",
                "Network Security", "Web Application Security"));
        roles.put("devops", List.of("Linux", "Docker", "Kubernetes", "CI/CD", "AWS", "Git"));
        roles.put("mobile", List.of("Flutter", "Android", "Kotlin", "iOS", "REST APIs"));
        return roles;
    }

    private static Map<String, String> resources() {
        Map<String, String> map = new HashMap<>();
        map.put("Python", "https://docs.python.org/3/tutorial/");
        map.put("JavaScript", "https://javascript.info/");
        map.put("Java", "https://dev.java/learn/");
        map.put("Go", "https://go.dev/tour/");
        map.put("SQL", "https://sqlbolt.com/");
        map.put("React", "https://react.dev/learn");
        map.put("Node.js", "https://nodejs.org/en/learn");
        map.put("Docker", "https://docker-curriculum.com/");
        map.put("Kubernetes", "https://kubernetes.io/docs/tutorials/");
        map.put("AWS", "https://aws.amazon.com/getting-started/");
        map.put("System Design", "https://github.com/donnemartin/system-design-primer");
        map.put("Data Structures", "https://neetcode.io/roadmap");
        map.put("Machine Learning", "https://www.kaggle.com/learn");
        map.put("REST APIs", "https://restfulapi.net/");
        map.put("Microservices", "https://microservices.io/patterns/");
        map.put("Testing", "https://testingjavascript.com/");
        map.put("Git", "https://learngitbranching.js.org/");
        map.put("Linux", "https://linuxjourney.com/");
        map.put("Statistics", "https://khanacademy.org/math/statistics-probability");
        map.put("HTML", "https://web.dev/learn/html");
        map.put("CSS", "https://web.dev/learn/css");
        map.put("TypeScript", "https://www.typescriptlang.org/docs/handbook/intro.html");
        return map;
    }

    private static Map<String, Object> generateResume(Map<String, Object> profile) {
        String name = string(profile.get("name")).trim();
        if (name.isEmpty()) throw new ApiException(400, "name is required");
        List<String> skills = new ArrayList<>();
        for (String skill : strings(profile.get("skills"))) {
            if (!skill.isBlank()) skills.add(canonicalSkill(skill.trim()));
        }
        List<Map<String, Object>> experience = maps(profile.get("experience"));
        List<Map<String, Object>> projects = new ArrayList<>();
        for (Map<String, Object> project : maps(profile.get("projects"))) {
            Map<String, Object> copy = new LinkedHashMap<>(project);
            String url = normalizeOptionalUrl(project.get("url"), "project URL");
            if (!url.isEmpty()) copy.put("url", url);
            projects.add(copy);
        }
        List<Map<String, Object>> education = maps(profile.get("education"));
        StringBuilder mine = new StringBuilder(string(profile.get("extra_text")));
        for (Map<String, Object> project : projects) mine.append(' ').append(string(project.get("description")));
        for (Map<String, Object> entry : experience)
            for (String highlight : strings(entry.get("highlights"))) mine.append(' ').append(highlight);
        for (String skill : extractSkillNames(mine.toString())) if (!skills.contains(skill)) skills.add(skill);
        String target = string(profile.get("target_role")).trim();
        List<String> keywords = targetKeywords(target);
        List<String> ordered = new ArrayList<>();
        for (String skill : skills) if (keywords.contains(skill)) ordered.add(skill);
        for (String skill : skills) if (!keywords.contains(skill)) ordered.add(skill);
        List<String> missing = keywords.stream().filter(skill -> !skills.contains(skill)).toList();

        List<Map<String, Object>> rewrittenExperience = new ArrayList<>();
        for (Map<String, Object> entry : experience) {
            Map<String, Object> copy = new LinkedHashMap<>(entry);
            List<String> highlights = strings(entry.get("highlights")).stream().filter(s -> !s.isBlank())
                    .map(index::rewriteBullet).toList();
            copy.put("highlights", highlights);
            rewrittenExperience.add(copy);
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("name", name);
        header.put("email", string(profile.get("email")));
        header.put("phone", string(profile.get("phone")));
        header.put("linkedin_url", normalizeOptionalUrl(profile.get("linkedin_url"), "LinkedIn URL"));
        header.put("github_url", normalizeOptionalUrl(profile.get("github_url"), "GitHub URL"));
        header.put("target_role", target);
        Map<String, Object> skillBlock = Map.of("categorized", categorize(ordered), "flat", ordered);
        List<String> suggestions = suggestions(target, ordered, missing, experience);
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("header", header);
        resume.put("professional_summary", summary(name, target, ordered));
        resume.put("skills", skillBlock);
        resume.put("experience", rewrittenExperience);
        resume.put("projects", projects);
        resume.put("education", education);
        resume.put("suggestions", suggestions);
        List<Map<String, Object>> assessments = maps(profile.get("verified_assessments"));
        if (!assessments.isEmpty()) resume.put("verified_assessments", assessments);
        return resume;
    }

    private static String normalizeOptionalUrl(Object value, String label) {
        String url = string(value).trim();
        if (url.isEmpty()) return "";
        if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getHost().isBlank()
                    || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https")))
                throw new IllegalArgumentException();
            return uri.toASCIIString();
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, label + " must be a valid HTTP or HTTPS URL");
        }
    }

    private static String canonicalSkill(String skill) {
        return ALIASES.getOrDefault(skill.toLowerCase(Locale.ROOT), skill);
    }

    private static List<String> targetKeywords(String target) {
        String role = target.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : ROLE_KEYWORDS.entrySet())
            if (role.contains(entry.getKey())) return entry.getValue();
        return List.of();
    }

    private static String rewriteBullet(String bullet) {
        String value = bullet.trim();
        String low = value.toLowerCase(Locale.ROOT);
        for (String weak : List.of("responsible for", "worked on", "helped with", "was involved in", "tasked with")) {
            if (low.startsWith(weak)) { value = value.substring(weak.length()).trim(); break; }
        }
        Map<String, String> gerunds = Map.ofEntries(
                Map.entry("building", "Built"), Map.entry("developing", "Developed"), Map.entry("creating", "Created"),
                Map.entry("designing", "Designed"), Map.entry("managing", "Managed"), Map.entry("writing", "Wrote"),
                Map.entry("automating", "Automated"), Map.entry("optimising", "Optimised"), Map.entry("optimizing", "Optimized"),
                Map.entry("working", "Worked"), Map.entry("implementing", "Implemented"), Map.entry("leading", "Led"),
                Map.entry("maintaining", "Maintained"), Map.entry("testing", "Tested"), Map.entry("deploying", "Deployed"),
                Map.entry("integrating", "Integrated"), Map.entry("migrating", "Migrated"), Map.entry("improving", "Improved"));
        boolean converted = false;
        for (Map.Entry<String, String> entry : gerunds.entrySet()) {
            if (value.toLowerCase(Locale.ROOT).startsWith(entry.getKey() + " ")) {
                value = entry.getValue() + value.substring(entry.getKey().length());
                converted = true;
                break;
            }
        }
        List<String> strong = List.of("Built", "Developed", "Engineered", "Designed", "Implemented", "Optimised",
                "Optimized", "Automated", "Delivered", "Led", "Shipped", "Reduced", "Increased",
                "Improved", "Streamlined", "Launched", "Architected", "Decreased", "Saved",
                "Enhanced", "Achieved", "Scaled", "Migrated", "Secured", "Coordinated");
        if (!converted && !value.isEmpty() && Character.isLowerCase(value.charAt(0))
                && !startsWithAny(value, strong))
            value = Character.toUpperCase(value.charAt(0)) + value.substring(1);
        if (!value.isEmpty() && !startsWithAny(value, strong)) {
            value = "Developed " + Character.toLowerCase(value.charAt(0)) + value.substring(1);
        }
        return value;
    }

    private static boolean startsWithAny(String text, List<String> prefixes) {
        String lower = text.toLowerCase(Locale.ROOT);
        return prefixes.stream().anyMatch(prefix -> lower.startsWith(prefix.toLowerCase(Locale.ROOT)));
    }

    private static String summary(String name, String target, List<String> skills) {
        if (skills.isEmpty()) return name + " is an aspiring " + (target.isEmpty() ? "software professional" : target)
                + " seeking to apply strong fundamentals in a growth-oriented team.";
        String role = target.isEmpty() ? "software professional" : target;
        String top = String.join(", ", skills.subList(0, Math.min(6, skills.size())));
        return role + " skilled in " + top + ". Hands-on experience building, testing and shipping "
                + "software end to end, with a focus on clean code and measurable outcomes. "
                + "Looking to contribute to a product-driven engineering team.";
    }

    private static List<String> suggestions(String target, List<String> skills, List<String> missing,
                                            List<Map<String, Object>> experience) {
        List<String> tips = new ArrayList<>();
        if (!missing.isEmpty()) tips.add("For a '" + (target.isEmpty() ? "software" : target)
                + "' role, your resume will be stronger if you add evidence of: "
                + String.join(", ", missing.subList(0, Math.min(5, missing.size())))
                + ". (Only add what you have used — then take our skill test to verify it.)");
        if (experience.isEmpty()) tips.add("No work experience listed — add at least 2 projects with what YOU built, the stack, and a number (users, % faster, hours saved).");
        for (Map<String, Object> entry : experience) for (String highlight : strings(entry.get("highlights"))) {
            if (highlight.chars().noneMatch(Character::isDigit))
                tips.add("Quantify this bullet: '" + highlight.substring(0, Math.min(60, highlight.length()))
                        + "...' — recruiters respond to numbers.");
        }
        if (skills.size() < 5) tips.add("Very few skills detected. Take the skill tests on this app and add verified skills to your resume.");
        return tips.subList(0, Math.min(6, tips.size()));
    }

    private static String resumeToText(Map<String, Object> resume) {
        Map<String, Object> header = object(resume.get("header"));
        List<String> lines = new ArrayList<>();
        lines.add(string(header.get("name")).toUpperCase(Locale.ROOT));
        List<String> contact = new ArrayList<>();
        if (!string(header.get("email")).isEmpty()) contact.add(string(header.get("email")));
        if (!string(header.get("phone")).isEmpty()) contact.add(string(header.get("phone")));
        if (!string(header.get("linkedin_url")).isEmpty()) contact.add("LinkedIn: " + string(header.get("linkedin_url")));
        if (!string(header.get("github_url")).isEmpty()) contact.add("GitHub: " + string(header.get("github_url")));
        lines.add(String.join(" | ", contact));
        lines.add("");
        if (!string(header.get("target_role")).isEmpty()) {
            lines.add("TARGET ROLE: " + header.get("target_role")); lines.add("");
        }
        lines.add("PROFESSIONAL SUMMARY"); lines.add(string(resume.get("professional_summary"))); lines.add("");
        lines.add("SKILLS");
        Map<String, Object> categorized = object(object(resume.get("skills")).get("categorized"));
        categorized.forEach((category, value) -> lines.add("  " + category + ": " + String.join(", ", strings(value))));
        lines.add("");
        List<Map<String, Object>> experience = maps(resume.get("experience"));
        if (!experience.isEmpty()) {
            lines.add("EXPERIENCE");
            for (Map<String, Object> entry : experience) {
                lines.add("  " + string(entry.get("role")) + " — " + string(entry.get("company"))
                        + " (" + string(entry.get("duration")) + ")");
                for (String highlight : strings(entry.get("highlights"))) lines.add("    • " + highlight);
            }
            lines.add("");
        }
        List<Map<String, Object>> projects = maps(resume.get("projects"));
        if (!projects.isEmpty()) {
            lines.add("PROJECTS");
            for (Map<String, Object> project : projects) {
                lines.add("  " + string(project.get("name")) + ": " + string(project.get("description")));
                if (!string(project.get("url")).isEmpty()) lines.add("    Link: " + string(project.get("url")));
            }
            lines.add("");
        }
        List<Map<String, Object>> education = maps(resume.get("education"));
        if (!education.isEmpty()) {
            lines.add("EDUCATION");
            for (Map<String, Object> item : education)
                lines.add("  " + string(item.get("degree")) + ", " + string(item.get("institute"))
                        + " (" + string(item.get("year")) + ")");
            lines.add("");
        }
        List<Map<String, Object>> assessments = maps(resume.get("verified_assessments"));
        if (!assessments.isEmpty()) {
            lines.add("TECH EXPLORER SKILL ASSESSMENT");
            for (Map<String, Object> assessment : assessments) {
                lines.add("  " + string(assessment.get("skill")) + " — Level "
                        + string(assessment.get("level")) + ", " + string(assessment.get("percent"))
                        + "% (" + string(assessment.get("assessed_at")) + ")");
            }
            lines.add("  In-app assessment record; not a third-party certification.");
            lines.add("");
        }
        return String.join("\n", lines);
    }

    private static byte[] resumeToPdf(Map<String, Object> resume) {
        Map<String, Object> header = object(resume.get("header"));
        List<PdfLine> lines = new ArrayList<>();
        addPdfText(lines, string(header.get("name")).toUpperCase(Locale.ROOT), 24, true, "");
        addPdfText(lines, string(header.get("target_role")), 11, true, "");
        addPdfText(lines, String.join("  |  ", contactParts(header)), 9, false, "");
        if (!string(header.get("linkedin_url")).isEmpty())
            addPdfText(lines, "LinkedIn: " + string(header.get("linkedin_url")), 9, false,
                    string(header.get("linkedin_url")));
        if (!string(header.get("github_url")).isEmpty())
            addPdfText(lines, "GitHub: " + string(header.get("github_url")), 9, false,
                    string(header.get("github_url")));
        addPdfGap(lines, 8);

        addPdfSection(lines, "TECHNICAL SKILLS");
        Map<String, Object> categorized = object(object(resume.get("skills")).get("categorized"));
        for (Map.Entry<String, Object> entry : categorized.entrySet())
            addPdfText(lines, entry.getKey() + ": " + String.join(", ", strings(entry.getValue())), 9, false, "");

        String summary = string(resume.get("professional_summary"));
        if (!summary.isBlank()) {
            addPdfSection(lines, "PROFESSIONAL SUMMARY");
            addPdfText(lines, summary, 9, false, "");
        }

        List<Map<String, Object>> experience = maps(resume.get("experience"));
        if (!experience.isEmpty()) {
            addPdfSection(lines, "PROFESSIONAL EXPERIENCE");
            for (Map<String, Object> entry : experience) {
                String heading = string(entry.get("role"));
                if (!string(entry.get("company")).isBlank()) heading += " | " + string(entry.get("company"));
                if (!string(entry.get("duration")).isBlank()) heading += " (" + string(entry.get("duration")) + ")";
                addPdfText(lines, heading, 9.5f, true, "");
                for (String highlight : strings(entry.get("highlights")))
                    addPdfText(lines, "- " + highlight, 9, false, "");
                addPdfGap(lines, 4);
            }
        }

        List<Map<String, Object>> projects = maps(resume.get("projects"));
        if (!projects.isEmpty()) {
            addPdfSection(lines, "PROJECTS");
            for (Map<String, Object> project : projects) {
                addPdfText(lines, string(project.get("name")), 9.5f, true, "");
                addPdfText(lines, string(project.get("description")), 9, false, "");
                String url = string(project.get("url"));
                if (!url.isEmpty()) addPdfText(lines, "Project: " + url, 8.5f, false, url);
                addPdfGap(lines, 4);
            }
        }

        List<Map<String, Object>> education = maps(resume.get("education"));
        if (!education.isEmpty()) {
            addPdfSection(lines, "EDUCATION");
            for (Map<String, Object> item : education) {
                String degree = string(item.get("degree"));
                if (!string(item.get("year")).isBlank()) degree += " — " + string(item.get("year"));
                addPdfText(lines, degree, 9.5f, true, "");
                addPdfText(lines, string(item.get("institute")), 9, false, "");
            }
        }

        List<Map<String, Object>> assessments = maps(resume.get("verified_assessments"));
        if (!assessments.isEmpty()) {
            addPdfSection(lines, "TECH EXPLORER SKILL ASSESSMENT");
            for (Map<String, Object> assessment : assessments)
                addPdfText(lines, string(assessment.get("skill")) + " — Level "
                        + string(assessment.get("level")) + ", " + string(assessment.get("percent"))
                        + "% (" + string(assessment.get("assessed_at")) + ")", 9, false, "");
            addPdfText(lines, "In-app assessment record; not a third-party certification.", 8, false, "");
        }

        List<PdfPage> pages = layoutPdfPages(lines);
        return encodePdf(pages);
    }

    private static List<String> contactParts(Map<String, Object> header) {
        List<String> parts = new ArrayList<>();
        if (!string(header.get("email")).isBlank()) parts.add(string(header.get("email")));
        if (!string(header.get("phone")).isBlank()) parts.add(string(header.get("phone")));
        return parts;
    }

    private static void addPdfSection(List<PdfLine> lines, String title) {
        addPdfGap(lines, 7);
        lines.add(new PdfLine(asciiPdf(title), 10, true, "", true));
    }

    private static void addPdfGap(List<PdfLine> lines, float points) {
        lines.add(new PdfLine("", points, false, "", false));
    }

    private static void addPdfText(List<PdfLine> lines, String text, float size, boolean bold, String url) {
        String sanitized = asciiPdf(text).replace('\r', ' ').replace('\n', ' ');
        if (sanitized.isBlank()) return;
        StringBuilder current = new StringBuilder();
        for (String word : sanitized.split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (pdfTextWidth(candidate, size, bold) <= 504) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (!current.isEmpty()) {
                lines.add(new PdfLine(current.toString(), size, bold, url, false));
                current.setLength(0);
            }
            String remaining = word;
            while (pdfTextWidth(remaining, size, bold) > 504) {
                int split = Math.max(1, Math.min(remaining.length() - 1, (int) (504 / (size * 0.55f))));
                lines.add(new PdfLine(remaining.substring(0, split), size, bold, url, false));
                remaining = remaining.substring(split);
            }
            current.append(remaining);
        }
        if (!current.isEmpty()) lines.add(new PdfLine(current.toString(), size, bold, url, false));
    }

    private static float pdfTextWidth(String text, float size, boolean bold) {
        float width = 0;
        for (char c : text.toCharArray()) {
            if (c == ' ') width += size * 0.28f;
            else if ("ilI.,:;'!|".indexOf(c) >= 0) width += size * 0.25f;
            else if ("mwMW@%&".indexOf(c) >= 0) width += size * 0.78f;
            else if (Character.isUpperCase(c)) width += size * 0.64f;
            else width += size * (bold ? 0.54f : 0.5f);
        }
        return width;
    }

    private static String asciiPdf(String value) {
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
                .replace("—", "-").replace("–", "-").replace("•", "-").replace("’", "'")
                .replace("“", "\"").replace("”", "\"");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c >= 32 && c <= 126) result.append(c);
            else if (Character.getType(c) != Character.NON_SPACING_MARK) result.append('?');
        }
        return result.toString();
    }

    private static List<PdfPage> layoutPdfPages(List<PdfLine> lines) {
        List<PdfPage> pages = new ArrayList<>();
        PdfPage page = new PdfPage();
        pages.add(page);
        float y = 744;
        for (PdfLine line : lines) {
            float leading = line.text().isEmpty() ? line.size() : line.size() * 1.38f;
            if (y - leading < 46) {
                page = new PdfPage();
                pages.add(page);
                y = 744;
            }
            if (line.rule()) {
                float baseline = y - line.size();
                page.content().append("0.12 0.14 0.20 rg\nBT /F2 ")
                        .append(line.size()).append(" Tf 54 ").append(baseline).append(" Td (")
                        .append(pdfEscape(line.text())).append(") Tj ET\n")
                        .append("0.18 0.22 0.34 RG 0.6 w 54 ")
                        .append(baseline - 3).append(" m 558 ").append(baseline - 3).append(" l S\n");
            } else if (!line.text().isEmpty()) {
                float baseline = y - line.size();
                if (!line.url().isEmpty()) page.annotations().add(new PdfAnnotation(
                        54, baseline - 2, Math.min(558, 54 + pdfTextWidth(line.text(), line.size(), line.bold())),
                        baseline + line.size() + 2, line.url()));
                if (!line.url().isEmpty()) page.content().append("0.08 0.29 0.62 rg\n");
                else page.content().append("0.12 0.14 0.20 rg\n");
                page.content().append("BT /").append(line.bold() ? "F2" : "F1").append(' ')
                        .append(line.size()).append(" Tf 54 ").append(baseline).append(" Td (")
                        .append(pdfEscape(line.text())).append(") Tj ET\n");
                if (!line.url().isEmpty()) page.content().append("0 0 0 rg\n");
            }
            y -= leading;
        }
        return pages;
    }

    private static String pdfEscape(String value) {
        return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private static byte[] encodePdf(List<PdfPage> pages) {
        List<String> objects = new ArrayList<>(List.of("", "", 
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>"));
        List<Integer> pageIds = new ArrayList<>();
        for (PdfPage page : pages) {
            int pageId = objects.size() + 1;
            objects.add("");
            int streamId = objects.size() + 1;
            byte[] content = page.content().toString().getBytes(StandardCharsets.US_ASCII);
            objects.add("<< /Length " + content.length + " >>\nstream\n"
                    + page.content() + "endstream");
            List<Integer> annotationIds = new ArrayList<>();
            for (PdfAnnotation annotation : page.annotations()) {
                int annotationId = objects.size() + 1;
                annotationIds.add(annotationId);
                objects.add("<< /Type /Annot /Subtype /Link /Rect [" + annotation.left() + " "
                        + annotation.bottom() + " " + annotation.right() + " " + annotation.top()
                        + "] /Border [0 0 0] /A << /S /URI /URI ("
                        + pdfEscape(annotation.url()) + ") >> >>");
            }
            String annotations = annotationIds.isEmpty() ? "" : " /Annots ["
                    + annotationIds.stream().map(id -> id + " 0 R").reduce((a, b) -> a + " " + b).orElse("")
                    + "]";
            objects.set(pageId - 1, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]"
                    + " /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents "
                    + streamId + " 0 R" + annotations + " >>");
            pageIds.add(pageId);
        }
        objects.set(0, "<< /Type /Catalog /Pages 2 0 R >>");
        objects.set(1, "<< /Type /Pages /Kids ["
                + pageIds.stream().map(id -> id + " 0 R").reduce((a, b) -> a + " " + b).orElse("")
                + "] /Count " + pages.size() + " >>");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeAscii(output, "%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(output.size());
            writeAscii(output, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xrefOffset = output.size();
        writeAscii(output, "xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int i = 1; i < offsets.size(); i++)
            writeAscii(output, String.format(Locale.ROOT, "%010d 00000 n \n", offsets.get(i)));
        writeAscii(output, "trailer\n<< /Size " + (objects.size() + 1)
                + " /Root 1 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF\n");
        return output.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private record PdfLine(String text, float size, boolean bold, String url, boolean rule) {}
    private record PdfAnnotation(float left, float bottom, float right, float top, String url) {}

    private static final class PdfPage {
        private final StringBuilder content = new StringBuilder();
        private final List<PdfAnnotation> annotations = new ArrayList<>();
        StringBuilder content() { return content; }
        List<PdfAnnotation> annotations() { return annotations; }
    }

    private static Map<String, Double> weights() {
        return WEIGHTS;
    }

    private static double weight(String skill) {
        return weights().getOrDefault(skill, 1.0);
    }

    private static Map<String, Object> fit(List<String> userSkills, List<String> required) {
        Set<String> user = new LinkedHashSet<>(userSkills);
        double total = required.stream().mapToDouble(index::weight).sum();
        if (total == 0) total = 1.0;
        double got = 0;
        List<String> have = new ArrayList<>(), missing = new ArrayList<>();
        for (String skill : required) {
            if (user.contains(skill)) { got += weight(skill); have.add(skill); }
            else missing.add(skill);
        }
        return Map.of("percent", round(got * 100 / total), "have", have, "missing", missing);
    }

    private static Map<String, Object> matchCompanies(List<String> skills, int limit) {
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> company : COMPANIES) {
            List<String> required = strings(company.get("required_skills"));
            Map<String, Object> fit = fit(skills, required);
            double percent = number(fit.get("percent"));
            String verdict = percent >= 60 ? "ready" : percent >= 40 ? "close" : "stretch";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("company", company.get("name"));
            item.put("about", company.get("about"));
            item.put("fit_percent", percent);
            item.put("matched_skills", fit.get("have"));
            item.put("missing_skills", fit.get("missing"));
            item.put("verdict", verdict);
            ranked.add(item);
        }
        ranked.sort(Comparator.comparingDouble((Map<String, Object> item) -> number(item.get("fit_percent"))).reversed());
        List<Map<String, Object>> limited = ranked.subList(0, Math.min(limit, ranked.size()));
        return Map.of("ready_now", filteredVerdict(limited, "ready"), "close", filteredVerdict(limited, "close"),
                "stretch", filteredVerdict(limited, "stretch"), "all", new ArrayList<>(limited),
                "total_companies", ranked.size());
    }

    private static List<Map<String, Object>> filteredVerdict(List<Map<String, Object>> list, String verdict) {
        return list.stream().filter(item -> verdict.equals(item.get("verdict"))).toList();
    }

    private static Map<String, Object> gapAnalysis(List<String> skills, String companyName) {
        Map<String, Object> target = COMPANIES.stream().filter(company ->
                string(company.get("name")).equalsIgnoreCase(companyName)).findFirst()
                .orElseThrow(() -> new ApiException(404, "unknown company '" + companyName + "'"));
        List<String> required = strings(target.get("required_skills"));
        Map<String, Object> match = fit(skills, required);
        double fitPercent = number(match.get("percent"));
        List<String> missing = strings(match.get("missing"));
        List<String> sortedMissing = new ArrayList<>(missing);
        sortedMissing.sort(Comparator.comparingDouble(index::weight).reversed());
        List<Map<String, Object>> roadmap = new ArrayList<>();
        double week = 1;
        for (String skill : sortedMissing) {
            double weeks = WEEK_LENGTH.getOrDefault(skill, 2.0);
            roadmap.add(Map.of("skill", skill, "why", "Required by " + target.get("name") + ".",
                    "resource", RESOURCES.getOrDefault(skill, "https://roadmap.sh/"),
                    "start_week", week, "duration_weeks", weeks,
                    "verify_with_test", "Take the '" + skill + "' test in this app after finishing — target 75%+."));
            week += weeks;
        }
        double totalWeight = Math.max(required.stream().mapToDouble(index::weight).sum(), 1.0);
        double missingWeight = missing.stream().mapToDouble(index::weight).sum();
        double projected = missing.isEmpty() ? fitPercent
                : round(fitPercent + (100 - fitPercent) * (missingWeight / totalWeight) * 0.9);
        List<Map<String, Object>> related = new ArrayList<>();
        Map<String, Object> jobResult = getJobs(string(target.get("name")), 5, false);
        for (Map<String, Object> job : maps(jobResult.get("jobs"))) {
            if (string(job.get("company")).equalsIgnoreCase(string(target.get("name"))) && related.size() < 3)
                related.add(job);
        }
        String message = "You are " + fitPercent + "% ready for " + target.get("name") + " today. "
                + (missing.isEmpty() ? "You already match every listed skill — apply now!"
                : "Finish the " + missing.size() + "-skill roadmap (~" + weekNumber(week - 1)
                + " weeks) and you should be ~" + projected + "% ready.");
        return Map.of("company", target.get("name"), "about", target.get("about"),
                "current_fit_percent", fitPercent, "matched_skills", match.get("have"),
                "missing_skills", missing, "roadmap", roadmap, "total_weeks", weekNumber(week - 1),
                "projected_fit_percent_after_roadmap", projected, "related_jobs", related, "message", message);
    }

    private static Object weekNumber(double value) {
        return value == Math.rint(value) ? (int) value : value;
    }

    private static double round(double number) {
        return Math.round(number * 10.0) / 10.0;
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private static List<Map<String, Object>> availableTests() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String skill : ASSESSMENT_SKILLS) {
            List<Map<String, Object>> levels = new ArrayList<>();
            for (int level = 1; level <= 4; level++) {
                levels.add(Map.of("level", level, "label", levelLabel(level),
                        "tier", levelTier(level), "questions", 100, "interview_style", 40, "practice", 60));
            }
            list.add(Map.of("skill", skill, "questions", 100, "mcq", 100, "code", 0, "levels", levels));
        }
        return list;
    }

    private static Map<String, Object> getTest(String skill) {
        List<Map<String, Object>> questions = testQuestions(skill);
        return publicTest(skill, 0, questions);
    }

    private static Map<String, Object> getTest(String skill, int level) {
        return getTest(skill, level, 100);
    }

    private static Map<String, Object> getTest(String skill, int level, int questionCount) {
        return getTest(skill, level, questionCount, false);
    }

    private static Map<String, Object> getTest(String skill, int level, int questionCount,
                                                boolean interviewPractice) {
        String normalized = normalizeAssessmentSkill(skill);
        List<Map<String, Object>> questions = assessmentQuestions(normalized, level, interviewPractice);
        return publicTest(normalized, level, questions.subList(0, questionCount), interviewPractice);
    }

    private static Map<String, Object> publicTest(String skill, int level, List<Map<String, Object>> questions) {
        return publicTest(skill, level, questions, false);
    }

    private static Map<String, Object> publicTest(String skill, int level, List<Map<String, Object>> questions,
                                                  boolean interviewPractice) {
        List<Map<String, Object>> safe = new ArrayList<>();
        int dsaCount = 0;
        int technologyCount = 0;
        for (int i = 0; i < questions.size(); i++) {
            Map<String, Object> q = questions.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            item.put("kind", q.get("kind"));
            item.put("question", q.get("q"));
            item.put("code", q.getOrDefault("code", null));
            if ("free_text".equals(q.get("response_type"))) item.put("response_type", "free_text");
            else item.put("options", q.get("options"));
            if (q.containsKey("source_type")) item.put("source_type", q.get("source_type"));
            if (q.containsKey("company")) item.put("company", q.get("company"));
            if ("dsa".equals(q.get("source_type"))) dsaCount++;
            if ("technology".equals(q.get("source_type"))) technologyCount++;
            safe.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", skill);
        result.put("total", safe.size());
        result.put("questions", safe);
        if (level > 0) {
            result.put("level", level);
            result.put("level_label", levelLabel(level));
            result.put("company_tier", levelTier(level));
            result.put("interview_style_count", Math.min(safe.size(), 40));
            result.put("practice_count", Math.max(0, safe.size() - 40));
        }
        if (interviewPractice) {
            result.put("dsa_count", dsaCount);
            result.put("technology_count", technologyCount);
        }
        if (level == 4 && interviewPractice) result.put("self_assessment", true);
        return result;
    }

    private static String normalizeAssessmentSkill(String rawSkill) {
        String key = rawSkill.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s._-]+", "");
        return switch (key) {
            case "java" -> "Java";
            case "javascript", "js" -> "JavaScript";
            case "html", "html5" -> "HTML";
            case "css", "css3" -> "CSS";
            case "c" -> "C";
            case "c++", "cpp", "cplusplus" -> "C++";
            case "node", "nodejs", "node.js" -> "Node.js";
            case "python", "python3" -> "Python";
            case "sql", "mysql", "mysq", "my sql" -> "SQL";
            case "aiml", "ai&ml", "aiandml", "artificialintelligence", "aimachinelearning" -> "AI & ML";
            case "cybersecurity", "infosec", "informationsecurity" -> "Cyber Security";
            default -> throw new ApiException(404, "no test available for '" + rawSkill + "'");
        };
    }

    private static String levelLabel(int level) {
        return switch (level) {
            case 1 -> "Easy";
            case 2 -> "Average";
            case 3 -> "Hard";
            case 4 -> "Extreme";
            default -> throw new ApiException(400, "level must be between 1 and 4");
        };
    }

    private static String levelTier(int level) {
        return switch (level) {
            case 1 -> "Foundations & college basics";
            case 2 -> "Tier 3 interview preparation";
            case 3 -> "Tier 2 interview preparation (TCS, Infosys)";
            case 4 -> "Tier 1 interview preparation (Google, Amazon, Microsoft)";
            default -> throw new ApiException(400, "level must be between 1 and 4");
        };
    }

    private static List<Map<String, Object>> assessmentQuestions(String skill, int level) {
        return assessmentQuestions(skill, level, false);
    }

    private static List<Map<String, Object>> assessmentQuestions(String skill, int level,
                                                                  boolean interviewPractice) {
        if (level < 1 || level > 4) throw new ApiException(400, "level must be between 1 and 4");
        List<QuestionConcept> concepts = assessmentConcepts(skill, level);
        if (concepts.size() < 4) throw new ApiException(500, "assessment question bank is incomplete for " + skill);
        List<Map<String, Object>> questions = new ArrayList<>(100);
        String[] interviewStems = {
                "In an original %s interview-style question, which statement about %s is correct?",
                "A hiring panel asks about %s in %s. Which answer is accurate?",
                "Which explanation of %s would you give in a %s technical interview?",
                "For %s, select the correct statement about %s.",
                "A reviewer asks you to explain %s in %s. Which response is correct?",
                "Which statement best demonstrates knowledge of %s in %s?",
                "During %s interview preparation, how should you describe %s?",
                "Choose the technically accurate answer about %s in %s."
        };
        for (int i = 0; i < 40; i++) {
            int conceptIndex = i % concepts.size();
            int variant = i / concepts.size();
            QuestionConcept concept = concepts.get(conceptIndex);
            String stem = interviewStems[variant % interviewStems.length];
            String prompt = String.format(Locale.ROOT, stem,
                    (variant % 2 == 0 ? skill : concept.topic), (variant % 2 == 0 ? concept.topic : skill));
            questions.add(buildAssessmentQuestion(i, prompt, concept, concepts, "interview-style", variant));
        }
        String[] scenarios = {
                "an onboarding feature", "a code review", "a data import", "a production bug",
                "a performance change", "a small team project", "an API update", "an application release",
                "a test failure", "a system migration", "a user-facing improvement", "a technical handoff"
        };
        for (int i = 0; i < 60; i++) {
            int conceptIndex = i % concepts.size();
            int variant = i / concepts.size();
            QuestionConcept concept = concepts.get(conceptIndex);
            String prompt = "Practice " + (i + 1) + ": While working on " + scenarios[variant % scenarios.length]
                    + " in " + skill + ", which statement about " + concept.topic + " is correct?";
            questions.add(buildAssessmentQuestion(40 + i, prompt, concept, concepts, "practice", variant));
        }
        if (interviewPractice) return mixDsaInterviewQuestions(skill, level, questions);
        return questions;
    }

    private static List<Map<String, Object>> mixDsaInterviewQuestions(String skill, int level,
                                                                       List<Map<String, Object>> technologyQuestions) {
        List<QuestionConcept> dsaConcepts = dsaInterviewConcepts(level);
        List<Map<String, Object>> companyQuestions = level == 4
                ? InterviewQuestionBank.levelFourQuestions() : List.of();
        List<Map<String, Object>> mixed = new ArrayList<>(technologyQuestions.size());
        int dsaIndex = 0;
        int technologyIndex = 0;
        for (int i = 0; i < technologyQuestions.size(); i++) {
            boolean useDsa = (3 * (i + 1) + 3) / 4 > (3 * i + 3) / 4;
            if (useDsa) {
                if (dsaIndex < companyQuestions.size()) {
                    mixed.add(new LinkedHashMap<>(companyQuestions.get(dsaIndex++)));
                    continue;
                }
                int variant = dsaIndex / dsaConcepts.size();
                QuestionConcept concept = dsaConcepts.get(dsaIndex % dsaConcepts.size());
                String prompt = "DSA · " + levelLabel(level) + ": Which statement about "
                        + concept.topic + " is correct?";
                mixed.add(buildAssessmentQuestion(i, prompt, concept, dsaConcepts, "dsa", variant));
                dsaIndex++;
            } else {
                Map<String, Object> source = technologyQuestions.get(technologyIndex++);
                Map<String, Object> item = new LinkedHashMap<>(source);
                item.put("source_type", "technology");
                mixed.add(item);
            }
        }
        if (level == 4) {
            for (int i = 0; i < mixed.size(); i++) {
                Map<String, Object> source = mixed.get(i);
                Map<String, Object> item = new LinkedHashMap<>(source);
                if (!Boolean.TRUE.equals(item.get("company_problem"))) {
                    String topic = string(item.get("topic"));
                    item.put("q", "Explain " + topic
                            + " in your own words. Describe how you would apply it and discuss its complexity or trade-offs.");
                    item.put("solution", item.get("explain"));
                }
                item.put("response_type", "free_text");
                item.remove("options");
                item.remove("answer");
                mixed.set(i, item);
            }
        }
        return mixed;
    }

    private static List<QuestionConcept> dsaInterviewConcepts(int level) {
        String[][] facts = switch (level) {
            case 1, 2, 3, 4 -> new String[][]{
                    {"array access|Reading an array element by index takes O(1) time; finding an arbitrary value by scanning takes O(n).",
                            "linear search|Linear search checks elements one by one and works even when the input is unsorted.",
                            "binary search|Binary search takes O(log n) time on a sorted random-access collection.",
                            "stack order|A stack follows last-in, first-out order.",
                            "queue order|A queue follows first-in, first-out order.",
                            "hash set lookup|A hash set provides average O(1) membership checks, though worst-case lookup can be O(n).",
                            "recursion base case|A recursive algorithm needs a base case that stops further recursive calls.",
                            "linked-list head insertion|Inserting a node at the head of a singly linked list takes O(1) time.",
                            "nested loop complexity|Two nested loops each running n times perform O(n^2) iterations.",
                            "two pointers|Two pointers can find a pair in a sorted array in O(n) time by moving inward."},
                    {"prefix sums|A prefix-sum array answers a static range-sum query in O(1) after O(n) preprocessing.",
                            "merge sort|Merge sort runs in O(n log n) time and uses O(n) auxiliary space for arrays.",
                            "breadth-first search|BFS finds shortest paths by edge count in an unweighted graph.",
                            "depth-first search|DFS explores a path until it cannot continue, then backtracks.",
                            "linked-list cycle detection|Floyd's tortoise-and-hare algorithm detects a linked-list cycle in O(n) time and O(1) extra space.",
                            "heap top k|A min-heap of size k can find the k largest values in O(n log k) time.",
                            "balanced binary search tree|Search, insertion, and deletion in a balanced binary search tree take O(log n) time.",
                            "sliding window|A sliding window can process a contiguous range in O(n) when each endpoint advances at most n times.",
                            "stable sorting|A stable sort preserves the relative order of records with equal keys.",
                            "hash collision|A hash collision occurs when distinct keys map to the same table index or hash value."},
                    {"dynamic programming|Dynamic programming reuses solutions to overlapping subproblems.",
                            "DAG topological ordering|A topological ordering exists only for a directed acyclic graph.",
                            "Dijkstra|Dijkstra's algorithm assumes nonnegative edge weights for its usual shortest-path guarantee.",
                            "disjoint set union|Path compression and union by rank make disjoint-set operations nearly constant amortized time.",
                            "trie prefix search|A trie can search a string of length L in O(L) time, independent of the number of stored strings.",
                            "monotonic stack|A monotonic stack can find the next greater element for every array item in O(n) total time.",
                            "backtracking pruning|Pruning a backtracking branch is safe when that branch cannot lead to a valid solution.",
                            "binary search on answer|Binary search on an answer requires a monotonic feasibility predicate.",
                            "quick sort|Quicksort has average O(n log n) time but worst-case O(n^2) time with poor pivots.",
                            "amortized analysis|Amortized analysis bounds the average cost per operation over a sequence, not a probability distribution."},
                    {"Bellman-Ford|Bellman-Ford supports negative edge weights and can detect a reachable negative-weight cycle.",
                            "segment tree|A segment tree supports point updates and range queries in O(log n) time.",
                            "Fenwick tree|A Fenwick tree supports prefix-sum queries and point updates in O(log n) time.",
                            "KMP matching|The Knuth-Morris-Pratt algorithm finds a pattern in O(n + m) time using a prefix table.",
                            "strongly connected components|Every pair of vertices in a strongly connected component is mutually reachable.",
                            "minimum spanning tree|Kruskal's algorithm sorts edges and uses disjoint-set union to avoid cycles.",
                            "binary lifting|Binary lifting answers a tree ancestor query in O(log n) after O(n log n) preprocessing.",
                            "A star heuristic|An admissible A-star heuristic never overestimates the remaining optimal path cost.",
                            "max flow residual graph|A residual graph represents remaining capacity and permits flow to be rerouted through reverse edges.",
                            "dynamic programming state|A correct DP state must preserve enough information to determine future choices and costs."}
            };
            default -> throw new ApiException(400, "level must be between 1 and 4");
        };
        List<QuestionConcept> concepts = new ArrayList<>(facts[level - 1].length);
        for (String fact : facts[level - 1]) {
            String[] parts = fact.split("\\|", 2);
            concepts.add(new QuestionConcept(parts[0], parts[1], "Correct: " + parts[1]));
        }
        return concepts;
    }

    private static boolean isInterviewPractice(Map<String, String> query) {
        String value = query.get("interview_practice");
        if (value == null || value.equalsIgnoreCase("false")) return false;
        if (value.equalsIgnoreCase("true")) return true;
        throw new ApiException(400, "interview_practice must be true or false");
    }

    private static Map<String, Object> buildAssessmentQuestion(int index, String prompt, QuestionConcept correct,
                                                                 List<QuestionConcept> concepts, String sourceType,
                                                                 int variant) {
        List<String> choices = new ArrayList<>();
        choices.add(correct.truth);
        int offset = 1 + Math.floorMod(index + variant, concepts.size() - 1);
        for (int step = 0; step < concepts.size() && choices.size() < 4; step++) {
            QuestionConcept candidate = concepts.get((concepts.indexOf(correct) + offset + step) % concepts.size());
            if (!candidate.truth.equals(correct.truth) && !choices.contains(candidate.truth)) choices.add(candidate.truth);
        }
        while (choices.size() < 4) choices.add("This behavior is not defined by the language.");
        int answer = Math.floorMod(index * 3 + variant, choices.size());
        String correctValue = choices.remove(0);
        choices.add(answer, correctValue);
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("q", prompt);
        question.put("kind", "mcq");
        question.put("options", choices);
        question.put("answer", answer);
        question.put("explain", correct.explanation);
        question.put("source_type", sourceType);
        question.put("topic", correct.topic);
        return question;
    }

    private static List<QuestionConcept> assessmentConcepts(String skill, int level) {
        String[][] facts;
        switch (skill) {
            case "Java" -> facts = new String[][]{
                    {"primitive types|Java has eight primitive types, including int, char, and boolean.",
                            "arrays|Java arrays use zero-based indexes.",
                            "String|Java String objects are immutable.",
                            "entry point|A standard Java application starts in public static void main(String[] args).",
                            "access modifiers|private limits direct member access to its declaring class."},
                    {"encapsulation|Encapsulation keeps state private and exposes intentional methods.",
                            "interfaces|A Java class can implement multiple interfaces.",
                            "overriding|An overriding method must remain compatible with the parent's return and access rules.",
                            "exceptions|A finally block normally executes whether or not a try block throws.",
                            "collections|ArrayList preserves insertion order and allows indexed access."},
                    {"hashCode contract|Equal objects must return the same hash code.",
                            "HashMap|HashMap provides expected constant-time lookup when hashes are well distributed.",
                            "streams|Java streams are lazy until a terminal operation runs.",
                            "checked exceptions|Checked exceptions must be caught or declared.",
                            "synchronization|An instance synchronized method locks the receiver object."},
                    {"volatile|volatile makes writes visible across threads but does not make compound updates atomic.",
                            "deadlock|A circular wait for locks can cause deadlock.",
                            "garbage collection|Java garbage collection does not guarantee when an unreachable object is collected.",
                            "thread pools|ExecutorService can reuse worker threads across submitted tasks.",
                            "ConcurrentHashMap|ConcurrentHashMap supports concurrent access without locking the whole map for every operation."}};
            case "JavaScript" -> facts = new String[][]{
                    {"const|const prevents rebinding a variable, but does not freeze an object it references.",
                            "strict equality|=== compares without JavaScript's implicit type coercion.",
                            "arrays|JavaScript arrays use zero-based indexes.",
                            "undefined|A declared variable without an assigned value evaluates to undefined.",
                            "functions|A function can be stored in a variable and passed as a value."},
                    {"closures|A closure retains access to variables from its lexical outer scope.",
                            "map|Array.prototype.map returns a new array of mapped values.",
                            "event bubbling|Bubbling sends an event from its target toward ancestor elements.",
                            "promises|A fulfilled promise invokes its fulfillment handler in a later microtask.",
                            "spread syntax|Object spread copies enumerable own properties into a new object."},
                    {"event loop|The event loop processes queued callbacks after the current call stack clears.",
                            "microtasks|Promise reactions run before the next timer task in a standard browser event loop.",
                            "prototype chain|Property lookup can continue through an object's prototype chain.",
                            "async functions|An async function always returns a Promise.",
                            "this binding|An arrow function takes this from its surrounding lexical scope."},
                    {"worker threads|Node.js worker threads can execute JavaScript in parallel isolates.",
                            "memory leaks|An object remains reachable if a live reference still points to it.",
                            "weak references|WeakMap keys do not keep their key objects alive by themselves.",
                            "backpressure|Streams use backpressure to prevent a fast producer overwhelming a slower consumer.",
                            "race conditions|Two asynchronous operations can complete in a different order from their start order."}};
            case "HTML" -> facts = new String[][]{
                    {"document structure|A valid HTML document normally declares a doctype and contains html, head, and body elements.",
                            "links|The anchor element uses href to identify its destination.",
                            "images|The img element's alt text provides a text alternative for an image.",
                            "headings|Heading elements represent document structure, not just visual size.",
                            "forms|A label's for value associates it with an input having the matching id."},
                    {"semantic elements|main identifies the dominant content of a page.",
                            "button types|A button inside a form defaults to submit unless its type is specified.",
                            "form validation|required asks the browser to reject an empty required control.",
                            "data attributes|Custom data attributes are exposed on elements through the dataset property.",
                            "tables|A table caption provides a title for the table."},
                    {"accessibility names|An accessible name lets assistive technology identify a control's purpose.",
                            "landmarks|Navigation landmarks help assistive technology move between page regions.",
                            "input modes|input type=email enables email-oriented validation and input behavior.",
                            "deferred scripts|A deferred external script runs after parsing and before DOMContentLoaded.",
                            "responsive images|The picture element can offer alternative image sources for different conditions."},
                    {"ARIA|Native semantic HTML should be preferred over recreating its role with ARIA.",
                            "focus order|Keyboard focus order should follow a meaningful reading and interaction sequence.",
                            "security|Untrusted content must not be inserted as executable markup.",
                            "performance|Explicit image dimensions help browsers reserve layout space before an image loads.",
                            "progressive enhancement|Core content should remain available when optional scripts fail."}};
            case "CSS" -> facts = new String[][]{
                    {"cascade|When specificity is equal, a later applicable declaration wins in the cascade.",
                            "box model|With content-box sizing, declared width excludes padding and borders.",
                            "class selectors|A class selector begins with a period.",
                            "flexbox|display:flex creates a flex formatting context for direct children.",
                            "color|The color property controls an element's text color."},
                    {"grid|CSS Grid can define rows and columns for two-dimensional layouts.",
                            "specificity|An ID selector has greater specificity than a class selector.",
                            "inheritance|Many text-related properties inherit from the parent by default.",
                            "media queries|A media query conditionally applies styles for a matching environment.",
                            "position sticky|A sticky element remains in normal flow until its scroll threshold is reached."},
                    {"stacking context|A child cannot escape its ancestor stacking context by using a larger z-index.",
                            "containing blocks|An absolutely positioned element is positioned relative to its containing block.",
                            "custom properties|CSS custom properties can be read with var().",
                            "container queries|Container queries can adapt styles to a component's container size.",
                            "logical properties|Logical properties adapt to writing direction and block flow."},
                    {"layout containment|Containment can limit layout work to a component boundary.",
                            "cascade layers|Declared cascade layers are ordered before specificity is compared between layers.",
                            "subgrid|Subgrid lets a nested grid use tracks defined by its parent grid.",
                            "reduced motion|prefers-reduced-motion lets a site reduce nonessential animation.",
                            "rendering cost|Changing geometric properties can trigger layout, while transforms often avoid layout."}};
            case "C" -> facts = new String[][]{
                    {"types|C int is an integer type and char stores a character code unit.",
                            "arrays|A C array's first element has index zero.",
                            "functions|A function prototype declares a function's signature before its definition.",
                            "pointers|A pointer stores an address and can be dereferenced to access its target.",
                            "scope|A block-scoped local variable is usable only within its block."},
                    {"const pointers|const int *p prevents modifying the pointed-to int through p.",
                            "structs|A struct groups named members that may have different types.",
                            "dynamic memory|Memory returned by malloc must eventually be released with free.",
                            "strings|A C string is terminated by a null character.",
                            "headers|An include guard prevents a header from being processed repeatedly."},
                    {"undefined behavior|Signed integer overflow in C has undefined behavior.",
                            "pointer arithmetic|Pointer arithmetic advances in units of the pointed-to type.",
                            "ownership|Using a pointer after its allocated object is freed is undefined behavior.",
                            "function pointers|A function pointer can refer to a function with a compatible signature.",
                            "volatile|volatile does not provide inter-thread synchronization."},
                    {"memory ordering|C atomics provide explicit memory-order operations for concurrent access.",
                            "data races|Unsynchronized conflicting accesses to a non-atomic object create a data race.",
                            "aliasing|Strict aliasing rules constrain accessing an object through incompatible pointer types.",
                            "alignment|An object must be stored at an address satisfying its alignment requirement.",
                            "resource safety|Every successful allocation path needs a clear release path."}};
            case "C++" -> facts = new String[][]{
                    {"types|C++ bool represents true or false values.",
                            "references|A reference must be initialized when it is declared.",
                            "classes|A class groups data and member functions.",
                            "constructors|A constructor initializes a new object.",
                            "vectors|std::vector manages a resizable sequence of elements."},
                    {"RAII|RAII ties resource lifetime to object lifetime.",
                            "virtual functions|A virtual function enables dynamic dispatch through a base pointer or reference.",
                            "unique_ptr|std::unique_ptr expresses exclusive ownership of a resource.",
                            "const methods|A const member function cannot modify ordinary non-mutable members.",
                            "templates|A function template can be instantiated for different types."},
                    {"move semantics|Move semantics can transfer resources from an object that is no longer needed.",
                            "rule of five|A resource-owning type may need destructor, copy/move constructors, and copy/move assignments.",
                            "unordered_map|std::unordered_map provides expected constant-time lookup.",
                            "undefined behavior|Accessing an object after its lifetime ends is undefined behavior.",
                            "smart pointers|std::shared_ptr uses shared ownership and releases the object after the last owner."},
                    {"memory model|Conflicting unsynchronized non-atomic accesses across threads create a data race.",
                            "acquire-release|Acquire and release operations can establish synchronization between threads.",
                            "allocator-aware containers|Standard containers can use allocators to customize storage acquisition.",
                            "object slicing|Copying a derived object into a base value can discard derived-only state.",
                            "lock-free progress|Lock-free algorithms must ensure system-wide progress without requiring every thread to finish."}};
            case "Node.js" -> facts = new String[][]{
                    {"runtime|Node.js runs JavaScript outside the browser using the V8 engine.",
                            "modules|CommonJS modules use require and module.exports.",
                            "npm|npm manages JavaScript project packages and scripts.",
                            "callbacks|A callback is a function passed to be called later.",
                            "environment variables|Node.js reads process environment variables from process.env."},
                    {"event loop|Node.js uses an event loop to coordinate asynchronous callbacks.",
                            "streams|Readable streams emit data incrementally instead of requiring one giant buffer.",
                            "promises|A rejected promise should be handled with catch or try/catch around await.",
                            "HTTP server|The built-in http module can create an HTTP server.",
                            "middleware|Express middleware can inspect or change a request before the response."},
                    {"event loop phases|Timer callbacks are scheduled according to their timers phase.",
                            "backpressure|A writable stream returning false signals that the producer should wait for drain.",
                            "worker threads|Worker threads are useful for CPU-intensive JavaScript work.",
                            "process errors|An unhandled rejection should be handled rather than silently ignored.",
                            "cluster|Multiple processes can use separate CPU cores for server workloads."},
                    {"graceful shutdown|A server should stop accepting new work and drain in-flight requests before exit.",
                            "idempotency|An idempotency key can prevent duplicate side effects on retried requests.",
                            "resource limits|Request size and concurrency limits reduce denial-of-service exposure.",
                            "observability|Structured logs should include request context without leaking secrets.",
                            "event loop latency|Long synchronous CPU work blocks progress on the event loop."}};
            case "Python" -> facts = new String[][]{
                    {"basic types|Python has built-in list, tuple, dict, and set collection types.",
                            "indentation|Python indentation defines the grouping of a suite of statements.",
                            "functions|The def keyword defines a named function.",
                            "slicing|A slice such as values[1:] selects items from index one onward.",
                            "dictionaries|A dictionary stores key-to-value mappings."},
                    {"mutable defaults|A mutable default argument is created once when the function is defined.",
                            "comprehensions|A list comprehension creates a list from an iterable expression.",
                            "context managers|A with statement uses a context manager to manage setup and cleanup.",
                            "exceptions|A finally block is intended for cleanup whether or not an exception occurs.",
                            "generators|A generator yields values lazily as it is iterated."},
                    {"GIL|In standard CPython builds, the GIL limits simultaneous execution of Python bytecode by threads.",
                            "asyncio|asyncio is designed for concurrent asynchronous I/O tasks.",
                            "hashability|Dictionary keys must be hashable.",
                            "deep copies|A deep copy recursively copies nested objects when supported.",
                            "decorators|A decorator transforms or wraps a function or class."},
                    {"multiprocessing|Multiprocessing can run CPU-bound Python work in separate processes.",
                            "async cancellation|Async tasks should allow cancellation to propagate after cleanup.",
                            "descriptor protocol|Descriptors customize attribute access through methods such as __get__.",
                            "typing|Type annotations are not enforced at runtime by Python itself.",
                            "resource safety|A context manager can ensure a resource is released after an exception."}};
            case "AI & ML" -> facts = new String[][]{
                    {"supervised learning|Supervised learning trains a model from examples paired with target labels.",
                            "unsupervised learning|Unsupervised learning looks for structure in data without supplied target labels.",
                            "reinforcement learning|Reinforcement learning learns behavior using rewards from interactions with an environment.",
                            "training set|A training set is used to fit a model's parameters.",
                            "test set|A final test set estimates performance on data not used to fit or tune the model."},
                    {"loss function|A loss function quantifies model prediction error for an optimization procedure.",
                            "gradient descent|Gradient descent updates parameters using information from the loss gradient.",
                            "bias and variance|High bias can cause underfitting, while high variance can cause sensitivity to training samples.",
                            "hyperparameter|A hyperparameter is configured outside the model's ordinary parameter-learning process.",
                            "feature scaling|Scaling numeric features can improve optimization for some algorithms."},
                    {"confusion matrix|A confusion matrix summarizes predicted versus actual classes.",
                            "F1 score|The F1 score is the harmonic mean of precision and recall.",
                            "stratified split|A stratified split preserves approximate class proportions across partitions.",
                            "transformer attention|Self-attention lets a transformer representation incorporate information from other tokens.",
                            "tokenization|Tokenization maps text into units a language model can process."},
                    {"model drift|Model drift is a change in model behavior or performance over time that warrants monitoring.",
                            "RAG grounding|Retrieval-augmented generation can ground a response in retrieved documents but does not guarantee correctness.",
                            "AI evaluation|Evaluating a generative model should include task quality, safety, and representative test cases.",
                            "training-serving skew|Training-serving skew occurs when model inputs differ between training and production.",
                            "data privacy|Sensitive training data should be minimized, protected, and handled under applicable privacy rules."}};
            case "Cyber Security" -> facts = new String[][]{
                    {"integrity|Integrity protects information from unauthorized alteration.",
                            "availability|Availability aims to keep systems and data usable when authorized users need them.",
                            "authentication|Authentication verifies the identity of a user or service.",
                            "authorization|Authorization determines what an authenticated identity is allowed to do.",
                            "password storage|Passwords should be stored with a salted, purpose-built password hashing scheme."},
                    {"DNS|DNS translates domain names to records such as IP addresses.",
                            "VPN|A VPN can create an encrypted tunnel but does not make an endpoint inherently trustworthy.",
                            "patch management|Prioritizing exposed, exploitable vulnerabilities helps reduce security risk.",
                            "endpoint protection|Endpoint monitoring can detect suspicious activity on workstations and servers.",
                            "backup recovery|Regularly tested backups help restore data after incidents such as ransomware."},
                    {"secure defaults|Secure defaults should deny access unless a user or service is explicitly permitted.",
                            "input validation|Server-side input validation reduces unexpected values but should complement context-safe output handling.",
                            "security headers|HTTP security headers can enable browser protections when configured for the application.",
                            "secrets management|Credentials should be kept out of source code and rotated if exposed.",
                            "attack surface|Reducing unnecessary services and permissions can reduce an application's attack surface."},
                    {"certificate validation|TLS clients should validate certificate chains and hostnames to authenticate a server.",
                            "containment|Incident containment limits ongoing damage while preserving a path for investigation.",
                            "security control testing|A control should be tested in its deployed context rather than assumed effective from configuration alone.",
                            "supply chain risk|Dependency inventories and updates help teams manage third-party software exposure.",
                            "risk acceptance|Residual risk acceptance should be an explicit decision by an authorized owner."}};
            case "SQL" -> facts = new String[][]{
                    {"SELECT|SELECT retrieves columns or expressions from a query source.",
                            "WHERE|WHERE filters rows before grouping.",
                            "primary keys|A primary key uniquely identifies a row and cannot be NULL.",
                            "NULL|SQL comparisons with NULL evaluate to UNKNOWN; IS NULL tests nullness.",
                            "ORDER BY|ORDER BY sorts the result rows."},
                    {"JOIN|An INNER JOIN returns rows that match the join condition in both inputs.",
                            "GROUP BY|GROUP BY forms groups for aggregate calculations.",
                            "HAVING|HAVING filters groups after grouping.",
                            "foreign keys|A foreign key can enforce referential integrity between tables.",
                            "indexes|An index can speed up selective lookups at the cost of storage and write work."},
                    {"transactions|ACID transactions provide atomicity, consistency, isolation, and durability.",
                            "window functions|A window function computes across related rows without collapsing them into one group row.",
                            "normalization|Normalization can reduce duplicated facts and update anomalies.",
                            "isolation|A transaction isolation level controls which concurrent changes a transaction may observe.",
                            "query plans|EXPLAIN can help inspect how a database plans a query."},
                    {"composite indexes|A composite B-tree index is most directly useful when predicates align with its leading columns.",
                            "deadlocks|A database may abort one transaction to break a lock deadlock.",
                            "serializable isolation|Serializable isolation aims to make concurrent transactions equivalent to a serial order.",
                            "keyset pagination|Keyset pagination can avoid scanning large offsets when ordered keys are indexed.",
                            "sargability|A sargable predicate allows the optimizer to use an index search efficiently."}};
            default -> throw new ApiException(404, "no test available for '" + skill + "'");
        }
        String[][] supplementalFacts = supplementalAssessmentFacts(skill);
        for (int levelIndex = 0; levelIndex < facts.length; levelIndex++) {
            String[] expanded = new String[facts[levelIndex].length + supplementalFacts[levelIndex].length];
            System.arraycopy(facts[levelIndex], 0, expanded, 0, facts[levelIndex].length);
            System.arraycopy(supplementalFacts[levelIndex], 0, expanded, facts[levelIndex].length,
                    supplementalFacts[levelIndex].length);
            facts[levelIndex] = expanded;
        }
        List<QuestionConcept> result = new ArrayList<>();
        for (String fact : facts[level - 1]) {
            String[] parts = fact.split("\\|", 3);
            result.add(new QuestionConcept(parts[0], parts[1], "Correct: " + parts[1]));
        }
        return result;
    }

    private static String[][] supplementalAssessmentFacts(String skill) {
        return switch (skill) {
            case "Java" -> new String[][]{
                    {"class and object|A class defines a type; an object is an instance of that type.",
                            "constructors|A constructor has the class name and no declared return type.",
                            "inheritance|A Java class can extend only one direct superclass.",
                            "equals|String content should generally be compared with equals rather than ==.",
                            "loops|A for loop can repeat a statement while its condition remains true."},
                    {"overloading|Methods can be overloaded by changing their parameter lists.",
                            "abstract classes|An abstract class cannot be instantiated directly.",
                            "static members|A static member belongs to the class rather than a particular instance.",
                            "generics|Java generics provide compile-time type checks for parameterized types.",
                            "try-with-resources|Try-with-resources closes declared AutoCloseable resources automatically."},
                    {"Set|A Set does not permit duplicate elements according to its equality contract.",
                            "Comparator|A Comparator defines an ordering independently from the compared class.",
                            "Optional|Optional represents a value that may be absent and should not normally be used as a field.",
                            "ConcurrentModificationException|Fail-fast collection iterators may throw when the collection is structurally modified unexpectedly.",
                            "type erasure|Most Java generic type parameters are erased from runtime instances."},
                    {"CompletableFuture|CompletableFuture supports composing asynchronous stages without blocking each stage.",
                            "safe publication|Final fields receive special initialization guarantees when an object is constructed correctly.",
                            "virtual threads|Virtual threads are lightweight threads intended to support many blocking tasks.",
                            "lock ordering|Consistent lock acquisition order can prevent circular-wait deadlocks.",
                            "garbage collector tuning|Choosing a collector involves workload-specific latency and throughput trade-offs."}};
            case "JavaScript" -> new String[][]{
                    {"let scope|let is block-scoped, unlike var's function-scoped binding.",
                            "null|typeof null evaluates to the historical string 'object'.",
                            "template literals|Template literals use backticks and can interpolate expressions with ${expression}.",
                            "NaN|NaN is not equal to itself; Number.isNaN provides a direct NaN check.",
                            "truthiness|An empty string is falsy in a JavaScript conditional."},
                    {"filter|Array.prototype.filter creates a new array containing elements that pass its predicate.",
                            "reduce|Array.prototype.reduce combines array elements into an accumulated result.",
                            "destructuring|Destructuring assigns values from arrays or properties from objects to bindings.",
                            "event listener|addEventListener registers a handler without replacing other handlers of the same event type.",
                            "module exports|An ES module uses export and import declarations to share bindings."},
                    {"Promise.all|Promise.all rejects when any input promise rejects, unless rejection is handled before aggregation.",
                            "optional chaining|Optional chaining stops a property access chain when its left side is nullish.",
                            "nullish coalescing|The ?? operator falls back only for null or undefined, unlike ||.",
                            "microtask order|Promise callbacks queued as microtasks run after the current stack and before the next task.",
                            "prototype inheritance|Object.create can create an object with a chosen prototype."},
                    {"AbortController|AbortController can signal cancellation to APIs such as fetch that accept an abort signal.",
                            "structured cloning|structuredClone can copy supported cyclic data structures.",
                            "event delegation|A delegated handler can process descendant events through bubbling at a shared ancestor.",
                            "module strictness|JavaScript modules execute in strict mode by default.",
                            "memory retention|Closures can retain outer-scope objects while the closure remains reachable."}};
            case "HTML" -> new String[][]{
                    {"paragraphs|The p element represents a paragraph of text.",
                            "ordered lists|The ol element represents an ordered list of list items.",
                            "document language|The lang attribute on html identifies the primary language of the document.",
                            "external links|The target attribute can choose where a link destination opens.",
                            "metadata|The title element supplies the document title shown by browsers."},
                    {"fieldset|A fieldset groups related form controls and can have a legend.",
                            "autocomplete|The autocomplete attribute can describe the expected purpose of an input.",
                            "select controls|An option element supplies a selectable choice inside a select control.",
                            "table headers|The th element marks a header cell in a table.",
                            "details disclosure|The details element provides a built-in disclosure widget."},
                    {"srcset|The srcset attribute lets a browser choose among image resources for different display needs.",
                            "dialog|The dialog element represents a dialog that can be opened with its methods.",
                            "autocomplete tokens|Standard autocomplete tokens help browsers identify form field purposes.",
                            "button labels|A button's accessible name should describe the action it performs.",
                            "track captions|The track element can associate text tracks such as captions with media."},
                    {"CSP|Content Security Policy can restrict the sources from which a page may load executable scripts.",
                            "sandboxed iframe|The sandbox attribute applies additional restrictions to embedded content.",
                            "form method|GET form submissions normally encode successful controls in the URL query string.",
                            "HTML parsing|Browsers parse HTML into a DOM tree before scripts can query the resulting nodes.",
                            "native validation|Client-side HTML validation improves usability but does not replace server-side validation."}};
            case "CSS" -> new String[][]{
                    {"padding|Padding is the space between an element's content and its border.",
                            "border|A border is drawn around an element's padding and content box.",
                            "descendant selector|A space between selectors matches a descendant at any depth.",
                            "font size|The font-size property controls the size used to render text.",
                            "display none|display:none removes an element from normal layout."},
                    {"box sizing|With border-box sizing, the declared width includes padding and border.",
                            "flex direction|flex-direction controls the main axis direction in a flex container.",
                            "grid gap|The gap property sets spacing between grid tracks or flex items.",
                            "pseudo classes|A pseudo-class such as :hover selects an element in a particular state.",
                            "overflow|The overflow property controls content that exceeds an element's box."},
                    {"z-index|Within a stacking context, z-index influences the stack level of positioned elements.",
                            "flex shrink|flex-shrink controls how flex items shrink when the flex line has negative free space.",
                            "grid auto placement|CSS Grid auto-placement can place items into available cells.",
                            "specificity tie|When cascade origin, importance, layer, and specificity tie, later source order wins.",
                            "inherit keyword|The inherit keyword explicitly takes a property's computed value from the parent."},
                    {"style recalculation|Changing a class can require style recalculation and may also trigger layout or paint.",
                            "will change|will-change should be used sparingly because it can consume rendering resources.",
                            "cascade important|An important declaration participates in a different priority order from normal declarations.",
                            "scroll snap|Scroll snapping can align scroll positions to configured container and child snap points.",
                            "intrinsic sizing|Intrinsic sizing keywords can size boxes based on their content contributions."}};
            case "C" -> new String[][]{
                    {"operators|The % operator computes an integer remainder for integer operands.",
                            "conditional|The if statement conditionally executes a controlled statement.",
                            "header files|The stdio.h header declares standard input and output functions such as printf.",
                            "return values|A return statement can send a value from a function to its caller.",
                            "enumerations|An enum declares a set of named integer constants."},
                    {"calloc|calloc allocates space for an array and initializes its bytes to zero.",
                            "realloc|realloc may move an allocated block, so its returned pointer must be checked.",
                            "pointer constness|int *const p makes p itself non-reassignable after initialization.",
                            "file streams|fopen returns a FILE pointer or a null pointer when opening fails.",
                            "struct pointers|The arrow operator accesses a member through a pointer to a structure."},
                    {"sequence points|Unsequenced modifications of the same scalar object can cause undefined behavior.",
                            "integer promotions|Integer promotions convert eligible integer types before many arithmetic operations.",
                            "restrict|The restrict qualifier provides an optimization promise about pointer-based access.",
                            "effective type|Effective-type rules constrain how allocated storage may be accessed through typed lvalues.",
                            "signal safety|Only a limited set of operations is safe to perform from an asynchronous signal handler."},
                    {"atomic compare exchange|An atomic compare-exchange operation updates an object only when its observed value matches the expected value.",
                            "happens before|A happens-before relation orders operations for concurrency reasoning.",
                            "memory lifetime|Reading an indeterminate automatic object can produce undefined behavior.",
                            "integer conversion|Converting an out-of-range unsigned value to another unsigned width follows modulo arithmetic.",
                            "ABI boundaries|A public C ABI should avoid exposing compiler-specific C++ object layouts."}};
            case "C++" -> new String[][]{
                    {"namespaces|A namespace groups declarations under a named scope.",
                            "initialization|Brace initialization rejects many narrowing conversions.",
                            "access control|A class's private members are accessible to its member functions and friends.",
                            "overloading|C++ functions can be overloaded when their parameter lists differ.",
                            "const objects|A const object cannot be modified through ordinary non-mutable member operations."},
                    {"override|The override specifier asks the compiler to verify that a virtual function overrides a base function.",
                            "vector reallocation|Growing a vector beyond its capacity can invalidate pointers and iterators to its elements.",
                            "exception safety|A strong exception guarantee leaves program state unchanged if an operation fails.",
                            "unique ownership|std::move enables transferring a movable object's resources.",
                            "enum class|A scoped enum does not implicitly convert to an integer type."},
                    {"perfect forwarding|A forwarding reference with std::forward can preserve an argument's value category.",
                            "optional|std::optional represents an object that may or may not contain a value.",
                            "variant|std::variant stores one value from a fixed set of alternative types.",
                            "iterator invalidation|Container operations can invalidate iterators according to that container's rules.",
                            "constexpr|A constexpr function may be evaluated at compile time when its inputs permit it."},
                    {"atomic ordering|Relaxed atomic operations guarantee atomicity but do not establish inter-thread synchronization by themselves.",
                            "coroutines|A coroutine can suspend and later resume its execution.",
                            "concepts|C++ concepts express compile-time constraints on template arguments.",
                            "shared ownership cycles|A cycle of shared_ptr owners can keep objects alive indefinitely.",
                            "false sharing|False sharing occurs when independent data used by different threads shares a cache line."}};
            case "Node.js" -> new String[][]{
                    {"path module|The path module provides utilities for working with filesystem paths.",
                            "JSON parsing|JSON.parse converts valid JSON text into JavaScript values.",
                            "file reading|The fs module provides APIs for reading and writing files.",
                            "package scripts|A package.json scripts entry can define a command run by npm.",
                            "request response|An HTTP server responds to a request by writing a response and ending it."},
                    {"async filesystem|Node.js offers promise-based filesystem APIs through node:fs/promises.",
                            "error-first callback|The traditional Node.js callback convention passes an error as its first argument.",
                            "stream piping|Piping a readable stream to a writable stream helps coordinate flow and backpressure.",
                            "routing|A request handler should match the request method and path before choosing a response.",
                            "environment config|Configuration secrets should be read from environment or a secrets manager, not committed."},
                    {"event loop blocking|Synchronous CPU-heavy work blocks callbacks from progressing on the same event loop.",
                            "unhandled errors|A rejected async request should be caught and converted to an intentional response.",
                            "process isolation|Each Node.js worker process has its own memory space.",
                            "stream pipeline|The pipeline utility propagates errors and coordinates stream teardown.",
                            "dependency lockfile|A lockfile records dependency versions for repeatable installation."},
                    {"graceful timeout|A shutdown path should enforce a deadline so stuck requests cannot block exit forever.",
                            "request validation|Validate untrusted request data before using it in filesystem or database operations.",
                            "least privilege|A service should run with only the operating-system permissions it needs.",
                            "timing attacks|Constant-time comparison is appropriate for comparing secret authentication tokens.",
                            "load shedding|Rejecting excess work early can protect a service from overload."}};
            case "Python" -> new String[][]{
                    {"comments|A hash character starts a comment outside a string literal.",
                            "boolean values|Python's built-in Boolean values are True and False.",
                            "for loops|A for loop iterates over items supplied by an iterable.",
                            "sets|A set stores unique hashable elements.",
                            "string methods|The lower method returns a lowercased string without changing the original string."},
                    {"enumerate|enumerate yields an index and value while iterating over an iterable.",
                            "zip|zip combines items from iterables and stops when the shortest input is exhausted by default.",
                            "dataclasses|The dataclass decorator can generate common methods for a class.",
                            "pathlib|pathlib provides object-oriented filesystem path operations.",
                            "exception hierarchy|Specific exception handlers should generally precede broader handlers."},
                    {"threading|Threads are often useful for overlapping I/O-bound work in Python.",
                            "async generator|An async generator can yield values during asynchronous iteration.",
                            "default factory|A dataclass default_factory creates a fresh mutable default for each instance.",
                            "pickle|Unpickling data from an untrusted source can execute unsafe behavior.",
                            "module cache|Imported modules are cached in sys.modules during a process."},
                    {"contextvars|Context variables can hold state local to an asynchronous execution context.",
                            "subinterpreters|Subinterpreters isolate interpreter state, though extension compatibility must be considered.",
                            "hash randomization|Hash randomization can vary string and bytes hash values between processes.",
                            "exception chaining|raise ... from ... can preserve the original exception as a cause.",
                            "profiling|A profiler helps identify actual runtime hot spots before optimization."}};
            case "AI & ML" -> new String[][]{
                    {"training data|A machine-learning model learns patterns from training data.",
                            "features|Features are input variables used by a model to make predictions.",
                            "classification|Classification predicts a category such as spam or not spam.",
                            "regression|Regression predicts a numeric value such as a price.",
                            "labels|A label is the target value supplied for a supervised-learning example."},
                    {"overfitting|An overfit model performs well on training data but generalizes poorly to unseen data.",
                            "validation set|A validation set helps compare models or tune parameters without using the final test set.",
                            "precision|Precision is the fraction of predicted positives that are actually positive.",
                            "recall|Recall is the fraction of actual positives that the model identifies.",
                            "normalization|Feature scaling can help optimization when input features have very different ranges."},
                    {"data leakage|Data leakage occurs when training uses information unavailable at prediction time.",
                            "cross validation|Cross-validation estimates generalization by repeating evaluation across data splits.",
                            "class imbalance|Class imbalance can make accuracy misleading when the rare class matters.",
                            "regularization|Regularization penalizes model complexity to reduce overfitting.",
                            "embedding|An embedding represents an item such as a token as a vector in a learned space."},
                    {"distribution shift|A model can lose accuracy when production data distributions differ from training data.",
                            "retrieval augmented generation|RAG retrieves relevant external context and provides it to a language model at generation time.",
                            "calibration|A calibrated classifier's confidence estimates correspond to observed outcome frequencies.",
                            "offline evaluation|Offline evaluation cannot fully replace monitoring behavior after deployment.",
                            "prompt injection|Prompt injection is untrusted input that attempts to override an AI system's instructions."}};
            case "Cyber Security" -> new String[][]{
                    {"confidentiality|Confidentiality limits information access to authorized people and systems.",
                            "multi-factor authentication|Multi-factor authentication requires evidence from at least two different factor categories.",
                            "least privilege|Least privilege grants only the access needed to perform a task.",
                            "hashing|A cryptographic hash is designed to be one-way and is not encryption.",
                            "patching|Applying security updates can remediate known software vulnerabilities."},
                    {"TLS|TLS helps protect data in transit when correctly configured and validated.",
                            "firewall|A firewall applies rules to allow or block network traffic.",
                            "phishing|Phishing uses deceptive messages to trick recipients into unsafe actions.",
                            "incident response|Incident response includes preparation, detection, containment, eradication, and recovery activities.",
                            "logging|Centralized logs help investigators correlate events across systems."},
                    {"SQL injection|Parameterized queries separate SQL instructions from untrusted input values.",
                            "cross-site scripting|Context-aware output encoding helps prevent untrusted text from executing as browser script.",
                            "vulnerability management|Risk-based vulnerability management considers severity, exposure, and available mitigations.",
                            "SIEM|A SIEM correlates security events from multiple data sources to support detection and investigation.",
                            "network segmentation|Segmentation can limit lateral movement by restricting communication between zones."},
                    {"zero trust|Zero-trust design continuously evaluates access rather than trusting a network location alone.",
                            "threat modeling|Threat modeling identifies assets, trust boundaries, threats, and possible mitigations.",
                            "key rotation|Rotating cryptographic keys limits the period of exposure when a key is compromised.",
                            "evidence preservation|Incident responders should preserve relevant evidence with documented handling.",
                            "defense in depth|Defense in depth uses multiple complementary controls so one failure is not the only barrier."}};
            case "SQL" -> new String[][]{
                    {"DISTINCT|SELECT DISTINCT removes duplicate result rows from the selected output.",
                            "COUNT|COUNT(*) counts rows, while COUNT(column) ignores rows where that column is NULL.",
                            "LIKE|LIKE matches text patterns using wildcards such as percent and underscore.",
                            "INSERT|INSERT adds rows to a table.",
                            "DELETE|DELETE removes rows selected by its predicate."},
                    {"LEFT JOIN|A LEFT JOIN preserves every left-side row even when no right-side match exists.",
                            "CASE|CASE expressions return a value chosen by matching conditions.",
                            "COALESCE|COALESCE returns the first non-NULL expression in its argument list.",
                            "UNION|UNION combines result sets and removes duplicates unless UNION ALL is used.",
                            "CTE|A common table expression names a query result for use by a statement."},
                    {"window partition|PARTITION BY divides rows into independent groups for a window function.",
                            "row number|ROW_NUMBER assigns a sequential number to rows within a window ordering.",
                            "rollback|ROLLBACK undoes uncommitted transaction changes where the database supports transactions.",
                            "check constraint|A CHECK constraint restricts values according to a Boolean condition.",
                            "covering index|A covering index contains the data needed by a query without an additional table lookup."},
                    {"write skew|Snapshot isolation can permit write skew unless constraints or stronger coordination prevent it.",
                            "partial index|A partial index stores entries only for rows satisfying its predicate in systems that support it.",
                            "lock escalation|Some database engines can replace many fine-grained locks with a coarser lock.",
                            "plan stability|Statistics changes can cause a cost-based optimizer to choose a different query plan.",
                            "online migration|A safe schema migration often uses compatible expand-and-contract steps."}};
            default -> throw new ApiException(404, "no assessment extension for '" + skill + "'");
        };
    }

    private static final class QuestionConcept {
        final String topic;
        final String truth;
        final String explanation;

        QuestionConcept(String topic, String truth, String explanation) {
            this.topic = topic;
            this.truth = truth;
            this.explanation = explanation;
        }
    }

    private static List<Map<String, Object>> testQuestions(String skill) {
        Object value = TESTS.get(skill);
        if (value == null) throw new ApiException(404, "no test available for '" + skill + "'");
        return maps(value);
    }

    private static Map<String, Object> gradeTest(String skill, List<?> answers) {
        List<Map<String, Object>> questions = testQuestions(skill);
        return gradeQuestions(skill, 0, questions, answers);
    }

    private static Map<String, Object> gradeTest(String skill, int level, List<?> answers) {
        return gradeTest(skill, level, 100, answers);
    }

    private static Map<String, Object> gradeTest(String skill, int level, int questionCount, List<?> answers) {
        return gradeTest(skill, level, questionCount, false, answers);
    }

    private static Map<String, Object> gradeTest(String skill, int level, int questionCount,
                                                  boolean interviewPractice, List<?> answers) {
        return gradeTest(skill, level, questionCount, interviewPractice, answers, List.of());
    }

    private static Map<String, Object> gradeTest(String skill, int level, int questionCount,
                                                  boolean interviewPractice, List<?> answers,
                                                  List<?> responses) {
        String normalized = normalizeAssessmentSkill(skill);
        List<Map<String, Object>> questions = assessmentQuestions(normalized, level, interviewPractice)
                .subList(0, questionCount);
        return gradeQuestions(normalized, level, questions, interviewPractice, answers, responses);
    }

    private static int assessmentQuestionCount(Map<String, String> query) {
        String raw = query.get("question_count");
        if (raw == null) return 100;
        int count;
        try {
            count = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw new ApiException(400, "question_count must be 10, 20, 30, ..., or 100");
        }
        if (count < 10 || count > 100 || count % 10 != 0)
            throw new ApiException(400, "question_count must be 10, 20, 30, ..., or 100");
        return count;
    }

    private static Map<String, Object> gradeQuestions(String skill, int level,
                                                       List<Map<String, Object>> questions, List<?> answers) {
        return gradeQuestions(skill, level, questions, false, answers);
    }

    private static Map<String, Object> gradeQuestions(String skill, int level,
                                                       List<Map<String, Object>> questions,
                                                       boolean interviewPractice, List<?> answers) {
        return gradeQuestions(skill, level, questions, interviewPractice, answers, List.of());
    }

    private static Map<String, Object> gradeQuestions(String skill, int level,
                                                       List<Map<String, Object>> questions,
                                                       boolean interviewPractice, List<?> answers,
                                                       List<?> responses) {
        if (answers.size() != questions.size())
            throw new ApiException(400, "expected " + questions.size() + " answers, got " + answers.size());
        boolean selfAssessment = level == 4 && interviewPractice;
        if (selfAssessment && responses.size() != questions.size())
            throw new ApiException(400, "expected " + questions.size() + " written responses, got " + responses.size());
        int correct = 0;
        int answered = 0;
        int dsaCount = 0;
        int technologyCount = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            Map<String, Object> question = questions.get(i);
            if (selfAssessment) {
                Object response = responses.get(i);
                if (response != null && !(response instanceof String))
                    throw new ApiException(400, "each written response must be text or null");
                String writtenAnswer = response instanceof String text ? text.trim() : "";
                if (writtenAnswer.length() > 20_000)
                    throw new ApiException(400, "each written response must be 20000 characters or fewer");
                if (!writtenAnswer.isEmpty()) answered++;
                if ("dsa".equals(question.get("source_type"))) dsaCount++;
                if ("technology".equals(question.get("source_type"))) technologyCount++;
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("question", question.get("q"));
                detail.put("your_answer", writtenAnswer.isEmpty() ? "(not answered)" : writtenAnswer);
                detail.put("correct", false);
                detail.put("explanation", question.get("explain"));
                detail.put("solution", question.get("solution"));
                if (question.containsKey("source_type")) detail.put("source_type", question.get("source_type"));
                if (question.containsKey("company")) detail.put("company", question.get("company"));
                details.add(detail);
                continue;
            }
            List<String> options = strings(question.get("options"));
            int answer = (int) number(question.get("answer"));
            Object submitted = answers.get(i);
            boolean right = submitted instanceof Number n && n.intValue() == answer
                    && n.doubleValue() == n.intValue();
            if (right) correct++;
            if ("dsa".equals(question.get("source_type"))) dsaCount++;
            if ("technology".equals(question.get("source_type"))) technologyCount++;
            String yourAnswer = submitted instanceof Number n && n.intValue() >= 0 && n.intValue() < options.size()
                    ? options.get(n.intValue()) : "(skipped)";
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("question", question.get("q"));
            detail.put("your_answer", yourAnswer);
            detail.put("correct_answer", options.get(answer));
            detail.put("correct", right);
            detail.put("explanation", question.get("explain"));
            if (question.containsKey("source_type")) detail.put("source_type", question.get("source_type"));
            if (question.containsKey("company")) detail.put("company", question.get("company"));
            if (question.containsKey("solution")) detail.put("solution", question.get("solution"));
            details.add(detail);
        }
        if (selfAssessment) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("skill", skill);
            result.put("total", questions.size());
            result.put("answered", answered);
            result.put("self_assessment", true);
            result.put("advice", "Compare your written approach with each reference solution and assess its correctness, complexity, and edge cases.");
            result.put("per_question", details);
            result.put("level", level);
            result.put("level_label", levelLabel(level));
            result.put("company_tier", levelTier(level));
            result.put("question_source", "original company-topic interview practice");
            result.put("dsa_count", dsaCount);
            result.put("technology_count", technologyCount);
            return result;
        }
        double percent = round(100.0 * correct / questions.size());
        String band, advice;
        if (percent >= 85) { band = "Expert"; advice = "Strong command. Add this skill prominently to your resume and mention it in interviews."; }
        else if (percent >= 70) { band = "Proficient"; advice = "Good level. Review the questions you missed and you're interview-ready."; }
        else if (percent >= 50) { band = "Learning"; advice = "Basics are there. Follow the resource link in your gap roadmap and retake in a week."; }
        else { band = "Beginner"; advice = "Focus on fundamentals first — take the guided resource, practise, then retake the test."; }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", skill);
        result.put("correct", correct);
        result.put("total", questions.size());
        result.put("percent", percent);
        result.put("band", band);
        result.put("advice", advice);
        result.put("per_question", details);
        if (level > 0) {
            result.put("level", level);
            result.put("level_label", levelLabel(level));
            result.put("company_tier", levelTier(level));
            result.put("question_source", "original interview-style and practice questions");
        }
        if (interviewPractice) {
            result.put("dsa_count", dsaCount);
            result.put("technology_count", technologyCount);
        }
        return result;
    }

    private static Map<String, Object> marketSkills() {
        Map<String, Object> jobResponse = getJobs("", 500, true, true);
        List<Map<String, Object>> responseJobs = maps(jobResponse.get("jobs"));
        String source = string(jobResponse.getOrDefault("source", "seed"));
        List<Map<String, Object>> jobs = responseJobs;
        if (source.startsWith("live-") || source.equals("cache")) {
            List<Map<String, Object>> liveJobs = responseJobs.stream()
                    .filter(job -> !string(job.get("source")).equals("seed"))
                    .toList();
            if (!liveJobs.isEmpty()) jobs = liveJobs;
            else source = "seed";
        }
        int fetchedJobs = jobs.size();
        jobs = jobs.stream().filter(index::isTechnologyMarketListing).toList();
        Map<String, List<String>> roleSkills = new LinkedHashMap<>();
        roleSkills.put("Cyber Security", List.of("Cyber Security", "Networking", "Linux",
                "Operating Systems", "Network Security"));
        roleSkills.put("Data Scientist", List.of("Python", "SQL", "Machine Learning", "Statistics"));
        roleSkills.put("Data & Analytics", List.of("SQL", "Python", "Data Analysis"));
        roleSkills.put("AI & ML Engineer", List.of("Python", "Machine Learning", "Generative AI & LLM"));
        roleSkills.put("Frontend Developer", List.of("JavaScript", "HTML", "CSS"));
        roleSkills.put("Cloud Engineer", List.of("AWS", "Azure", "GCP", "Linux", "Docker"));
        roleSkills.put("Backend Developer", List.of("Java", "Python", "SQL"));
        roleSkills.put("Full Stack Engineer", List.of("Java", "Web Development", "JavaScript", "Python", "SQL"));
        Map<String, Integer> roleCounts = new LinkedHashMap<>();
        for (String role : roleSkills.keySet()) roleCounts.put(role, 0);
        String[] marketSkillLabels = {"Machine Learning", "Python", "Java", "Web Development", "C++",
                "System Design", "Cloud Computing", "Cyber Security", "SQL", "Generative AI & LLM"};
        String[] marketSkillExpressions = {
                "\\bmachine[\\s-]+learning\\b|\\bdeep learning\\b|\\bml\\b",
                "\\bpython\\b",
                "\\bjava\\b(?!\\s*script\\b)",
                "\\bhtml\\b|\\bcss\\b|\\bjava\\s*script\\b|\\bjavascript\\b|\\bjs\\b",
                "(?<![a-z0-9])c\\+\\+(?![a-z0-9])|\\bcpp\\b|\\bc plus plus\\b",
                "\\bsystem design\\b|\\bsystems? designer\\b|\\bsystems? architect\\b|\\bsolution architect\\b|\\bhigh[- ]level design\\b|\\blow[- ]level design\\b|\\bdistributed systems\\b",
                "\\bcloud computing\\b|\\bcloud (?:platform|services?|infrastructure)\\b|\\baws\\b|\\bazure\\b|\\bgcp\\b|\\bgoogle cloud\\b|\\bamazon web services\\b",
                "\\bcyber[\\s-]*security\\b|\\bcybersecurity\\b|\\binformation security\\b|\\bnetwork security\\b|\\binfosec\\b|\\bpenetration testing\\b|\\bethical hacking\\b|\\bapplication security\\b",
                "\\bsql\\b|\\bmysql\\b|\\bpostgres(?:ql)?\\b|\\bsqlite\\b|\\bpl/sql\\b|\\bt-sql\\b",
                "\\bgenerative ai\\b|\\bgen ai\\b|\\blarge language models?\\b|\\bllms?\\b|\\bgpt(?:-\\d+(?:\\.\\d+)?)?\\b|\\bprompt engineering\\b|\\bretrieval augmented generation\\b"
        };
        String[] marketSkillColors = {"#f59e0b", "#635bff", "#8b5cf6", "#10b981", "#f43f5e",
                "#14b8a6", "#0284c7", "#a855f7", "#ec4899", "#0ea5e9"};
        List<Pattern> marketSkillPatterns = new ArrayList<>();
        for (String expression : marketSkillExpressions)
            marketSkillPatterns.add(Pattern.compile(expression, Pattern.CASE_INSENSITIVE));
        int[] marketSkillCounts = new int[marketSkillLabels.length];
        for (Map<String, Object> job : jobs) {
            String listingText = String.join(" ", List.of(
                    string(job.get("title")),
                    string(job.get("description")),
                    String.join(" ", strings(job.get("required_skills"))),
                    String.join(" ", strings(job.get("tags")))));
            for (int i = 0; i < marketSkillPatterns.size(); i++)
                if (marketSkillPatterns.get(i).matcher(listingText).find()) marketSkillCounts[i]++;
            for (Map.Entry<String, Pattern> rolePattern : MARKET_ROLE_PATTERNS.entrySet())
                if (matchesMarketRole(rolePattern.getKey(), string(job.get("title")), strings(job.get("tags"))))
                    roleCounts.merge(rolePattern.getKey(), 1, Integer::sum);
        }
        List<Map<String, Object>> topSkills = new ArrayList<>();
        int listingCount = jobs.size();
        int totalSkillMatches = 0;
        for (int count : marketSkillCounts) totalSkillMatches += count;
        final int skillMatchTotal = totalSkillMatches;
        int[] skillPercentTenths = new int[marketSkillCounts.length];
        List<Integer> remainderOrder = new ArrayList<>();
        int assignedTenths = 0;
        for (int i = 0; i < marketSkillCounts.length; i++) {
            remainderOrder.add(i);
            if (skillMatchTotal > 0) {
                int scaledCount = marketSkillCounts[i] * 1000;
                skillPercentTenths[i] = scaledCount / skillMatchTotal;
                assignedTenths += skillPercentTenths[i];
            }
        }
        if (skillMatchTotal > 0) {
            remainderOrder.sort(Comparator.comparingInt(
                    (Integer i) -> marketSkillCounts[i] * 1000 % skillMatchTotal).reversed());
            for (int i = 0; i < 1000 - assignedTenths; i++)
                skillPercentTenths[remainderOrder.get(i)]++;
        }
        for (int i = 0; i < marketSkillLabels.length; i++) {
            int count = marketSkillCounts[i];
            topSkills.add(Map.of("label", marketSkillLabels[i], "count", count,
                    "percent", skillPercentTenths[i] / 10.0,
                    "color", marketSkillColors[i]));
        }
        topSkills.sort(Comparator
                .comparingDouble((Map<String, Object> skill) -> ((Number) skill.get("percent")).doubleValue())
                .reversed()
                .thenComparing(skill -> string(skill.get("label"))));
        List<Map<String, Object>> roles = new ArrayList<>();
        int totalRoleMatches = roleCounts.values().stream().mapToInt(Integer::intValue).sum();
        int[] rolePercentTenths = new int[roleCounts.size()];
        List<Integer> roleRemainderOrder = new ArrayList<>();
        int assignedRoleTenths = 0;
        int roleIndex = 0;
        for (int count : roleCounts.values()) {
            roleRemainderOrder.add(roleIndex);
            if (totalRoleMatches > 0) {
                rolePercentTenths[roleIndex] = count * 1000 / totalRoleMatches;
                assignedRoleTenths += rolePercentTenths[roleIndex];
            }
            roleIndex++;
        }
        if (totalRoleMatches > 0) {
            List<Integer> roleCountsInOrder = new ArrayList<>(roleCounts.values());
            roleRemainderOrder.sort(Comparator.comparingInt(
                    (Integer i) -> roleCountsInOrder.get(i) * 1000 % totalRoleMatches).reversed());
            for (int i = 0; i < 1000 - assignedRoleTenths; i++)
                rolePercentTenths[roleRemainderOrder.get(i)]++;
        }
        roleIndex = 0;
        for (Map.Entry<String, Integer> entry : roleCounts.entrySet()) {
            roles.add(Map.of("title", entry.getKey(), "count", entry.getValue(),
                    "percent", rolePercentTenths[roleIndex++] / 10.0,
                    "skills", roleSkills.getOrDefault(entry.getKey(), List.of())));
        }
        roles.sort(Comparator
                .comparingDouble((Map<String, Object> role) -> ((Number) role.get("percent")).doubleValue())
                .reversed()
                .thenComparing(role -> string(role.get("title"))));
        return Map.of("skills", topSkills, "roles", roles, "total_jobs", jobs.size(),
                "fetched_jobs", fetchedJobs, "requested_jobs", 500,
                "source", source,
                "fetched_at", jobResponse.getOrDefault("fetched_at", System.currentTimeMillis() / 1000.0));
    }

    private static Map<String, Pattern> marketRolePatterns() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        patterns.put("Cyber Security", Pattern.compile(
                "\\bcyber[\\s-]*security\\b|\\binformation[\\s-]+security\\b|\\bsecurity[\\s-]+(?:analyst|engineer|specialist|consultant)\\b|\\bsoc[\\s-]+analyst\\b|\\bsecurity operations center\\b|\\bpenetration[\\s-]+(?:tester|testing)\\b|\\binfosec\\b|\\bnetwork[\\s-]+security\\b",
                Pattern.CASE_INSENSITIVE));
        patterns.put("Data Scientist", Pattern.compile(
                "\\bdata[\\s/-]+scientist\\b|\\bdata[\\s/-]+science\\b|\\bmachine[\\s-]+learning[\\s-]+scientist\\b",
                Pattern.CASE_INSENSITIVE));
        patterns.put("Data & Analytics", Pattern.compile(
                "\\bdata[\\s/-]+analyst\\b|\\banalytics[\\s-]+(?:engineer|analyst)\\b|\\bbusiness[\\s-]+intelligence\\b|\\bbi[\\s-]+(?:analyst|developer|engineer)\\b|\\breporting[\\s-]+analyst\\b|\\binsights[\\s-]+analyst\\b|\\bdata[\\s-]+analytics\\b",
                Pattern.CASE_INSENSITIVE));
        patterns.put("AI & ML Engineer", Pattern.compile(
                "\\bai\\s*/\\s*ml\\b|\\bai[\\s-]+(?:engineer|developer|researcher)\\b|\\bartificial intelligence\\b|\\b(?:machine learning|ml)[\\s-]+(?:engineer|developer|researcher)\\b|\\bllm[\\s-]+engineer\\b|\\bgen[ -]?ai[\\s-]+engineer\\b|\\bnlp[\\s-]+engineer\\b|\\bcomputer vision engineer\\b",
                Pattern.CASE_INSENSITIVE));
        patterns.put("Frontend Developer", Pattern.compile(
                "\\bfront[\\s-]?end\\b|\\bui[\\s-]+(?:developer|engineer)\\b|\\breact[\\s-]+(?:developer|engineer)\\b",
                Pattern.CASE_INSENSITIVE));
        patterns.put("Cloud Engineer", Pattern.compile(
                "\\bcloud[\\s-]+(?:engineer|architect|developer|platform|infrastructure)\\b|\\bdevops(?:[\\s-]+engineer)?\\b|\\bsite reliability engineer\\b|\\bsre\\b|\\b(?:aws|azure|gcp)[\\s-]+(?:engineer|architect)\\b|\\bplatform engineer\\b",
                Pattern.CASE_INSENSITIVE));
        patterns.put("Backend Developer", Pattern.compile(
                "\\bback[\\s-]?end\\b|\\bserver[\\s-]?side[\\s-]+(?:engineer|developer)\\b|\\bapi[\\s-]+(?:engineer|developer)\\b",
                Pattern.CASE_INSENSITIVE));
        patterns.put("Full Stack Engineer", Pattern.compile(
                "\\bfull[\\s-]?stack\\b",
                Pattern.CASE_INSENSITIVE));
        return Collections.unmodifiableMap(patterns);
    }

    static boolean matchesMarketRole(String role, String title, List<String> tags) {
        Pattern pattern = MARKET_ROLE_PATTERNS.get(role);
        return pattern != null && pattern.matcher(title + " " + String.join(" ", tags)).find();
    }

    private static Map<String, Object> getJobs(String query, int limit, boolean liveFirst) {
        return getJobs(query, limit, liveFirst, false);
    }

    private static Map<String, Object> getJobs(String query, int limit, boolean liveFirst, boolean preferAdzuna) {
        List<String> notes = new ArrayList<>();
        if (liveFirst) {
            String cacheQuery = query.trim().toLowerCase(Locale.ROOT);
            long now = System.currentTimeMillis();
            if (!cachedJobs.isEmpty() && now - cacheTime < 600_000
                    && (cachedJobsQuery.isEmpty() || cachedJobsQuery.equals(cacheQuery))) {
                return Map.of("jobs", filterJobs(cachedJobs, query, limit), "source", "cache",
                        "fetched_at", cacheTime / 1000.0);
            }
            if (preferAdzuna) {
                try {
                    List<Map<String, Object>> live = fetchAdzuna(query, limit);
                    if (!live.isEmpty()) {
                        appendSeed(live, query, limit);
                        cacheJobs(live, query);
                        return Map.of("jobs", live, "source", "live-adzuna", "fetched_at", cacheTime / 1000.0);
                    }
                } catch (Exception ex) {
                    notes.add("adzuna: " + ex.getClass().getSimpleName());
                }
                try {
                    List<Map<String, Object>> live = fetchArbeitnow(limit);
                    if (!live.isEmpty()) {
                        cacheJobs(live, query);
                        return Map.of("jobs", live, "source", "live-arbeitnow", "fetched_at", cacheTime / 1000.0);
                    }
                } catch (Exception ex) {
                    notes.add("arbeitnow: " + ex.getClass().getSimpleName());
                }
            }
            try {
                List<Map<String, Object>> live = fetchRemotive(query, limit);
                if (!live.isEmpty()) {
                    appendSeed(live, query, limit);
                    cacheJobs(live, query);
                    return Map.of("jobs", live, "source", "live-remotive", "fetched_at", cacheTime / 1000.0);
                }
            } catch (Exception ex) {
                notes.add("remotive: " + ex.getClass().getSimpleName());
            }
            if (!preferAdzuna) {
                try {
                    List<Map<String, Object>> live = fetchAdzuna(query, limit);
                    if (!live.isEmpty()) {
                        appendSeed(live, query, limit);
                        cacheJobs(live, query);
                        return Map.of("jobs", live, "source", "live-adzuna", "fetched_at", cacheTime / 1000.0);
                    }
                } catch (Exception ex) {
                    notes.add("adzuna: " + ex.getClass().getSimpleName());
                }
            }
            if (!cachedJobs.isEmpty() && System.currentTimeMillis() - cacheTime < 600_000
                    && (cachedJobsQuery.isEmpty() || cachedJobsQuery.equals(cacheQuery))) {
                List<Map<String, Object>> filtered = filterJobs(cachedJobs, query, limit);
                return Map.of("jobs", filtered, "source", "cache", "fetched_at", cacheTime / 1000.0, "notes", notes);
            }
        }
        List<Map<String, Object>> seeds = seedJobs(query, limit);
        return Map.of("jobs", seeds, "source", "seed", "fetched_at", System.currentTimeMillis() / 1000.0,
                "notes", notes.isEmpty() ? List.of("live sources unreachable") : notes);
    }

    private static void cacheJobs(List<Map<String, Object>> jobs, String query) {
        cachedJobs = List.copyOf(jobs);
        cachedJobsQuery = query.trim().toLowerCase(Locale.ROOT);
        cacheTime = System.currentTimeMillis();
    }

    private static void appendSeed(List<Map<String, Object>> jobs, String query, int limit) {
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> job : jobs) seen.add(string(job.get("id")));
        for (Map<String, Object> job : seedJobs(query, limit)) {
            if (seen.add(string(job.get("id")))) jobs.add(job);
            if (jobs.size() >= limit) break;
        }
    }

    private static List<Map<String, Object>> fetchRemotive(String query, int limit) throws Exception {
        String url = "https://remotive.com/api/remote-jobs?limit=" + Math.min(limit, 50)
                + (query.isBlank() ? "" : "&search=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("HTTP " + response.statusCode());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> job : maps(object(Json.parse(response.body())).get("jobs"))) {
            String description = string(job.get("description"));
            List<String> tags = strings(job.get("tags"));
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("id", "remotive-" + job.get("id"));
            normalized.put("title", string(job.get("title")).trim());
            normalized.put("company", string(job.get("company_name")).trim());
            normalized.put("location", job.getOrDefault("candidate_required_location", "Remote"));
            normalized.put("url", job.get("url"));
            normalized.put("type", job.getOrDefault("job_type", "full_time"));
            normalized.put("published", prefix(string(job.get("publication_date")), 10));
            normalized.put("salary", job.getOrDefault("salary", ""));
            normalized.put("tags", tags);
            normalized.put("description", prefix(description, 4000));
            normalized.put("required_skills", extractSkillNames(job.get("title") + " " + String.join(" ", tags) + " " + prefix(description, 2500)));
            normalized.put("source", "remotive");
            result.add(normalized);
        }
        return result;
    }

    private static List<Map<String, Object>> fetchArbeitnow(int limit) throws Exception {
        int pages = Math.min(2, Math.max(1, (limit + 299) / 300));
        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int page = 1; page <= pages; page++) {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("https://www.arbeitnow.com/api/job-board-api?page=" + page))
                    .timeout(Duration.ofSeconds(12)).GET().build();
            requests.add(HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CompletableFuture<HttpResponse<String>> pending : requests) {
            HttpResponse<String> response;
            try {
                response = pending.get(13, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ex;
            } catch (ExecutionException | TimeoutException ex) {
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) continue;
            for (Map<String, Object> job : maps(object(Json.parse(response.body())).get("data"))) {
                String slug = string(job.get("slug"));
                String url = string(job.get("url"));
                String id = slug.isBlank() ? url : slug;
                if (id.isBlank() || !seen.add(id)) continue;
                String title = string(job.get("title")).trim();
                String description = string(job.get("description"));
                List<String> tags = strings(job.get("tags"));
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("id", "arbeitnow-" + id);
                normalized.put("title", title);
                normalized.put("company", string(job.get("company_name")));
                normalized.put("location", string(job.get("location")));
                normalized.put("url", url);
                normalized.put("type", String.join(", ", strings(job.get("job_types"))));
                normalized.put("published", prefix(string(job.get("created_at")), 10));
                normalized.put("salary", "");
                normalized.put("tags", tags);
                normalized.put("description", prefix(description, 4000));
                normalized.put("required_skills", extractSkillNames(
                        title + " " + String.join(" ", tags) + " " + prefix(description, 2500)));
                normalized.put("source", "arbeitnow");
                result.add(normalized);
                if (result.size() >= limit) return result;
            }
        }
        if (result.isEmpty()) throw new IOException("Arbeitnow returned no listings");
        return result;
    }

    private static boolean isTechnologyMarketListing(Map<String, Object> job) {
        String title = string(job.get("title"));
        if (TECH_ROLE_TITLE.matcher(title).find()) return true;
        return strings(job.get("tags")).stream().anyMatch(tag -> TECH_ROLE_TAG.matcher(tag.trim()).matches());
    }

    private static List<Map<String, Object>> fetchAdzuna(String query, int limit) throws Exception {
        String id = System.getenv("ADZUNA_APP_ID"), key = System.getenv("ADZUNA_APP_KEY");
        if (id == null || id.isBlank() || key == null || key.isBlank()) return List.of();
        int pageSize = 50;
        int pageCount = Math.min(10, Math.max(1, (limit + pageSize - 1) / pageSize));
        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int page = 1; page <= pageCount; page++) {
            String url = "https://api.adzuna.com/v1/api/jobs/in/search/" + page + "?app_id="
                    + URLEncoder.encode(id, StandardCharsets.UTF_8) + "&app_key="
                    + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&results_per_page=" + pageSize
                    + (query.isBlank() ? "" : "&what=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
            requests.add(HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CompletableFuture<HttpResponse<String>> pending : requests) {
            HttpResponse<String> response;
            try {
                response = pending.get(9, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ex;
            } catch (ExecutionException | TimeoutException ex) {
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) continue;
            for (Map<String, Object> job : maps(object(Json.parse(response.body())).get("results"))) {
                String idValue = string(job.get("id"));
                if (idValue.isBlank() || !seen.add(idValue)) continue;
                String description = string(job.get("description"));
                Map<String, Object> company = object(job.get("company"));
                Map<String, Object> location = object(job.get("location"));
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("id", "adzuna-" + idValue);
                normalized.put("title", string(job.get("title")).trim());
                normalized.put("company", company.getOrDefault("display_name", "Unknown"));
                normalized.put("location", location.getOrDefault("display_name", ""));
                normalized.put("url", job.get("redirect_url"));
                normalized.put("type", "full_time");
                normalized.put("published", prefix(string(job.get("created")), 10));
                normalized.put("salary", "");
                normalized.put("tags", List.of());
                normalized.put("description", prefix(description, 4000));
                normalized.put("required_skills", extractSkillNames(job.get("title") + " " + prefix(description, 2500)));
                normalized.put("source", "adzuna");
                result.add(normalized);
                if (result.size() >= limit) return result;
            }
        }
        if (result.isEmpty()) throw new IOException("Adzuna returned no listings");
        return result;
    }

    private static String prefix(String text, int length) {
        return text.substring(0, Math.min(length, text.length()));
    }

    private static List<Map<String, Object>> seedJobs(String query, int limit) {
        List<Map<String, Object>> jobs = new ArrayList<>();
        String[] titles = {"Software Engineer", "Senior Software Engineer", "Backend Developer",
                "Frontend Developer", "Full Stack Developer", "Data Analyst", "Machine Learning Engineer",
                "DevOps Engineer", "SDE I", "SDE II", "QA Automation Engineer", "Product Engineer",
                "Cyber Security Analyst", "Security Engineer"};
        String[] locations = {"Bengaluru", "Bengaluru", "Hyderabad", "Pune", "Chennai", "NCR", "Remote (India)", "Mumbai"};
        int i = 0;
        for (Map<String, Object> company : COMPANIES) {
            String name = string(company.get("name"));
            List<String> skills = strings(company.get("required_skills"));
            String blurb = string(company.get("about"));
            String haystack = (name + " " + String.join(" ", skills) + " " + blurb).toLowerCase(Locale.ROOT);
            if (!query.isBlank() && !haystack.contains(query.toLowerCase(Locale.ROOT))) continue;
            int seed = i * 1103515245 + 12345;
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("id", "seed-" + i);
            job.put("title", titles[Math.floorMod(seed, titles.length)]);
            job.put("company", name);
            job.put("location", locations[Math.floorMod(seed >>> 4, locations.length)]);
            job.put("url", null);
            job.put("type", "full_time");
            job.put("published", String.format(Locale.ROOT, "2026-09-%02d", 10 + Math.floorMod(seed >>> 8, 14)));
            job.put("salary", "₹" + (6 + Math.floorMod(seed >>> 12, 40)) + " – ₹"
                    + (46 + Math.floorMod(seed >>> 16, 45)) + " LPA");
            job.put("tags", skills.subList(0, Math.min(4, skills.size())));
            job.put("description", name + ": " + blurb + " Core stack: " + String.join(", ", skills) + ".");
            job.put("required_skills", skills);
            job.put("source", "seed");
            jobs.add(job);
            if (jobs.size() == limit) break;
            i++;
        }
        return jobs;
    }

    private static List<Map<String, Object>> filterJobs(List<Map<String, Object>> jobs, String query, int limit) {
        if (query.isBlank()) return new ArrayList<>(jobs.subList(0, Math.min(limit, jobs.size())));
        String q = query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> result = jobs.stream().filter(job ->
                string(job.get("title")).toLowerCase(Locale.ROOT).contains(q)
                        || string(job.get("company")).toLowerCase(Locale.ROOT).contains(q)
                        || strings(job.get("required_skills")).stream().anyMatch(skill -> skill.toLowerCase(Locale.ROOT).contains(q)))
                .limit(limit).toList();
        return result.isEmpty() ? new ArrayList<>(jobs.subList(0, Math.min(limit, jobs.size()))) : result;
    }

    private static String prefix(String text, int length, boolean ignored) {
        return prefix(text, length);
    }

    private static final class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }

    static final class Json {
        private final String input;
        private int cursor;
        private Json(String input) { this.input = input; }

        static Object parse(String input) {
            Json parser = new Json(input);
            Object value = parser.value();
            parser.space();
            if (parser.cursor != input.length()) throw new IllegalArgumentException("invalid JSON at position " + parser.cursor);
            return value;
        }

        static String stringify(Object value) {
            StringBuilder output = new StringBuilder();
            write(value, output);
            return output.toString();
        }

        private static void write(Object value, StringBuilder output) {
            if (value == null) { output.append("null"); return; }
            if (value instanceof String string) {
                output.append('"');
                for (int i = 0; i < string.length(); i++) {
                    char c = string.charAt(i);
                    switch (c) {
                        case '"' -> output.append("\\\"");
                        case '\\' -> output.append("\\\\");
                        case '\b' -> output.append("\\b");
                        case '\f' -> output.append("\\f");
                        case '\n' -> output.append("\\n");
                        case '\r' -> output.append("\\r");
                        case '\t' -> output.append("\\t");
                        default -> { if (c < 0x20) output.append(String.format("\\u%04x", (int) c)); else output.append(c); }
                    }
                }
                output.append('"');
            } else if (value instanceof Number || value instanceof Boolean) output.append(value);
            else if (value instanceof Map<?, ?> map) {
                output.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) output.append(',');
                    first = false;
                    write(String.valueOf(entry.getKey()), output);
                    output.append(':');
                    write(entry.getValue(), output);
                }
                output.append('}');
            } else if (value instanceof Iterable<?> values) {
                output.append('[');
                boolean first = true;
                for (Object item : values) {
                    if (!first) output.append(',');
                    first = false;
                    write(item, output);
                }
                output.append(']');
            } else write(String.valueOf(value), output);
        }

        private Object value() {
            space();
            if (cursor >= input.length()) throw new IllegalArgumentException("unexpected end of JSON");
            char c = input.charAt(cursor);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (input.startsWith("true", cursor)) { cursor += 4; return true; }
            if (input.startsWith("false", cursor)) { cursor += 5; return false; }
            if (input.startsWith("null", cursor)) { cursor += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            cursor++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (take('}')) return result;
            while (true) {
                space();
                if (cursor >= input.length() || input.charAt(cursor) != '"') throw new IllegalArgumentException("expected JSON object key");
                String key = string();
                space();
                expect(':');
                result.put(key, value());
                space();
                if (take('}')) return result;
                expect(',');
            }
        }

        private List<Object> array() {
            cursor++;
            List<Object> result = new ArrayList<>();
            space();
            if (take(']')) return result;
            while (true) {
                result.add(value());
                space();
                if (take(']')) return result;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (cursor < input.length()) {
                char c = input.charAt(cursor++);
                if (c == '"') return result.toString();
                if (c != '\\') { result.append(c); continue; }
                if (cursor >= input.length()) throw new IllegalArgumentException("invalid JSON escape");
                char escaped = input.charAt(cursor++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (cursor + 4 > input.length()) throw new IllegalArgumentException("invalid Unicode escape");
                        result.append((char) Integer.parseInt(input.substring(cursor, cursor + 4), 16));
                        cursor += 4;
                    }
                    default -> throw new IllegalArgumentException("invalid JSON escape");
                }
            }
            throw new IllegalArgumentException("unterminated JSON string");
        }

        private Object number() {
            int start = cursor;
            if (take('-')) { }
            while (cursor < input.length() && Character.isDigit(input.charAt(cursor))) cursor++;
            boolean decimal = false;
            if (take('.')) {
                decimal = true;
                while (cursor < input.length() && Character.isDigit(input.charAt(cursor))) cursor++;
            }
            if (cursor < input.length() && (input.charAt(cursor) == 'e' || input.charAt(cursor) == 'E')) {
                decimal = true; cursor++;
                if (cursor < input.length() && (input.charAt(cursor) == '+' || input.charAt(cursor) == '-')) cursor++;
                while (cursor < input.length() && Character.isDigit(input.charAt(cursor))) cursor++;
            }
            if (start == cursor) throw new IllegalArgumentException("invalid JSON value at position " + cursor);
            String number = input.substring(start, cursor);
            try { return decimal ? Double.parseDouble(number) : Long.parseLong(number); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("invalid JSON number"); }
        }

        private void space() {
            while (cursor < input.length() && Character.isWhitespace(input.charAt(cursor))) cursor++;
        }

        private boolean take(char expected) {
            if (cursor < input.length() && input.charAt(cursor) == expected) { cursor++; return true; }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw new IllegalArgumentException("expected '" + expected + "' at position " + cursor);
        }
    }
}
