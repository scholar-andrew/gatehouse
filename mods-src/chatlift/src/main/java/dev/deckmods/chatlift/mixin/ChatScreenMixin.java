package dev.deckmods.chatlift.mixin;

import dev.deckmods.chatlift.ChatLift;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.InBedChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ChatScreen draws the input box, the chat history and command suggestions in
 * extractRenderState, so shifting the pose for that call moves all of it.
 * init() also runs on resize, so a flag keeps the keyboard request to once per chat.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
	@Unique
	private boolean chatlift$opened;

	@Inject(method = "init", at = @At("TAIL"))
	private void chatlift$onInit(CallbackInfo ci) {
		// The sleep screen is a chat screen too; don't pop the keyboard or lift it there.
		if ((Object) this instanceof InBedChatScreen) return;
		if (!chatlift$opened) {
			chatlift$opened = true;
			ChatLift.onChatOpened();
		}
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void chatlift$onRemoved(CallbackInfo ci) {
		if (chatlift$opened) {
			chatlift$opened = false;
			ChatLift.onChatClosed();
		}
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void chatlift$raise(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		graphics.pose().pushMatrix();
		float lift = (Object) this instanceof InBedChatScreen ? 0f : ChatLift.lift(graphics.guiHeight());
		graphics.pose().translate(0f, -lift);
	}

	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void chatlift$restore(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		graphics.pose().popMatrix();
	}
}
