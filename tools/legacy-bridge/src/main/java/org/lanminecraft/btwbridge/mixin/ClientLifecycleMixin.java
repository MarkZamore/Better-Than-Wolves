package org.lanminecraft.btwbridge.mixin;

import org.lanminecraft.btwbridge.SkinProfiles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.class_1600", priority = 900, remap = false)
public abstract class ClientLifecycleMixin {
    @Inject(method = "method_2954()V", at = @At("HEAD"), remap = false)
    private void updateProfiles(CallbackInfo callback) { SkinProfiles.tick(this); }

    // Redirects are applied after argument modifiers. NM appends its branding
    // with ModifyArg, so resolve the launch context at the final Display call.
    // Retain the authored title when there is no launcher context.
    @Redirect(method = "*", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setTitle(Ljava/lang/String;)V"), order = 10000, remap = false)
    private void launcherTitle(String authorTitle) {
        String title = System.getProperty("minecraft.portable.identity.windowTitleText", "").trim();
        try {
            Class.forName("org.lwjgl.opengl.Display").getMethod("setTitle", String.class)
                .invoke(null, title.isEmpty() ? authorTitle : title);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("The native LWJGL window title could not be set.", failure);
        }
    }
}
