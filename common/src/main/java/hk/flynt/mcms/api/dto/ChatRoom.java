package hk.flynt.mcms.api.dto;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import hk.flynt.mcms.api.Js;

import java.util.ArrayList;
import java.util.List;

/** 聊天室（群组）。 */
public record ChatRoom(long id, String name, String code, int unread, boolean allowJoin,
                       long ownerId, int memberCount) {
    public static ChatRoom from(JsonObject o) {
        if (o == null) return null;
        return new ChatRoom(
                Js.lng(o, "id", 0),
                Js.str(o, "name", "未命名"),
                Js.str(o, "code", ""),
                Js.in(o, "unread", 0),
                Js.bool(o, "allowJoin", false),
                Js.lng(o, "ownerId", 0),
                Js.in(o, "memberCount", 0));
    }

    public static List<ChatRoom> listFrom(JsonArray arr) {
        List<ChatRoom> out = new ArrayList<>();
        if (arr == null) return out;
        for (var e : arr) {
            if (e.isJsonObject()) {
                ChatRoom r = from(e.getAsJsonObject());
                if (r != null) out.add(r);
            }
        }
        return out;
    }
}