package hk.flynt.mcms.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.network.chat.Component;

/**
 * 客户端平台能力抽象（expect）。
 * 各平台在 <code>&lt;platform&gt;/.../PlatformClientImpl</code> 中提供实现。
 */
public final class PlatformClient {
    private PlatformClient() {
    }

    /** 在玩家聊天栏打印一条系统消息（会自动切回客户端线程）。 */
    @ExpectPlatform
    public static void sendChatMessage(String text) {
        throw new AssertionError("Platform implementation not present");
    }

    /** 在玩家聊天栏打印一条富文本消息（含颜色/点击事件）。 */
    @ExpectPlatform
    public static void sendChatComponent(Component component) {
        throw new AssertionError("Platform implementation not present");
    }

    /** 把一条消息作为普通聊天直接发送到当前 Minecraft 服务器（用于 .say）。 */
    @ExpectPlatform
    public static void sendServerChat(String text) {
        throw new AssertionError("Platform implementation not present");
    }
}