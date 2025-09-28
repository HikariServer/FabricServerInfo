package com.atsukigames.serverinfo.mixin;

import com.atsukigames.serverinfo.ServerInfo;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin {
    @Inject(method = "onHandSwing", at = @At("HEAD"))
    private void serverinfo$onHandSwing(HandSwingC2SPacket packet, CallbackInfo ci) {
        ServerPlayerEntity player = ((ServerPlayNetworkHandler)(Object)this).player;
        ServerInfo.trySelect(player);
    }

    @Inject(method = "onPlayerInteractItem", at = @At("HEAD"))
    private void serverinfo$onPlayerInteractItem(PlayerInteractItemC2SPacket packet, CallbackInfo ci) {
        ServerPlayerEntity player = ((ServerPlayNetworkHandler)(Object)this).player;
        ServerInfo.trySelect(player);
    }

    @Inject(method = "onPlayerInteractBlock", at = @At("HEAD"))
    private void serverinfo$onPlayerInteractBlock(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        ServerPlayerEntity player = ((ServerPlayNetworkHandler)(Object)this).player;
        ServerInfo.trySelect(player);
    }
}
