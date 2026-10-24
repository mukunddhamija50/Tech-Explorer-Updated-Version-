import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class ApiSmokeTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String TEST_TOKEN = "skillmatch-smoke-test-session-token";
    private static final String TEST_PASSWORD = "smoke-test-password-123";

    private ApiSmokeTest() {}

    public static void main(String[] args) throws Exception {
        String api = args.length > 0 ? args[0] : "http://127.0.0.1:8000";
        String legacy = args.length > 1 ? args[1] : "http://127.0.0.1:8080";

        require(args.length > 2, "pass a dedicated disposable SQLite path as the third argument");
        Path database = Path.of(args[2]);
        seedAuthenticatedSmokeUser(database);
        testOtpSignup(api);
        String loginCookie = testPasswordLogin(api);
        check(get(api + "/api/health"), 200, "\"ok\":true");
        check(get(api + "/api/runtime"), 200, "\"runtime\":\"java\"");
        check(get(api + "/api/runtime"), 200, "\"resume_pdf\":true");
        check(get(api + "/api/runtime"), 200, "\"auth_sql\":true");
        check(get(api + "/api/runtime"), 200, "\"build_timestamp\":");
        check(getAnonymous(api + "/api/auth/session"), 200, "\"authenticated\":false");
        check(getAnonymous(api + "/api/account/state"), 401, "Please log in");
        check(postAnonymous(api + "/api/resume/generate", "{\"name\":\"Unauthorized\"}"), 401, "Please log in");
        check(get(api + "/api/auth/config"), 200, "\"google_client_id\":");
        check(getWithCookie(api + "/api/account/state", loginCookie), 200, "\"name\":\"Smoke Account\"");
        check(post(api + "/api/account/progress",
                "{\"section\":\"maker\",\"state\":{\"skills\":[\"JavaScript\"],\"draft_skills\":\"JavaScript\"}}"),
                200, "\"ok\":true");
        check(post(api + "/api/account/activity",
                "{\"type\":\"assessment\",\"title\":\"JavaScript - 90%\",\"details\":{\"skill\":\"JavaScript\",\"percent\":90}}"),
                200, "\"ok\":true");
        String accountState = get(api + "/api/account/state");
        check(accountState, 200, "\"last_section\":\"maker\"");
        check(accountState, 200, "\"draft_skills\":\"JavaScript\"");
        check(accountState, 200, "\"JavaScript - 90%\"");
        check(get(api + "/index.html"), 200, "href=\"auth.html?next=skillmatch.html\"");
        String skillMatch = get(api + "/skillmatch.html");
        check(skillMatch, 200, "id=\"skillmatchApp\"");
        check(skillMatch, 200, "Interview Preparation");
        check(skillMatch, 200, "location.protocol === 'file:'");
        check(skillMatch, 200, "Start SkillMatch.bat");
        check(skillMatch, 200, "skillMatchServerBootstrapPending");
        check(skillMatch, 200, "href=\"auth.html?next=skillmatch.html\"");
        check(skillMatch, 200, "href=\"auth.html?next=skillmatch.html#signup\"");
        require(skillMatch.contains("<button data-tab=\"tests\" onclick=\"go('tests')\">🎤 Interview Preparation</button>"),
                "interview preparation has a top-level navigation button");
        require(skillMatch.contains("onclick=\"go('tests')\""), "home screen opens interview preparation");
        String home = get(api + "/index.html");
        require(!home.contains("id=\"detailView\""), "job details are not embedded on the home page");
        require(!home.contains("Python + SQL</span>"), "market chart does not embed sample values");
        require(!home.contains("Python Developer</span>"), "role demand chart does not embed sample values");
        check(home, 200, "class=\"hero-chart\"");
        check(home, 200, "Most Requested Skills");
        check(get(api + "/details.html?type=job&key=Python+Developer"), 200, "id=\"detailContainer\"");
        check(get(api + "/indexr.html"), 404, "Not Found");
        check(get(api + "/styler.css"), 200, "#skillmatchApp");
        check(get(api + "/docs"), 200, "SkillMatch API");
        check(get(api + "/AppData.java"), 404, "Not Found");

        String resume = post(api + "/api/resume/generate",
                "{\"name\":\"Mukund Dhamija\",\"email\":\"mukund@gmail.com\","
                        + "\"phone\":\"+91 9876543210\",\"target_role\":\"Backend Developer\","
                        + "\"skills\":[\"python\",\"java\",\"sql\"],"
                        + "\"experience\":[{\"role\":\"Backend Intern\",\"company\":\"FinEdge\","
                        + "\"duration\":\"2025\",\"highlights\":[\"responsible for building REST APIs used by 3000 users\"]}],"
                        + "\"projects\":[],\"education\":[],\"extra_text\":\"docker and git\"}");
        check(resume, 200, "\"name\":\"Mukund Dhamija\"");
        require(resume.contains("Built REST APIs") && resume.contains("\"Docker\""), "resume processing");
        String javascriptResume = post(api + "/api/resume/generate",
                "{\"name\":\"JavaScript Candidate\",\"skills\":[\"Java Script\",\"node.js\"]}");
        check(javascriptResume, 200, "\"Programming Languages\":[\"JavaScript\"]");
        check(javascriptResume, 200, "\"Backend\":[\"Node.js\"]");
        require(!javascriptResume.contains("\"Soft Skills\":[\"JavaScript\""),
                "JavaScript is categorized as a programming language, not a soft skill");
        byte[] resumePdf = postBytes(api + "/api/resume/generate/pdf",
                "{\"name\":\"Test Candidate\",\"email\":\"test@example.com\","
                        + "\"linkedin_url\":\"linkedin.com/in/test-candidate\","
                        + "\"github_url\":\"https://github.com/test-candidate\","
                        + "\"skills\":[\"Java\"],\"projects\":[{\"name\":\"Demo\","
                        + "\"description\":\"A project\",\"url\":\"github.com/test-candidate/demo\"}]}");
        require(resumePdf.length > 500, "generated resume PDF has content");

        check(post(api + "/api/resume/scan", "{\"text\":\"Python Java SQL Docker and Git skills\"}"),
                200, "\"skill_count\":5");
        check(post(api + "/api/match/companies",
                "{\"skills\":[\"Python\",\"SQL\",\"REST APIs\",\"Docker\",\"Git\",\"Linux\",\"AWS\",\"System Design\",\"Microservices\"]}"),
                200, "\"total_companies\":500");
        check(post(api + "/api/match/gap", "{\"skills\":[\"Python\",\"SQL\"],\"company\":\"Razorpay\"}"),
                200, "\"company\":\"Razorpay\"");

        validateMarketRoleTitleMatching();
        String test = get(api + "/api/tests/Python");
        check(test, 200, "\"total\":8");
        require(!test.contains("\"answer\""), "test answers are not exposed");
        check(post(api + "/api/tests/Python/submit", "{\"answers\":[1,2,1,0,1,1,1,1]}"),
                200, "\"percent\":100.0");
        String marketData = get(api + "/api/market/skills");
        check(marketData, 200, "\"total_jobs\":");
        check(marketData, 200, "\"title\":\"Full Stack Engineer\"");
        check(marketData, 200, "\"title\":\"Cyber Security\"");
        check(marketData, 200, "\"title\":\"AI & ML Engineer\"");
        check(marketData, 200, "\"title\":\"Backend Developer\"");
        check(marketData, 200, "\"title\":\"Cloud Engineer\"");
        check(marketData, 200, "\"title\":\"Frontend Developer\"");
        check(marketData, 200, "\"title\":\"Data Scientist\"");
        require(!marketData.contains("\"title\":\"AI & Machine Learning\""),
                "AI and Machine Learning role uses the requested title");
        require(!marketData.contains("\"title\":\"Backend Development\""),
                "backend role uses the requested title");
        validateMarketSkillDemand(marketData);

        String testsCatalog = get(api + "/api/tests");
        check(testsCatalog, 200, "\"skill\":\"AI & ML\"");
        check(testsCatalog, 200, "\"skill\":\"Cyber Security\"");
        List<String> assessmentSkills = List.of("Java", "JavaScript", "HTML", "CSS", "C", "C++",
                "Node.js", "Python", "SQL", "AI & ML", "Cyber Security");
        for (String skill : assessmentSkills) for (int level = 1; level <= 4; level++) {
            String assessment = get(api + "/api/tests/" + java.net.URLEncoder.encode(skill, StandardCharsets.UTF_8)
                    + "?level=" + level);
            check(assessment, 200, "\"total\":100");
            check(assessment, 200, "\"interview_style_count\":40");
            check(assessment, 200, "\"practice_count\":60");
            require(!assessment.contains("\"answer\""), "level assessment answers are not exposed");
            require(!assessment.contains("\"dsa_count\""), "resume assessments keep their existing question mix");
            if (skill.equals("AI & ML"))
                check(assessment, 200, List.of("training data", "overfitting", "data leakage", "distribution shift").get(level - 1));
            if (skill.equals("Cyber Security"))
                check(assessment, 200, List.of("integrity", "TLS", "SQL injection", "zero trust").get(level - 1));
        }
        String sqlTest = get(api + "/api/tests/SQL?level=2");
        require(sqlTest.equals(get(api + "/api/tests/MySQL?level=2")), "SQL and MySQL use the same assessment");
        String graded = post(api + "/api/tests/Java/submit?level=3",
                "{\"answers\":[" + String.join(",", Collections.nCopies(100, "null")) + "]}");
        check(graded, 200, "\"total\":100");
        check(graded, 200, "\"level_label\":\"Hard\"");
        String shortAssessment = get(api + "/api/tests/Java?level=2&question_count=10");
        check(shortAssessment, 200, "\"total\":10");
        check(shortAssessment, 200, "\"interview_style_count\":10");
        String shortGraded = post(api + "/api/tests/Java/submit?level=2&question_count=10",
                "{\"answers\":[" + String.join(",", Collections.nCopies(10, "null")) + "]}");
        check(shortGraded, 200, "\"total\":10");
        check(get(api + "/api/tests/Java?level=2&question_count=15"), 400,
                "question_count must be 10, 20, 30, ..., or 100");
        String practiceAssessment = get(api + "/api/tests/Java?level=4&question_count=20");
        check(practiceAssessment, 200, "\"total\":20");
        require(!practiceAssessment.contains("\"solution\":"),
                "worked solutions stay hidden until a Level 4 practice set is submitted");
        String practiceGraded = post(api + "/api/tests/Java/submit?level=4&question_count=20",
                "{\"answers\":[" + String.join(",", Collections.nCopies(20, "null")) + "]}");
        check(practiceGraded, 200, "\"total\":20");
        String companyInterview = get(api
                + "/api/tests/Java?level=4&question_count=20&interview_practice=true");
        check(companyInterview, 200, "\"dsa_count\":15");
        check(companyInterview, 200, "\"self_assessment\":true");
        check(companyInterview, 200, "\"response_type\":\"free_text\"");
        require(!companyInterview.contains("\"options\""),
                "Level 4 interview practice questions do not expose multiple-choice options");
        for (String company : List.of("Amazon", "Swiggy", "Blinkit", "Flipkart", "PhonePe",
                "Paytm", "JPMorgan Chase", "NVIDIA", "Google")) {
            check(companyInterview, 200, company + " interview practice");
        }
        require(!companyInterview.contains("\"solution\":"),
                "company practice solutions are not revealed before submission");
        List<Object> practiceResponses = new java.util.ArrayList<>(Collections.nCopies(20, null));
        practiceResponses.set(0, "Use two pointers and track maximum walls.");
        practiceResponses.set(1, "Dynamic programming recurrence");
        String companyReview = post(api
                        + "/api/tests/Java/submit?level=4&question_count=20&interview_practice=true",
                index.Json.stringify(Map.of("answers", Collections.nCopies(20, null),
                        "responses", practiceResponses)));
        check(companyReview, 200, "\"solution\":\"");
        check(companyReview, 200, "\"company\":\"Amazon\"");
        check(companyReview, 200, "Complexity:");
        check(companyReview, 200, "\"self_assessment\":true");
        check(companyReview, 200, "\"answered\":2");
        check(companyReview, 200, "\"your_answer\":\"Use two pointers and track maximum walls.\"");
        require(!companyReview.contains("\"percent\":"),
                "open-ended interview practice is not incorrectly given an MCQ score");
        String lowerLevelInterview = get(api
                + "/api/tests/Java?level=3&question_count=20&interview_practice=true");
        require(!lowerLevelInterview.contains(" interview practice — "),
                "company question bank is restricted to Level 4 interview practice");
        List<String> dsaTopics = List.of("array access", "prefix sums", "dynamic programming", "Bellman-Ford");
        for (int level = 1; level <= 4; level++) {
            for (int questionCount = 20; questionCount <= 100; questionCount += 10) {
                int dsaCount = (3 * questionCount + 3) / 4;
                String interview = get(api + "/api/tests/Java?level=" + level + "&question_count="
                        + questionCount + "&interview_practice=true");
                check(interview, 200, "\"dsa_count\":" + dsaCount);
                check(interview, 200, "\"technology_count\":" + (questionCount - dsaCount));
                check(interview, 200, "\"source_type\":\"dsa\"");
                check(interview, 200, "\"source_type\":\"technology\"");
                if (level < 4 || questionCount >= 30)
                    check(interview, 200, dsaTopics.get(level - 1));
            }
            String gradedInterview = post(api + "/api/tests/Java/submit?level=" + level
                            + "&question_count=20&interview_practice=true",
                    "{\"answers\":[" + String.join(",", Collections.nCopies(20, "null")) + "]"
                            + (level == 4
                                    ? ",\"responses\":[" + String.join(",", Collections.nCopies(20, "null")) + "]"
                                    : "") + "}");
            check(gradedInterview, 200, "\"dsa_count\":15");
            check(gradedInterview, 200, "\"technology_count\":5");
        }

        String linkScan = post(api + "/api/resume/scan",
                "{\"text\":\"Java developer\\nGitHub: https://github.com/example\\nLinkedIn: linkedin.com/in/example\"}");
        check(linkScan, 200, "\"platform\":\"GitHub\"");
        check(linkScan, 200, "\"platform\":\"LinkedIn\"");
        String assessedResume = post(api + "/api/resume/generate",
                "{\"name\":\"Test Candidate\",\"target_role\":\"Backend Developer\",\"skills\":[\"Java\"],"
                        + "\"verified_assessments\":[{\"skill\":\"Java\",\"level\":3,\"level_label\":\"Hard\","
                        + "\"percent\":82,\"assessed_at\":\"2026-01-01\",\"passed\":true}]}");
        check(assessedResume, 200, "\"verified_assessments\"");
        check(assessedResume, 200, "TECH EXPLORER SKILL ASSESSMENT");
        check(get(api + "/api/jobs?limit=5&live=false"), 200, "\"source\":\"seed\"");

        check(get(legacy + "/api/skills"), 200, "Python + SQL");
        check(get(legacy + "/api/jobs"), 200, "Full Stack Developer");
        check(get(legacy + "/api/programs"), 200, "Cloud Engineering with AWS & Azure");
        String legacyMarketData = get(legacy + "/api/market/skills");
        check(legacyMarketData, 200, "\"title\":\"Cyber Security\"");
        check(legacyMarketData, 200, "\"total_jobs\":");
        check(post(api + "/api/auth/logout", "{}"), 200, "\"ok\":true");
        check(getAnonymous(api + "/api/account/state"), 401, "Please log in");
        System.out.println("All Java API smoke tests passed.");
    }

    private static void validateMarketSkillDemand(String response) {
        int bodyStart = response.indexOf('\n') + 1;
        Map<String, Object> data = index.object(index.Json.parse(response.substring(bodyStart)));
        int totalJobs = ((Number) data.get("total_jobs")).intValue();
        int fetchedJobs = ((Number) data.get("fetched_jobs")).intValue();
        require(((Number) data.get("requested_jobs")).intValue() == 500,
                "market API requests a 500-listing sample");
        require(fetchedJobs >= totalJobs, "technology listing count does not exceed fetched listings");
        Object rawSkills = data.get("skills");
        require(rawSkills instanceof List<?>, "market API returns a skill list");
        List<?> skills = (List<?>) rawSkills;
        List<String> expected = List.of("Machine Learning", "Python", "Java", "Web Development", "C++",
                "System Design", "Cloud Computing", "Cyber Security", "SQL", "Generative AI & LLM");
        require(skills.size() == expected.size(), "market API returns the 10 requested skill groups");
        List<String> actualSkills = skills.stream()
                .map(value -> (String) index.object(value).get("label"))
                .sorted()
                .toList();
        require(actualSkills.equals(expected.stream().sorted().toList()),
                "market API returns every supported skill category");
        int totalSkillMatches = 0;
        double totalPercent = 0;
        double previousSkillPercent = Double.POSITIVE_INFINITY;
        for (Object value : skills) {
            Map<String, Object> skill = index.object(value);
            int count = ((Number) skill.get("count")).intValue();
            double percent = ((Number) skill.get("percent")).doubleValue();
            totalSkillMatches += count;
            totalPercent += percent;
            require(percent <= previousSkillPercent, "skill percentages are in descending order");
            require(count >= 0 && count <= totalJobs, skill.get("label") + " count is within the listing sample");
            previousSkillPercent = percent;
        }
        require(Math.abs(totalPercent - (totalSkillMatches == 0 ? 0.0 : 100.0)) < 0.001,
                "skill percentage shares sum to 100 when there are matched skill listings");
        for (Object value : skills) {
            Map<String, Object> skill = index.object(value);
            int count = ((Number) skill.get("count")).intValue();
            double percent = ((Number) skill.get("percent")).doubleValue();
            double exactShare = totalSkillMatches == 0 ? 0.0 : 100.0 * count / totalSkillMatches;
            require(Math.abs(percent - exactShare) <= 0.1,
                    skill.get("label") + " chart share matches its proportion of tracked skill listings");
        }
        Object rawRoles = data.get("roles");
        require(rawRoles instanceof List<?>, "market API returns role demand");
        List<?> roles = (List<?>) rawRoles;
        List<String> expectedRoles = List.of("Cyber Security", "Data Scientist", "Data & Analytics",
                "AI & ML Engineer", "Frontend Developer", "Cloud Engineer", "Backend Developer",
                "Full Stack Engineer");
        require(roles.size() == expectedRoles.size(), "market API returns all supported role categories");
        List<String> actualRoles = roles.stream()
                .map(value -> (String) index.object(value).get("title"))
                .sorted()
                .toList();
        require(actualRoles.equals(expectedRoles.stream().sorted().toList()),
                "market API returns every supported role category");
        int totalRoleMatches = 0;
        for (Object value : roles)
            totalRoleMatches += ((Number) index.object(value).get("count")).intValue();
        double totalRolePercent = 0;
        double previousRolePercent = Double.POSITIVE_INFINITY;
        for (int i = 0; i < roles.size(); i++) {
            Map<String, Object> role = index.object(roles.get(i));
            int count = ((Number) role.get("count")).intValue();
            double percent = ((Number) role.get("percent")).doubleValue();
            double expectedPercent = totalRoleMatches == 0 ? 0.0
                    : Math.round(count * 1000.0 / totalRoleMatches) / 10.0;
            totalRolePercent += percent;
            require(percent <= previousRolePercent, "role percentages are in descending order");
            require(Math.abs(percent - expectedPercent) <= 0.1,
                    role.get("title") + " share uses the total matched role count");
            require(count >= 0 && count <= totalJobs, role.get("title") + " listing count is in range");
            previousRolePercent = percent;
        }
        require(Math.abs(totalRolePercent - (totalRoleMatches == 0 ? 0.0 : 100.0)) < 0.001,
                "role percentage shares sum to 100 when there are matched job roles");
    }

    private static void validateMarketRoleTitleMatching() {
        Map<String, String> examples = Map.of(
                "Cyber Security", "Cybersecurity Analyst",
                "Data Scientist", "Senior Data-Scientist",
                "Data & Analytics", "Business Intelligence Engineer",
                "AI & ML Engineer", "AI/ML Developer",
                "Frontend Developer", "Front-End Developer",
                "Cloud Engineer", "Site Reliability Engineer",
                "Backend Developer", "API Developer",
                "Full Stack Engineer", "Full-Stack Engineer");
        for (Map.Entry<String, String> example : examples.entrySet()) {
            require(index.matchesMarketRole(example.getKey(), example.getValue(), List.of()),
                    example.getKey() + " matches common job-title variants");
        }
    }

    private static long seedAuthenticatedSmokeUser(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM users WHERE identifier='email:smoke@example.test'")) {
                delete.executeUpdate();
            }
            long userId;
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO users(identifier,email,display_name,password_salt,password_hash,created_at)
                    VALUES('email:smoke@example.test','smoke@example.test','Smoke Account',?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                byte[] salt = new byte[16];
                new SecureRandom().nextBytes(salt);
                insert.setString(1, Base64.getEncoder().encodeToString(salt));
                insert.setString(2, testPasswordHash(TEST_PASSWORD, salt));
                insert.setLong(3, System.currentTimeMillis());
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    require(keys.next(), "smoke user was inserted into SQL storage");
                    userId = keys.getLong(1);
                }
            }
            String tokenHash = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(TEST_TOKEN.getBytes(StandardCharsets.UTF_8)));
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO sessions(token_hash,user_id,expires_at,created_at) VALUES(?,?,?,?)
                    """)) {
                long now = System.currentTimeMillis();
                insert.setString(1, tokenHash);
                insert.setLong(2, userId);
                insert.setLong(3, now + 60_000);
                insert.setLong(4, now);
                insert.executeUpdate();
            }
            return userId;
        }
    }

    private static void testOtpSignup(String api) throws Exception {
        HttpResponse<String> otpRequest = postAnonymousResponse(api + "/api/auth/otp/request",
                "{\"identifier\":\"signup-smoke@example.test\",\"purpose\":\"signup\"}");
        require(otpRequest.statusCode() == 200, "local OTP request succeeds without an email provider");
        Object parsedOtpRequest = index.Json.parse(otpRequest.body());
        Object codeValue = index.object(parsedOtpRequest).get("development_code");
        require(codeValue instanceof String && ((String) codeValue).matches("\\d{6}"),
                "local development OTP is returned as a six-digit code");
        String code = (String) codeValue;
        HttpResponse<String> verification = postAnonymousResponse(api + "/api/auth/otp/verify",
                "{\"identifier\":\"signup-smoke@example.test\",\"code\":\"" + code + "\"}");
        require(verification.statusCode() == 200, "OTP verification succeeds");
        String verificationToken = String.valueOf(index.object(index.Json.parse(verification.body()))
                .get("verification_token"));
        HttpResponse<String> signup = postAnonymousResponse(api + "/api/auth/signup/complete",
                "{\"identifier\":\"signup-smoke@example.test\",\"verification_token\":\""
                        + verificationToken + "\",\"name\":\"Signup Smoke\",\"password\":\""
                        + TEST_PASSWORD + "\"}");
        require(signup.statusCode() == 200, "verified OTP creates a SQL account");
        require(!signup.body().contains("access_token"), "session token is not exposed in JSON");
        String cookie = signup.headers().firstValue("Set-Cookie").orElse("");
        require(cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax"),
                "signup session uses a protected same-site cookie");
        check(getWithCookie(api + "/api/account/state", cookieValue(cookie)), 200, "\"Signup Smoke\"");
    }

    private static String testPasswordLogin(String api) throws Exception {
        HttpResponse<String> response = postAnonymousResponse(api + "/api/auth/login",
                "{\"identifier\":\"smoke@example.test\",\"password\":\"" + TEST_PASSWORD + "\"}");
        require(response.statusCode() == 200, "password login succeeds");
        require(!response.body().contains("access_token"), "login session token is not exposed in JSON");
        String cookie = response.headers().firstValue("Set-Cookie").orElse("");
        require(cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax"),
                "login session uses a protected same-site cookie");
        check(getWithCookie(api + "/api/account/state", cookieValue(cookie)), 200, "\"Smoke Account\"");
        return TEST_TOKEN;
    }

    private static String testPasswordHash(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 210_000, 256);
        try {
            return Base64.getEncoder().encodeToString(
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } finally {
            spec.clearPassword();
        }
    }

    private static String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + TEST_TOKEN).GET().build();
        return send(request);
    }

    private static String post(String url, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        return send(request);
    }

    private static String getAnonymous(String url) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).GET().build());
    }

    private static String getWithCookie(String url, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Cookie", "skillmatch_session=" + token).GET().build());
    }

    private static String postAnonymous(String url, String json) throws Exception {
        return responseText(postAnonymousResponse(url, json));
    }

    private static HttpResponse<String> postAnonymousResponse(String url, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String responseText(HttpResponse<String> response) {
        return response.statusCode() + "\n" + response.body();
    }

    private static String cookieValue(String setCookie) {
        String value = setCookie.split(";", 2)[0];
        int separator = value.indexOf('=');
        return separator < 0 ? "" : value.substring(separator + 1);
    }

    private static byte[] postBytes(String url, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        require(response.statusCode() == 200, "expected PDF HTTP 200, got " + response.statusCode());
        require(response.headers().firstValue("Content-Type").orElse("").startsWith("application/pdf"),
                "resume download uses PDF content type");
        require(response.headers().firstValue("Content-Disposition").orElse("").contains("attachment"),
                "resume PDF is downloaded as an attachment");
        byte[] data = response.body();
        require(new String(data, 0, Math.min(8, data.length), StandardCharsets.US_ASCII).startsWith("%PDF-1."),
                "resume download begins with a PDF signature");
        String structure = new String(data, StandardCharsets.ISO_8859_1);
        require(structure.contains("/Subtype /Link")
                        && structure.contains("https://linkedin.com/in/test-candidate")
                        && structure.contains("https://github.com/test-candidate")
                        && structure.contains("https://github.com/test-candidate/demo"),
                "PDF contains clickable LinkedIn, GitHub, and project links");
        return data;
    }

    private static String send(HttpRequest request) throws Exception {
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() + "\n" + response.body();
    }

    private static void check(String response, int status, String expected) {
        String statusLine = status + "\n";
        require(response.startsWith(statusLine), "expected HTTP " + status + ", got: " + response.lines().findFirst().orElse(""));
        require(response.contains(expected), "response did not contain: " + expected);
    }

    private static void require(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
    }
}
