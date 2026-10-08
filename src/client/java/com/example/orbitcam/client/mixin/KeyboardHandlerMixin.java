package com.example.orbitcam.client.mixin;

import com.example.orbitcam.client.OrbitCam;
import com.example.orbitcam.client.OrbitZoom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;

/** 在按键分发的最前面截获放大镜键，使其不被原版逻辑消费 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void orbitcam$captureZoomKey(final long handle, final int action, final KeyEvent event, CallbackInfo ci) {
		if (OrbitZoom.consumeZoomKey(action, event)) {
			ci.cancel();
		}
	}
}
