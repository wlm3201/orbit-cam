package com.example.orbitcam.client.mixin;

import com.example.orbitcam.client.PivotOutline;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 借用“方块破坏进度”的渲染时机提交轴心线框。
 * 该阶段已经绑定好关卡渲染状态与相机，适合提交 gizmo 指令。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

	@Inject(method = "submitBlockDestroyAnimation", at = @At("RETURN"))
	private void orbitcam$submitPivotOutline(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
			LevelRenderState levelRenderState, CallbackInfo ci) {
		// 注意：mixin 要求处理器的方法签名与目标方法参数完全一致，不能省略前置参数
		PivotOutline.submit(levelRenderState);
	}
}
