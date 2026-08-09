package hk.flynt.mcms.platform.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class PlatformClientImpl {
    private PlatformClientImpl() {
    }

    public static void sendChatMessage(String text) {
        send(Component.literal(text));
    }

    public static void sendChatComponent(Component component) {
        send(component);
    }

    public static void sendServerChat(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().sendChat(text);
        }
    }

    private static void send(Component component) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.isSameThread()) {
            mc.player.displayClientMessage(component, false);
        } else {
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.displayClientMessage(component, false);
                }
            });
        }
    }
}