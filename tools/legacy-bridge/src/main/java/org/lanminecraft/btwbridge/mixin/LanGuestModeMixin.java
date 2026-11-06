package org.lanminecraft.btwbridge.mixin;

import org.lanminecraft.btwbridge.LanGuestMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(targets = "net.minecraft.class_393", remap = false)
public abstract class LanGuestModeMixin {
    @ModifyArgs(method = "method_1027(Lnet/minecraft/class_356;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/class_601;method_3000(Lnet/minecraft/class_1157;Z)Ljava/lang/String;"),
        require = 1, allow = 1, remap = false)
    private void inheritCreativeGuestMode(Args args) {
        Object selectedMode = args.get(0);
        Object guestMode = LanGuestMode.forShare(selectedMode, args.<Boolean>get(1));
        if (guestMode != selectedMode) args.set(0, guestMode);
    }
}
