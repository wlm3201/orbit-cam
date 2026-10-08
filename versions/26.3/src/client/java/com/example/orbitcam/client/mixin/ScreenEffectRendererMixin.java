package com.example.orbitcam.client.mixin;

import com.example.orbitcam.client.OrbitCam;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家身体跟随相机时，头部其实在相机位置（可能在方块内部），
 * 此时应屏蔽“卡在方块里”的遮挡贴图，否则屏幕会被糊住。
 */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {

	@Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
	private static void orbitcam$hideInWallOverlay(Identifier atlasLocation, float u0, float v0, float u1, float v1,
			PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int color, CallbackInfo ci) {
		if (OrbitCam.shouldHideInWallOverlay()) {
			ci.cancel();
		}
	}
}
