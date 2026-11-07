package org.lanminecraft.btwbridge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.class_356;

/** User-selected new-world policy. Existing worlds are never visited here. */
@Mixin(targets = "net.minecraft.class_363", priority = 900, remap = false)
public abstract class WorldDifficultyMixin {
    @Shadow private int difficultyID;
    @Shadow private boolean lockDifficulty;
    @Shadow private class_356 buttonDifficultyLevel;

    @Inject(method = "method_915", at = @At("HEAD"), require = 1, allow = 1)
    private void selectNightmare(CallbackInfo callback) {
        difficultyID = 2;
        lockDifficulty = true;
    }

    @Inject(method = "method_915", at = @At("TAIL"), require = 1, allow = 1)
    private void keepSingleDifficulty(CallbackInfo callback) {
        // CE owns the buttons added to this vanilla screen. Disable only its
        // difficulty selector, leaving game mode, world type and seed intact.
        if (buttonDifficultyLevel != null) buttonDifficultyLevel.field_1055 = false;
    }
}
