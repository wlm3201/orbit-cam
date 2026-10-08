package com.example.orbitcam.client.mixin;

import com.example.orbitcam.client.OrbitHud;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.2 的 HUD 注入点：把准星与状态文字委托给 {@link OrbitHud} */
@Mixin(Hud.class)
public class HudMixin {

	/**
	 * 整体包一层而不是 HEAD/RETURN 配对：第三方 mod 若在 HEAD 无条件 {@code ci.cancel()}，
	 * 方法体走不到 RETURN，还原逻辑就不会执行，push 的位姿会泄漏给后续整个 HUD。
	 * 详见 {@code src/client/java/com/example/orbitcam/client/OrbitHud.java} 的说明。
	 */
	@WrapMethod(method = "extractCrosshair")
	private void orbitcam$shiftCrosshair(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker,
			Operation<Void> original) {
		boolean skip = OrbitHud.beginCrosshair(graphics);
		try {
			if (!skip) {
				original.call(graphics, deltaTracker);
			}
		} finally {
			OrbitHud.endCrosshair(graphics);
		}
	}

	@Redirect(method = "extractCrosshair",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getCameraType()Lnet/minecraft/client/CameraType;"))
	private CameraType orbitcam$crosshairPerspective(Options options) {
		return OrbitHud.crosshairPerspective(options);
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void orbitcam$statusHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		OrbitHud.submitStatusText(graphics);
	}
}
