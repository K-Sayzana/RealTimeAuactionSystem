package com.auction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class TemplateEngine {

    private static final String TEMPLATE_DIR = "/templates/";

    public static String render(String templateName, Map<String, String> vars) {
        String html = loadFile(templateName + ".html");

        if (html.contains("{{navbar}}")) {
            html = html.replace("{{navbar}}", buildNavbar(vars));
        }

        for (Map.Entry<String, String> entry : vars.entrySet()) {
            html = html.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }

        return html;
    }

    public static String render(String templateName) {
        return render(templateName, Map.of());
    }

    private static String buildNavbar(Map<String, String> vars) {
        String navbar     = loadFile("_navbar.html");
        String activePage = vars.getOrDefault("activePage", "");
        String role       = vars.getOrDefault("role", "BIDDER");
        String username   = vars.getOrDefault("username", "");

        navbar = navbar.replace("{{nav-auction}}", "auction".equals(activePage) ? "active" : "");

        String secondLink;
        if ("SELLER".equals(role)) {
            String cls = "admin".equals(activePage) ? "active" : "";
            secondLink = "<a href=\"/admin\" class=\"" + cls + "\">&#9881; Dashboard</a>";
        } else {
            String cls = "profile".equals(activePage) ? "active" : "";
            secondLink = "<a href=\"/profile\" class=\"" + cls + "\">&#128100; Profile</a>";
        }

        navbar = navbar.replace("{{nav-second-link}}", secondLink);
        navbar = navbar.replace("{{username}}", username);

        return navbar;
    }

    private static String loadFile(String filename) {
        String path = TEMPLATE_DIR + filename;
        InputStream is = TemplateEngine.class.getResourceAsStream(path);

        if (is == null) {
            throw new RuntimeException(
                    "[TemplateEngine] Template not found: " + path
                            + "\nCheck that the file exists in src/main/resources/templates/"
            );
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("[TemplateEngine] Failed to read: " + path, e);
        }
    }
}