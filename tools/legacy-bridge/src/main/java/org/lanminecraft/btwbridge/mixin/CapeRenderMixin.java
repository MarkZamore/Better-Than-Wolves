package org.lanminecraft.btwbridge.mixin;

import org.lanminecraft.btwbridge.SkinProfiles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.class_570", remap = false)
public abstract class CapeRenderMixin {
    @Redirect(method = "method_1582(Lnet/minecraft/class_1631;F)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1631;method_3334()Ljava/lang/String;"), remap = false)
    private String preserveNamedEars(@Coerce Object player) {
        return SkinProfiles.namedGetterEffect(player, "method_3334");
    }

    // Ordinal 0 checks the deadmau5 skin. Ordinal 1 is the cape only.
    // Its renderer assumes a downloader exists, so pair the precise absent
    // asset gate with a false loaded result for that same registered player.
    @Redirect(method = "method_1582(Lnet/minecraft/class_1631;F)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/class_525;method_5819()Z", ordinal = 1), remap = false)
    private boolean absentLauncherCapeLoaded(@Coerce Object cape, @Coerce Object player, float partialTicks) {
        return SkinProfiles.capeLoaded(cape, player);
    }
}
