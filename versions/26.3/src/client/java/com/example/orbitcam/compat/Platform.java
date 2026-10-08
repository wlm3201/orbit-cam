package com.example.orbitcam.client.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

/**
 * 版本兼容层（26.3）。
 *
 * <p>26.3 起 MC 切换到 SDL：鼠标键编号从 1 开始（GLFW 是 0 开始），
 * 鼠标按键状态需要直接用 SDL 查询；HUD 相关类名/字段也与旧版不同。</p>
 */
public final class Platform {

	private Platform() {
	}

	/** 当前版本里承载准星/状态文字的类（26.3 为 Hud） */
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

	/** 在 HUD 上显示一条提示（开关轨道相机时） */
	public static void setOverlayMessage(Minecraft mc, Component message) {
		if (mc.gui != null) {
			mc.gui.hud.setOverlayMessage(message, false);
		}
	}

	public static Camera mainCamera(Minecraft mc) {
		return mc.gameRenderer.mainCamera();
	}

	public static InputConstants.Type keyboardType() {
		return InputConstants.Type.KEYBOARD;
	}

	public static int pressAction() {
		return InputConstants.PRESS;
	}

	public static int releaseAction() {
		return InputConstants.RELEASE;
	}

	/**
	 * 查询鼠标键是否按下。
	 * 26.3 的按键编号与 SDL 一致（左键 = 1），SDL 的按键掩码为 {@code 1 << (button - 1)}。
	 */
	public static boolean isMouseButtonDown(Minecraft mc, int button) {
		if (mc.getWindow() == null || button <= 0) {
			return false;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer x = stack.mallocFloat(1);
			FloatBuffer y = stack.mallocFloat(1);
			int buttons = SDLMouse.SDL_GetMouseState(x, y);
			return (buttons & (1 << (button - 1))) != 0;
		}
	}

	public static void setCursorVisible(Minecraft mc, boolean visible) {
		if (mc.getWindow() == null) {
			return;
		}
		if (visible) {
			SDLMouse.SDL_ShowCursor();
			mc.getWindow().selectCursor(CursorTypes.ARROW);
		} else {
			SDLMouse.SDL_HideCursor();
		}
	}

}
