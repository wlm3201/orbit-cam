package com.example.orbitcam.client.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 版本兼容层（26.1.2）。
 *
 * <p>旧版把界面相关的内容都放在 {@code Gui} 里，屏幕/overlay 也直接挂在 Minecraft 上，
 * 与 26.2+ 的 {@code Gui#hud} 结构不同，这里统一成同一套接口。</p>
 */
public final class Platform {

	private Platform() {
	}

	/** 当前版本里承载准星/状态文字的类（26.1.2 为 Gui） */
	public static Class<?> hudClass() {
		return Gui.class;
	}

	public static Screen screen(Minecraft mc) {
		return mc.screen;
	}

	public static boolean hasScreen(Minecraft mc) {
		return mc.screen != null;
	}

	public static boolean hasOverlay(Minecraft mc) {
		return mc.getOverlay() != null;
	}

	public static void setOverlayMessage(Minecraft mc, Component message) {
		if (mc.gui != null) {
			mc.gui.setOverlayMessage(message, false);
		}
	}

	public static Camera mainCamera(Minecraft mc) {
		return mc.gameRenderer.getMainCamera();
	}

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
