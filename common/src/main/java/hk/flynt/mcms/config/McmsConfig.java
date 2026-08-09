package hk.flynt.mcms.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.platform.Platform;
import hk.flynt.mcms.McmsMod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 本地配置文件 config/mcms.json。密码按需求明文保存。 */
public class McmsConfig {
    public static final String DEFAULT_SERVER_URL = "https://mcms.flynt.hk/api";
    public static final String DEFAULT_PREFIX = ".";

    private static final Gson GSON = new Gson();
    private static volatile McmsConfig instance;

    private final Path file;
    private volatile String serverUrl = DEFAULT_SERVER_URL;
    private volatile String commandPrefix = DEFAULT_PREFIX;
    private volatile String username = "";
    private volatile String password = "";
    private volatile String token = "";
    private volatile String avatar = "";
    private volatile long userId;
    private volatile long currentRoomId;

    private McmsConfig(Path file) {
        this.file = file;
    }

    public static McmsConfig get() {
        if (instance == null) {
            synchronized (McmsConfig.class) {
                if (instance == null) {
                    instance = load(Platform.getConfigFolder().resolve("mcms.json"));
                }
            }
        }
        return instance;
    }

    private static McmsConfig load(Path file) {
        McmsConfig c = new McmsConfig(file);
        try {
            if (Files.exists(file)) {
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("serverUrl") && !root.get("serverUrl").isJsonNull()) c.serverUrl = root.get("serverUrl").getAsString();
                if (root.has("commandPrefix") && !root.get("commandPrefix").isJsonNull()) c.commandPrefix = root.get("commandPrefix").getAsString();
                if (root.has("username") && !root.get("username").isJsonNull()) c.username = root.get("username").getAsString();
                if (root.has("password") && !root.get("password").isJsonNull()) c.password = root.get("password").getAsString();
                if (root.has("token") && !root.get("token").isJsonNull()) c.token = root.get("token").getAsString();
                if (root.has("avatar") && !root.get("avatar").isJsonNull()) c.avatar = root.get("avatar").getAsString();
                if (root.has("userId")) c.userId = root.get("userId").getAsLong();
                if (root.has("currentRoomId")) c.currentRoomId = root.get("currentRoomId").getAsLong();
            }
        } catch (Exception e) {
            McmsMod.LOGGER.warn("[MCMS] config load failed: {}", e.toString());
        }
        return c;
    }

    public synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("serverUrl", serverUrl);
            root.addProperty("commandPrefix", commandPrefix);
            root.addProperty("username", username);
            root.addProperty("password", password);
            root.addProperty("token", token);
            root.addProperty("avatar", avatar);
            root.addProperty("userId", userId);
            root.addProperty("currentRoomId", currentRoomId);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            McmsMod.LOGGER.warn("[MCMS] config save failed: {}", e.toString());
        }
    }

    // ---- getters / setters ----

    public String getServerUrl() {
        return serverUrl;
    }

    public String getCommandPrefix() {
        return commandPrefix;
    }

    public void setCommandPrefix(String prefix) {
        this.commandPrefix = prefix == null ? "" : prefix;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getToken() {
        return token;
    }

    public String getAvatar() {
        return avatar;
    }

    public long getUserId() {
        return userId;
    }

    public long getCurrentRoomId() {
        return currentRoomId;
    }

    public void setCurrentRoomId(long id) {
        this.currentRoomId = id;
    }

    public void setCredentials(String username, String password, String token, String avatar, long userId) {
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.token = token == null ? "" : token;
        this.avatar = avatar == null ? "" : avatar;
        this.userId = userId;
    }

    public void setTokenOnly(String token) {
        this.token = token == null ? "" : token;
    }

    public void clearCredentials() {
        this.username = "";
        this.password = "";
        this.token = "";
        this.avatar = "";
        this.userId = 0;
        this.currentRoomId = 0;
    }
}