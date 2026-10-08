package com.example.orbitcam.client.mixin;

import com.example.orbitcam.client.OrbitCam;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让“玩家的眼睛位置”和“玩家的视线方向”都改成轨道相机的结果。
 *
 * <p>这样第三方模组（如高亮、拾取、瞄准类）只要调用 {@code player.getEyePosition()}
 * 或 {@code getViewVector()} 就能自动跟随轨道相机，无需专门适配。</p>
 */
@Mixin(Entity.class)
public class EntityMixin {

	/** 只对本机玩家生效，其它实体保持原样 */
	@Inject(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
	private void orbitcam$eyeAtCamera(float partialTick, CallbackInfoReturnable<Vec3> cir) {
		Minecraft mc = Minecraft.getInstance();
		if ((Object) this != mc.player) {
			return;
		}
		Vec3 eye = OrbitCam.cameraEye();
		if (eye != null) {
			cir.setReturnValue(eye);
		}
	}

	/** 视线方向改为鼠标指针所指的射线（未接管相机时返回 null，走原版） */
	@Inject(method = "getViewVector(F)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
	private void orbitcam$lookAtPointer(float partialTick, CallbackInfoReturnable<Vec3> cir) {
		Minecraft mc = Minecraft.getInstance();
		if ((Object) this != mc.player) {
			return;
		}
		Vec3 ray = OrbitCam.pointerViewRay();
		if (ray != null) {
			cir.setReturnValue(ray);
		}
	}
}
