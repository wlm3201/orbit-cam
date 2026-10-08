package com.example.orbitcam.client.mixin;

import com.example.orbitcam.client.OrbitCam;
import com.example.orbitcam.client.OrbitZoom;
import com.example.orbitcam.client.compat.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鼠标输入接管。
 *
 * <p>职责：把旋转/平移键的按下事件拦下来；滚轮交给环绕距离/放大镜；
 * 把原版“转视角”改成“转相机”；并在自由指针模式下收集鼠标位移。</p>
 *
 * <p><b>注入点约定</b>：需要“吃掉原版行为”的注入（{@code onButton} / {@code onScroll}）
 * 一律挂在方法体<b>靠后的位置</b>，而不是 HEAD——让其它模组的回调先跑完，
 * 我们只在最后一刻压住原版。详见这两个方法的注释。
 * 只有“纯旁路”的注入（收集位移、阻止重新抓取）才留在 HEAD。</p>
 */
@Mixin(value = MouseHandler.class, priority = 3000)
public class MouseHandlerMixin {

	@Shadow
	private double accumulatedDX;

	@Shadow
	private double accumulatedDY;

	/**
	 * 鼠标按键：注入点刻意放在方法体<b>末尾</b>——原版把按键交给 {@code KeyMapping} 之前，而不是 HEAD。
	 *
	 * <p>MC 这里没有“捕获/冒泡”模型：{@code MouseHandler#onButton} 是一条由 GLFW/SDL 回调
	 * 进入的单向链，各模组的注入按<b>注入点在方法体中的位置</b>先后执行
	 * （priority 只决定同一位置上的相对次序），一旦有人 {@code ci.cancel()}，
	 * 它<b>之后</b>的回调与剩余原版代码会全部被跳过。</p>
	 *
	 * <p>所以“劫持放在冒泡底部”在这个模型里的做法是：把注入点尽量往后放。
	 * 之前放在 HEAD 时，MaLiLib 的 {@code MixinMouse}（挂在
	 * {@code getFramerateLimitTracker().onInputReceived()} 之后，比我们靠前）
	 * 会被我们的 cancel 整个跳过，litematica 的左右键设角点就永远收不到事件。
	 * 挪到 {@code KeyMapping.set} 之前后：别人的处理全都先跑完，
	 * 我们只在最后一刻决定要不要压住原版——语义与旧行为一致（原版破坏/放置照样不发生），
	 * 但第三方热键能正常触发。</p>
	 */
	@Inject(method = "onButton", cancellable = true,
			at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
					target = "Lnet/minecraft/client/KeyMapping;set(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V"))
	private void orbitcam$onButton(long handle, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getWindow() == null || handle != mc.getWindow().handle()) {
			return;
		}
		if (Platform.hasScreen(mc) || Platform.hasOverlay(mc)) {
			return;
		}

		OrbitCam.refreshModifier();

		int button = buttonInfo.button();
		if (action == Platform.pressAction()) {
			// 能走到这里说明没有第三方先消费掉这次按下（见本方法的注释），
			// 于是这次按下归相机：占位直到松手，旋转/平移才被允许
			if (OrbitCam.shouldCaptureMousePress(button)) {
				OrbitCam.markCameraHeld(button);
				OrbitCam.noteHybridPress(button);
				ci.cancel();
			}
		} else if (action == Platform.releaseAction()) {
			OrbitCam.clearCameraHeld(button);
			if (OrbitCam.consumeHybridClick(button)) {
				OrbitCam.replayVanillaClick(button);
			}
		}
	}

	/** 相机接管期间保持鼠标不被重新抓取（否则自由指针就没了） */
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void orbitcam$keepCursorFree(CallbackInfo ci) {
		if (OrbitCam.isEngaged()) {
			ci.cancel();
		}
	}

	/**
	 * 滚轮：同样挂在方法体的后半段（原版把滚动量换算成格数之后、决定切快捷栏之前）。
	 *
	 * <p>与 {@link #orbitcam$onButton(long, MouseButtonInfo, int, CallbackInfo)} 同理：
	 * MaLiLib 的滚轮回调挂在 {@code onInputReceived()} 之后，比这里靠前，
	 * 于是 litematica 这类模组的“修饰键 + 滚轮”操作（伸缩选区、增减、切操作模式）先执行；
	 * 它消费掉事件后我们就不会再缩放，没消费则轮到我们缩放并阻止原版切快捷栏。</p>
	 *
	 * <p>用 {@code AFTER} 而不是 {@code BEFORE}：让原版先把滚动量累加器消费掉，
	 * 否则我们取消时累加器不清零，滚动量会在后续事件里累积漂移。</p>
	 */
	@Inject(method = "onScroll", cancellable = true,
			at = @At(value = "INVOKE", shift = At.Shift.AFTER,
					target = "Lnet/minecraft/client/ScrollWheelHandler;onMouseScroll(DD)Lorg/joml/Vector2i;"))
	private void orbitcam$onScroll(long handle, double xOffset, double yOffset, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getWindow() == null || handle != mc.getWindow().handle()) {
			return;
		}
		if (Platform.hasScreen(mc) || Platform.hasOverlay(mc)) {
			return;
		}
		OrbitCam.refreshModifier();
		if (OrbitCam.onScroll(yOffset)) {
			ci.cancel();
		}
	}

	/**
	 * 原版“鼠标转视角”：接管时取消并把位移交给相机；
	 * 未接管时记录“玩家转过视角”，用于打断退出轨道相机的过渡动画。
	 *
	 * <p>注意：形参是“距上次处理间隔的时间”，不是视角增量，且该方法每帧都会被调用，
	 * 因此必须判断真实的鼠标位移，否则会每帧误判为“玩家转过视角”，导致退出过渡永远被清零。</p>
	 */
	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void orbitcam$turnPlayer(double mousea, CallbackInfo ci) {
		if (!OrbitCam.isEngaged()) {
			if (accumulatedDX != 0.0 || accumulatedDY != 0.0) {
				OrbitZoom.notePlayerTurn();
			}
			return;
		}
		ci.cancel();
		OrbitCam.applyLookDelta(accumulatedDX, accumulatedDY);
	}

	/**
	 * 自由指针（未抓取）模式下原版不会调用 turnPlayer，
	 * 因此在这里把累积位移交给相机，保证拖拽旋转依然生效。
	 */
	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
	private void orbitcam$captureFreeCursor(CallbackInfo ci) {
		MouseHandler self = (MouseHandler) (Object) this;
		Minecraft mc = Minecraft.getInstance();
		// 与 onButton/onScroll 保持一致：有界面或 overlay 时一律不参与
		if (Platform.hasScreen(mc) || Platform.hasOverlay(mc)) {
			return;
		}
		if (OrbitCam.isEngaged() && !self.isMouseGrabbed()) {
			OrbitCam.applyLookDelta(accumulatedDX, accumulatedDY);
		}
	}
}
