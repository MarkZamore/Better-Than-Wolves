package org.lanminecraft.btwbridge.mixin;

import org.lanminecraft.btwbridge.SkinProfiles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.class_988", remap = false)
public abstract class PlayerNameEffectsMixin {
    @Redirect(method = "method_2580(Lnet/minecraft/class_856;)V",
        at = @At(value = "FIELD", target = "Lnet/minecraft/class_988;field_3982:Ljava/lang/String;", opcode = 180, ordinal = 0), remap = false)
    private String preserveNamedDeathItem(@Coerce Object player) {
        return SkinProfiles.namedFieldEffect(player);
    }
}
