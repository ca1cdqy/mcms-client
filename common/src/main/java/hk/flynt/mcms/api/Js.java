package hk.flynt.mcms.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Gson 容错取值工具（前端接口 status 可能是 1 或 true）。 */
public final class Js {
    private Js() {
    }

    public static int status(JsonElement e) {
        if (e == null || e.isJsonNull()) return 0;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean() ? 1 : 0;
            if (p.isNumber()) return p.getAsInt();
        }
        return 0;
    }

    public static String str(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() ? e.getAsString() : null;
    }

    public static String str(JsonObject o, String key, String def) {
        String v = str(o, key);
        return v != null ? v : def;
    }

    public static long lng(JsonObject o, String key, long def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        try { return e.getAsLong(); } catch (Exception ex) { return def; }
    }

    public static int in(JsonObject o, String key, int def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        try { return e.getAsInt(); } catch (Exception ex) { return def; }
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return def;
        try { return e.getAsBoolean(); } catch (Exception ex) { return def; }
    }

    public static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }
}