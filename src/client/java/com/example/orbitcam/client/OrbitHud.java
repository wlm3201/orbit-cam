package com.example.orbitcam.client;

import com.example.orbitcam.client.compat.Platform;
import com.example.orbitcam.client.config.OrbitConfig;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 准星与状态文字的界面逻辑（与版本无关的部分）。
 *
 * <p>26.1.2 把这些职责放在 {@code Gui} 里，26.2+ 拆到了 {@code Hud}，
 * 因此注入点必须写在各版本专属的 mixin 中；这里承载具体实现，
 * 避免三个版本各自维护一份相同的代码。</p>
 *
 * <p><b>为什么不自己画准星而要平移位姿：</b>
 * 动态准星类 mod（如 DynamicCrosshair）并不替换准星，
 * 而是 wrap 住原版那次 blit、沿用原版算出来的 x/y。
 * 只要我们把位姿平移好，它们的准星会自然跟着走到鼠标处；
 * 反过来，若改成自己按坐标画，它们的准星就会留在屏幕中心。</p>
 */
public final class OrbitHud {

	/** 本帧是否在提取准星前压入过矩阵，用于保证 push/pop 严格配对 */
	private static boolean crosshairShifted;

	private OrbitHud() {
	}

	/**
	 * 提取准星前调用：把位姿平移到鼠标指针处。
	 *
	 * <p>调用方必须保证 {@link #endCrosshair(GuiGraphicsExtractor)} 一定会执行（try/finally），
	 * 不能依赖原方法走到 RETURN——第三方 mod 可能在 HEAD 就 cancel 掉整个方法，
	 * 那样 RETURN 上的还原不会触发，位姿会泄漏给后续共用同一张 {@code GuiGraphics} 的整个 HUD。</p>
	 *
	 * @return true 表示应当跳过原版的准星提取（例如正在显示鼠标指针）
	 */
	public static boolean beginCrosshair(GuiGraphicsExtractor graphics) {
		crosshairShifted = false;
		if (!shouldMoveCrosshair()) {
			return false;
		}
		if (OrbitCam.isCursorShown()) {
			return true;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(
				(float) (OrbitCam.pointerGuiX(graphics.guiWidth()) - graphics.guiWidth() / 2.0),
				(float) (OrbitCam.pointerGuiY(graphics.guiHeight()) - graphics.guiHeight() / 2.0));
		crosshairShifted = true;
		return false;
	}

	/**
	 * 提取准星后调用：还原位姿。
	 * 以 {@link #crosshairShifted} 而不是重新判断状态为准，避免两次注入之间状态变化导致栈失衡。
	 */
	public static void endCrosshair(GuiGraphicsExtractor graphics) {
		if (crosshairShifted) {
			crosshairShifted = false;
			graphics.pose().popMatrix();
		}
	}

	/** 轨道相机开启时强制按第一人称处理，否则第三人称下原版不画准星 */
	public static CameraType crosshairPerspective(Options options) {
		return OrbitCam.isEngaged() ? CameraType.FIRST_PERSON : options.getCameraType();
	}

	/** 在界面上绘制当前的模式状态文字（位置由配置决定） */
	public static void submitStatusText(GuiGraphicsExtractor graphics) {
		OrbitConfig config = OrbitConfig.INSTANCE;
		Minecraft mc = Minecraft.getInstance();
		if (!OrbitCam.isEngaged() || Platform.hasScreen(mc)) {
			return;
		}
		if (config.showStatusHud) {
			graphics.nextStratum();
			graphics.text(mc.font, OrbitCam.statusText(),
					(int) config.statusHudX, (int) config.statusHudY, 0xFFFFFFFF);
		}
	}

	private static boolean shouldMoveCrosshair() {
		Minecraft mc = Minecraft.getInstance();
		return OrbitCam.isEngaged() && !Platform.hasScreen(mc);
	}
}
