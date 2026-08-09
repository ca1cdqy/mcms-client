package hk.flynt.mcms.fabric.mixin;

import com.mojang.brigadier.suggestion.Suggestions;
import hk.flynt.mcms.command.McmsCommands;
import hk.flynt.mcms.config.McmsConfig;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;

/**
 * 聊天框补全：输入命令前缀时，用我们自己的 Brigadier 调度器计算建议并复用原版下拉框。
 * Tab 应用、循环、消失等全部交给原版机制处理，行为与原版命令补全完全一致。
 */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {
    @Shadow
    @Final
    private EditBox input;

    @Shadow
    private CompletableFuture<Suggestions> pendingSuggestions;

    @Shadow
    private CommandSuggestions.SuggestionsList suggestions;

    @Shadow
    private boolean keepSuggestions;

    @Shadow
    public abstract void showSuggestions(boolean narrateFirstSuggestion);

    @Inject(method = "updateCommandInfo", at = @At("HEAD"), cancellable = true)
    private void mcms$updateCommandInfo(CallbackInfo ci) {
        String text = this.input.getValue();
        if (text == null) return;
        String prefix = McmsConfig.get().getCommandPrefix();
        if (prefix.isEmpty() || !text.startsWith(prefix)) return;
        ci.cancel();

        int cursor = Math.min(this.input.getCursorPosition(), text.length());
        String cmd = cursor >= prefix.length() ? text.substring(prefix.length(), cursor) : "";
        int cursorInCmd = cursor - prefix.length();

        // 镜像原版：keepSuggestions 为 true（Tab 应用过建议后）时保留当前列表，供 Tab 循环
        if (!this.keepSuggestions) {
            this.suggestions = null;
            this.input.setSuggestion(null);
        }
        Suggestions sug = McmsCommands.complete(cmd, cursorInCmd, prefix.length());
        this.pendingSuggestions = CompletableFuture.completedFuture(sug);
        this.pendingSuggestions.thenRun(() -> this.showSuggestions(true));
    }
}