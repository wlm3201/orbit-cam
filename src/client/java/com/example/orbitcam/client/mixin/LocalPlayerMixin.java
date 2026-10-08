package com.example.orbitcam.client.mixin;

import com.example.orbitcam.client.OrbitCam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 轨道相机开启期间冻结玩家自身的移动输入：
 * WASD 等按键改为驱动相机轴心点（见 OrbitCam#moveByKeys），而不是驱动玩家身体。
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {

	@Inject(method = "applyInput", at = @At("HEAD"), cancellable = true)
	private void orbitcam$freezeBody(CallbackInfo ci) {
		if (OrbitCam.isEngaged()) {
			ci.cancel();
		}
	}

	/**
	 * 从轨道相机位置重新拾取准星。
	 *
	 * <p>必须注入在“拾取结果产出”处，而不是 {@code Minecraft#pick} 的返回处：
	 * 这样 {@code pick} 写入 {@code Minecraft#hitResult} 时用的已经是轨道相机的结果，
	 * 其它在 {@code pick} 返回点注入的模组（如精确放置类）不会因为注入先后读到旧的原版结果。</p>
	 */
	@Inject(method = "raycastHitResult", at = @At("RETURN"), cancellable = true)
	private void orbitcam$pickFromCamera(float partialTicks, Entity cameraEntity,
			CallbackInfoReturnable<HitResult> cir) {
		Minecraft mc = Minecraft.getInstance();
		if ((Object) this != mc.player) {
			return;
		}
		HitResult hit = OrbitCam.overrideHitResult(mc);
		if (hit != null) {
			cir.setReturnValue(hit);
		}
	}
}
