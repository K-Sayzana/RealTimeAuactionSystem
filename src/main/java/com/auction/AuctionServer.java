package com.auction;

import static spark.Spark.*;
import com.google.gson.Gson;
import java.util.*;


public class AuctionServer {

    private static final Gson GSON = new Gson();
    private static DatabaseManager db;

    public static final int MAX_DURATION_MINUTES = 43_200;

    public static void main(String[] args) throws Exception {

        db = new DatabaseManager();
        db.connect();

        AuctionWebSocket.setDatabase(db);

        port(4567);
        threadPool(50, 4, 60_000);
        webSocket("/auction-ws", AuctionWebSocket.class);

        AuctionWebSocket.startTimerThread();

        before((req, res) -> res.header("Access-Control-Allow-Origin", "*"));

        options("/*", (req, res) -> {
            res.header("Access-Control-Allow-Origin",  "*");
            res.header("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            res.header("Access-Control-Allow-Headers", "Content-Type");
            return "OK";
        });


        get("/", (req, res) -> {
            Map<String, String> session = db.getSession(req.cookie("session"));
            if (session != null) {
                res.redirect("SELLER".equals(session.get("role")) ? "/admin" : "/auction");
                return null;
            }
            res.type("text/html");
            return TemplateEngine.render("login");
        });

        post("/api/login", (req, res) -> {
            res.type("application/json");
            @SuppressWarnings("unchecked")
            Map<String, String> body = GSON.fromJson(req.body(), Map.class);
            String username = body.get("username");
            String password = body.get("password");

            if (username == null || password == null) {
                res.status(400);
                return "{\"error\":\"Username and password required.\"}";
            }

            String token = db.login(username, password);
            if (token == null) {
                res.status(401);
                return "{\"error\":\"Invalid username or password.\"}";
            }

            res.cookie("/", "session", token, 86400, false, false);

            Map<String, String> sessionData = db.getSession(token);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("token",    token);
            resp.put("username", sessionData.get("username"));
            resp.put("role",     sessionData.get("role"));
            resp.put("redirect", "SELLER".equals(sessionData.get("role")) ? "/admin" : "/auction");
            return GSON.toJson(resp);
        });

        post("/api/register", (req, res) -> {
            res.type("application/json");
            @SuppressWarnings("unchecked")
            Map<String, String> body = GSON.fromJson(req.body(), Map.class);
            String username = body.getOrDefault("username", "").trim();
            String password = body.getOrDefault("password", "");
            String role     = "SELLER".equals(body.get("role")) ? "SELLER" : "BIDDER";

            if (username.length() < 3) return "{\"error\":\"Username must be at least 3 characters.\"}";
            if (password.length() < 4) return "{\"error\":\"Password must be at least 4 characters.\"}";

            String error = db.register(username, password, role);
            if (error != null) { res.status(400); return "{\"error\":\"" + error + "\"}"; }
            return "{\"success\":true,\"message\":\"Account created! Please log in.\"}";
        });

        post("/api/logout", (req, res) -> {
            String token = req.cookie("session");
            if (token != null) db.logout(token);
            res.removeCookie("session");
            res.redirect("/");
            return null;
        });


        get("/auction", (req, res) -> {
            Map<String, String> session = requireLogin(req, res);
            if (session == null) return null;
            res.type("text/html");
            String roleBadge = "SELLER".equals(session.get("role")) ? "badge-seller" : "badge-open";
            return TemplateEngine.render("auction", Map.of(
                    "username",   session.get("username"),
                    "role",       session.get("role"),
                    "role-badge", roleBadge,
                    "activePage", "auction"
            ));
        });

        get("/profile", (req, res) -> {
            Map<String, String> session = requireLogin(req, res);
            if (session == null) return null;
            res.type("text/html");
            return TemplateEngine.render("profile", Map.of(
                    "username",   session.get("username"),
                    "role",       session.get("role"),
                    "activePage", "profile"
            ));
        });

        get("/admin", (req, res) -> {
            Map<String, String> session = requireRole(req, res, "SELLER");
            if (session == null) return null;
            res.type("text/html");
            return TemplateEngine.render("admin", Map.of(
                    "username",        session.get("username"),
                    "role",            session.get("role"),
                    "activePage",      "admin",
                    "maxDurationMins", String.valueOf(MAX_DURATION_MINUTES)
            ));
        });


        get("/api/stats", (req, res) -> {
            res.type("application/json");
            Map<String, String> session = requireLogin(req, res);
            if (session == null) return null;
            String username = session.get("username");
            return "SELLER".equals(session.get("role"))
                    ? GSON.toJson(db.getSellerStats(username))
                    : GSON.toJson(db.getUserStats(username));
        });

        get("/api/items", (req, res) -> {
            res.type("application/json");
            return GSON.toJson(db.getAllItems());
        });

        get("/api/items/seller", (req, res) -> {
            res.type("application/json");
            Map<String, String> session = requireRole(req, res, "SELLER");
            if (session == null) return null;
            return GSON.toJson(db.getItemsBySeller(session.get("username")));
        });

        post("/api/items", (req, res) -> {
            res.type("application/json");
            Map<String, String> session = requireRole(req, res, "SELLER");
            if (session == null) return null;

            @SuppressWarnings("unchecked")
            Map<String, Object> body = GSON.fromJson(req.body(), Map.class);
            String name     = (String) body.get("name");
            String desc     = (String) body.getOrDefault("description", "");
            double price    = ((Number) body.get("startPrice")).doubleValue();
            int    duration = ((Number) body.get("durationMinutes")).intValue();

            if (name == null || name.isBlank()) { res.status(400); return "{\"error\":\"Name required.\"}"; }
            if (price <= 0)                      { res.status(400); return "{\"error\":\"Price must be positive.\"}"; }
            if (duration < 1)                    { res.status(400); return "{\"error\":\"Duration must be at least 1 minute.\"}"; }
            if (duration > MAX_DURATION_MINUTES) { res.status(400); return "{\"error\":\"Duration cannot exceed 1 month (43 200 minutes).\"}"; }

            db.addItem(name, desc, price, duration, session.get("username"));

            Map<String, Object> newItemMsg = new LinkedHashMap<>();
            newItemMsg.put("type", "ITEM_ADDED");
            newItemMsg.put("item", db.getAllItems().stream()
                    .filter(i -> name.equals(i.get("name")) && session.get("username").equals(i.get("seller")))
                    .reduce((first, second) -> second)
                    .orElse(null));
            AuctionWebSocket.broadcastToAllStatic(new Gson().toJson(newItemMsg));

            return "{\"success\":true}";
        });

        post("/api/items/:id/close", (req, res) -> {
            res.type("application/json");
            Map<String, String> session = requireRole(req, res, "SELLER");
            if (session == null) return null;

            int itemId = Integer.parseInt(req.params(":id"));
            db.closeItem(itemId);

            Map<String, Object> item      = db.getItem(itemId);
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type",    "ITEM_CLOSED");
            broadcast.put("itemId",  itemId);
            broadcast.put("item",    item);
            broadcast.put("message", "Lot #" + itemId + " closed by seller.");
            broadcast.put("seller",  item.get("seller"));
            AuctionWebSocket.broadcastToAllStatic(GSON.toJson(broadcast));
            return "{\"success\":true}";
        });

        get("/api/items/:id/bids", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            return GSON.toJson(db.getBidHistory(id));
        });

        post("/api/export", (req, res) -> {
            res.type("application/json");
            Map<String, String> session = requireRole(req, res, "SELLER");
            if (session == null) return null;
            String filename = db.exportResultsToFile();
            Map<String, String> resp = new HashMap<>();
            resp.put("filename", filename);
            resp.put("message",  "Exported successfully!");
            return GSON.toJson(resp);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AuctionWebSocket.stopTimerThread();
            db.close();
            System.out.println("[SERVER] Shut down cleanly.");
        }));

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║   GavelHouse Auction — Server started                ║");
        System.out.println("║   Open in your browser:  http://localhost:4567       ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
    }

    private static Map<String, String> requireLogin(spark.Request req, spark.Response res) throws Exception {
        Map<String, String> session = db.getSession(req.cookie("session"));
        if (session == null) { res.redirect("/"); return null; }
        return session;
    }

    private static Map<String, String> requireRole(spark.Request req, spark.Response res, String role) throws Exception {
        Map<String, String> session = requireLogin(req, res);
        if (session == null) return null;
        if (!role.equals(session.get("role"))) { res.redirect("/auction"); return null; }
        return session;
    }
}