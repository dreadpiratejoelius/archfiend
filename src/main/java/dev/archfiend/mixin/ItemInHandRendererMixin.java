package dev.archfiend.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.archfiend.ArchfiendClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the first-person hand while a drop sequence plays (part of the HUD hider). */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin
{
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void archfiend$hideHandDuringSequence(float partialTick, PoseStack poseStack, SubmitNodeCollector collector,
                                                  LocalPlayer player, int light, CallbackInfo ci)
    {
        if (ArchfiendClient.hudHidden()) ci.cancel();
    }
}
