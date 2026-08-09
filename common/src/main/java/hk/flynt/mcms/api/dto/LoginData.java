package hk.flynt.mcms.api.dto;

import com.google.gson.JsonObject;
import hk.flynt.mcms.api.Js;

/** /user/login 与 /user/autoLogin 的返回数据。 */
public record LoginData(long id, String token, String username, String avatar, String role, int userType) {
    public static LoginData from(JsonObject o) {
        if (o == null) return null;
        return new LoginData(
                Js.lng(o, "id", 0),
                Js.str(o, "token", ""),
                Js.str(o, "username", ""),
                Js.str(o, "avatar", ""),
                Js.str(o, "role", ""),
                Js.in(o, "userType", 0));
    }
}