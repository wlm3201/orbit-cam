package com.example.orbitcam.client.mixin;

import com.example.orbitcam.client.OrbitCam;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 身体跟随相机时屏蔽“卡在方块里”的遮挡贴图（26.1.2，此时接口还是 renderTex） */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {

	@Inject(method = "renderTex", at = @At("HEAD"), cancellable = true)
	private static void orbitcam$hideInWallOverlay(TextureAtlasSprite sprite, PoseStack poseStack,
			MultiBufferSource bufferSource, CallbackInfo ci) {
		if (OrbitCam.shouldHideInWallOverlay()) {
			ci.cancel();
		}
	}
}
