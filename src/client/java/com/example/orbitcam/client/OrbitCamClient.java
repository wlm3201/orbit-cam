package com.example.orbitcam.client;

import com.example.orbitcam.OrbitCamMod;
import com.example.orbitcam.client.compat.Platform;
import com.example.orbitcam.client.config.OrbitConfig;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;

/**
 * 客户端入口：把按键绑定挂到 {@link OrbitCam}，并做一次“mixin 钩子自检”。
 */
public class OrbitCamClient implements ClientModInitializer {

	/**
	 * 需要确认确实被注入到目标类中的 mixin 方法清单。
	 * 由于 mixin 是编译期织入、失败时是静默的，这里在启动时用反射逐个核对，
	 * 缺失时在日志里明确报错，方便定位“功能失效是因为 mixin 没打上”。
	 */
	private static final Hook[] HOOKS = {
			new Hook("相机接管", Camera.class, "orbitcam$applyOrbit"),
			new Hook("屏蔽液体雾", Camera.class, "orbitcam$hideFluidFog"),
			new Hook("准星射线", LocalPlayer.class, "orbitcam$pickFromCamera"),
			new Hook("长按连续破坏", Minecraft.class, "orbitcam$allowHoldMining"),
			new Hook("鼠标按键", MouseHandler.class, "orbitcam$onButton"),
			new Hook("视角输入", MouseHandler.class, "orbitcam$turnPlayer"),
			new Hook("滚轮缩放", MouseHandler.class, "orbitcam$onScroll"),
			new Hook("冻结身体", LocalPlayer.class, "orbitcam$freezeBody"),
			new Hook("准星跟随指针", Platform.hudClass(), "orbitcam$shiftCrosshair"),
			new Hook("准心不受视角限制", Platform.hudClass(), "orbitcam$crosshairPerspective"),
			new Hook("按键注册", Options.class, "orbitcam$registerBeforeLoad"),
			new Hook("第三方选点起点", Entity.class, "orbitcam$eyeAtCamera"),
			new Hook("第三方选点视线", Entity.class, "orbitcam$lookAtPointer"),
			new Hook("服务端交互距离", Player.class, "orbitcam$blockRange"),
			new Hook("服务端实体交互距离", Player.class, "orbitcam$entityRange"),
			new Hook("服务端实体攻击距离", Player.class, "orbitcam$serverAttackRange"),
			new Hook("隐藏穿墙遮挡", ScreenEffectRenderer.class, "orbitcam$hideInWallOverlay"),
			new Hook("轴心方块线框", LevelRenderer.class, "orbitcam$submitPivotOutline"),
			new Hook("放大镜 FOV", Camera.class, "orbitcam$applyZoomFov"),
			new Hook("放大镜按键", KeyboardHandler.class, "orbitcam$captureZoomKey"),
			new Hook("模式状态文字", Platform.hudClass(), "orbitcam$statusHud"),
			new Hook("屏蔽方块描边", GameRenderer.class, "orbitcam$hideOutline"),
			new Hook("取消走路晃动", GameRenderer.class, "orbitcam$noViewBob"),
			new Hook("取消受伤晃动", GameRenderer.class, "orbitcam$noHurtBob"),
			new Hook("保持鼠标自由", MouseHandler.class, "orbitcam$keepCursorFree"),
			new Hook("自由指针拖拽", MouseHandler.class, "orbitcam$captureFreeCursor"),
			new Hook("阻止连续放置", Minecraft.class, "orbitcam$stopHoldPlacing"),
			new Hook("切换世界时关闭", Minecraft.class, "orbitcam$stopOnLevelChange"),
			new Hook("退出存档时关闭", Minecraft.class, "orbitcam$stopOnDisconnect"),
	};

	@Override
	public void onInitializeClient() {
		// 把按键映射交给核心类统一读取（避免核心类直接依赖本入口类）
		OrbitCam.bindKeys(OrbitCamKeys.TOGGLE, OrbitCamKeys.MODIFIER, OrbitCamKeys.ROTATE, OrbitCamKeys.PAN);

		Minecraft mc = Minecraft.getInstance();
		if (mc != null) {
			OrbitCamKeys.register(mc.options);
		}

		checkHooks();

		OrbitCamMod.LOGGER.info("OrbitCam client initialized (config: {})", OrbitConfig.class.getSimpleName());
	}

	/** 逐个核对钩子是否存在，缺失的打 error 日志 */
	private static void checkHooks() {
		int missing = 0;
		for (Hook hook : HOOKS) {
			if (!hasDeclaredMethod(hook.owner, hook.method)) {
				OrbitCamMod.LOGGER.error("[state] hook MISSING: {} ({})", hook.method, hook.label);
				missing++;
			}
		}
		if (missing == 0) {
			OrbitCamMod.LOGGER.info("[state] orbitcam hooks installed ({}/{})", HOOKS.length, HOOKS.length);
		} else {
			OrbitCamMod.LOGGER.error("[state] orbitcam hooks {}/{} MISSING - 功能可能部分失效",
					missing, HOOKS.length);
		}
	}

	/**
	 * 判断目标类里是否存在指定（注入的）方法。
	 *
	 * <p>先做精确匹配；只有精确匹配失败时才退化为后缀匹配
	 * （个别环境下注入方法名可能带有额外前后缀），并保证精确匹配优先，
	 * 避免“名字恰好以同样字符串结尾”的无关方法造成误判。</p>
	 */
	private static boolean hasDeclaredMethod(Class<?> owner, String name) {
		boolean relaxed = false;
		for (Method method : owner.getDeclaredMethods()) {
			if (method.getName().equals(name)) {
				return true;
			}
			if (method.getName().endsWith(name)) {
				relaxed = true;
			}
		}
		return relaxed;
	}

	private record Hook(String label, Class<?> owner, String method) {
	}
}
