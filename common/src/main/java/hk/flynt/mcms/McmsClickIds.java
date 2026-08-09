package hk.flynt.mcms;

import net.minecraft.resources.Identifier;

/** 自定义聊天点击事件 ID。 */
public final class McmsClickIds {
    /** 点击消息把对应群组设为当前群组。payload: 房间 id 的字符串。 */
    public static final Identifier SWITCH_ROOM = Identifier.fromNamespaceAndPath("mcms", "switch_room");

    private McmsClickIds() {
    }
}