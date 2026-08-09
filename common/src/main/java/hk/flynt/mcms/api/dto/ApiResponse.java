package hk.flynt.mcms.api.dto;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** 接口统一返回包装：{ status, data, content }。status 1 为成功。 */
public record ApiResponse(int status, JsonElement data, String content) {
    public boolean ok() {
        return status == 1;
    }

    public boolean expired() {
        return status == -1;
    }

    public JsonObject dataObj() {
        return data != null && data.isJsonObject() ? data.getAsJsonObject() : null;
    }
}