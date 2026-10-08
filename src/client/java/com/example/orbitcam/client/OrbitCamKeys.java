package com.example.orbitcam.client;

import com.example.orbitcam.OrbitCamMod;
import com.example.orbitcam.client.compat.Platform;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.util.Arrays;

/** 模组用到的全部按键绑定定义与注册逻辑 */
public final class OrbitCamKeys {

	/** 按键分类：会显示在原版“控制”设置界面里 */
	public static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("orbitcam", "orbitcam"));

	/** 开关轨道相机（默认 F6） */
	public static final KeyMapping TOGGLE = new KeyMapping(
			"key.orbitcam.toggle",
			Platform.keyboardType(),
			InputConstants.KEY_F6,
			CATEGORY);

	/** 修饰键：ALT_HOLD / ALT_TOGGLE 模式下切换“相机态 / 准常态”（默认左 Alt） */
	public static final KeyMapping MODIFIER = new KeyMapping(
			"key.orbitcam.modifier",
			Platform.keyboardType(),
			InputConstants.KEY_LALT,
			CATEGORY);

	/** 旋转视角键（默认鼠标左键） */
	public static final KeyMapping ROTATE = new KeyMapping(
			"key.orbitcam.rotate",
			InputConstants.Type.MOUSE,
			InputConstants.MOUSE_BUTTON_LEFT,
			CATEGORY);

	/** 平移轴心键（默认鼠标右键） */
	public static final KeyMapping PAN = new KeyMapping(
			"key.orbitcam.pan",
			InputConstants.Type.MOUSE,
			InputConstants.MOUSE_BUTTON_RIGHT,
			CATEGORY);

	/** 放大镜（默认 C） */
	public static final KeyMapping ZOOM = new KeyMapping(
			"key.orbitcam.zoom",
			Platform.keyboardType(),
			InputConstants.KEY_C,
			CATEGORY);

	/** 是否已注册过（避免重复注册） */
	private static boolean registered;

	/** Options 里存放按键映射的字段（缓存，避免每次都反射查找） */
	private static Field keyMappingsField;
	/** 字段查找是否已失败过：失败即致命（按键全部失效），没必要反复重试 */
	private static boolean keyMappingsLookupFailed;

	private OrbitCamKeys() {
	}

	/**
	 * 把本模组的按键挂进 {@code Options} 的按键数组，
	 * 这样玩家才能在设置里改键，且按键状态能被原版保存/加载。
	 *
	 * <p>注意：按键若不在该数组中，就收不到任何按下/松开事件，
	 * 所以这里是“必须成功”的一步，失败会打 error 日志。</p>
	 */
	public static void register(Options options) {
		if (options == null || registered) {
			return;
		}
		// 用 & 而不是 &&：即使前一个失败也要尝试剩下的，避免部分注册后状态不明
		boolean ok = append(options, TOGGLE);
		ok &= append(options, MODIFIER);
		ok &= append(options, ROTATE);
		ok &= append(options, PAN);
		ok &= append(options, ZOOM);
		registered = ok;
	}

	/** 反射追加一个按键映射到 {@code Options.keyMappings} 数组（原版未提供注册接口） */
	private static boolean append(Options options, KeyMapping binding) {
		Field field = keyMappingsField();
		if (field == null) {
			return false;
		}
		try {
			KeyMapping[] all = (KeyMapping[]) field.get(options);
			for (KeyMapping mapping : all) {
				if (mapping == binding) {
					return true;
				}
			}
			KeyMapping[] updated = Arrays.copyOf(all, all.length + 1);
			updated[all.length] = binding;
			field.set(options, updated);
			return true;
		} catch (Throwable t) {
			OrbitCamMod.LOGGER.error("Could not register OrbitCam keybind in options", t);
			return false;
		}
	}

	/**
	 * 反射取得 {@code Options.keyMappings} 字段。
	 * 该字段是非静态的 final：{@code setAccessible(true)} 之后允许反射写入
	 * （理论上有被 JIT 常量折叠的风险，但 TrustFinalNonStaticFields 默认关闭，实践可靠）。
	 */
	private static Field keyMappingsField() {
		if (keyMappingsField != null) {
			return keyMappingsField;
		}
		if (keyMappingsLookupFailed) {
			return null;
		}
		try {
			Field field = Options.class.getDeclaredField("keyMappings");
			field.setAccessible(true);
			keyMappingsField = field;
			return field;
		} catch (Throwable t) {
			keyMappingsLookupFailed = true;
			OrbitCamMod.LOGGER.error("找不到 Options.keyMappings 字段，OrbitCam 的按键将无法生效", t);
			return null;
		}
	}
}
