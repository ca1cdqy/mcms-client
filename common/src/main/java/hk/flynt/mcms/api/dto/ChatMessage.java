package hk.flynt.mcms.api.dto;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import hk.flynt.mcms.api.Js;

import java.util.ArrayList;
import java.util.List;

/** 聊天消息。chatRoomUserId == 0 表示系统消息。type: 0文本 1图片 2视频 3链接 4文件。 */
public record ChatMessage(long id, long chatRoomId, long chatRoomUserId, String content, int type,
                          boolean withdrawn, Long answerMessageId, Sender sender) {

    public record Sender(long id, String username, String avatar) {
        public static Sender from(JsonObject o) {
            if (o == null) return null;
            return new Sender(Js.lng(o, "id", 0), Js.str(o, "username", "未知"), Js.str(o, "avatar", ""));
        }
    }

    public static ChatMessage from(JsonObject o) {
        if (o == null) return null;
        long crid = Js.lng(o, "chatRoomId", 0);
        if (crid == 0) {
            JsonObject sender = Js.obj(o, "sender");
            crid = sender != null ? Js.lng(sender, "chatRoomId", 0) : 0;
        }
        // 前端实际结构：sender.nickUserName / sender.user.username / 顶层 senderUser / senderUserId
        JsonObject senderObj = Js.obj(o, "sender");
        JsonObject userObj = Js.obj(senderObj, "user");
        JsonObject senderUserObj = Js.obj(o, "senderUser");

        String username = Js.str(senderObj, "nickUserName");
        if (username == null || username.isEmpty()) {
            username = userObj != null ? Js.str(userObj, "username", null) : null;
        }
        if (username == null || username.isEmpty()) {
            username = senderUserObj != null ? Js.str(senderUserObj, "username", null) : null;
        }
        String avatar = "";
        if (userObj != null) avatar = Js.str(userObj, "avatar", "");
        if (avatar.isEmpty() && senderUserObj != null) avatar = Js.str(senderUserObj, "avatar", "");

        long senderUserId = Js.lng(o, "senderUserId", 0);
        if (senderUserId == 0 && userObj != null) senderUserId = Js.lng(userObj, "id", 0);
        if (senderUserId == 0 && senderUserObj != null) senderUserId = Js.lng(senderUserObj, "id", 0);

        Long answer = null;
        if (o.has("answerMessageId") && !o.get("answerMessageId").isJsonNull()) {
            try { answer = o.get("answerMessageId").getAsLong(); } catch (Exception ignored) { }
        }
        return new ChatMessage(
                Js.lng(o, "id", 0),
                crid,
                Js.lng(o, "chatRoomUserId", 0),
                Js.str(o, "content", ""),
                Js.in(o, "type", 0),
                Js.bool(o, "isWithDraw", false),
                answer,
                new Sender(senderUserId, username == null ? "" : username, avatar));
    }

    public static List<ChatMessage> listFrom(JsonArray arr) {
        List<ChatMessage> out = new ArrayList<>();
        if (arr == null) return out;
        for (var e : arr) {
            if (e.isJsonObject()) {
                ChatMessage m = from(e.getAsJsonObject());
                if (m != null) out.add(m);
            }
        }
        return out;
    }

    public String senderName() {
        if (chatRoomUserId == 0) return "系统";
        if (sender != null && sender.username() != null && !sender.username().isBlank()) {
            return sender.username();
        }
        if (sender != null && sender.id() != 0) {
            return "ID:" + sender.id();
        }
        return "未知";
    }

    public String displayContent() {
        if (withdrawn) return "[消息已撤回]";
        return switch (type) {
            case 1 -> "[图片]";
            case 2 -> "[视频]";
            case 3 -> "[链接]";
            case 4 -> "[文件]";
            default -> content == null ? "" : content;
        };
    }
}