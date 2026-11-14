package dev.deckmods.handofffix.mixin;

import java.util.HashSet;
import java.util.Set;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.driver.sdl.SDLCommonDriver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Controlify names a controller "<hash>" or "<hash>-N", where N is how many
 * connected controllers already share the hash. Steam Input pads all share one
 * hash, so after an unplug and replug N can repeat an ID that is still in use.
 * The newer controller then replaces the older one in Controlify's map, and the
 * older one (the Deck) vanishes when the newer one disconnects. This picks the
 * first "<hash>" / "<hash>-N" that no connected controller is using.
 */
@Mixin(value = SDLCommonDriver.class, remap = false)
public abstract class SDLCommonDriverMixin {
	@Inject(method = "createUid", at = @At("RETURN"), cancellable = true)
	private void handofffix$uniqueUid(CallbackInfoReturnable<String> cir) {
		String uid = cir.getReturnValue();
		if (uid == null) return;
		Set<String> used = new HashSet<>();
		try {
			for (ControllerEntity c : Controlify.instance().getControllerManager().orElseThrow().getConnectedControllers()) {
				used.add(c.uid());
			}
		} catch (RuntimeException e) {
			return;
		}
		if (!used.contains(uid)) return;
		int dash = uid.lastIndexOf('-');
		String base = dash > 0 && uid.substring(dash + 1).chars().allMatch(Character::isDigit) ? uid.substring(0, dash) : uid;
		String pick = base;
		for (int n = 1; used.contains(pick); n++) pick = base + "-" + n;
		cir.setReturnValue(pick);
	}
}
