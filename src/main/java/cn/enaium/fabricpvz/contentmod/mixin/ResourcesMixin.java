package cn.enaium.fabricpvz.contentmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import cn.enaium.fabricpvz.contentmod.ContentModAssets;
import cn.enaium.fabricpvz.portable.lawn.Resources;
import cn.enaium.fabricpvz.portable.sexy.ResourceManager;

/**
 * Keeps the mod's seed-packet art in the game's resource table.
 *
 * <p>The published content API accepts an {@code ImageID} for packet art but has no mod image-id
 * range and no "resources were (re-)loaded" event, while the game binds every resource of a group by
 * overwriting the ids of that group ({@code Resources::extractResourcesByName}). Those two facts
 * together mean a mod that ships its own packet art has to re-install it whenever the game finishes
 * loading a resource group - this mixin does exactly that, and nothing else.
 *
 * <p>The mixin config is {@code required: false}: if a loader cannot apply it, the mod still loads
 * and the game still runs (the in-level seed bank is then covered by the mod's
 * {@code LawnEvents.TICK} safety net), and the end-to-end harness reports
 * {@link ContentModAssets#mixinCallbacks} so a silent failure cannot hide.
 */
@Mixin(Resources.class)
public class ResourcesMixin {

    /** Diagnostics hook: proves the mixin was applied, even for a group name the game rejects. */
    @Inject(method = "extractResourcesByName", at = @At("HEAD"))
    private static void pvzContentMod$beforeResourceGroup(ResourceManager theResourceManager, String theName,
            CallbackInfoReturnable<Boolean> cir) {
        ContentModAssets.noteMixinCallback();
    }

    /**
     * Re-installs the mod's packet art after the group's own resources were bound. {@code @At("RETURN")}
     * injects before every return of the target, including the {@code false} the game returns when a
     * resource of the group could not be loaded - {@code @At("TAIL")} would only cover the last one.
     */
    @Inject(method = "extractResourcesByName", at = @At("RETURN"))
    private static void pvzContentMod$afterResourceGroup(ResourceManager theResourceManager, String theName,
            CallbackInfoReturnable<Boolean> cir) {
        ContentModAssets.reapplyOverrides();
    }
}
