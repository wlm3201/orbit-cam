package com.example.orbitcam.client.mixin;

import com.example.orbitcam.client.OrbitCam;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 与“交互”及“世界切换”相关的 Minecraft 层介入 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

	/**
	 * 让“长按连续破坏/使用”在鼠标未被相机占用时依然成立。
	 * 原版要求鼠标处于抓取状态才允许长按，而轨道相机下鼠标是释放的。
	 */
	@Redirect(method = "handleKeybinds",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
	private boolean orbitcam$allowHoldMining(MouseHandler handler) {
		if (handler.isMouseGrabbed()) {
			return true;
		}
		return OrbitCam.isEngaged() && !OrbitCam.isCameraHeld(InputConstants.MOUSE_BUTTON_LEFT);
	}

	/** 右键被相机接管时，阻止原版的“长按连续放置” */
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void orbitcam$stopHoldPlacing(CallbackInfo ci) {
		if (OrbitCam.isCameraHeld(InputConstants.MOUSE_BUTTON_RIGHT)) {
			ci.cancel();
		}
	}

	/**
	 * 维度切换 / 重生 / 进入存档都会走 {@code setLevel} 安装新的 ClientLevel，
	 * 这里在切换刚开始时就关掉轨道相机，避免旧世界的相机状态带进新维度。
	 */
	@Inject(method = "setLevel", at = @At("HEAD"))
	private void orbitcam$stopOnLevelChange(ClientLevel level, CallbackInfo ci) {
		OrbitCam.stopForWorldChange("切换世界/维度");
	}

	/**
	 * 退出存档：所有断开路径（保存并退出、进度界面断开、disconnect(Screen, boolean)）
	 * 最终都会汇聚到 {@code disconnect(Screen, boolean, boolean)}，因此在这一层拦截一次即可。
	 */
	@Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
	private void orbitcam$stopOnDisconnect(Screen screen, boolean pauseScreenShown, boolean isRealDisconnect,
			CallbackInfo ci) {
		OrbitCam.stopForWorldChange("退出存档/断线");
	}
}
