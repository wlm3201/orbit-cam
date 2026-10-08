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

/**
 * 26.3 的 HUD 注入点：把准星与状态文字委托给 {@link OrbitHud}。
 * 26.3 的 HUD 改为“提取渲染状态”的模式，因此这里把提取准星整体包一层。
 */
@Mixin(Hud.class)
public class HudMixin {

	/**
	 * 用 {@code @WrapMethod} 整体包一层，而不是 HEAD/RETURN 配对。
	 *
	 * <p>原因：第三方 mod（如 EntityCrosshair）会在同一个方法的 HEAD 无条件 {@code ci.cancel()}。
	 * 一旦被取消，方法体根本走不到 RETURN，挂在 RETURN 上的还原逻辑就不会执行，
	 * 于是 push 出去的位姿再也弹不回来，后续共用同一张 {@code GuiGraphics} 提取的
	 * 快捷栏、生命值等整个 HUD 都会被平移，看上去就是“HUD 跟着鼠标跑”。</p>
	 *
	 * <p>包一层后，无论原方法体是正常跑完还是被别人 cancel，finally 都能还原。
	 * 同时保留“平移位姿、让原版/第三方准星在自己坐标系里画”的语义，
	 * 这样 DynamicCrosshair（它只 wrap 内部 blit 并透传 x/y）仍然会跟着鼠标走。</p>
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
