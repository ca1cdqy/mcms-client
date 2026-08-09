package hk.flynt.mcms.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hk.flynt.mcms.McmsMod;
import hk.flynt.mcms.api.dto.ApiResponse;
import hk.flynt.mcms.api.dto.ChatMessage;
import hk.flynt.mcms.api.dto.ChatRoom;
import hk.flynt.mcms.api.dto.LoginData;
import hk.flynt.mcms.api.dto.MessagePage;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCMS REST 客户端。base = https://mcms.flynt.hk/api，鉴权使用自定义请求头 {@code token}。
 * 完全模拟网页端 requestData 的请求方式（POST JSON body + token 头）。
 */
public class McmsApiClient {
    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final HttpClient http;
    private volatile String token = "";

    public McmsApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token;
    }

    public String getToken() {
        return token;
    }

    public ApiResponse request(String path, JsonObject body) throws IOException, InterruptedException {
        return request(path, null, body, "POST");
    }

    public ApiResponse request(String path, Map<String, Object> query, JsonObject body, String method) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder(baseUrl).append(path);
        if (query != null && !query.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, Object> e : query.entrySet()) {
                if (!first) sb.append('&');
                first = false;
                sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
            }
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(sb.toString()))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        String t = token;
        if (t != null && !t.isEmpty()) {
            rb.header("token", t);
        }
        if ("GET".equalsIgnoreCase(method)) {
            rb.GET();
        } else {
            rb.method(method.toUpperCase(),
                    body != null ? HttpRequest.BodyPublishers.ofString(GSON.toJson(body)) : HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            McmsMod.LOGGER.warn("[MCMS] request failed: {} -> {}", path, e.toString());
            return new ApiResponse(0, null, "网络请求失败：" + e.getMessage());
        }
        if (resp.statusCode() >= 400) {
            McmsMod.LOGGER.warn("[MCMS] HTTP {} for {}", resp.statusCode(), path);
            return new ApiResponse(0, null, "HTTP " + resp.statusCode());
        }
        return parse(resp.body());
    }

    private ApiResponse parse(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            int status = Js.status(root.get("status"));
            JsonElement data = root.has("data") && !root.get("data").isJsonNull() ? root.get("data") : null;
            String content = Js.str(root, "content", "");
            return new ApiResponse(status, data, content);
        } catch (Exception e) {
            McmsMod.LOGGER.warn("[MCMS] bad response body: {}", body);
            return new ApiResponse(0, null, "无法解析响应");
        }
    }

    // ---------- 认证 ----------

    public LoginData login(String username, String password) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        ApiResponse r = request("/user/login", body);
        return r.ok() ? LoginData.from(r.dataObj()) : null;
    }

    public LoginData autoLogin(String token) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        ApiResponse r = request("/user/autoLogin", body);
        return r.ok() ? LoginData.from(r.dataObj()) : null;
    }

    public void exitLogin() throws IOException, InterruptedException {
        request("/user/exitLogin", new JsonObject());
    }

    // ---------- 聊天 ----------

    public List<ChatRoom> getMyRooms() throws IOException, InterruptedException {
        ApiResponse r = request("/chat/getMyRooms", new JsonObject());
        if (!r.ok()) return List.of();
        return ChatRoom.listFrom(Js.arr(r.dataObj(), "list"));
    }

    public ChatRoom getRoomById(long id) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        ApiResponse r = request("/chat/getRoomById", body);
        return r.ok() ? ChatRoom.from(r.dataObj()) : null;
    }

    public MessagePage getMessages(long roomId, int limit, Long beforeId) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("roomId", roomId);
        body.addProperty("limit", limit);
        if (beforeId != null) body.addProperty("beforeId", beforeId);
        ApiResponse r = request("/chat/getMessages", body);
        if (!r.ok()) return new MessagePage(List.of(), false);
        JsonObject d = r.dataObj();
        boolean hasMore = d != null && d.has("hasMore") && d.get("hasMore").getAsBoolean();
        return new MessagePage(ChatMessage.listFrom(Js.arr(d, "list")), hasMore);
    }

    public ChatRoom createRoom(String name, boolean allowJoin) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("allowJoin", allowJoin ? 1 : 0);
        ApiResponse r = request("/chat/createRoom", body);
        return r.ok() ? ChatRoom.from(r.dataObj()) : null;
    }

    public ChatRoom joinByCode(String code) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        ApiResponse r = request("/chat/joinByCode", body);
        return r.ok() ? ChatRoom.from(r.dataObj()) : null;
    }

    public boolean quitRoom(long id) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        return request("/chat/quitRoom", body).ok();
    }

    public boolean withdrawMessage(long messageId) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("messageId", messageId);
        return request("/chat/withdrawMessage", body).ok();
    }

    // ---------- 城市 区域 街道 建筑 楼层 房间 ----------

    private static JsonObject b() {
        return new JsonObject();
    }

    private static JsonObject page(int page, int size) {
        JsonObject o = b();
        o.addProperty("page", page);
        o.addProperty("pageSize", size);
        return o;
    }

    public ApiResponse cityList() throws IOException, InterruptedException {
        return request("/city/getList", page(1, 1000));
    }

    public ApiResponse cityGet(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/city/getById", o);
    }

    public ApiResponse cityAdd(String name, String visitCommand, String desc, String size, long builderUserId) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("name", name);
        if (visitCommand != null && !visitCommand.isEmpty()) o.addProperty("visitCommand", visitCommand);
        if (desc != null && !desc.isEmpty()) o.addProperty("desc", desc);
        if (size != null && !size.isEmpty()) o.addProperty("size", size);
        if (builderUserId != 0) o.addProperty("builderUserId", builderUserId);
        return request("/city/add", o);
    }

    public ApiResponse cityUpdate(long id, String name, String visitCommand, String desc, String size, long builderUserId) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        o.addProperty("name", name);
        if (visitCommand != null && !visitCommand.isEmpty()) o.addProperty("visitCommand", visitCommand);
        if (desc != null && !desc.isEmpty()) o.addProperty("desc", desc);
        if (size != null && !size.isEmpty()) o.addProperty("size", size);
        if (builderUserId != 0) o.addProperty("builderUserId", builderUserId);
        return request("/city/update", o);
    }

    public ApiResponse cityDelete(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/city/delete", o);
    }

    public ApiResponse areaList(long cityId) throws IOException, InterruptedException {
        JsonObject o = page(1, 1000);
        if (cityId != 0) o.addProperty("cityId", cityId);
        return request("/area/getList", o);
    }

    public ApiResponse areaGet(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/area/getById", o);
    }

    public ApiResponse areaAdd(long cityId, String name) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("cityId", cityId);
        o.addProperty("name", name);
        return request("/area/add", o);
    }

    public ApiResponse areaUpdate(long id, String name) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        o.addProperty("name", name);
        return request("/area/update", o);
    }

    public ApiResponse areaDelete(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/area/delete", o);
    }

    public ApiResponse streetList(long areaId) throws IOException, InterruptedException {
        JsonObject o = b();
        if (areaId != 0) o.addProperty("areaId", areaId);
        return request("/street/getList", o);
    }

    public ApiResponse streetGet(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/street/getById", o);
    }

    public ApiResponse streetAdd(long areaId, String name) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("areaId", areaId);
        o.addProperty("name", name);
        return request("/street/add", o);
    }

    public ApiResponse streetUpdate(long id, String name) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        o.addProperty("name", name);
        return request("/street/update", o);
    }

    public ApiResponse streetDelete(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/street/delete", o);
    }

    public ApiResponse buildingList(long areaId) throws IOException, InterruptedException {
        JsonObject o = page(1, 1000);
        if (areaId != 0) o.addProperty("areaId", areaId);
        return request("/building/getList", o);
    }

    public ApiResponse buildingGet(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/building/getById", o);
    }

    public ApiResponse buildingAdd(long streetId, String name, int maxFloor, boolean redStone, boolean disabled, String desc) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("streetId", streetId);
        o.addProperty("name", name);
        o.addProperty("maxFloor", maxFloor);
        o.addProperty("haveRedStonePower", redStone ? 1 : 0);
        o.addProperty("disabled", disabled ? 1 : 0);
        if (desc != null && !desc.isEmpty()) o.addProperty("desc", desc);
        return request("/building/add", o);
    }

    public ApiResponse buildingUpdate(long id, String name, int maxFloor, boolean redStone, boolean disabled, String desc) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("maxFloor", maxFloor);
        o.addProperty("haveRedStonePower", redStone ? 1 : 0);
        o.addProperty("disabled", disabled ? 1 : 0);
        if (desc != null && !desc.isEmpty()) o.addProperty("desc", desc);
        return request("/building/update", o);
    }

    public ApiResponse buildingDelete(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/building/delete", o);
    }

    public ApiResponse floorList(long buildingId) throws IOException, InterruptedException {
        JsonObject o = b();
        if (buildingId != 0) o.addProperty("buildingId", buildingId);
        return request("/floor/getList", o);
    }

    public ApiResponse floorGet(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/floor/getById", o);
    }

    public ApiResponse floorAdd(long buildingId, int num, int height) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("buildingId", buildingId);
        o.addProperty("num", num);
        o.addProperty("height", height);
        return request("/floor/add", o);
    }

    public ApiResponse floorUpdate(long id, int num, int height) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        o.addProperty("num", num);
        o.addProperty("height", height);
        return request("/floor/update", o);
    }

    public ApiResponse floorDelete(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/floor/delete", o);
    }

    public ApiResponse floorDeleteBatch(List<Long> ids) throws IOException, InterruptedException {
        JsonObject o = b();
        o.add("ids", toArr(ids));
        return request("/floor/deleteBatch", o);
    }

    public ApiResponse floorRooms(long buildingId) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("buildingId", buildingId);
        return request("/floor/getByBuildingIdWithRooms", o);
    }

    public ApiResponse roomList(long floorId) throws IOException, InterruptedException {
        JsonObject o = b();
        if (floorId != 0) o.addProperty("floorId", floorId);
        return request("/room/getList", o);
    }

    public ApiResponse roomGet(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/room/getById", o);
    }

    public ApiResponse roomAdd(long floorId, String name, int size) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("floorId", floorId);
        o.addProperty("name", name);
        o.addProperty("size", size);
        return request("/room/add", o);
    }

    public ApiResponse roomUpdate(long id, String name, int size) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("size", size);
        return request("/room/update", o);
    }

    public ApiResponse roomDelete(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/room/delete", o);
    }

    public ApiResponse roomDeleteBatch(List<Long> ids) throws IOException, InterruptedException {
        JsonObject o = b();
        o.add("ids", toArr(ids));
        return request("/room/deleteBatch", o);
    }

    private static JsonArray toArr(List<Long> ids) {
        JsonArray a = new JsonArray();
        for (Long id : ids) a.add(id);
        return a;
    }

    // ---------- 入住记录 / 入住申请 ----------

    public ApiResponse recordList() throws IOException, InterruptedException {
        return request("/user-take-up-room-record/getList", b());
    }

    public ApiResponse recordByUser(long userId) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("userId", userId);
        return request("/user-take-up-room-record/getByUserId", o);
    }

    public ApiResponse recordAdd(long roomId, long userId) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("roomId", roomId);
        o.addProperty("userId", userId);
        return request("/user-take-up-room-record/add", o);
    }

    public ApiResponse recordDelete(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/user-take-up-room-record/delete", o);
    }

    public ApiResponse requestList(long userId, int status) throws IOException, InterruptedException {
        JsonObject o = page(1, 100);
        if (userId != 0) o.addProperty("userId", userId);
        if (status != 0) o.addProperty("status", status);
        return request("/user-take-up-room-request/getList", o);
    }

    public ApiResponse requestMine() throws IOException, InterruptedException {
        return request("/user-take-up-room-request/getMyList", b());
    }

    public ApiResponse requestGet(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/user-take-up-room-request/getById", o);
    }

    public ApiResponse requestAdd(long roomId, String desc) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("roomId", roomId);
        o.addProperty("desc", desc == null ? "" : desc);
        return request("/user-take-up-room-request/add", o);
    }

    public ApiResponse requestUpdate(long id, int status, String answer) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        o.addProperty("status", status);
        if (answer != null && !answer.isEmpty()) o.addProperty("answer", answer);
        return request("/user-take-up-room-request/update", o);
    }

    public ApiResponse requestDelete(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/user-take-up-room-request/delete", o);
    }

    // ---------- 帖子 / 评论 / 点赞 ----------

    public ApiResponse postList(long cityId, long areaId, long buildingId, int page, int pageSize) throws IOException, InterruptedException {
        JsonObject o = page(page, pageSize);
        if (cityId != 0) o.addProperty("cityId", cityId);
        if (areaId != 0) o.addProperty("areaId", areaId);
        if (buildingId != 0) o.addProperty("buildingId", buildingId);
        return request("/post/getList", o);
    }

    public ApiResponse postAdd(String title, String content, int priv, long cityId, long areaId, long buildingId) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("title", title);
        o.addProperty("content", content == null ? "" : content);
        o.addProperty("private", priv);
        if (cityId != 0) o.addProperty("cityId", cityId);
        if (areaId != 0) o.addProperty("areaId", areaId);
        if (buildingId != 0) o.addProperty("buildingId", buildingId);
        return request("/post/add", o);
    }

    public ApiResponse postDelete(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/post/delete", o);
    }

    public ApiResponse postLike(long postId) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("postId", postId);
        return request("/post-like/add", o);
    }

    public ApiResponse postUnlike(long postId) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("postId", postId);
        return request("/post-like/delete", o);
    }

    public ApiResponse postComments(long postId) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("postId", postId);
        return request("/post-feedback/getByPostId", o);
    }

    public ApiResponse postComment(long itemId, boolean isItemPost, String content) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("itemId", itemId);
        o.addProperty("isItemPost", isItemPost ? 1 : 0);
        o.addProperty("content", content == null ? "" : content);
        return request("/post-feedback/add", o);
    }

    public ApiResponse postCommentDelete(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/post-feedback/delete", o);
    }

    // ---------- 日志 ----------

    public ApiResponse logList(int page, int pageSize) throws IOException, InterruptedException {
        return request("/log/getList", page(page, pageSize));
    }

    public ApiResponse logGet(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/log/getById", o);
    }

    public ApiResponse logDelete(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/log/delete", o);
    }

    // ---------- 社区玩家 ----------

    public ApiResponse getPublicUsers() throws IOException, InterruptedException {
        return request("/player-public/getPublicUsers", b());
    }

    public ApiResponse getOnlineStatus(List<Long> userIds) throws IOException, InterruptedException {
        JsonObject o = b();
        o.add("userIds", toArr(userIds));
        return request("/chat/getOnlineStatus", o);
    }
    // ---------- 用户 / 管理 ----------

    public ApiResponse userSearch(String name) throws IOException, InterruptedException {
        JsonObject o = page(1, 100);
        if (name != null && !name.trim().isEmpty()) o.addProperty("name", name.trim());
        return request("/user/searchList", o);
    }

    public ApiResponse userStats(long userId) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", userId);
        return request("/user/getStats", o);
    }

    public ApiResponse userChangePassword(String oldPassword, String newPassword) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("oldPassword", oldPassword);
        o.addProperty("newPassword", newPassword);
        return request("/user/changePassword", o);
    }

    public ApiResponse userChangeInfo(int sex, int age) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("sex", sex);
        o.addProperty("age", age);
        return request("/user/changeUserInfo", o);
    }

    public ApiResponse adminUsers() throws IOException, InterruptedException {
        return request("/admin/getUsers", b());
    }

    public ApiResponse adminCreate(String username, String password, int sex, int age) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("username", username);
        o.addProperty("password", password);
        o.addProperty("sex", sex);
        o.addProperty("age", age);
        return request("/admin/createUser", o);
    }

    public ApiResponse adminUpdate(long id, int sex, int age, int isAdmin, int disabled, int q0, int q1, int q2, int q3) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        o.addProperty("sex", sex);
        o.addProperty("age", age);
        o.addProperty("isAdmin", isAdmin);
        o.addProperty("disabled", disabled);
        o.addProperty("roomQuotaSize0", q0);
        o.addProperty("roomQuotaSize1", q1);
        o.addProperty("roomQuotaSize2", q2);
        o.addProperty("roomQuotaSize3", q3);
        return request("/admin/updateUser", o);
    }

    public ApiResponse adminDelete(long id) throws IOException, InterruptedException {
        JsonObject o = b();
        o.addProperty("id", id);
        return request("/admin/deleteUser", o);
    }

    public ApiResponse adminQuota(List<Long> userIds, int deltaQuota, int size) throws IOException, InterruptedException {
        JsonObject o = b();
        o.add("userIds", toArr(userIds));
        o.addProperty("deltaQuota", deltaQuota);
        o.addProperty("size", size);
        return request("/admin/batchUpdateQuota", o);
    }
}