package com.getgrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GetGrade {

    private static final String BASE_URL = "https://intranet.tam.ch/bmz";
    private static final String LOGIN_URL = BASE_URL + "/";
    private static final String GRADEBOOK_URL = BASE_URL + "/default/gradebook/index";
    private static final String API_PERIODS = BASE_URL + "/gradebook/ajax-get-periods";
    private static final String API_COURSES = BASE_URL + "/gradebook/ajax-get-courses-for-period";
    private static final String API_GRADES = BASE_URL + "/gradebook/ajax-list-get-grades";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final Map<String, String> cookies = new HashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Properties cfg = loadConfig();
        String user = required(cfg, "tam.user");
        String pass = required(cfg, "tam.password");
        String csvPath = cfg.getProperty("output.csv", "grades.csv");
        String jsonPath = cfg.getProperty("output.json", "grades.json");

        GetGrade app = new GetGrade();
        app.login(user, pass);
        String studentId = app.fetchStudentId();
        System.out.println("    studentId = " + studentId);

        JsonNode periods = app.getJson(API_PERIODS, null);
        ArrayNode periodData = (ArrayNode) periods.path("data");
        System.out.println("    " + periodData.size() + " Semester gefunden.");

        List<Grade> allGrades = new ArrayList<>();
        for (JsonNode period : periodData) {
            String periodId = period.path("periodId").asText();
            String periodName = period.path("periodName").asText();
            System.out.println("[Semester] " + periodName + " (id=" + periodId + ")");

            Map<String, String> courseParams = new LinkedHashMap<>();
            courseParams.put("periodId", periodId);
            courseParams.put("view", "list");
            JsonNode courses = app.getJson(API_COURSES, courseParams);
            ArrayNode courseData = (ArrayNode) courses.path("data");

            for (JsonNode course : courseData) {
                String courseId = course.path("courseId").asText();
                String courseName = course.path("courseName").asText();
                Map<String, String> gParams = new LinkedHashMap<>();
                gParams.put("studentId", studentId);
                gParams.put("courseId", courseId);
                gParams.put("periodId", periodId);
                JsonNode gradesResp = app.getJson(API_GRADES, gParams);
                ArrayNode gradeData = (ArrayNode) gradesResp.path("data");
                System.out.println("  " + courseName + " — " + gradeData.size() + " Noten");
                for (JsonNode g : gradeData) {
                    Grade row = new Grade();
                    row.period = periodName;
                    row.subject = courseName;
                    row.title = textOrNull(g, "title");
                    row.value = textOrNull(g, "value");
                    row.date = formatDate(textOrNull(g, "date"));
                    row.weight = textOrNull(g, "gradeWeight");
                    row.stroked = "1".equals(textOrNull(g, "isGradeStroked"));
                    allGrades.add(row);
                }
            }
        }

        System.out.println("Schreibe " + csvPath + " und " + jsonPath + " (" + allGrades.size() + " Noten)...");
        writeCsv(allGrades, csvPath);
        app.writeJson(allGrades, jsonPath);
        System.out.println("Fertig.");
    }

    private void login(String user, String pass) throws IOException {
        System.out.println("[1/3] Login-Seite holen...");
        Connection.Response loginPage = Jsoup.connect(LOGIN_URL)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "de-CH,de;q=0.9,en;q=0.8")
                .maxBodySize(0)
                .method(Connection.Method.GET)
                .execute();
        cookies.putAll(loginPage.cookies());
        Document loginDoc = loginPage.parse();

        Map<String, String> formData = extractLoginForm(loginDoc);
        formData.put("loginuser", user);
        formData.put("loginpassword", pass);
        String csrf = extractCsrfToken(loginDoc);

        System.out.println("[2/3] Login ausfuehren...");
        Connection conn = Jsoup.connect(LOGIN_URL)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "de-CH,de;q=0.9,en;q=0.8")
                .header("Referer", LOGIN_URL)
                .header("Origin", BASE_URL)
                .cookies(cookies)
                .data(formData)
                .method(Connection.Method.POST)
                .maxBodySize(0)
                .followRedirects(true);
        if (csrf != null) {
            conn.header("X-Csrf-Token", csrf);
            conn.header("X-CSRF-Token", csrf);
        }
        Connection.Response resp = conn.execute();
        cookies.putAll(resp.cookies());
    }

    private String fetchStudentId() throws IOException {
        System.out.println("[3/3] Notenbuch-Seite holen (studentId extrahieren)...");
        Connection.Response gbResp = Jsoup.connect(GRADEBOOK_URL)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "de-CH,de;q=0.9,en;q=0.8")
                .header("Referer", LOGIN_URL)
                .cookies(cookies)
                .maxBodySize(0)
                .method(Connection.Method.GET)
                .followRedirects(true)
                .execute();
        cookies.putAll(gbResp.cookies());
        String body = gbResp.body();

        if (body == null || body.contains("name=\"loginpassword\"")
                && body.contains("login-form") && !body.contains("studentId")) {
            Files.writeString(Path.of("login_response.html"), body == null ? "" : body);
            throw new RuntimeException("Login fehlgeschlagen. Siehe login_response.html.");
        }

        Matcher m = Pattern.compile("studentId\\s*=\\s*(\\d+)").matcher(body);
        if (!m.find()) {
            Files.writeString(Path.of("gradebook_response.html"), body);
            throw new RuntimeException("studentId nicht in der Seite gefunden. Siehe gradebook_response.html.");
        }
        return m.group(1);
    }

    private JsonNode getJson(String url, Map<String, String> data) throws IOException {
        Connection conn = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Language", "de-CH,de;q=0.9,en;q=0.8")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", GRADEBOOK_URL)
                .header("Origin", BASE_URL)
                .cookies(cookies)
                .ignoreContentType(true)
                .maxBodySize(0)
                .followRedirects(true);
        if (data == null) {
            conn.method(Connection.Method.GET);
        } else {
            conn.method(Connection.Method.POST);
            conn.data(data);
        }
        Connection.Response resp = conn.execute();
        cookies.putAll(resp.cookies());
        String body = resp.body();
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new IOException("Konnte JSON-Antwort nicht parsen von " + url + ": "
                    + body.substring(0, Math.min(400, body.length())), e);
        }
    }

    private static Map<String, String> extractLoginForm(Document loginDoc) {
        Map<String, String> data = new LinkedHashMap<>();
        Elements form = loginDoc.select("form.login-form, form[name=login-form]");
        if (form.isEmpty()) {
            throw new RuntimeException("Login-Form nicht gefunden – Seitenlayout geaendert?");
        }
        for (Element input : form.first().select("input[name]")) {
            data.put(input.attr("name"), input.attr("value"));
        }
        data.putIfAbsent("loginschool", "bmz");
        return data;
    }

    private static String extractCsrfToken(Document loginDoc) {
        for (Element s : loginDoc.select("script")) {
            String html = s.html();
            int idx = html.indexOf("csrfToken");
            if (idx >= 0) {
                int q1 = html.indexOf('\'', idx);
                int q2 = html.indexOf('\'', q1 + 1);
                if (q1 > 0 && q2 > q1) return html.substring(q1 + 1, q2);
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.asText();
    }

    private static String formatDate(String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(raw);
        if (m.find()) return m.group(3) + "." + m.group(2) + "." + m.group(1);
        return raw;
    }

    private static Properties loadConfig() throws IOException {
        Path p = Path.of("config.properties");
        if (!Files.exists(p)) {
            throw new RuntimeException(
                    "config.properties nicht gefunden. Kopiere config.properties.example "
                            + "zu config.properties und trage deine Credentials ein.");
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(p)) {
            props.load(in);
        }
        return props;
    }

    private static String required(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new RuntimeException("Property '" + key + "' fehlt in config.properties");
        }
        return v;
    }

    private static void writeCsv(List<Grade> grades, String path) throws IOException {
        try (FileWriter w = new FileWriter(path)) {
            w.write("Semester;Fach;Titel;Datum;Note;Gewichtung;Gestrichen\n");
            for (Grade g : grades) {
                w.write(csv(g.period) + ";" + csv(g.subject) + ";" + csv(g.title) + ";"
                        + csv(g.date) + ";" + csv(g.value) + ";" + csv(g.weight) + ";"
                        + (g.stroked ? "ja" : "nein") + "\n");
            }
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private void writeJson(List<Grade> grades, String path) throws IOException {
        ObjectNode root = json.createObjectNode();
        for (Grade g : grades) {
            String pKey = g.period == null ? "Unbekannt" : g.period;
            String sKey = g.subject == null ? "Unbekannt" : g.subject;
            ObjectNode periodNode = (ObjectNode) root.get(pKey);
            if (periodNode == null) {
                periodNode = json.createObjectNode();
                root.set(pKey, periodNode);
            }
            ArrayNode subjArr = (ArrayNode) periodNode.get(sKey);
            if (subjArr == null) {
                subjArr = json.createArrayNode();
                periodNode.set(sKey, subjArr);
            }
            ObjectNode gn = json.createObjectNode();
            gn.put("title", g.title);
            gn.put("date", g.date);
            gn.put("value", g.value);
            gn.put("weight", g.weight);
            gn.put("stroked", g.stroked);
            subjArr.add(gn);
        }
        json.enable(SerializationFeature.INDENT_OUTPUT);
        json.writeValue(new java.io.File(path), root);
    }

    public static class Grade {
        public String period;
        public String subject;
        public String title;
        public String date;
        public String value;
        public String weight;
        public boolean stroked;
    }
}
