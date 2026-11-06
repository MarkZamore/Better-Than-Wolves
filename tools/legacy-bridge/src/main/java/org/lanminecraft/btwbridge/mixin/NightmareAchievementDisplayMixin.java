package org.lanminecraft.btwbridge.mixin;

import org.lanminecraft.btwbridge.SkinProfiles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.itlesports.nightmaremode.item.items.template.ItemAchievementGranter", remap = false)
public abstract class NightmareAchievementDisplayMixin {
    @Redirect(method = "displayAchievement(Lnet/minecraft/class_814;Lnet/minecraft/class_988;)V",
        at = @At(value = "FIELD", target = "Lnet/minecraft/class_988;field_3982:Ljava/lang/String;", opcode = 180),
        require = 1, allow = 1, remap = false)
    private String achievementDisplayName(@Coerce Object player) {
        return SkinProfiles.namedFieldEffect(player);
    }
}
