package com.coloryr.allmusic.client.mixins;

import com.coloryr.allmusic.client.AllMusicClient;
import com.coloryr.allmusic.client.core.AllMusicCore;
import com.coloryr.allmusic.client.core.BlockCommandRoute;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPacketListener.class, priority = 1100)
public class BlockCommands {
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void blockCommand(String command, CallbackInfo info) {
        if (AllMusicCore.bridge instanceof AllMusicClient client
                && client.getBlockCommands().intercept(command)) {
            info.cancel();
        }
    }

    @Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
    private void unattendedBlockCommand(String command, Screen screen, CallbackInfo info) {
        if (BlockCommandRoute.matches(command) && AllMusicCore.bridge instanceof AllMusicClient client) {
            client.message("请在聊天栏手动输入 /music block 命令来管理本地黑名单");
            info.cancel();
        }
    }

    @Inject(method = "handleCommands", at = @At("RETURN"))
    private void blockSuggestions(ClientboundCommandsPacket packet, CallbackInfo info) {
        if (AllMusicCore.bridge instanceof AllMusicClient client) {
            ClientPacketListener listener = (ClientPacketListener) (Object) this;
            client.getBlockCommands().registerSuggestions(listener.getCommands());
        }
    }
}
