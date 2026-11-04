package org.lanminecraft.btwbridge.mixin;

import org.lanminecraft.btwbridge.SkinProfiles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.class_1635", remap = false)
public abstract class LivingNameEffectsMixin {
    @Redirect(method = "method_5777(Lnet/minecraft/class_1699;FFF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/class_1699;method_2518()Ljava/lang/String;"), remap = false)
    private String preserveNamedPlayerRotation(@Coerce Object entity) {
        // Non-player mob names retain their actual native getter result.
        return SkinProfiles.namedGetterEffect(entity, "method_2518");
    }
}
