package hk.flynt.mcms;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientChatEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.platform.Platform;
import hk.flynt.mcms.command.McmsCommands;
import hk.flynt.mcms.config.McmsConfig;
import hk.flynt.mcms.irc.McmsIrc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McmsMod {
    public static final String MOD_ID = "mcms";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static boolean autoLoginStarted = false;

    private McmsMod() {
    }

    public static void init() {
        LOGGER.info("[MCMS] common init, mc = {}", Platform.getMinecraftVersion());

        // 拦截 "." 前缀输入，作为本模组命令处理，不再发往服务器
        ClientChatEvent.SEND.register((message, component) -> {
            String prefix = McmsConfig.get().getCommandPrefix();
            if (!prefix.isEmpty() && message != null && message.startsWith(prefix)) {
                if (McmsCommands.execute(message.substring(prefix.length()))) {
                    return EventResult.interruptFalse();
                }
            }
            return EventResult.pass();
        });

        // 进入世界后触发自动登录
        ClientTickEvent.CLIENT_PRE.register(mc -> {
            if (!autoLoginStarted && mc.player != null) {
                autoLoginStarted = true;
                McmsIrc.get().autoLogin();
            }
        });
    }
}