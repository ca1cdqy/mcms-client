package hk.flynt.mcms.neoforge.mixin;

import hk.flynt.mcms.McmsClickIds;
import hk.flynt.mcms.irc.McmsIrc;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截聊天消息上的自定义点击事件：左键点击把对应群组设为当前群组。
 */
@Mixin(ChatScreen.class)
public class ChatScreenMixin {
    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true)
    private void mcms$handleComponentClicked(Style style, boolean bl, CallbackInfoReturnable<Boolean> cir) {
        ClickEvent event = style.getClickEvent();
        if (event instanceof ClickEvent.Custom custom && McmsClickIds.SWITCH_ROOM.equals(custom.id())) {
            custom.payload().ifPresent(tag -> McmsIrc.get().openRoomFromClick(tag.asString().orElse("")));
            cir.setReturnValue(true);
        }
    }
}