package com.example.orbitcam.client.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 版本兼容层（26.2）。
 *
 * <p>该版本仍使用 GLFW：鼠标键编号从 0 开始，可直接用 GLFW 查询按键状态。</p>
 */
public final class Platform {

	private Platform() {
	}

	/** 当前版本里承载准星/状态文字的类（26.2 为 Hud） */
	public static Class<?> hudClass() {
		return Hud.class;
	}

	public static Screen screen(Minecraft mc) {
		return mc.gui == null ? null : mc.gui.screen();
	}

	public static boolean hasScreen(Minecraft mc) {
		return screen(mc) != null;
	}

	public static boolean hasOverlay(Minecraft mc) {
		return mc.gui != null && mc.gui.overlay() != null;
	}

	public static void setOverlayMessage(Minecraft mc, Component message) {
		if (mc.gui != null) {
			mc.gui.hud.setOverlayMessage(message, false);
		}
	}

	public static Camera mainCamera(Minecraft mc) {
		return mc.gameRenderer.mainCamera();
	}

	/** 26.2 里键盘键的类型标识仍是 KEYSYM */
	public static InputConstants.Type keyboardType() {
		return InputConstants.Type.KEYSYM;
	}

	public static int pressAction() {
		return GLFW.GLFW_PRESS;
	}

	public static int releaseAction() {
		return GLFW.GLFW_RELEASE;
	}

	public static boolean isMouseButtonDown(Minecraft mc, int button) {
		return mc.getWindow() != null
				&& GLFW.glfwGetMouseButton(mc.getWindow().handle(), button) == GLFW.GLFW_PRESS;
	}

	public static void setCursorVisible(Minecraft mc, boolean visible) {
		if (mc.getWindow() == null) {
			return;
		}
		GLFW.glfwSetInputMode(mc.getWindow().handle(), GLFW.GLFW_CURSOR,
				visible ? GLFW.GLFW_CURSOR_NORMAL : GLFW.GLFW_CURSOR_HIDDEN);
	}

}
