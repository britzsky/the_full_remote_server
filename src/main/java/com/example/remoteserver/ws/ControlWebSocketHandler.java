package com.example.remoteserver.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ControlWebSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, WebSocketSession> agents = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> adminsByAgent = new ConcurrentHashMap<>();
    private final Map<String, String> sessionAgentId = new ConcurrentHashMap<>();
    private final Map<String, String> sessionRole = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        send(session, info("CONNECTED", "WebSocket connected"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode root = mapper.readTree(message.getPayload());
        String type = root.path("type").asText("");
        String agentId = root.path("agentId").asText("");

        switch (type) {
            case "REGISTER_AGENT" -> registerAgent(session, agentId);
            case "REGISTER_ADMIN" -> registerAdmin(session, agentId);
            case "FRAME", "AGENT_STATUS" -> forwardToAdmins(agentId, message.getPayload());
            case "MOUSE_MOVE", "MOUSE_CLICK", "KEY_TYPE", "KEY_PRESS" -> forwardToAgent(agentId, message.getPayload(), session);
            default -> send(session, error("UNKNOWN_TYPE", "Unknown message type: " + type));
        }
    }

    private void registerAgent(WebSocketSession session, String agentId) throws IOException {
        if (agentId == null || agentId.isBlank()) {
            send(session, error("INVALID_AGENT_ID", "agentId is required"));
            return;
        }
        agents.put(agentId, session);
        sessionAgentId.put(session.getId(), agentId);
        sessionRole.put(session.getId(), "AGENT");
        send(session, info("AGENT_REGISTERED", "Agent registered: " + agentId));
        forwardToAdmins(agentId, json("AGENT_ONLINE", agentId, "online"));
    }

    private void registerAdmin(WebSocketSession session, String agentId) throws IOException {
        if (agentId == null || agentId.isBlank()) {
            send(session, error("INVALID_AGENT_ID", "agentId is required"));
            return;
        }
        adminsByAgent.computeIfAbsent(agentId, key -> ConcurrentHashMap.newKeySet()).add(session);
        sessionAgentId.put(session.getId(), agentId);
        sessionRole.put(session.getId(), "ADMIN");
        send(session, info("ADMIN_REGISTERED", "Admin registered for agent: " + agentId));
        send(session, json(agents.containsKey(agentId) ? "AGENT_ONLINE" : "AGENT_OFFLINE", agentId,
                agents.containsKey(agentId) ? "online" : "offline"));
    }

    private void forwardToAgent(String agentId, String payload, WebSocketSession sender) throws IOException {
        WebSocketSession agent = agents.get(agentId);
        if (agent == null || !agent.isOpen()) {
            send(sender, error("AGENT_OFFLINE", "Agent is not connected: " + agentId));
            return;
        }
        send(agent, payload);
    }

    private void forwardToAdmins(String agentId, String payload) throws IOException {
        Set<WebSocketSession> admins = adminsByAgent.get(agentId);
        if (admins == null) return;
        for (WebSocketSession admin : admins) {
            if (admin.isOpen()) send(admin, payload);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String agentId = sessionAgentId.remove(session.getId());
        String role = sessionRole.remove(session.getId());
        if (agentId == null || role == null) return;

        if ("AGENT".equals(role)) {
            agents.remove(agentId);
            forwardToAdmins(agentId, json("AGENT_OFFLINE", agentId, "offline"));
        } else {
            Set<WebSocketSession> admins = adminsByAgent.get(agentId);
            if (admins != null) admins.remove(session);
        }
    }

    private void send(WebSocketSession session, String payload) throws IOException {
        if (session != null && session.isOpen()) {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        }
    }

    private String info(String type, String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("message", message);
        return node.toString();
    }

    private String error(String type, String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("message", message);
        return node.toString();
    }

    private String json(String type, String agentId, String status) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("agentId", agentId);
        node.put("status", status);
        return node.toString();
    }
}
