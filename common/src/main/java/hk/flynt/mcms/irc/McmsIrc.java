package hk.flynt.mcms.irc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hk.flynt.mcms.McmsClickIds;
import hk.flynt.mcms.McmsMod;
import hk.flynt.mcms.api.McmsApiClient;
import hk.flynt.mcms.api.McmsWebSocketClient;
import hk.flynt.mcms.api.dto.ChatMessage;
import hk.flynt.mcms.api.dto.ChatRoom;
import hk.flynt.mcms.api.dto.LoginData;
import hk.flynt.mcms.config.McmsConfig;
import hk.flynt.mcms.platform.PlatformClient;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 游戏内 IRC 群组系统：管理登录态、群组列表、当前群组与 WebSocket 事件，
 * 并把聊天消息展示到游戏聊天栏。
 */
public class McmsIrc {
    private static final McmsIrc INSTANCE = new McmsIrc();

    private final McmsConfig config = McmsConfig.get();
    private final McmsApiClient api = new McmsApiClient(config.getServerUrl());
    private final McmsWebSocketClient ws;
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcms-worker");
        t.setDaemon(true);
        return t;
    });

    private final Map<Long, ChatRoom> rooms = new LinkedHashMap<>();
    private volatile long currentRoomId;
    private volatile long recentActiveRoomId;
    private volatile boolean loggedIn = false;
    private volatile String sessionId = "";
    private volatile String wsState = "未连接";

    private McmsIrc() {
        api.setToken(config.getToken());
        currentRoomId = config.getCurrentRoomId();
        ws = new McmsWebSocketClient(toWsUrl(config.getServerUrl()));
        ws.setListener(new McmsWebSocketClient.Listener() {
            @Override
            public void onEvent(String key, JsonObject value, JsonObject frame) {
                handleWsEvent(key, value);
            }

            @Override
            public void onState(McmsWebSocketClient.State state) {
                wsState = state.name();
            }

            @Override
            public void onConnected(String sid) {
                sessionId = sid;
                McmsMod.LOGGER.info("[MCMS] ws connected, session={}", sid);
            }
        });
    }

    public static McmsIrc get() {
        return INSTANCE;
    }

    /** 供命令层使用的 REST 客户端。 */
    public hk.flynt.mcms.api.McmsApiClient getApi() {
        return api;
    }

    /** 在工作线程执行网络任务，避免阻塞客户端线程。 */
    public void async(Runnable task) {
        worker.execute(task);
    }

    /** 当前登录用户 ID。 */
    public long getSelfUserId() {
        return config.getUserId();
    }

    private static String toWsUrl(String serverUrl) {
        String u = serverUrl;
        if (u.startsWith("https://")) u = "wss://" + u.substring("https://".length());
        else if (u.startsWith("http://")) u = "ws://" + u.substring("http://".length());
        return u;
    }

    // ---------- 输出 ----------

    public void print(String text) {
        printText(Component.literal("[MCMS] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }

    public void printOk(String text) {
        printText(Component.literal("[MCMS] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.GREEN)));
    }

    public void printErr(String text) {
        printText(Component.literal("[MCMS] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.RED)));
    }

    public void printText(Component component) {
        PlatformClient.sendChatComponent(component);
    }

    private void system(String text) {
        PlatformClient.sendChatComponent(Component.literal("[系统] " + text).withStyle(ChatFormatting.GRAY));
    }

    // ---------- 认证 ----------

    public void login(String username, String password, boolean save) {
        worker.execute(() -> {
            try {
                print("正在登录 " + username + " ...");
                LoginData d = api.login(username, password);
                if (d == null) {
                    printErr("登录失败：账号或密码错误");
                    return;
                }
                config.setCredentials(d.username(), save ? password : "", d.token(), d.avatar(), d.id());
                config.save();
                api.setToken(d.token());
                loggedIn = true;
                ws.connect(String.valueOf(d.id()), d.token());
                print("登录成功：欢迎回来，[" + d.username() + "]");
                refreshRoomsSilently();
            } catch (Exception e) {
                print("登录失败：" + e.getMessage());
            }
        });
    }

    public void autoLogin() {
        worker.execute(() -> {
            try {
                if (config.getToken() != null && !config.getToken().isEmpty()) {
                    print("正在自动登录 ...");
                    LoginData d = api.autoLogin(config.getToken());
                    if (d != null) {
                        config.setTokenOnly(d.token());
                        config.save();
                        api.setToken(d.token());
                        loggedIn = true;
                        ws.connect(String.valueOf(d.id()), d.token());
                        print("自动登录成功：[" + d.username() + "]");
                        refreshRoomsSilently();
                        return;
                    }
                }
                if (!config.getPassword().isEmpty() && !config.getUsername().isEmpty()) {
                    LoginData d = api.login(config.getUsername(), config.getPassword());
                    if (d != null) {
                        config.setCredentials(d.username(), config.getPassword(), d.token(), d.avatar(), d.id());
                        config.save();
                        api.setToken(d.token());
                        loggedIn = true;
                        ws.connect(String.valueOf(d.id()), d.token());
                        print("自动登录成功：[" + d.username() + "]");
                        refreshRoomsSilently();
                        return;
                    }
                }
                // 无凭证时静默
            } catch (Exception e) {
                McmsMod.LOGGER.warn("[MCMS] autoLogin failed: {}", e.toString());
            }
        });
    }

    public void logout() {
        worker.execute(() -> {
            try {
                api.exitLogin();
            } catch (Exception ignored) {
            }
            ws.disconnect();
            loggedIn = false;
            rooms.clear();
            currentRoomId = 0;
            recentActiveRoomId = 0;
            config.clearCredentials();
            config.save();
            print("已退出登录");
        });
    }

    public void status() {
        worker.execute(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("登录: ").append(loggedIn ? "是" : "否");
            if (loggedIn) sb.append(" [").append(config.getUsername()).append("]");
            sb.append(" | WS: ").append(wsState);
            long cur = currentRoomId;
            if (cur != 0) {
                ChatRoom r;
                synchronized (rooms) {
                    r = rooms.get(cur);
                }
                sb.append(" | 当前群组: ").append(r != null ? r.name() + "(" + r.code() + ")" : cur);
            } else {
                sb.append(" | 未选择群组");
            }
            sb.append(" | 群组数: ").append(rooms.size());
            print(sb.toString());
        });
    }

    // ---------- 群组操作 ----------

    private void refreshRoomsSilently() {
        try {
            List<ChatRoom> list = api.getMyRooms();
            synchronized (rooms) {
                rooms.clear();
                for (ChatRoom r : list) rooms.put(r.id(), r);
            }
        } catch (Exception e) {
            McmsMod.LOGGER.warn("[MCMS] refreshRooms failed: {}", e.toString());
        }
    }

    public void listRooms() {
        worker.execute(() -> {
            try {
                if (!loggedIn) {
                    print("尚未登录，请先 .login <账号> <密码>");
                    return;
                }
                List<ChatRoom> list = api.getMyRooms();
                synchronized (rooms) {
                    rooms.clear();
                    for (ChatRoom r : list) rooms.put(r.id(), r);
                }
                if (list.isEmpty()) {
                    print("还没有加入任何群组，可用 .irc create <名称> 创建，或 .irc join <群号> 加入");
                    return;
                }
                print("我的群组（" + list.size() + "）：");
                for (ChatRoom r : list) {
                    Component c = Component.literal("  ")
                            .append(Component.literal(r.name()).withStyle(ChatFormatting.GREEN))
                            .append(Component.literal("（群号 " + r.code() + "）").withStyle(ChatFormatting.GRAY));
                    if (r.id() == currentRoomId) {
                        c = c.copy().append(Component.literal(" [当前]").withStyle(ChatFormatting.YELLOW));
                    }
                    printText(c);
                }
            } catch (Exception e) {
                print("获取群组失败：" + e.getMessage());
            }
        });
    }

    public void setRoomByCode(String code) {
        worker.execute(() -> {
            try {
                if (!loggedIn) {
                    print("尚未登录，请先 .login <账号> <密码>");
                    return;
                }
                refreshRoomsSilently();
                ChatRoom target = null;
                synchronized (rooms) {
                    for (ChatRoom r : rooms.values()) {
                        if (code.equals(r.code())) {
                            target = r;
                            break;
                        }
                    }
                }
                if (target == null) {
                    print("未找到群号 " + code + "，可用 .irc list 查看");
                    return;
                }
                currentRoomId = target.id();
                config.setCurrentRoomId(target.id());
                config.save();
                ws.sendRead(target.id());
                print("已切换到群组 " + target.name() + "（群号 " + target.code() + "）");
            } catch (Exception e) {
                print("切换群组失败：" + e.getMessage());
            }
        });
    }

    public void createRoom(String name) {
        worker.execute(() -> {
            try {
                if (!loggedIn) {
                    print("尚未登录，请先 .login <账号> <密码>");
                    return;
                }
                if (name == null || name.trim().isEmpty()) {
                    print("用法：.irc create <群组名称>");
                    return;
                }
                ChatRoom r = api.createRoom(name.trim(), false);
                if (r == null) {
                    printErr("创建群组失败");
                    return;
                }
                synchronized (rooms) {
                    rooms.put(r.id(), r);
                }
                currentRoomId = r.id();
                config.setCurrentRoomId(r.id());
                config.save();
                print("群组创建成功：" + r.name() + "（群号 " + r.code() + "）");
            } catch (Exception e) {
                print("创建群组失败：" + e.getMessage());
            }
        });
    }

    public void joinByCode(String code) {
        worker.execute(() -> {
            try {
                if (!loggedIn) {
                    print("尚未登录，请先 .login <账号> <密码>");
                    return;
                }
                ChatRoom r = api.joinByCode(code.trim());
                if (r == null) {
                    print("加入失败：群号 " + code + " 无效");
                    return;
                }
                synchronized (rooms) {
                    rooms.put(r.id(), r);
                }
                currentRoomId = r.id();
                config.setCurrentRoomId(r.id());
                config.save();
                print("已加入群组 " + r.name() + "（群号 " + r.code() + "）");
            } catch (Exception e) {
                print("加入群组失败：" + e.getMessage());
            }
        });
    }

    public void quitRoom() {
        worker.execute(() -> {
            try {
                if (!loggedIn || currentRoomId == 0) {
                    print("当前未选择群组");
                    return;
                }
                long id = currentRoomId;
                if (api.quitRoom(id)) {
                    synchronized (rooms) {
                        rooms.remove(id);
                    }
                    currentRoomId = 0;
                    config.setCurrentRoomId(0);
                    config.save();
                    print("已退出群组");
                } else {
                    printErr("退出群组失败");
                }
            } catch (Exception e) {
                print("退出群组失败：" + e.getMessage());
            }
        });
    }

    // ---------- 发消息 ----------

    public void sendMessage(String text) {
        if (text == null || text.trim().isEmpty()) {
            print("用法：.irc <文字>");
            return;
        }
        long roomId = currentRoomId != 0 ? currentRoomId : recentActiveRoomId;
        if (roomId == 0) {
            print("未选择群组。请先 .irc list 查看，再用 .irc set <群号> 选择");
            return;
        }
        ChatRoom r;
        synchronized (rooms) {
            r = rooms.get(roomId);
        }
        if (r == null) {
            print("群组信息缺失，请先 .irc list 刷新");
            return;
        }
        recentActiveRoomId = roomId;
        // 不本地回显：服务器会重新广播我们的消息
        ws.sendChat(roomId, text, 0, null);
    }

    /** .irc reply <消息ID> <内容>：以引用方式回复指定消息。 */
    public void sendReply(String messageIdStr, String text) {
        if (text == null || text.trim().isEmpty()) {
            print("用法：.irc reply <消息ID> <内容>");
            return;
        }
        long messageId;
        try {
            messageId = Long.parseLong(messageIdStr.trim());
        } catch (NumberFormatException e) {
            printErr("无效的消息 ID");
            return;
        }
        long roomId = currentRoomId != 0 ? currentRoomId : recentActiveRoomId;
        if (roomId == 0) {
            printErr("未选择群组。请先 .irc set <群号> 选择");
            return;
        }
        ws.sendChat(roomId, text, 0, messageId);
    }

    /** 把媒体内容拼成可直接打开的完整 URL（与原版一致：{origin}/api/file/download/{file}）。 */
    private String mediaUrl(String content) {
        if (content == null || content.isEmpty()) return null;
        if (content.startsWith("http://") || content.startsWith("https://")) return content;
        // 内容可能是 JSON（如 {"file":"xxx.png"}），取 file 字段
        String file = content.trim();
        if (file.startsWith("{") && file.endsWith("}")) {
            try {
                JsonObject o = JsonParser.parseString(file).getAsJsonObject();
                if (o.has("file") && !o.get("file").isJsonNull()) {
                    file = o.get("file").getAsString();
                }
            } catch (Exception ignored) {
            }
        }
        if (file == null || file.isEmpty()) return null;
        String base = config.getServerUrl();
        if (base == null || base.isEmpty()) return null;
        if (base.endsWith("/api")) base = base.substring(0, base.length() - 4);
        if (file.startsWith("/api/file/download/")) return base + file;
        String t = file.startsWith("/") ? file.substring(1) : file;
        return base + "/api/file/download/" + t;
    }
    /** 点击消息切换群组（来自 ClickEvent.Custom 的 payload：房间 id）。 */
    public void openRoomFromClick(String roomIdStr) {
        try {
            long id = Long.parseLong(roomIdStr);
            ChatRoom r;
            synchronized (rooms) {
                r = rooms.get(id);
            }
            if (r == null) {
                print("群组信息缺失，请先 .irc list 刷新");
                return;
            }
            currentRoomId = id;
            config.setCurrentRoomId(id);
            config.save();
            ws.sendRead(id);
            print("已切换到群组 " + r.name() + "（群号 " + r.code() + "）");
        } catch (NumberFormatException e) {
            print("无效的群组 ID");
        }
    }

    public List<String> getRoomCodes() {
        synchronized (rooms) {
            List<String> out = new ArrayList<>();
            for (ChatRoom r : rooms.values()) {
                if (r.code() != null && !r.code().isEmpty()) out.add(r.code());
            }
            return out;
        }
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    // ---------- WebSocket 事件 ----------

    private void handleWsEvent(String key, JsonObject value) {
        switch (key) {
            case "chat.message" -> onChatMessage(value);
            case "chat.withdraw" -> onWithdraw(value);
            case "chat.systemMessage" -> onSystemMessage(value);
            case "chat.kicked" -> onKicked(value);
            case "chat.roomDeleted" -> onRoomDeleted(value);
            case "chat.memberChanged" -> onMemberChanged(value);
            case "chat.nickNameUpdated" -> onNickUpdated(value);
            case "chat.unreadChanged" -> onUnreadChanged(value);
            case "userOnline" -> onUserOnline(value, true);
            case "userOffline" -> onUserOnline(value, false);
            default -> { }
        }
    }

    private void onChatMessage(JsonObject value) {
        ChatMessage m = ChatMessage.from(value);
        if (m == null) return;
        if (m.chatRoomId() != 0) recentActiveRoomId = m.chatRoomId();
        String sender = m.senderName();
        String body = m.displayContent();
        if (m.chatRoomUserId() == 0) {
            system(body);
            return;
        }
        ChatRoom r;
        synchronized (rooms) {
            r = rooms.get(m.chatRoomId());
        }
        String roomName = r != null ? r.name() : String.valueOf(m.chatRoomId());
        String pfx = config.getCommandPrefix();
        final String prefix = (pfx == null || pfx.isEmpty()) ? "." : pfx;

        // [群名] 点击切换到该群组
        Component group = Component.literal("[" + roomName + "]")
                .withStyle(s -> s.withClickEvent(new ClickEvent.Custom(McmsClickIds.SWITCH_ROOM,
                                Optional.<Tag>of(StringTag.valueOf(String.valueOf(m.chatRoomId())))))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击切换到该群组")))
                        .withColor(ChatFormatting.GOLD));
        // [用户名] 点击打开聊天栏输入 .irc @<用户>
        Component user = Component.literal("[" + sender + "]")
                .withStyle(s -> s.withClickEvent(new ClickEvent.SuggestCommand(prefix + "irc @" + sender + " "))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击 @ 该成员")))
                        .withColor(ChatFormatting.AQUA));
        // 内容：媒体 → 浏览器打开；文本 → 点击引用回复
        Component content;
        if (m.withdrawn()) {
            content = Component.literal(body).withStyle(ChatFormatting.GRAY);
        } else if (m.type() == 1 || m.type() == 2 || m.type() == 3 || m.type() == 4) {
            Style st = Style.EMPTY.withColor(ChatFormatting.GREEN)
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击在浏览器中打开")));
            String url = mediaUrl(m.content());
            if (url != null) {
                try {
                    st = st.withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(url)));
                } catch (Exception ignored) {
                }
            }
            content = Component.literal(body).withStyle(st);
        } else {
            content = Component.literal(body)
                    .withStyle(s -> s.withClickEvent(new ClickEvent.SuggestCommand(prefix + "irc reply " + m.id() + " "))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击引用回复")))
                            .withColor(ChatFormatting.WHITE));
        }
        printText(Component.literal("").append(group).append(user).append(Component.literal(" ")).append(content));
    }

    private void onWithdraw(JsonObject value) {
        long roomId = value != null && value.has("chatRoomId") ? value.get("chatRoomId").getAsLong() : 0;
        if (roomId == currentRoomId) {
            system("一条消息被撤回");
        }
    }

    private void onSystemMessage(JsonObject value) {
        String content = value != null && value.has("content") ? value.get("content").getAsString() : "";
        system(content);
    }

    private void onKicked(JsonObject value) {
        print("你已被移出群组");
        long id = value != null && value.has("chatRoomId") ? value.get("chatRoomId").getAsLong() : 0;
        if (id == currentRoomId) {
            currentRoomId = 0;
            config.setCurrentRoomId(0);
            config.save();
        }
    }

    private void onRoomDeleted(JsonObject value) {
        system("一个群组已被删除");
    }

    private void onMemberChanged(JsonObject value) {
        system("群组成员发生变化");
    }

    private void onNickUpdated(JsonObject value) {
        system("成员昵称已更新");
    }

    private void onUnreadChanged(JsonObject value) {
        // 未读数变化，暂不打扰玩家
    }

    private void onUserOnline(JsonObject value, boolean online) {
        // 成员上下线，暂不打扰玩家
    }
}