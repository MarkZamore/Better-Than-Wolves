package org.lanminecraft.btwbridge.mixin;

import org.lanminecraft.btwbridge.SkinProfiles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "api.achievement.AchievementEventDispatcher", remap = false)
public abstract class BtwAchievementDisplayMixin {
    @Redirect(method = "handleEvent(Lapi/achievement/AchievementEventDispatcher$AchievementEvent;Lnet/minecraft/class_988;Ljava/lang/Object;)V",
        at = @At(value = "FIELD", target = "Lnet/minecraft/class_988;field_3982:Ljava/lang/String;", opcode = 180),
        require = 1, allow = 1, remap = false)
    private static String achievementDisplayName(@Coerce Object player) {
        return SkinProfiles.namedFieldEffect(player);
    }
}
