package org.lanminecraft.btwbridge.mixin;

import api.client.mojapi.ProfileUtils;
import api.client.mojapi.UserProfile;
import java.util.Optional;
import org.lanminecraft.btwbridge.SkinProfiles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ProfileUtils.class, remap = false)
public final class ProfileLookupMixin {
    @Inject(method = "getUserProfile(Ljava/lang/String;Z)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true, remap = false)
    private static void launcherProfile(String name, boolean immediate, CallbackInfoReturnable<Optional<UserProfile>> result) {
        Optional<UserProfile> profile = SkinProfiles.find(name);
        if (profile.isPresent()) result.setReturnValue(profile);
    }
}
