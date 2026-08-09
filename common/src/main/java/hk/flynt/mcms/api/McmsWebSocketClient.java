package hk.flynt.mcms.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hk.flynt.mcms.McmsMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MCMS WebSocket 客户端。连接 wss://&lt;host&gt;/api，JSON 帧 {key, value, timestamp, msgId}。
 * 协议（来自前端逆向）：
 *  out: auth {userId, token} / keepAlive {timestamp} / chat.send {chatRoomId, content, type, answerMessageId} / chat.read {chatRoomId}
 *  in:  connected {sessionId} / keepAliveAck / chat.message / chat.withdraw / chat.systemMessage / ...
 */
public class McmsWebSocketClient {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    public interface Listener {
        void onEvent(String key, JsonObject value, JsonObject frame);

        void onState(State state);

        void onConnected(String sessionId);
    }

    private static final Gson GSON = new Gson();
    private static final long KEEPALIVE_MS = 5000;
    private static final long AUTH_TIMEOUT_MS = 15000;
    private static final long RECONNECT_MIN_MS = 2000;
    private static final long RECONNECT_MAX_MS = 30000;

    private final String wsUrl;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcms-ws");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket ws;
    private volatile Listener listener;
    private volatile State state = State.DISCONNECTED;
    private volatile String userId = "";
    private volatile String token = "";
    private volatile boolean manuallyClosed = false;
    private volatile int reconnectAttempts = 0;

    public McmsWebSocketClient(String wsUrl) {
        this.wsUrl = wsUrl;
    }

    public synchronized void connect(String userId, String token) {
        connect(userId, token, null);
    }

    public synchronized void connect(String userId, String token, Listener listener) {
        this.userId = userId == null ? "" : userId;
        this.token = token == null ? "" : token;
        if (listener != null) {
            this.listener = listener;
        }
        this.manuallyClosed = false;
        this.reconnectAttempts = 0;
        WebSocket old = this.ws;
        if (old != null) {
            try { old.abort(); } catch (Exception ignored) { }
            this.ws = null;
        }
        open();
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized void disconnect() {
        manuallyClosed = true;
        WebSocket w = this.ws;
        this.ws = null;
        if (w != null) {
            try { w.abort(); } catch (Exception ignored) { }
        }
        setState(State.DISCONNECTED);
    }

    public State getState() {
        return state;
    }

    public boolean isConnected() {
        return state == State.CONNECTED && ws != null;
    }

    private void setState(State s) {
        state = s;
        Listener l = listener;
        if (l != null) l.onState(s);
    }

    private void open() {
        setState(State.CONNECTING);
        HttpClient client = HttpClient.newHttpClient();
        client.newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .buildAsync(URI.create(wsUrl), new WsListener())
                .whenComplete((w, err) -> {
                    if (err != null) {
                        McmsMod.LOGGER.warn("[MCMS] ws open failed: {}", err.toString());
                        scheduleReconnect();
                    }
                });
    }

    private void scheduleReconnect() {
        if (manuallyClosed) return;
        setState(State.RECONNECTING);
        long delay = Math.min(RECONNECT_MAX_MS, RECONNECT_MIN_MS * (1L << Math.min(reconnectAttempts, 5)));
        reconnectAttempts++;
        scheduler.schedule(this::open, delay, TimeUnit.MILLISECONDS);
    }

    private void send(String key, JsonObject value) {
        WebSocket w = ws;
        if (w == null) return;
        JsonObject frame = new JsonObject();
        frame.addProperty("key", key);
        frame.add("value", value);
        frame.addProperty("timestamp", System.currentTimeMillis());
        frame.addProperty("msgId", UUID.randomUUID().toString());
        try {
            w.sendText(GSON.toJson(frame), true);
        } catch (Exception e) {
            McmsMod.LOGGER.warn("[MCMS] ws send {} failed: {}", key, e.toString());
        }
    }

    public void sendChat(long roomId, String content, int type, Long answerMessageId) {
        JsonObject v = new JsonObject();
        v.addProperty("chatRoomId", roomId);
        v.addProperty("content", content);
        v.addProperty("type", type);
        if (answerMessageId != null) v.addProperty("answerMessageId", answerMessageId);
        send("chat.send", v);
    }

    public void sendRead(long roomId) {
        JsonObject v = new JsonObject();
        v.addProperty("chatRoomId", roomId);
        send("chat.read", v);
    }

    private void startKeepAlive() {
        scheduler.scheduleAtFixedRate(() -> {
            if (isConnected()) {
                JsonObject v = new JsonObject();
                v.addProperty("timestamp", System.currentTimeMillis());
                send("keepAlive", v);
            }
        }, KEEPALIVE_MS, KEEPALIVE_MS, TimeUnit.MILLISECONDS);
    }

    private final class WsListener implements WebSocket.Listener {
        private final StringBuilder textBuf = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            ws = webSocket;
            JsonObject v = new JsonObject();
            v.addProperty("userId", userId);
            v.addProperty("token", token);
            send("auth", v);
            webSocket.request(1);
            // auth 超时
            scheduler.schedule(() -> {
                if (state == State.CONNECTING) {
                    McmsMod.LOGGER.warn("[MCMS] ws auth timeout");
                    try { webSocket.abort(); } catch (Exception ignored) { }
                }
            }, AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuf.append(data);
            if (last) {
                String msg = textBuf.toString();
                textBuf.setLength(0);
                handleMessage(msg);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            McmsMod.LOGGER.info("[MCMS] ws closed: {} {}", statusCode, reason);
            if (!manuallyClosed) scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            McmsMod.LOGGER.warn("[MCMS] ws error: {}", error.toString());
            if (!manuallyClosed) scheduleReconnect();
        }
    }

    private void handleMessage(String msg) {
        JsonObject frame;
        try {
            frame = JsonParser.parseString(msg).getAsJsonObject();
        } catch (Exception e) {
            McmsMod.LOGGER.warn("[MCMS] unparseable ws message");
            return;
        }
        String key = Js.str(frame, "key", "");
        JsonObject value = Js.obj(frame, "value");
        Listener l = listener;
        if (key.equals("connected")) {
            setState(State.CONNECTED);
            reconnectAttempts = 0;
            startKeepAlive();
            if (l != null) l.onConnected(value != null ? Js.str(value, "sessionId", "") : "");
            return;
        }
        if (key.equals("keepAliveAck")) {
            return;
        }
        if (l != null) {
            l.onEvent(key, value, frame);
        }
    }
}