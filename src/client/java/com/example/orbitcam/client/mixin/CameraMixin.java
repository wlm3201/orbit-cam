package com.example.orbitcam.client.mixin;

import com.example.orbitcam.client.OrbitCam;
import com.example.orbitcam.client.OrbitZoom;
import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 相机接管点。
 *
 * <p>在原版相机 {@code alignWithEntity} 计算完成后：
 * 先记录原版姿态（用于退出时的平滑过渡），驱动 {@link OrbitCam#update()}，
 * 再把相机位置/朝向改成轨道相机计算出的姿态。</p>
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity", at = @At("RETURN"))
	private void orbitcam$applyOrbit(float partialTicks, CallbackInfo ci) {
		Camera self = (Camera) (Object) this;
		// 记录原版相机姿态 + 每帧推进轨道相机状态
		OrbitCam.recordVanillaPose(self.position(), self.yRot(), self.xRot());
		OrbitCam.update();
		if (!OrbitCam.isActive()) {
			return;
		}

		OrbitCam.CameraPose pose = OrbitCam.computePose();
		if (pose == null) {
			return;
		}

		this.setRotation(pose.yaw(), pose.pitch());
		this.setPosition(pose.position());
		// 同步玩家实体朝向，让准星/放置朝向等逻辑跟随相机
		OrbitCam.syncPlayerRotation(pose.yaw(), pose.pitch());
	}

	/** 在原版 FOV 计算完成后乘上放大镜系数；同时记录原版 FOV 作为缩放基准 */
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void orbitcam$applyZoomFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		OrbitZoom.noteVanillaFov(cir.getReturnValue());
		if (!OrbitCam.isEngaged()) {
			return;
		}
		double scale = OrbitZoom.zoomFovScale();
		if (scale != 1.0) {
			cir.setReturnValue((float) (cir.getReturnValue() * scale));
		}
	}

	/** 轨道相机可能在水/岩浆内部，屏蔽液体雾效避免整屏被遮住 */
	@Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
	private void orbitcam$hideFluidFog(CallbackInfoReturnable<FogType> cir) {
		if (OrbitCam.isEngaged()) {
			cir.setReturnValue(FogType.NONE);
		}
	}
}
