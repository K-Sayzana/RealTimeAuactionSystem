package com.auction;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@WebSocket
public class AuctionWebSocket {

    private static final Gson GSON = new Gson();

    private static DatabaseManager db;

    private static final Map<Session, Map<String, String>> CLIENTS = new ConcurrentHashMap<>();

    private static ScheduledExecutorService scheduler;

    public static void setDatabase(DatabaseManager database) {
        db = database;
    }

    public static void startTimerThread() {
        scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleAtFixedRate(() -> {
            try {
                List<Integer> expired = db.closeExpiredItems();

                for (int itemId : expired) {
                    Map<String, Object> item    = db.getItem(itemId);
                    String              winner  = (String) item.get("top_bidder");
                    String              name    = (String) item.get("name");
                    double              price   = ((Number) item.get("current_bid")).doubleValue();

                    String notification;
                    if (winner == null || winner.isBlank() || "None".equalsIgnoreCase(winner)) {
                        notification = "Auction closed! The " + name + " passed without a winning bid.";
                    } else {
                        notification = "Auction concluded! " + name + " has been successfully sold to "
                                + winner + " for $" + String.format("%.2f", price) + ".";
                    }

                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("type",    "ITEM_CLOSED");
                    msg.put("itemId",  itemId);
                    msg.put("item",    item);
                    msg.put("message", notification);
                    msg.put("seller",  item.get("seller"));

                    broadcastClosureToRelevantClients(GSON.toJson(msg), (String) item.get("seller"));
                }

                List<Map<String, Object>> allItems = db.getAllItems();
                Map<String, Object> tick = new LinkedHashMap<>();
                tick.put("type",  "TICK");
                tick.put("items", allItems);
                broadcastToIdentifiedOnly(GSON.toJson(tick));

            } catch (Exception e) {
                System.err.println("[TIMER] " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
        System.out.println("[TIMER] Started.");
    }

    public static void stopTimerThread() {
        if (scheduler != null) scheduler.shutdownNow();
    }


    @OnWebSocketConnect
    public void onConnect(Session session) {
        session.setIdleTimeout(30 * 60 * 1000L);

        CLIENTS.put(session, new ConcurrentHashMap<>());
        System.out.println("[WS] Connected — total: " + CLIENTS.size());

        try {
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("type",    "CONNECTED");
            ack.put("message", "Connected");
            session.getRemote().sendString(GSON.toJson(ack));
        } catch (IOException e) {
            System.err.println("[WS] Could not send CONNECTED ack: " + e.getMessage());
        }
    }

    @OnWebSocketMessage
    public void onMessage(Session sender, String raw) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = GSON.fromJson(raw, Map.class);
            String type = (String) msg.get("type");
            if (type == null) return;

            switch (type) {

                case "IDENTIFY" -> {
                    String token = (String) msg.get("token");
                    if (token == null || token.isBlank()) {
                        sendError(sender, "No session token provided.");
                        return;
                    }

                    Map<String, String> session = db.getSession(token);
                    if (session == null) {
                        sendError(sender, "Session invalid or expired. Please log in again.");
                        return;
                    }

                    CLIENTS.put(sender, new ConcurrentHashMap<>(session));
                    System.out.println("[WS] Identified: " + session.get("username")
                            + " (" + session.get("role") + ")");

                    Map<String, Object> init = new LinkedHashMap<>();
                    init.put("type",     "INIT");
                    init.put("items",    db.getAllItems());
                    init.put("username", session.get("username"));
                    init.put("role",     session.get("role"));

                    if ("GRIDDER".equals(session.get("role")) || "BIDDER".equals(session.get("role"))) {
                        init.put("chat", db.getRecentChat());
                    }

                    sender.getRemote().sendString(GSON.toJson(init));
                    broadcastUserCount();
                }

                case "BID" -> handleBid(sender, msg);

                case "CHAT" -> {
                    Map<String, String> identity = CLIENTS.get(sender);
                    if (identity == null || "SELLER".equals(identity.get("role"))) return;

                    String message = (String) msg.get("message");
                    if (message == null || message.isBlank()) return;

                    message = message.substring(0, Math.min(message.length(), 300));

                    String username = identity.getOrDefault("username", "Anonymous");
                    db.saveChat(username, "BIDDER", message);

                    Map<String, Object> chatMsg = new LinkedHashMap<>();
                    chatMsg.put("type",     "CHAT");
                    chatMsg.put("username", username);
                    chatMsg.put("role",     "BIDDER");
                    chatMsg.put("message",  message);
                    broadcastToBidders(GSON.toJson(chatMsg));
                }

                case "CLOSE_ITEM" -> {
                    if (!isSeller(sender)) { sendError(sender, "Only sellers can close lots."); return; }
                    int itemId = ((Double) msg.get("itemId")).intValue();
                    db.closeItem(itemId);

                    Map<String, Object> item   = db.getItem(itemId);
                    String              sellerName = (String) item.get("seller");
                    Map<String, Object> bc      = new LinkedHashMap<>();
                    bc.put("type",    "ITEM_CLOSED");
                    bc.put("itemId",  itemId);
                    bc.put("item",    item);
                    bc.put("message", "Lot #" + itemId + " closed by seller.");
                    bc.put("seller",  sellerName);
                    broadcastClosureToRelevantClients(GSON.toJson(bc), sellerName);
                }

                case "EXPORT" -> {
                    if (!isSeller(sender)) { sendError(sender, "Only sellers can export."); return; }
                    String filename = db.exportResultsToFile();

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("type",     "EXPORT_DONE");
                    resp.put("filename", filename);
                    sender.getRemote().sendString(GSON.toJson(resp));
                }
            }

        } catch (Exception e) {
            System.err.println("[WS] Message error: " + e.getMessage());
        }
    }

    @OnWebSocketClose
    public void onClose(Session session, int code, String reason) {
        CLIENTS.remove(session);
        System.out.println("[WS] Disconnected — total: " + CLIENTS.size());
        broadcastUserCount();
    }

    @OnWebSocketError
    public void onError(Session session, Throwable error) {
        System.err.println("[WS] Socket error: " + error.getMessage());
        CLIENTS.remove(session);
    }


    private void handleBid(Session sender, Map<String, Object> msg) throws Exception {
        Map<String, String> identity = CLIENTS.get(sender);
        if (identity == null || "SELLER".equals(identity.get("role"))) {
            sendError(sender, "Sellers cannot place bids.");
            return;
        }

        int    itemId = ((Double) msg.get("itemId")).intValue();
        double amount = (Double) msg.get("amount");
        String bidder = identity.getOrDefault("username", "Anonymous");

        boolean accepted = db.placeBid(itemId, bidder, amount);
        if (accepted) {
            Map<String, Object> updated   = db.getItem(itemId);
            Map<String, Object> broadcast = new LinkedHashMap<>();
            broadcast.put("type",    "BID_UPDATE");
            broadcast.put("item",    updated);
            broadcast.put("message", bidder + " bid $" + String.format("%.2f", amount)
                    + " on " + updated.get("name"));
            broadcast.put("seller",  updated.get("seller"));

            broadcastBidUpdate(GSON.toJson(broadcast), (String) updated.get("seller"));
        } else {
            sendError(sender, "Bid rejected: must exceed the current price and the lot must be open.");
        }
    }


    public static synchronized void broadcastToAllStatic(String json) {
        List<Session> dead = new ArrayList<>();
        for (Session s : CLIENTS.keySet()) {
            if (s.isOpen()) {
                try { s.getRemote().sendString(json); }
                catch (IOException e) { dead.add(s); }
            } else {
                dead.add(s);
            }
        }
        dead.forEach(CLIENTS::remove);
    }

    private static synchronized void broadcastToIdentifiedOnly(String json) {
        List<Session> dead = new ArrayList<>();
        for (Map.Entry<Session, Map<String, String>> entry : CLIENTS.entrySet()) {
            Session s = entry.getKey();
            Map<String, String> id = entry.getValue();

            if (id == null || !id.containsKey("username")) {
                continue;
            }

            if (s.isOpen()) {
                try { s.getRemote().sendString(json); }
                catch (IOException e) { dead.add(s); }
            } else {
                dead.add(s);
            }
        }
        dead.forEach(CLIENTS::remove);
    }

    private static synchronized void broadcastBidUpdate(String json, String ownerSeller) {
        List<Session> dead = new ArrayList<>();
        for (Map.Entry<Session, Map<String, String>> entry : CLIENTS.entrySet()) {
            Session            s    = entry.getKey();
            Map<String, String> id  = entry.getValue();
            if (id == null || !id.containsKey("role")) continue;

            String             role = id.get("role");
            boolean isSeller        = "SELLER".equals(role);

            if (isSeller && !ownerSeller.equals(id.get("username"))) continue;

            if (s.isOpen()) {
                try { s.getRemote().sendString(json); }
                catch (IOException e) { dead.add(s); }
            } else {
                dead.add(s);
            }
        }
        dead.forEach(CLIENTS::remove);
    }

    private static synchronized void broadcastClosureToRelevantClients(String json, String ownerSeller) {
        broadcastBidUpdate(json, ownerSeller);
    }

    private static synchronized void broadcastToBidders(String json) {
        List<Session> dead = new ArrayList<>();
        for (Map.Entry<Session, Map<String, String>> entry : CLIENTS.entrySet()) {
            Session s = entry.getKey();
            Map<String, String> id = entry.getValue();
            if (id == null || !"BIDDER".equals(id.get("role"))) continue;
            if (s.isOpen()) {
                try { s.getRemote().sendString(json); }
                catch (IOException e) { dead.add(s); }
            } else {
                dead.add(s);
            }
        }
        dead.forEach(CLIENTS::remove);
    }

    private void broadcastUserCount() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type",  "USER_COUNT");
        m.put("count", CLIENTS.size());
        broadcastToAllStatic(GSON.toJson(m));
    }

    private void sendError(Session s, String message) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("type",    "ERROR");
        err.put("message", message);
        if (s.isOpen()) s.getRemote().sendString(GSON.toJson(err));
    }

    private boolean isSeller(Session s) {
        Map<String, String> id = CLIENTS.get(s);
        return id != null && "SELLER".equals(id.get("role"));
    }
}