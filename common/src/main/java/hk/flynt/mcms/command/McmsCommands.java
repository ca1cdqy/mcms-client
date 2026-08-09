package hk.flynt.mcms.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import hk.flynt.mcms.api.Js;
import hk.flynt.mcms.api.McmsApiClient;
import hk.flynt.mcms.api.dto.ApiResponse;
import hk.flynt.mcms.config.McmsConfig;
import hk.flynt.mcms.irc.McmsIrc;
import hk.flynt.mcms.platform.PlatformClient;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;

/**
 * "." 前缀命令：解析执行 + Tab 补全（补全走真实 Brigadier，与原版一致）。
 * 命令组：irc / say / login / logout / status / setprefix / help /
 *         user / city / area / street / building / floor / room /
 *         record / apply / post / log / admin / setting / player
 */
public final class McmsCommands {
    private static final CommandDispatcher<Object> DISPATCHER = new CommandDispatcher<>();

    static {
        // irc：子命令 + set 群号补全 + 直接发消息
        DISPATCHER.register(literal("irc")
                .then(literal("create").then(argument("name", StringArgumentType.greedyString()).executes(c -> 1)))
                .then(literal("join").then(argument("code", StringArgumentType.word()).executes(c -> 1)))
                .then(literal("list").executes(c -> 1))
                .then(literal("quit").executes(c -> 1))
                .then(literal("reply").then(argument("args", StringArgumentType.greedyString()).executes(c -> 1)))
                .then(literal("set").then(argument("code", StringArgumentType.word())
                        .suggests((c, b) -> {
                            String partial = b.getRemaining().toLowerCase();
                            for (String code : McmsIrc.get().getRoomCodes()) {
                                if (code.toLowerCase().startsWith(partial)) {
                                    b.suggest(code);
                                }
                            }
                            return b.buildFuture();
                        })
                        .executes(c -> 1)))
                .then(argument("text", StringArgumentType.greedyString()).executes(c -> 1))
                .executes(c -> 1));
        // 直接带参数的命令
        registerArg("say");
        registerArg("login");
        registerArg("setprefix");
        // 无参数命令
        register("logout");
        register("status");
        register("help");
        // 分组子命令
        registerGroup("user", "pass", "sex", "age", "search");
        registerGroup("city", "list", "get", "add", "set", "del");
        registerGroup("area", "list", "get", "add", "set", "del");
        registerGroup("street", "list", "get", "add", "set", "del");
        registerGroup("building", "list", "get", "add", "set", "del");
        registerGroup("floor", "list", "get", "add", "set", "del");
        registerGroup("room", "list", "get", "add", "set", "del");
        registerGroup("record", "list", "mine", "add", "del");
        registerGroup("apply", "mine", "list", "get", "add", "pass", "reject", "del");
        registerGroup("post", "list", "add", "del", "like", "unlike", "comments", "comment", "cdel");
        registerGroup("log", "list", "get", "del");
        registerGroup("admin", "users", "create", "del", "quota", "set");
        registerGroup("setting", "get", "set");
        registerGroup("player", "list", "online", "offline");
    }

    private static void registerArg(String name) {
        DISPATCHER.register(literal(name)
                .then(argument("arg", StringArgumentType.greedyString()).executes(c -> 1))
                .executes(c -> 1));
    }

    private static void register(String name) {
        DISPATCHER.register(literal(name).executes(c -> 1));
    }

    private static void registerGroup(String name, String... subs) {
        var cmd = literal(name);
        for (String s : subs) {
            cmd = cmd.then(literal(s).then(argument("arg", StringArgumentType.greedyString()).executes(c -> 1)));
        }
        cmd = cmd.executes(c -> 1);
        DISPATCHER.register(cmd);
    }

    private McmsCommands() {
    }

    public static boolean execute(String cmd) {
        String s = cmd == null ? "" : cmd;
        int sp = s.indexOf(' ');
        String head = sp < 0 ? s : s.substring(0, sp);
        String rest = sp < 0 ? "" : s.substring(sp + 1);
        switch (head) {
            case "irc" -> {
                return irc(rest);
            }
            case "say" -> {
                if (rest == null || rest.trim().isEmpty()) {
                    McmsIrc.get().print("用法：.say <要发送的文字>（把以 . 开头的内容直接发到服务器）");
                } else {
                    PlatformClient.sendServerChat(rest);
                }
                return true;
            }
            case "login" -> {
                String[] parts = rest.split(" ", -1);
                if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                    McmsIrc.get().login(parts[0], parts[1], true);
                } else {
                    McmsIrc.get().login(rest.trim(), "", false);
                }
                return true;
            }
            case "logout" -> {
                McmsIrc.get().logout();
                return true;
            }
            case "status" -> {
                McmsIrc.get().status();
                return true;
            }
            case "setprefix" -> {
                String p = rest;
                if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) {
                    p = p.substring(1, p.length() - 1);
                }
                McmsConfig.get().setCommandPrefix(p);
                McmsConfig.get().save();
                McmsIrc.get().status();
                if (p.isEmpty()) {
                    McmsIrc.get().print("命令前缀已设为空，命令处理已禁用（可编辑 config/mcms.json 恢复）");
                } else {
                    McmsIrc.get().print("命令前缀已设为 [" + p + "]");
                }
                return true;
            }
            case "help" -> {
                help();
                return true;
            }
            case "user" -> {
                return user(rest);
            }
            case "city" -> {
                return city(rest);
            }
            case "area" -> {
                return area(rest);
            }
            case "street" -> {
                return street(rest);
            }
            case "building" -> {
                return building(rest);
            }
            case "floor" -> {
                return floor(rest);
            }
            case "room" -> {
                return room(rest);
            }
            case "record" -> {
                return record(rest);
            }
            case "apply" -> {
                return apply(rest);
            }
            case "post" -> {
                return post(rest);
            }
            case "log" -> {
                return log(rest);
            }
            case "admin" -> {
                return admin(rest);
            }

            case "setting" -> {
                return setting(rest);
            }
            case "player" -> {
                return player(rest);
            }
            default -> {
                McmsIrc.get().print("未知命令 [" + head + "]，输入 .help 查看全部命令");
                return true;
            }
        }
    }

    // ---------- 工具 ----------

    private static String[] args(String rest) {
        if (rest == null || rest.trim().isEmpty()) return new String[0];
        return rest.trim().split("\\s+");
    }

    private static String tail(String rest, int skipTokens) {
        if (rest == null) return "";
        String[] a = args(rest);
        StringBuilder sb = new StringBuilder();
        int seen = 0;
        for (String t : a) {
            if (seen >= skipTokens) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t);
            }
            seen++;
        }
        return sb.toString();
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static JsonArray dataList(ApiResponse r) {
        if (r == null || r.data() == null) return null;
        JsonObject d = r.dataObj();
        if (d != null) {
            JsonArray a = Js.arr(d, "list");
            if (a != null) return a;
        }
        return r.data().isJsonArray() ? r.data().getAsJsonArray() : null;
    }

    /** 分页列表：超过 10 行自动翻页，底部提供 [上一页]/[下一页] 点击。 */
    private static void listRows(McmsIrc irc, ApiResponse r, String detailCmd, String listCmd, int page, Function<JsonObject, String> fmt) {
        JsonArray arr = dataList(r);
        if (arr == null || arr.size() == 0) {
            irc.print("（空）");
            return;
        }
        int pageSize = 10;
        int size = arr.size();
        int totalPages = Math.max(1, (size + pageSize - 1) / pageSize);
        int p = Math.max(1, Math.min(page, totalPages));
        int start = (p - 1) * pageSize;
        int end = Math.min(start + pageSize, size);
        for (int i = start; i < end; i++) {
            JsonElement e = arr.get(i);
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            long id = Js.lng(o, "id", 0);
            irc.printText(idRow(detailCmd, id, fmt.apply(o)));
        }
        if (size > pageSize) {
            MutableComponent foot = Component.literal("  ");
            if (p > 1) {
                foot.append(clickable("[上一页]", prefix() + listCmd + " " + (p - 1)));
            }
            foot.append(Component.literal(" " + p + "/" + totalPages + " ").withStyle(ChatFormatting.DARK_GRAY));
            if (p < totalPages) {
                foot.append(clickable("[下一页]", prefix() + listCmd + " " + (p + 1)));
            }
            irc.printText(foot);
        }
    }

    private static MutableComponent idRow(String detailCmd, long id, String text) {
        MutableComponent c = Component.literal("  ");
        if (detailCmd != null && !detailCmd.isEmpty()) {
            c.append(Component.literal("#" + id)
                    .withStyle(s -> s.withClickEvent(new ClickEvent.SuggestCommand(prefix() + detailCmd + " " + id))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击执行 " + detailCmd + " " + id)))
                            .withColor(ChatFormatting.GOLD)));
        } else {
            c.append(Component.literal("#" + id).withStyle(ChatFormatting.GOLD));
        }
        return c.append(Component.literal(" " + text).withStyle(ChatFormatting.GRAY));
    }

    private static Component clickable(String text, String cmd) {
        return Component.literal(text).withStyle(s -> s.withClickEvent(new ClickEvent.SuggestCommand(cmd))
                .withColor(ChatFormatting.GOLD));
    }

    private static String prefix() {
        String p = McmsConfig.get().getCommandPrefix();
        return p == null || p.isEmpty() ? "." : p;
    }

    private static void help() {
        McmsIrc irc = McmsIrc.get();
        banner(irc, " mcms 帮助菜单 ");
        helpRow(irc, new String[][]{{".irc", "聊天群组"}, {".say <文字>", "发到服务器"}, {".login/.logout/.status", "登录/退出/状态"}});
        helpRow(irc, new String[][]{{".user", "个人信息/改密/搜索"}, {".city", "城市"}, {".area", "区域"}, {".street", "街道"}});
        helpRow(irc, new String[][]{{".building", "建筑"}, {".floor", "楼层"}, {".room", "房间"}, {".record", "入住记录"}});
        helpRow(irc, new String[][]{{".apply", "入住申请"}, {".post", "帖子/评论/点赞"}, {".log", "日志"}});
        helpRow(irc, new String[][]{{".admin", "用户管理"}, {".player", "社区玩家"}, {".setting", "设置"}});
        helpRow(irc, new String[][]{{".help", "本菜单"}});
        irc.printText(Component.literal("  每个命令输入 .xxx 查看用法（如 .city）").withStyle(ChatFormatting.GRAY));
        bannerEnd(irc);
    }    // ---------- 菜单 / 配色辅助 ----------

    private static void banner(McmsIrc irc, String title) {
        irc.printText(Component.literal("━━━━━━━━ ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(title).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" ━━━━━━━━").withStyle(ChatFormatting.DARK_GRAY)));
    }

    private static void bannerEnd(McmsIrc irc) {
        irc.printText(Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 一行命令菜单：命令金色、描述浅灰、" - "与" | "深灰。 */
    private static void helpRow(McmsIrc irc, String[][] pairs) {
        MutableComponent c = Component.literal("");
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                c.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
            }
            c.append(Component.literal(pairs[i][0]).withStyle(ChatFormatting.GOLD));
            if (pairs[i].length > 1 && !pairs[i][1].isEmpty()) {
                c.append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(pairs[i][1]).withStyle(ChatFormatting.GRAY));
            }
        }
        irc.printText(c);
    }

    /** 用法菜单：上下深灰分隔线 + 着色用法行。 */
    private static void usageMenu(McmsIrc irc, String line) {
        String s = line;
        if (s.startsWith("用法：")) s = s.substring(3);
        String t = s.trim();
        String head = t.indexOf(' ') < 0 ? t : t.substring(0, t.indexOf(' '));
        if (head.startsWith(".")) head = head.substring(1);
        banner(irc, " " + head + " 用法 ");
        usageLine(irc, s);
        bannerEnd(irc);
    }

    private static void usageLine(McmsIrc irc, String line) {
        String[] parts = line.split(" \\| ");
        MutableComponent c = Component.literal("");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                c.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
            }
            c.append(Component.literal(parts[i]).withStyle(ChatFormatting.GRAY));
        }
        irc.printText(c);
    }
    // ---------- user ----------

    private static boolean user(String rest) {
        String[] a = args(rest);
        String sub = a.length > 0 ? a[0] : "";
        McmsIrc irc = McmsIrc.get();
        switch (sub) {
            case "" -> {
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().userStats(irc.getSelfUserId());
                        if (!r.ok()) {
                            irc.printErr(r.content());
                            return;
                        }
                        JsonObject d = r.dataObj();
                        if (d == null) d = new JsonObject();
                        irc.printText(Component.literal("我的 MCMS 账号").withStyle(ChatFormatting.GOLD));
                        irc.print("  用户: " + McmsConfig.get().getUsername());
                        for (String k : new String[]{"id", "username", "sex", "age", "role", "userType", "cityCount", "areaCount", "buildingCount", "roomCount"}) {
                            if (d.has(k) && !d.get(k).isJsonNull()) {
                                irc.print("  " + k + ": " + d.get(k).getAsString());
                            }
                        }
                        irc.print("  .user pass/stats/sex/age/search 查看更多");
                    } catch (Exception e) {
                        irc.printErr("获取信息失败：" + e.getMessage());
                    }
                });
                return true;
            }
            case "pass" -> {
                if (a.length < 3) {
                    usageMenu(irc, "用法：.user pass <原密码> <新密码>");
                    return true;
                }
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().userChangePassword(a[1], a[2]);
                        if (r.ok()) irc.printOk("密码修改成功（下次登录请用新密码）");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("修改密码失败：" + e.getMessage());
                    }
                });
                return true;
            }
            case "sex" -> {
                if (a.length < 2) {
                    usageMenu(irc, "用法：.user sex <0保密|1男|2女>");
                    return true;
                }
                int sex = parseInt(a[1], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().userChangeInfo(sex, -1);
                        if (r.ok()) irc.printOk("性别已更新");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("更新失败：" + e.getMessage());
                    }
                });
                return true;
            }
            case "age" -> {
                if (a.length < 2) {
                    usageMenu(irc, "用法：.user age <年龄>");
                    return true;
                }
                int age = parseInt(a[1], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().userChangeInfo(-1, age);
                        if (r.ok()) irc.printOk("年龄已更新");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("更新失败：" + e.getMessage());
                    }
                });
                return true;
            }
            case "search" -> {
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().userSearch(tail(rest, 1));
                        if (!r.ok()) {
                            irc.printErr(r.content());
                            return;
                        }
                        JsonArray arr = dataList(r);
                        if (arr == null || arr.size() == 0) {
                            irc.print("（无匹配用户）");
                            return;
                        }
                        for (JsonElement e : arr) {
                            if (!e.isJsonObject()) continue;
                            JsonObject o = e.getAsJsonObject();
                            irc.printText(Component.literal("  " + Js.str(o, "username", "?") + "（ID:" + Js.lng(o, "id", 0) + "）").withStyle(ChatFormatting.WHITE));
                        }
                    } catch (Exception e) {
                        irc.printErr("搜索失败：" + e.getMessage());
                    }
                });
                return true;
            }
            default -> {
                usageMenu(irc, "用法：.user [pass|sex|age|search]（裸输入查看我的信息）");
                return true;
            }
        }
    }

    // ---------- city ----------

    private static boolean city(String rest) {
        String[] a = args(rest);
        String sub = a.length > 0 ? a[0] : "";
        McmsIrc irc = McmsIrc.get();
        switch (sub) {
            case "" -> {
                usageMenu(irc, "用法：.city list | get <id> | add <名称> [访问指令] [尺寸] [建设者ID] | set <id> <名称> [...] | del <id>");
                return true;
            }
            case "list" -> {
                int page = a.length > 1 ? parseInt(a[1], 1) : 1;
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().cityList();
                        if (!r.ok()) { irc.printErr(r.content()); return; }
                        JsonArray arr = dataList(r);
                        if (arr == null || arr.size() == 0) { irc.print("（空）"); return; }
                        irc.print("城市列表（点击 #ID 查看详情，访问指令可直接点击运行）：");
                        int pageSize = 10;
                        int size = arr.size();
                        int totalPages = Math.max(1, (size + pageSize - 1) / pageSize);
                        int p = Math.max(1, Math.min(page, totalPages));
                        int start = (p - 1) * pageSize;
                        int end = Math.min(start + pageSize, size);
                        for (int i = start; i < end; i++) {
                            JsonElement e = arr.get(i);
                            if (!e.isJsonObject()) continue;
                            JsonObject o = e.getAsJsonObject();
                            long id = Js.lng(o, "id", 0);
                            String vc = Js.str(o, "visitCommand", "");
                            MutableComponent line = idRow("city get", id, Js.str(o, "name", "?"));
                            if (!vc.isEmpty()) {
                                line.append(Component.literal(" "))
                                        .append(Component.literal(vc).withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand(vc))
                                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击运行此指令")))
                                                .withColor(ChatFormatting.DARK_GREEN)));
                            }
                            irc.printText(line);
                        }
                        if (size > pageSize) {
                            MutableComponent foot = Component.literal("  ");
                            if (p > 1) foot.append(clickable("[上一页]", prefix() + "city list " + (p - 1)));
                            foot.append(Component.literal(" " + p + "/" + totalPages + " ").withStyle(ChatFormatting.DARK_GRAY));
                            if (p < totalPages) foot.append(clickable("[下一页]", prefix() + "city list " + (p + 1)));
                            irc.printText(foot);
                        }
                    } catch (Exception e) {
                        irc.printErr("获取城市失败：" + e.getMessage());
                    }
                });
            }
            case "get" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.city get <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        detail(irc, irc.getApi().cityGet(id), "城市", new String[]{"id", "name", "visitCommand", "size", "desc", "builderUserId", "builderUsername", "buildTime"});
                    } catch (Exception e) {
                        irc.printErr("获取城市失败：" + e.getMessage());
                    }
                });
            }
            case "add" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.city add <名称> [访问指令] [尺寸] [建设者ID]"); return true; }
                String name = a[1];
                String vc = a.length > 2 ? a[2] : "";
                String size = a.length > 3 ? a[3] : "";
                long bid = a.length > 4 ? parseLong(a[4], 0) : 0;
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().cityAdd(name, vc, "", size, bid);
                        if (r.ok()) irc.printOk("城市 [" + name + "] 添加成功");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("添加失败：" + e.getMessage());
                    }
                });
            }
            case "set" -> {
                if (a.length < 3) { usageMenu(irc, "用法：.city set <id> <名称> [访问指令] [尺寸] [建设者ID]"); return true; }
                long id = parseLong(a[1], 0);
                String name = a[2];
                String vc = a.length > 3 ? a[3] : "";
                String size = a.length > 4 ? a[4] : "";
                long bid = a.length > 5 ? parseLong(a[5], 0) : 0;
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().cityUpdate(id, name, vc, "", size, bid);
                        if (r.ok()) irc.printOk("城市 [" + name + "] 已保存");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("修改失败：" + e.getMessage());
                    }
                });
            }
            case "del" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.city del <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().cityDelete(id);
                        if (r.ok()) irc.printOk("城市已删除");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("删除失败：" + e.getMessage());
                    }
                });
            }
            default -> {
                usageMenu(irc, "用法：.city list | get <id> | add <名称> [访问指令] [尺寸] [建设者ID] | set <id> <名称> [...] | del <id>");
                return true;
            }
        }
        return true;
    }

    // ---------- area / street / building / floor / room（通用） ----------

    private static boolean area(String rest) {
        return crud(rest, "area", "区域", "city", "城市ID");
    }

    private static boolean street(String rest) {
        return crud(rest, "street", "街道", "area", "区域ID");
    }

    private static boolean building(String rest) {
        return crud(rest, "building", "建筑", "area", "区域ID");
    }

    private static boolean floor(String rest) {
        return crud(rest, "floor", "楼层", "building", "建筑ID");
    }

    private static boolean room(String rest) {
        return crud(rest, "room", "房间", "floor", "楼层ID");
    }

    private static boolean crud(String rest, String group, String label, String parentName, String parentLabel) {
        String[] a = args(rest);
        String sub = a.length > 0 ? a[0] : "";
        McmsIrc irc = McmsIrc.get();
        McmsApiClient api = irc.getApi();
        switch (sub) {
            case "" -> {
                usageMenu(irc, "用法：." + group + " list [父ID] | get <id> | add <" + parentLabel + "> <名称> [参数...] | set <id> <名称> [参数...] | del <id>");
                return true;
            }
            case "list" -> {
                long pid = a.length > 1 ? parseLong(a[1], 0) : 0;
                int page = a.length > 2 ? parseInt(a[2], 1) : 1;
                irc.async(() -> {
                    try {
                        ApiResponse r = listOf(api, group, parentName, pid);
                        if (!r.ok()) { irc.printErr(r.content()); return; }
                        irc.print(label + "列表" + (pid != 0 ? "（父ID:" + pid + "）" : "") + "：");
                        String listCmd = group + " list" + (pid != 0 ? " " + pid : "");
                        listRows(irc, r, group + " get", listCmd, page, o -> {
                            String name = Js.str(o, "name", "?");
                            String extra = extraOf(o, group);
                            return name + (extra.isEmpty() ? "" : "  " + extra);
                        });
                    } catch (Exception e) {
                        irc.printErr("获取" + label + "失败：" + e.getMessage());
                    }
                });
            }
            case "get" -> {
                if (a.length < 2) { usageMenu(irc, "用法：." + group + " get <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        detail(irc, getOf(api, group, id), label, new String[]{"id", "name", "desc", "size", "maxFloor", "num", "height", "cityId", "areaId", "streetId", "buildingId", "floorId", "builderUserId", "builderUsername", "visitCommand", "haveRedStonePower", "disabled"});
                    } catch (Exception e) {
                        irc.printErr("获取" + label + "失败：" + e.getMessage());
                    }
                });
            }
            case "add" -> {
                if (a.length < 3) { usageMenu(irc, "用法：." + group + " add <" + parentLabel + "> <名称> [参数...]"); return true; }
                long pid = parseLong(a[1], 0);
                String name = a[2];
                String t = tail(rest, 3);
                irc.async(() -> {
                    try {
                        ApiResponse r = addOf(api, group, pid, name, t);
                        if (r.ok()) irc.printOk(label + " [" + name + "] 添加成功");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("添加失败：" + e.getMessage());
                    }
                });
            }
            case "set" -> {
                if (a.length < 3) { usageMenu(irc, "用法：." + group + " set <id> <名称> [参数...]"); return true; }
                long id = parseLong(a[1], 0);
                String name = a[2];
                String t = tail(rest, 3);
                irc.async(() -> {
                    try {
                        ApiResponse r = updateOf(api, group, id, name, t);
                        if (r.ok()) irc.printOk(label + " [" + name + "] 已保存");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("修改失败：" + e.getMessage());
                    }
                });
            }
            case "del" -> {
                if (a.length < 2) { usageMenu(irc, "用法：." + group + " del <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = deleteOf(api, group, id);
                        if (r.ok()) irc.printOk(label + "已删除");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("删除失败：" + e.getMessage());
                    }
                });
            }
            default -> {
                usageMenu(irc, "用法：." + group + " list [父ID] | get <id> | add <" + parentLabel + "> <名称> [参数...] | set <id> <名称> [参数...] | del <id>");
                return true;
            }
        }
        return true;
    }

    private static ApiResponse listOf(McmsApiClient api, String group, String parentName, long pid) throws Exception {
        return switch (group) {
            case "area" -> api.areaList(pid);
            case "street" -> api.streetList(pid);
            case "building" -> api.buildingList(pid);
            case "floor" -> api.floorList(pid);
            default -> api.roomList(pid);
        };
    }

    private static ApiResponse getOf(McmsApiClient api, String group, long id) throws Exception {
        return switch (group) {
            case "area" -> api.areaGet(id);
            case "street" -> api.streetGet(id);
            case "building" -> api.buildingGet(id);
            case "floor" -> api.floorGet(id);
            default -> api.roomGet(id);
        };
    }

    private static ApiResponse addOf(McmsApiClient api, String group, long pid, String name, String t) throws Exception {
        switch (group) {
            case "area" -> {
                return api.areaAdd(pid, name);
            }
            case "street" -> {
                return api.streetAdd(pid, name);
            }
            case "building" -> {
                String[] x = args(t);
                int maxFloor = x.length > 0 ? parseInt(x[0], 1) : 1;
                boolean rs = x.length > 1 && "1".equals(x[1]);
                boolean dis = x.length > 2 && "1".equals(x[2]);
                String desc = x.length > 3 ? tail(t, 3) : "";
                return api.buildingAdd(pid, name, maxFloor, rs, dis, desc);
            }
            case "floor" -> {
                String[] x = args(t);
                int num = x.length > 0 ? parseInt(x[0], 1) : 1;
                int height = x.length > 1 ? parseInt(x[1], 3) : 3;
                return api.floorAdd(pid, num, height);
            }
            default -> {
                String[] x = args(t);
                int size = x.length > 0 ? parseInt(x[0], 0) : 0;
                return api.roomAdd(pid, name, size);
            }
        }
    }

    private static ApiResponse updateOf(McmsApiClient api, String group, long id, String name, String t) throws Exception {
        switch (group) {
            case "area" -> {
                return api.areaUpdate(id, name);
            }
            case "street" -> {
                return api.streetUpdate(id, name);
            }
            case "building" -> {
                String[] x = args(t);
                int maxFloor = x.length > 0 ? parseInt(x[0], 1) : 1;
                boolean rs = x.length > 1 && "1".equals(x[1]);
                boolean dis = x.length > 2 && "1".equals(x[2]);
                return api.buildingUpdate(id, name, maxFloor, rs, dis, "");
            }
            case "floor" -> {
                String[] x = args(t);
                int num = x.length > 0 ? parseInt(x[0], 1) : 1;
                int height = x.length > 1 ? parseInt(x[1], 3) : 3;
                return api.floorUpdate(id, num, height);
            }
            default -> {
                String[] x = args(t);
                int size = x.length > 0 ? parseInt(x[0], 0) : 0;
                return api.roomUpdate(id, name, size);
            }
        }
    }

    private static ApiResponse deleteOf(McmsApiClient api, String group, long id) throws Exception {
        return switch (group) {
            case "area" -> api.areaDelete(id);
            case "street" -> api.streetDelete(id);
            case "building" -> api.buildingDelete(id);
            case "floor" -> api.floorDelete(id);
            default -> api.roomDelete(id);
        };
    }

    private static String extraOf(JsonObject o, String group) {
        return switch (group) {
            case "building" -> "层数:" + Js.in(o, "maxFloor", 0);
            case "floor" -> "第" + Js.in(o, "num", 0) + "层 高:" + Js.in(o, "height", 0);
            case "room" -> "大小:" + Js.in(o, "size", 0);
            default -> "";
        };
    }

    private static void detail(McmsIrc irc, ApiResponse r, String label, String[] keys) {
        if (!r.ok()) {
            irc.printErr(r.content());
            return;
        }
        JsonObject d = r.dataObj();
        if (d == null) {
            irc.print(label + "：无数据");
            return;
        }
        irc.printText(Component.literal(label + "详情").withStyle(ChatFormatting.GOLD));
        for (String k : keys) {
            if (d.has(k) && !d.get(k).isJsonNull()) {
                String v = d.get(k).getAsString();
                if ("visitCommand".equals(k) && !v.isEmpty()) {
                    irc.printText(Component.literal("  visitCommand: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(v).withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand(v))
                                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击运行此指令")))
                                    .withColor(ChatFormatting.DARK_GREEN))));
                } else {
                    irc.printText(Component.literal("  " + k + ": ").withStyle(ChatFormatting.DARK_GRAY)
                            .append(Component.literal(v).withStyle(ChatFormatting.GRAY)));
                }
            }
        }
    }    // ---------- record ----------

    private static boolean record(String rest) {
        String[] a = args(rest);
        String sub = a.length > 0 ? a[0] : "";
        McmsIrc irc = McmsIrc.get();
        switch (sub) {
            case "" -> {
                usageMenu(irc, "用法：.record list | mine | add <roomId> <userId> | del <id>");
                return true;
            }
            case "list" -> {
                int page = a.length > 1 ? parseInt(a[1], 1) : 1;
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().recordList();
                        if (!r.ok()) { irc.printErr(r.content()); return; }
                        irc.print("入住记录：");
                        listRows(irc, r, null, "record list", page, o -> "房间ID:" + Js.lng(o, "roomId", 0) + " 用户:" + userOf(o));
                    } catch (Exception e) {
                        irc.printErr("获取记录失败：" + e.getMessage());
                    }
                });
            }
            case "mine" -> {
                int page = a.length > 1 ? parseInt(a[1], 1) : 1;
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().recordByUser(irc.getSelfUserId());
                        if (!r.ok()) { irc.printErr(r.content()); return; }
                        irc.print("我的入住记录：");
                        listRows(irc, r, null, "record mine", page, o -> "房间ID:" + Js.lng(o, "roomId", 0));
                    } catch (Exception e) {
                        irc.printErr("获取记录失败：" + e.getMessage());
                    }
                });
            }
            case "add" -> {
                if (a.length < 3) { usageMenu(irc, "用法：.record add <roomId> <userId>"); return true; }
                long roomId = parseLong(a[1], 0);
                long userId = parseLong(a[2], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().recordAdd(roomId, userId);
                        if (r.ok()) irc.printOk("已为玩家分配绑定该房间");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("分配失败：" + e.getMessage());
                    }
                });
            }
            case "del" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.record del <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().recordDelete(id);
                        if (r.ok()) irc.printOk("记录已删除");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("删除失败：" + e.getMessage());
                    }
                });
            }
            default -> {
                usageMenu(irc, "用法：.record list | mine | add <roomId> <userId> | del <id>");
                return true;
            }
        }
        return true;
    }

    private static String userOf(JsonObject o) {
        JsonObject u = Js.obj(o, "user");
        if (u != null) return Js.str(u, "username", "ID:" + Js.lng(u, "id", 0));
        return "用户ID:" + Js.lng(o, "userId", 0);
    }

    private static String statusOf(int s) {
        return switch (s) {
            case 0 -> "待审核";
            case 1 -> "已通过";
            case 2 -> "已拒绝";
            default -> String.valueOf(s);
        };
    }

    // ---------- apply ----------

    private static boolean apply(String rest) {
        String[] a = args(rest);
        String sub = a.length > 0 ? a[0] : "";
        McmsIrc irc = McmsIrc.get();
        switch (sub) {
            case "" -> {
                usageMenu(irc, "用法：.apply mine | list [status] | get <id> | add <roomId> <理由> | pass <id> [答复] | reject <id> [答复] | del <id>");
                return true;
            }
            case "mine" -> {
                int page = a.length > 1 ? parseInt(a[1], 1) : 1;
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().requestMine();
                        if (!r.ok()) { irc.printErr(r.content()); return; }
                        irc.print("我的入住申请：");
                        listRows(irc, r, "apply get", "apply mine", page, o -> "房间ID:" + Js.lng(o, "roomId", 0) + " 状态:" + statusOf(Js.in(o, "status", 0)));
                    } catch (Exception e) {
                        irc.printErr("获取申请失败：" + e.getMessage());
                    }
                });
            }
            case "list" -> {
                int status = a.length > 1 ? parseInt(a[1], 0) : 0;
                int page = a.length > 2 ? parseInt(a[2], 1) : 1;
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().requestList(0, status);
                        if (!r.ok()) { irc.printErr(r.content()); return; }
                        irc.print("入住申请列表：");
                        String listCmd = "apply list" + (status != 0 ? " " + status : "");
                        listRows(irc, r, "apply get", listCmd, page, o -> "房间ID:" + Js.lng(o, "roomId", 0) + " " + userOf(o) + " 状态:" + statusOf(Js.in(o, "status", 0)));
                    } catch (Exception e) {
                        irc.printErr("获取申请失败：" + e.getMessage());
                    }
                });
            }
            case "get" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.apply get <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        detail(irc, irc.getApi().requestGet(id), "入住申请", new String[]{"id", "roomId", "userId", "status", "desc", "answer", "createTime"});
                    } catch (Exception e) {
                        irc.printErr("获取申请失败：" + e.getMessage());
                    }
                });
            }
            case "add" -> {
                if (a.length < 3) { usageMenu(irc, "用法：.apply add <roomId> <理由/用途>"); return true; }
                long roomId = parseLong(a[1], 0);
                String desc = tail(rest, 2);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().requestAdd(roomId, desc);
                        if (r.ok()) irc.printOk("入住申请已提交");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("提交失败：" + e.getMessage());
                    }
                });
            }
            case "pass" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.apply pass <id> [答复]"); return true; }
                long id = parseLong(a[1], 0);
                String answer = tail(rest, 2);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().requestUpdate(id, 1, answer);
                        if (r.ok()) irc.printOk("已批准申请，房间已分配");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("审批失败：" + e.getMessage());
                    }
                });
            }
            case "reject" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.apply reject <id> [答复]"); return true; }
                long id = parseLong(a[1], 0);
                String answer = tail(rest, 2);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().requestUpdate(id, 2, answer);
                        if (r.ok()) irc.printOk("已拒绝申请");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("审批失败：" + e.getMessage());
                    }
                });
            }
            case "del" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.apply del <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().requestDelete(id);
                        if (r.ok()) irc.printOk("申请已删除");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("删除失败：" + e.getMessage());
                    }
                });
            }
            default -> {
                usageMenu(irc, "用法：.apply mine | list [status] | get <id> | add <roomId> <理由> | pass <id> [答复] | reject <id> [答复] | del <id>");
                return true;
            }
        }
        return true;
    }

    // ---------- post ----------

    private static boolean post(String rest) {
        String[] a = args(rest);
        String sub = a.length > 0 ? a[0] : "";
        McmsIrc irc = McmsIrc.get();
        switch (sub) {
            case "" -> {
                usageMenu(irc, "用法：.post list | add <标题> | <内容> | del <id> | like <id> | unlike <id> | comments <id> | comment <帖子ID> <内容> | cdel <评论ID>");
                return true;
            }
            case "list" -> {
                int page = a.length > 1 ? parseInt(a[1], 1) : 1;
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().postList(0, 0, 0, 1, 200);
                        if (!r.ok()) { irc.printErr(r.content()); return; }
                        irc.print("社区帖子：");
                        listRows(irc, r, null, "post list", page, o -> Js.str(o, "title", "?") + "（" + userOf(o) + "）");
                    } catch (Exception e) {
                        irc.printErr("获取帖子失败：" + e.getMessage());
                    }
                });
            }
            case "add" -> {
                int bar = rest.indexOf('|');
                if (bar < 0) { usageMenu(irc, "用法：.post add <标题> | <内容>"); return true; }
                String title = rest.substring(0, bar).trim();
                String content = rest.substring(bar + 1).trim();
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().postAdd(title, content, 0, 0, 0, 0);
                        if (r.ok()) irc.printOk("帖子发布成功：" + title);
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("发布失败：" + e.getMessage());
                    }
                });
            }
            case "del" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.post del <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().postDelete(id);
                        if (r.ok()) irc.printOk("帖子已删除");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("删除失败：" + e.getMessage());
                    }
                });
            }
            case "like" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.post like <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().postLike(id);
                        if (r.ok()) irc.printOk("点赞成功");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("点赞失败：" + e.getMessage());
                    }
                });
            }
            case "unlike" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.post unlike <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().postUnlike(id);
                        if (r.ok()) irc.printOk("已取消点赞");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("取消点赞失败：" + e.getMessage());
                    }
                });
            }
            case "comments" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.post comments <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().postComments(id);
                        if (!r.ok()) { irc.printErr(r.content()); return; }
                        JsonArray arr = dataList(r);
                        if (arr == null || arr.size() == 0) { irc.print("（暂无评论）"); return; }
                        irc.print("评论：");
                        for (JsonElement e : arr) {
                            if (!e.isJsonObject()) continue;
                            JsonObject o = e.getAsJsonObject();
                            irc.print("  " + userOf(o) + ": " + Js.str(o, "content", ""));
                        }
                    } catch (Exception e) {
                        irc.printErr("获取评论失败：" + e.getMessage());
                    }
                });
            }
            case "comment" -> {
                if (a.length < 3) { usageMenu(irc, "用法：.post comment <帖子ID> <内容>"); return true; }
                long id = parseLong(a[1], 0);
                String content = tail(rest, 2);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().postComment(id, true, content);
                        if (r.ok()) irc.printOk("评论成功");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("评论失败：" + e.getMessage());
                    }
                });
            }
            case "cdel" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.post cdel <评论ID>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().postCommentDelete(id);
                        if (r.ok()) irc.printOk("评论已删除");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("删除评论失败：" + e.getMessage());
                    }
                });
            }
            default -> {
                usageMenu(irc, "用法：.post list | add <标题> | <内容> | del <id> | like <id> | unlike <id> | comments <id> | comment <帖子ID> <内容> | cdel <评论ID>");
                return true;
            }
        }
        return true;
    }

    // ---------- log ----------

    private static boolean log(String rest) {
        String[] a = args(rest);
        String sub = a.length > 0 ? a[0] : "";
        McmsIrc irc = McmsIrc.get();
        switch (sub) {
            case "" -> {
                usageMenu(irc, "用法：.log list | get <id> | del <id>");
                return true;
            }
            case "list" -> {
                int page = a.length > 1 ? parseInt(a[1], 1) : 1;
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().logList(page, 50);
                        if (!r.ok()) { irc.printErr(r.content()); return; }
                        irc.print("日志：");
                        listRows(irc, r, "log get", "log list", page, o -> Js.str(o, "title", Js.str(o, "operation", "?")) + " " + userOf(o));
                    } catch (Exception e) {
                        irc.printErr("获取日志失败：" + e.getMessage());
                    }
                });
            }
            case "get" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.log get <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        detail(irc, irc.getApi().logGet(id), "日志", new String[]{"id", "userId", "operation", "level", "content", "createTime"});
                    } catch (Exception e) {
                        irc.printErr("获取日志失败：" + e.getMessage());
                    }
                });
            }
            case "del" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.log del <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().logDelete(id);
                        if (r.ok()) irc.printOk("日志已删除");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("删除失败：" + e.getMessage());
                    }
                });
            }
            default -> {
                usageMenu(irc, "用法：.log list | get <id> | del <id>");
                return true;
            }
        }
        return true;
    }    // ---------- admin ----------

    private static boolean admin(String rest) {
        String[] a = args(rest);
        String sub = a.length > 0 ? a[0] : "";
        McmsIrc irc = McmsIrc.get();
        switch (sub) {
            case "" -> {
                usageMenu(irc, "用法：.admin users | create <用户名> <密码> [性别] [年龄] | del <id> | quota <增量> <尺寸档0-3> <用户IDs...> | set <id> <额度0> <额度1> <额度2> <额度3> [isAdmin]");
                return true;
            }
            case "users" -> {
                int page = a.length > 1 ? parseInt(a[1], 1) : 1;
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().adminUsers();
                        if (!r.ok()) { irc.printErr(r.content()); return; }
                        irc.print("玩家列表：");
                        listRows(irc, r, null, "admin users", page, o -> Js.str(o, "username", "?") + " 额度:" + Js.in(o, "roomQuotaSize0", 0) + "/" + Js.in(o, "roomQuotaSize1", 0) + "/" + Js.in(o, "roomQuotaSize2", 0) + "/" + Js.in(o, "roomQuotaSize3", 0));
                    } catch (Exception e) {
                        irc.printErr("获取玩家失败：" + e.getMessage());
                    }
                });
            }
            case "create" -> {
                if (a.length < 3) { usageMenu(irc, "用法：.admin create <用户名> <密码> [性别] [年龄]"); return true; }
                String name = a[1];
                String pass = a[2];
                int sex = a.length > 3 ? parseInt(a[3], 0) : 0;
                int age = a.length > 4 ? parseInt(a[4], 0) : 0;
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().adminCreate(name, pass, sex, age);
                        if (r.ok()) irc.printOk("玩家 [" + name + "] 创建成功");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("创建失败：" + e.getMessage());
                    }
                });
            }
            case "del" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.admin del <id>"); return true; }
                long id = parseLong(a[1], 0);
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().adminDelete(id);
                        if (r.ok()) irc.printOk("玩家已删除");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("删除失败：" + e.getMessage());
                    }
                });
            }
            case "quota" -> {
                if (a.length < 4) { usageMenu(irc, "用法：.admin quota <增量> <尺寸档0-3> <用户IDs...>"); return true; }
                int delta = parseInt(a[1], 0);
                int size = parseInt(a[2], 0);
                List<Long> ids = new ArrayList<>();
                for (int i = 3; i < a.length; i++) ids.add(parseLong(a[i], 0));
                if (ids.isEmpty()) { irc.printErr("请提供用户ID"); return true; }
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().adminQuota(ids, delta, size);
                        if (r.ok()) irc.printOk("已为 " + ids.size() + " 名玩家调整额度");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("调整额度失败：" + e.getMessage());
                    }
                });
            }
            case "set" -> {
                if (a.length < 6) { usageMenu(irc, "用法：.admin set <id> <额度0> <额度1> <额度2> <额度3> [isAdmin]"); return true; }
                long id = parseLong(a[1], 0);
                int q0 = parseInt(a[2], 0);
                int q1 = parseInt(a[3], 0);
                int q2 = parseInt(a[4], 0);
                int q3 = parseInt(a[5], 0);
                int isAdmin = a.length > 6 ? parseInt(a[6], 0) : 0;
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().adminUpdate(id, -1, -1, isAdmin, -1, q0, q1, q2, q3);
                        if (r.ok()) irc.printOk("玩家信息已保存");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("保存失败：" + e.getMessage());
                    }
                });
            }
            default -> {
                usageMenu(irc, "用法：.admin users | create <用户名> <密码> [性别] [年龄] | del <id> | quota <增量> <尺寸档0-3> <用户IDs...> | set <id> <额度0> <额度1> <额度2> <额度3> [isAdmin]");
                return true;
            }
        }
        return true;
    }

    // ---------- player ----------

    private static boolean player(String rest) {
        String[] a = args(rest);
        String sub = a.length > 0 ? a[0] : "";
        McmsIrc irc = McmsIrc.get();
        int mode;
        switch (sub) {
            case "" -> {
                usageMenu(irc, "用法：.player list | online | offline");
                return true;
            }
            case "list" -> mode = 0;
            case "online" -> mode = 1;
            case "offline" -> mode = 2;
            default -> {
                usageMenu(irc, "用法：.player list | online | offline");
                return true;
            }
        }
        final int m = mode;
        final int pg = a.length > 1 ? parseInt(a[1], 1) : 1;
        final String modeCmd = sub;
        irc.async(() -> {
            try {
                ApiResponse r = irc.getApi().getPublicUsers();
                if (!r.ok()) {
                    irc.printErr(r.content());
                    return;
                }
                JsonArray arr = dataList(r);
                if (arr == null || arr.size() == 0) {
                    irc.print("（无玩家）");
                    return;
                }
                List<Long> ids = new ArrayList<>();
                for (JsonElement e : arr) {
                    if (e.isJsonObject()) ids.add(Js.lng(e.getAsJsonObject(), "id", 0));
                }
                Map<Long, Boolean> online = new HashMap<>();
                try {
                    ApiResponse st = irc.getApi().getOnlineStatus(ids);
                    JsonObject map = st.ok() ? Js.obj(st.dataObj(), "map") : null;
                    if (map != null) {
                        for (Long id : ids) {
                            JsonElement v = map.get(String.valueOf(id));
                            online.put(id, v != null && v.isJsonPrimitive() && v.getAsBoolean());
                        }
                    }
                } catch (Exception ignored) {
                }
                List<JsonObject> rows = new ArrayList<>();
                for (JsonElement e : arr) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    long id = Js.lng(o, "id", 0);
                    boolean isOnline = Boolean.TRUE.equals(online.get(id));
                    if (m == 1 && !isOnline) continue;
                    if (m == 2 && isOnline) continue;
                    rows.add(o);
                }
                String title = switch (m) {
                    case 1 -> "在线玩家";
                    case 2 -> "离线玩家";
                    default -> "玩家列表";
                };
                String listCmd = "player " + modeCmd;
                banner(irc, " " + title + " ");
                int pageSize = 10;
                int size = rows.size();
                int totalPages = Math.max(1, (size + pageSize - 1) / pageSize);
                int p = Math.max(1, Math.min(pg, totalPages));
                int start = (p - 1) * pageSize;
                int end = Math.min(start + pageSize, size);
                for (int i = start; i < end; i++) {
                    JsonObject o = rows.get(i);
                    long id = Js.lng(o, "id", 0);
                    boolean isOnline = Boolean.TRUE.equals(online.get(id));
                    MutableComponent line = idRow(null, id, Js.str(o, "username", "?"));
                    line.append(Component.literal(isOnline ? " [在线]" : " [离线]")
                            .withStyle(isOnline ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
                    irc.printText(line);
                }
                if (size > pageSize) {
                    MutableComponent foot = Component.literal("  ");
                    if (p > 1) foot.append(clickable("[上一页]", prefix() + listCmd + " " + (p - 1)));
                    foot.append(Component.literal(" " + p + "/" + totalPages + "（共" + size + "人） ").withStyle(ChatFormatting.DARK_GRAY));
                    if (p < totalPages) foot.append(clickable("[下一页]", prefix() + listCmd + " " + (p + 1)));
                    irc.printText(foot);
                } else {
                    irc.print("共 " + size + " 人");
                }
                bannerEnd(irc);
            } catch (Exception e) {
                irc.printErr("获取玩家失败：" + e.getMessage());
            }
        });
        return true;
    }
    // ---------- setting ----------

    private static boolean setting(String rest) {
        String[] a = args(rest);
        String sub = a.length > 0 ? a[0] : "";
        McmsIrc irc = McmsIrc.get();
        switch (sub) {
            case "" -> {
                usageMenu(irc, "用法：.setting get <key> | set <key> <value>");
                return true;
            }
            case "get" -> {
                if (a.length < 2) { usageMenu(irc, "用法：.setting get <key>"); return true; }
                String key = a[1];
                irc.async(() -> {
                    try {
                        ApiResponse r = irc.getApi().request("/setting/get", java.util.Map.of("key", key), null, "GET");
                        if (r.ok()) irc.print(key + " = " + (r.data() == null ? "" : r.data().toString()));
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("获取设置失败：" + e.getMessage());
                    }
                });
            }
            case "set" -> {
                if (a.length < 3) { usageMenu(irc, "用法：.setting set <key> <value>"); return true; }
                String key = a[1];
                String val = tail(rest, 2);
                irc.async(() -> {
                    try {
                        JsonObject body = new JsonObject();
                        body.addProperty("key", key);
                        body.addProperty("value", val);
                        ApiResponse r = irc.getApi().request("/setting/set", body);
                        if (r.ok()) irc.printOk("设置已保存");
                        else irc.printErr(r.content());
                    } catch (Exception e) {
                        irc.printErr("保存设置失败：" + e.getMessage());
                    }
                });
            }
            default -> {
                usageMenu(irc, "用法：.setting get <key> | set <key> <value>");
                return true;
            }
        }
        return true;
    }

    // ---------- irc（原有） ----------

    private static boolean irc(String rest) {
        String s = rest == null ? "" : rest;
        int sp = s.indexOf(' ');
        String sub = sp < 0 ? s : s.substring(0, sp);
        String args = sp < 0 ? "" : s.substring(sp + 1);
        McmsIrc irc = McmsIrc.get();
        switch (sub) {
            case "" -> {
                irc.status();
                return true;
            }
            case "list" -> {
                irc.listRooms();
                return true;
            }
            case "set" -> {
                irc.setRoomByCode(args.trim());
                return true;
            }
            case "create" -> {
                irc.createRoom(args);
                return true;
            }
            case "join" -> {
                irc.joinByCode(args.trim());
                return true;
            }
            case "quit" -> {
                irc.quitRoom();
                return true;
            }
            case "reply" -> {
                int sp2 = args.indexOf(' ');
                String idStr = (sp2 < 0 ? args : args.substring(0, sp2)).trim();
                String text = sp2 < 0 ? "" : args.substring(sp2 + 1);
                irc.sendReply(idStr, text);
                return true;
            }
            default -> {
                irc.sendMessage(s);
                return true;
            }
        }
    }
    // ---------- 补全 ----------

    /** 计算补全建议（与原版同机制）。cmd 为前缀之后的部分。 */
    public static Suggestions complete(String cmd, int cursor, int shift) {
        try {
            String c = cmd == null ? "" : cmd;
            StringReader reader = new StringReader(c);
            var parse = DISPATCHER.parse(reader, new Object());
            int cc = Math.max(0, Math.min(cursor, c.length()));
            Suggestions raw = DISPATCHER.getCompletionSuggestions(parse, cc).get();
            List<Suggestion> shifted = new ArrayList<>();
            for (Suggestion sug : raw.getList()) {
                StringRange r = sug.getRange();
                shifted.add(new Suggestion(new StringRange(r.getStart() + shift, r.getEnd() + shift), sug.getText(), sug.getTooltip()));
            }
            StringRange rr = raw.getRange();
            return new Suggestions(new StringRange(rr.getStart() + shift, rr.getEnd() + shift), shifted);
        } catch (Exception e) {
            return new Suggestions(new StringRange(0, 0), List.of());
        }
    }
}