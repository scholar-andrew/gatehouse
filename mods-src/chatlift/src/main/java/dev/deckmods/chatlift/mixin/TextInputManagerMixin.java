package dev.deckmods.chatlift.mixin;

import com.mojang.blaze3d.platform.TextInputManager;
import dev.deckmods.chatlift.SteamKeyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Where Minecraft starts and stops SDL text input and tells SDL where the text box is. */
@Mixin(TextInputManager.class)
public abstract class TextInputManagerMixin {
	@Shadow private int areaX;
	@Shadow private int areaY;
	@Shadow private int areaWidth;
	@Shadow private int areaHeight;

	@Inject(method = "applyTextInputArea", at = @At("HEAD"))
	private void chatlift$area(CallbackInfo ci) {
		SteamKeyboard.onArea(areaX, areaY, areaWidth, areaHeight);
	}

	@Inject(method = "startTextInput(Ljava/lang/Object;)V", at = @At("RETURN"))
	private void chatlift$start(Object owner, CallbackInfo ci) {
		SteamKeyboard.onStart(owner);
	}

	@Inject(method = "stopTextInput()V", at = @At("HEAD"))
	private void chatlift$stop(CallbackInfo ci) {
		SteamKeyboard.onStop();
	}
}
