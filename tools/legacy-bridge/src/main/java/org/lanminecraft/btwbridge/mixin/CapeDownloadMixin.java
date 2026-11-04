package org.lanminecraft.btwbridge.mixin;

import org.lanminecraft.btwbridge.SkinProfiles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.class_1631", remap = false)
public abstract class CapeDownloadMixin {
    // BTW turns a present profile's null cape URL into an empty string and
    // schedules URL(""). The launcher has no cape field: there is no asset.
    @Inject(method = "method_5716(Lnet/minecraft/class_1653;Ljava/lang/String;)Lnet/minecraft/class_525;",
        at = @At("HEAD"), cancellable = true, remap = false)
    private static void absentLauncherCape(@Coerce Object location, String name, CallbackInfoReturnable<Object> result) {
        if (SkinProfiles.hasNoCape(name)) result.setReturnValue(null);
    }
}
