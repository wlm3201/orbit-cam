package com.example.orbitcam.client;

import com.example.orbitcam.OrbitCamMod;
import com.example.orbitcam.client.compat.Platform;
import com.example.orbitcam.client.config.OrbitConfig;
import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Options;
import com.example.orbitcam.client.mixin.KeyMappingAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 轨道相机（Orbit Camera）的核心状态机与运算中心。
 *
 * <p>整体思路：把相机抽象成“绕着一个轴心点 {@link #pivot} 旋转、距离 {@link #distance} 可缩放”的模型；
 * 玩家的移动输入（WASD/空格/Shift）不再驱动玩家身体，而是驱动轴心点（即“飞行”）。
 * 所有对原版的介入都通过 mixin 调用到本类的静态方法中。</p>
 *
 * <p>关键状态：
 * <ul>
 *   <li>{@link #engaged}：是否已进入轨道相机模式（开关键切换）；</li>
 *   <li>{@link #cameraMode}：在“修饰键模式”下是否处于相机态（等价于 Alt 是否按下）；</li>
 *   <li>{@link #blend}：进入/退出时与原版相机姿态之间的插值权重，用于平滑过渡。</li>
 * </ul>
 */
public final class OrbitCam {
	/** 轴心点距离的下限 / 上限（格） */
	private static final double MIN_DISTANCE = 3.0;
	private static final double MAX_DISTANCE = 128.0;
	/**
	 * “能看到多远”类射线的长度上限（格）：32 区块，覆盖常见渲染距离。
	 *
	 * <p>刻意远大于 {@link #MAX_DISTANCE}：拾取交互距离默认就是 128，
	 * 若射线也只打 128，交互距离之外的地形就永远拾取不到。</p>
	 */
	static final double AIM_RAY_MAX_DISTANCE = 512.0;
	/**
	 * 轨道距离的滚轮缩放系数：每格缩放 0.95，与投影工坊（OrbitControls 的 {@code 0.95^zoomSpeed}）一致。
	 * GLFW 在 Windows 上把一格滚轮归一化成 1.0，所以 {@code exp(-rate) = 0.95} → {@code rate = -ln 0.95}。
	 * 注意它与 {@link OrbitZoom} 的变焦倍率不是一回事。
	 */
	private static final double SCALE_RATE = -Math.log(0.95);
	/**
	 * 平移的“像素 → 世界”换算系数。
	 * 2.0 = 距离 D 处一像素对应的世界长度（{@code 2·D·tan(fov/2) / 屏高}），
	 * 也就是内容严格跟着指针走的 1:1 抓取，与投影工坊 OrbitControls 的 panSpeed=1 相同。
	 * 原来的 4.0 会让内容比指针快一倍。
	 */
	private static final double PAN_PIXEL_SCALE = 2.0;
	/**
	 * 平滑系数的“单位时间常数”（秒）：配置里的 {@code rotateSmoothing} / {@code panSmoothing}
	 * 是无量纲倍率，乘上它才是真正的时间常数。1 → 0.05 秒，即投影工坊的观感。
	 */
	private static final double SMOOTHING_UNIT = 0.05;
	/** 平滑倍率的上限，与配置界面滑块范围一致 */
	private static final double SMOOTHING_MAX = 4.0;
	/** 进入/退出轨道相机时，姿态混合权重衰减的时间常数（秒） */
	private static final double BLEND_TAU = 0.09;
	/** 俯仰角上限，留 0.1° 余量避免视线与世界上方共线导致叉积退化 */
	static final float MAX_PITCH = 89.9F;
	/**
	 * 旋转“待应用增量”累加器的上限（度），也就是松手后最多还会自己转多少。
	 * 超过的部分被丢弃，因此极端快速拖动时会略微跟不上手（此时转速约 {@code MAX·k/帧}）。
	 */
	private static final double MAX_PENDING_ROTATE = 360.0;
	/** 小于该角度（度）的旋转残留视为已耗尽，直接清零，避免无限残留微小抖动 */
	private static final double PENDING_EPSILON = 1.0E-3;
	/** 平移“待应用偏移”累加器的上限（格），作用同 {@link #MAX_PENDING_ROTATE} */
	private static final double MAX_PENDING_PAN = 128.0;
	/** 小于该长度平方（格²）的平移残留视为已耗尽 */
	private static final double PENDING_PAN_EPSILON = 1.0E-8;
	/** 飞行速度惯性时间常数的上限系数，与配置里的 flightAccel/flightDecel 相乘 */
	private static final double FLIGHT_INERTIA_TAU = 0.5;

	/** 世界坐标的上方向（用于求右/上基向量） */
	static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);

	/**
	 * 把当前相机朝向同步给玩家实体：让“玩家朝向”始终等于相机朝向。
	 * 这样原版依赖玩家朝向的逻辑（准星射线、放置方块朝向、服务端交互判定等）才能跟随轨道相机。
	 */
	public static void syncPlayerRotation(float cameraYaw, float cameraPitch) {
		if (!isActive()) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		float pitch = Mth.clamp(cameraPitch, -MAX_PITCH, MAX_PITCH);
		player.yRotO = cameraYaw;
		player.xRotO = pitch;
		player.setYRot(cameraYaw);
		player.setXRot(pitch);
		// yBob/xBob 是渲染用的一人称手臂摆动基准角，一并同步，避免手臂朝向与画面不一致
		player.yBob = cameraYaw;
		player.xBob = pitch;
		player.yBobO = cameraYaw;
		player.xBobO = pitch;
	}

	/** 当前是否正在“旋转视角”（旋转键按下且模式允许） */
	public static boolean isRotating() {
		return rotateKey != null && isMappingDown(rotateKey) && viewControlArmed() && isCameraHeld(rotateKey);
	}

	/** 当前是否正在“平移轴心”（平移键按下且模式允许） */
	public static boolean isPanning() {
		return panKey != null && isMappingDown(panKey) && viewControlArmed() && isCameraHeld(panKey);
	}

	/**
	 * 旋转/平移在当前模式下是否已经生效（常驻模式始终生效，修饰键模式需要相机态）。
	 *
	 * <p>“是否轮到相机用这个键”另由 {@link #isCameraHeld(KeyMapping)} 判定，
	 * 那是按下那一刻定下来的，不是每帧猜的。</p>
	 */
	private static boolean viewControlArmed() {
		return isAlwaysOnMode() || cameraMode;
	}

	private static boolean isConflictingButton(int button) {
		return button == InputConstants.MOUSE_BUTTON_LEFT
				|| button == InputConstants.MOUSE_BUTTON_RIGHT
				|| button == InputConstants.MOUSE_BUTTON_MIDDLE;
	}

	/** 是否为“混合模式”（原版左键操作与相机旋转共存，靠拖拽阈值区分点击与拖拽） */
	public static boolean isHybridMode() {
		return OrbitConfig.INSTANCE.mouseMode == OrbitConfig.MouseMode.HYBRID;
	}

	/** 是否为“常驻模式”（INDEPENDENT / HYBRID）：无需修饰键即可旋转/平移 */
	public static boolean isAlwaysOnMode() {
		OrbitConfig.MouseMode mode = OrbitConfig.INSTANCE.mouseMode;
		return mode == OrbitConfig.MouseMode.INDEPENDENT || mode == OrbitConfig.MouseMode.HYBRID;
	}

	/** 是否应当屏蔽原版世界交互（仅在“修饰键模式”且处于相机态时屏蔽） */
	public static boolean isInteractionBlocked() {
		return !isAlwaysOnMode() && cameraMode;
	}

	/** 是否应当显示鼠标指针（修饰键模式下按住修饰键时显示，便于用指针进行交互） */
	public static boolean isCursorShown() {
		return cameraMode && isModifierMode();
	}

	/** 是否为“修饰键模式”（ALT_HOLD / ALT_TOGGLE） */
	private static boolean isModifierMode() {
		OrbitConfig.MouseMode mode = OrbitConfig.INSTANCE.mouseMode;
		return mode == OrbitConfig.MouseMode.ALT_HOLD || mode == OrbitConfig.MouseMode.ALT_TOGGLE;
	}

	/** 统一的“按键是否按下”判定：鼠标键走 Platform 查询，键盘键走原版 isDown() */
	private static boolean isMappingDown(KeyMapping mapping) {
		InputConstants.Key key = currentKey(mapping);
		if (key.getType() == InputConstants.Type.MOUSE) {
			return isMouseButtonDown(key.getValue());
		}
		return mapping.isDown();
	}

	/**
	 * 某次鼠标按下是否应当被轨道相机接管。
	 *
	 * <p>只在按下事件到达时判定一次（我们的注入点在事件链末尾，
	 * 能走到这里说明没有第三方先消费掉它）；判定为是则取消事件并
	 * 用 {@link #markCameraHeld(int)} 占位，直到松手。</p>
	 */
	public static boolean shouldCaptureMousePress(int mouseButton) {
		if (!engaged) {
			return false;
		}
		if (!isMouseButtonDown(mouseButton)) {
			return false;
		}
		// 中键（选取方块）只在相机态下接管
		if (mouseButton == InputConstants.MOUSE_BUTTON_MIDDLE) {
			return cameraMode;
		}
		if (rotateKey != null && matchesMouse(rotateKey, mouseButton)) {
			return isKeyArmed(rotateKey);
		}
		if (panKey != null && matchesMouse(panKey, mouseButton)) {
			return isKeyArmed(panKey);
		}
		return false;
	}

	/** 旋转/平移键是否处于“可触发”状态（已按下，且模式允许） */
	private static boolean isKeyArmed(KeyMapping mapping) {
		return isMappingDown(mapping) && viewControlArmed();
	}

	/** 通过 accessor 读取按键映射当前绑定的键（可能是鼠标键或键盘键） */
	private static InputConstants.Key currentKey(KeyMapping mapping) {
		return ((KeyMappingAccessor) mapping).orbitcam$getKey();
	}

	private static boolean matchesMouse(KeyMapping mapping, int mouseButton) {
		InputConstants.Key key = currentKey(mapping);
		return key.getType() == InputConstants.Type.MOUSE && key.getValue() == mouseButton;
	}

	/**
	 * 刷新“修饰键模式”下的相机态。
	 * ALT_TOGGLE 为按一下切换，ALT_HOLD（及其它模式）为按住即生效。
	 */
	public static void refreshModifier() {
		boolean down = modifierKey != null && modifierKey.isDown();
		if (!engaged) {
			cameraMode = false;
			prevModifierDown = down;
			return;
		}
		if (OrbitConfig.INSTANCE.mouseMode == OrbitConfig.MouseMode.ALT_TOGGLE) {
			if (down && !prevModifierDown) {
				cameraMode = !cameraMode;
			}
		} else {
			cameraMode = down;
		}
		prevModifierDown = down;
	}

	/** HUD 上显示的当前模式文案 */
	public static String statusText() {
		if (isHybridMode()) {
			return status("orbitcam.status.hybrid");
		}
		if (OrbitConfig.INSTANCE.mouseMode == OrbitConfig.MouseMode.INDEPENDENT) {
			return status("orbitcam.status.independent");
		}
		return status(cameraMode ? "orbitcam.status.camera" : "orbitcam.status.crosshair");
	}

	private static String status(String key) {
		return Component.translatable(key).getString();
	}

	// ===== 按键绑定（客户端初始化时由 bindKeys 注入）=====
	private static KeyMapping toggleKey;
	private static KeyMapping modifierKey;
	private static KeyMapping rotateKey;
	private static KeyMapping panKey;

	/** 是否已进入轨道相机 */
	static boolean engaged;
	private static boolean prevToggleDown;
	/** 修饰键模式下的“相机态”标志 */
	private static boolean cameraMode;
	private static boolean prevModifierDown;
	/**
	 * 需要跟踪的鼠标键数量（数组下标即按键编号，取“最大编号 + 1”）。
	 * 26.1/26.2（GLFW）的编号为 0~7，26.3 起（SDL）为 1~8，
	 * 因此这里必须是 9，否则 26.3 的第 8 号键会被静默忽略。
	 */
	private static final int MAX_MOUSE_BUTTONS = 9;
	/** 混合模式下：该键是否处于“已按下未释放” */
	private static final boolean[] hybridHeld = new boolean[MAX_MOUSE_BUTTONS];
	/** 该键的这次按下是否已被相机接管（按下时置位，松手或退出轨道相机时清除） */
	private static final boolean[] cameraHeld = new boolean[MAX_MOUSE_BUTTONS];
	/** 混合模式下：该键按下期间是否已超过拖拽阈值（超过则判定为拖拽而非点击） */
	private static final boolean[] hybridDragged = new boolean[MAX_MOUSE_BUTTONS];
	private static final double[] hybridDragX = new double[MAX_MOUSE_BUTTONS];
	private static final double[] hybridDragY = new double[MAX_MOUSE_BUTTONS];
	/** 轴心点：相机始终围绕它旋转 */
	static Vec3 pivot = Vec3.ZERO;
	/** 相机到轴心点的距离 */
	static double distance = 4.0;
	/** 实际渲染用的相机朝向（会平滑追赶 orbit 角度） */
	static float viewYaw;
	static float viewPitch;
	/** 输入直接驱动的目标朝向 */
	static float orbitYaw;
	static float orbitPitch;
	/** 与原版相机姿态的混合权重：1 = 完全轨道相机，0 = 完全原版 */
	private static float blend;
	private static double lastTime = -1.0;

	/**
	 * 累积的、尚未应用的旋转输入（度，不 wrap）。
	 * 开启旋转平滑时每帧只消耗一部分，剩下的留作下一帧的滞后量（松手后即惯性尾滑）。
	 */
	private static double pendingYaw;
	private static double pendingPitch;
	private static Vec3 pendingPan = Vec3.ZERO;
	/** WASD 等按键驱动的轴心点飞行速度 */
	private static Vec3 flightVel = Vec3.ZERO;
	/**
	 * 鼠标指针在屏幕上的归一化设备坐标（NDC，范围 -1 ~ 1）。
	 * 包内可见：{@link OrbitZoom} 需要它做放大镜锚点解算。
	 */
	static double pointerNdcX;
	static double pointerNdcY;
	/** 当前已生效的鼠标指针显示模式缓存，避免重复调用底层接口；-1 表示未初始化 */
	private static int cursorMode = -1;


	/** 原版相机本帧的姿态，用于退出时做混合过渡 */
	private static Vec3 vanillaPos = Vec3.ZERO;
	private static float vanillaYaw;
	private static float vanillaPitch;
	/** 原版姿态是否已记录过（首帧前为 false） */
	static boolean vanillaReady;
	/** 最近一次计算出的轨道相机眼睛位置，供 EntityMixin 覆写 getEyePosition */
	static Vec3 cameraEyePos = Vec3.ZERO;
	/** 进入轨道相机前的 smartCull（洞穴剔除）开关，退出时还原 */
	private static boolean smartCullBeforeEngage = true;
	/** 上一帧所在的关卡实例，用于兜底检测维度切换 / 进出存档 */
	private static ClientLevel lastLevel;
	/** 上一帧的玩家实体，用于兜底检测死亡重生（重生会重建 LocalPlayer） */
	private static LocalPlayer lastPlayer;
	/**
	 * 单人服务端侧的位置同步任务是否已排队但未执行。
	 *
	 * <p>合流用：{@link #syncBodyToCamera} 每帧都会调用，若无条件排队会在服务端卡顿时堆积任务。
	 * 由渲染线程置 true、服务端线程置 false，因此用 volatile。</p>
	 */
	private static volatile boolean serverSyncQueued;

	private OrbitCam() {
	}

	public static boolean isEngaged() {
		return engaged;
	}

	/** 是否仍需要接管相机：已开启，或退出后的过渡动画尚未结束 */
	public static boolean isActive() {
		return engaged || blend > 0.0F;
	}

	/** 绑定四个功能键：开关、修饰、旋转、平移（客户端初始化时调用） */
	public static void bindKeys(KeyMapping toggle, KeyMapping modifier, KeyMapping rotate, KeyMapping pan) {
		toggleKey = toggle;
		modifierKey = modifier;
		rotateKey = rotate;
		panKey = pan;
	}

	/**
	 * 标记“这次按下已被相机接管”，直到松手为止。
	 *
	 * <p>这是解决与第三方冲突的关键：旋转/平移是每帧轮询按键状态驱动的，
	 * 光靠事件侧拦不住；但“相机有没有资格用这个键”完全可以在<b>按下那一刻</b>定下来。
	 * 我们的注入点已在事件链末尾，若第三方（MaLiLib/litematica、selective-rendering 等）
	 * 先消费并取消了事件，我们根本不会跑到这里，也就不会标记——拖动自然不会转相机。</p>
	 *
	 * <p>好处是不需要认识任何一个第三方：谁处理谁取消，我们不处理就不占位。
	 * 侧键这类第三方不碰的键照样会被标记，独立模式下用侧键转视角不受影响。</p>
	 */
	public static void markCameraHeld(int button) {
		if (button >= 0 && button < MAX_MOUSE_BUTTONS) {
			cameraHeld[button] = true;
		}
	}

	/** 松手（或退出轨道相机）时清除“相机接管中”的标记 */
	public static void clearCameraHeld(int button) {
		if (button >= 0 && button < MAX_MOUSE_BUTTONS) {
			cameraHeld[button] = false;
		}
	}

	/** 相机是否仍握着这个鼠标键（用于屏蔽原版长按破坏/放置） */
	public static boolean isCameraHeld(int button) {
		return button >= 0 && button < MAX_MOUSE_BUTTONS && cameraHeld[button];
	}

	/**
	 * 某个旋转/平移键是否处于“这次按下归相机”的状态。
	 * 绑在键盘上的键没有按下事件可依据（也无法被第三方取消），一律视为可用。
	 */
	private static boolean isCameraHeld(KeyMapping mapping) {
		InputConstants.Key key = currentKey(mapping);
		return key.getType() != InputConstants.Type.MOUSE || isCameraHeld(key.getValue());
	}

	/**
	 * 混合模式下记录一次“可能被相机接管的按键按下”，用于在松手时判断是点击还是拖拽。
	 * 只跟踪会与原版冲突的三个键。
	 */
	public static void noteHybridPress(int button) {
		if (!isHybridMode() || !isConflictingButton(button) || button >= MAX_MOUSE_BUTTONS) {
			return;
		}
		hybridHeld[button] = true;
		hybridDragged[button] = false;
		hybridDragX[button] = 0.0;
		hybridDragY[button] = 0.0;
	}

	/** 取按键映射绑定的鼠标键编号，非鼠标键返回 -1 */
	private static int keyButton(KeyMapping mapping) {
		if (mapping == null) {
			return -1;
		}
		InputConstants.Key key = currentKey(mapping);
		return key.getType() == InputConstants.Type.MOUSE ? key.getValue() : -1;
	}

	/**
	 * 混合模式下：当前按住的旋转/平移键是否还没超过拖拽阈值。
	 * 未超过阈值时不施加视角变化，这样短按可以正常触发原版的破坏/放置。
	 */
	private static boolean dragBelowThreshold() {
		if (!isHybridMode() || OrbitConfig.INSTANCE.clickDragThreshold <= 0.0) {
			return false;
		}
		int button = keyButton(rotateKey);
		if (button < 0 || !hybridHeld[button]) {
			button = keyButton(panKey);
		}
		if (button < 0 || button >= MAX_MOUSE_BUTTONS || !hybridHeld[button]) {
			return false;
		}
		return !hybridDragged[button];
	}

	/** 累加所有“按下中”的冲突键的拖拽位移，超过阈值即标记为拖拽 */
	public static void accumulateHybridDrag(double dx, double dy) {
		for (int i = 0; i < MAX_MOUSE_BUTTONS; i++) {
			if (!hybridHeld[i] || hybridDragged[i]) {
				continue;
			}
			hybridDragX[i] += dx;
			hybridDragY[i] += dy;
			double threshold = OrbitConfig.INSTANCE.clickDragThreshold;
			if (threshold <= 0.0
					|| hybridDragX[i] * hybridDragX[i] + hybridDragY[i] * hybridDragY[i] >= threshold * threshold) {
				hybridDragged[i] = true;
			}
		}
	}

	/**
	 * 松手时结算一次混合模式按键：返回 true 表示这是一次“点击”（未拖拽），
	 * 应当补发给原版处理；同时清空该键的跟踪状态。
	 */
	public static boolean consumeHybridClick(int button) {
		if (button < 0 || button >= MAX_MOUSE_BUTTONS) {
			return false;
		}
		boolean held = hybridHeld[button];
		boolean dragged = hybridDragged[button];
		hybridHeld[button] = false;
		hybridDragged[button] = false;
		hybridDragX[button] = 0.0;
		hybridDragY[button] = 0.0;
		return held && !dragged;
	}

	private static void clearHybridPresses() {
		for (int i = 0; i < MAX_MOUSE_BUTTONS; i++) {
			hybridHeld[i] = false;
			hybridDragged[i] = false;
			hybridDragX[i] = 0.0;
			hybridDragY[i] = 0.0;
			cameraHeld[i] = false;
		}
	}

	/**
	 * 向原版补发一次鼠标点击（按下时已被本模组取消，松手时若判定为点击则补发）。
	 * 直接操作 KeyMapping 状态，绕过已被取消的 MouseHandler 事件。
	 */
	public static void replayVanillaClick(int button) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || Platform.hasScreen(mc) || Platform.hasOverlay(mc)) {
			return;
		}
		InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(button);
		KeyMapping.set(key, true);
		KeyMapping.click(key);
		KeyMapping.set(key, false);
	}

	/** 当前的轴心点（相机围绕它旋转） */
	public static Vec3 pivot() {
		return pivot;
	}

	/** 一次计算得到的相机姿态：位置 + 朝向 */
	public record CameraPose(Vec3 position, float yaw, float pitch) {
	}


	/** 开关轨道相机（由开关键或外部调用触发） */
	public static void toggle() {
		if (engaged) {
			disengage();
			showToast(false);
		} else if (canOrbit()) {
			engage();
			showToast(true);
		}
	}

	private static void showToast(boolean on) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) {
			return;
		}
		Platform.setOverlayMessage(mc, Component.translatable(on ? "orbitcam.toast.on" : "orbitcam.toast.off"));
	}

	/**
	 * 滚轮事件：变焦键按住时调整变焦倍率，否则调整环绕距离。
	 *
	 * <p>注意“缩放”在本模组里指两件不同的事：
	 * <b>轨道距离</b>（相机到轴心点的距离，滚轮，走 {@code scaleSensitivity}）与
	 * <b>变焦</b>（FOV 倍率，变焦键 + 滚轮，走 {@code zoomSensitivity}）。</p>
	 *
	 * <p>调整环绕距离时以指针射线方向为准做“锚点缩放”：
	 * 让指针指向的世界点在缩放前后尽量保持在指针下方，手感接近常见 3D 软件。</p>
	 *
	 * @return true 表示事件已被消费，应阻止原版切换快捷栏物品
	 */
	public static boolean onScroll(double yOffset) {
		// 不需要额外让位：注入点已排在其他模组之后，
		// 第三方消费掉的事件根本到不了这里（见 MouseHandlerMixin#orbitcam$onScroll）
		if (yOffset == 0.0 || !engaged || !canOrbit()) {
			return false;
		}
		if (OrbitZoom.onScroll(yOffset)) {
			return true;
		}
		// 与旋转/平移保持同一判定口径：处于相机态，或正在旋转/平移时才消费滚轮；
		// 否则把滚轮还给原版（切换快捷栏物品）
		if (!cameraMode && !isRotating() && !isPanning()) {
			return false;
		}
		Vec3 look = direction(viewYaw, viewPitch);
		double newDistance = Mth.clamp(
				distance * Math.exp(-yOffset * SCALE_RATE * OrbitConfig.INSTANCE.scaleSensitivity),
				MIN_DISTANCE, MAX_DISTANCE);

		Vec3 pointer = pointerRay();
		Vec3 shift = pointer.subtract(look).scale(distance - newDistance);

		pivot = pivot.add(shift);
		distance = newDistance;
		return true;
	}

	/**
	 * 应用鼠标位移：平移轴心点或旋转视角。
	 *
	 * <p>平移按“屏幕像素 → 世界距离”换算（1:1 抓取，内容跟指针同速）；
	 * 旋转按“屏幕高度 = 360°”换算，并跟随变焦倍率缩小步长。</p>
	 */
	public static void applyLookDelta(double dx, double dy) {
		if (!engaged) {
			return;
		}
		accumulateHybridDrag(dx, dy);
		if (dx == 0.0 && dy == 0.0) {
			return;
		}
		// 混合模式下未超过拖拽阈值前不转视角，以便短按仍能被识别为点击
		if (dragBelowThreshold()) {
			return;
		}
		refreshModifier();
		Minecraft mc = Minecraft.getInstance();
		Window window = mc.getWindow();
		if (window == null) {
			return;
		}

		boolean rotating = isRotating();
		boolean panning = isPanning();

		if (panning && !rotating) {
			double worldPerPixel = PAN_PIXEL_SCALE * distance * Math.tan(currentFovDeg(mc) * Mth.DEG_TO_RAD / 2.0)
					/ Math.max(window.getScreenHeight(), 1) * OrbitConfig.INSTANCE.panSensitivity;
			Vec3 look = OrbitZoom.effectiveLook();
			Vec3 axisRight = look.cross(WORLD_UP).normalize();
			Vec3 axisUp = axisRight.cross(look);
			pendingPan = pendingPan.subtract(axisRight.scale(dx * worldPerPixel)).add(axisUp.scale(dy * worldPerPixel));
			return;
		}

		double degreesPerPixel = 360.0 / Math.max(window.getScreenHeight(), 1)
				* OrbitConfig.INSTANCE.rotateSensitivity;
		double xo = dx * degreesPerPixel * OrbitZoom.zoomFovScale();
		double yo = dy * degreesPerPixel * OrbitZoom.zoomFovScale();
		Options options = mc.options;
		if (options.invertMouseX().get()) {
			xo = -xo;
		}
		if (options.invertMouseY().get()) {
			yo = -yo;
		}
		if (rotating) {
			pendingYaw += xo;
			pendingPitch += yo;
		}
	}


	/** NDC → GUI 坐标（左上角为原点） */
	public static double pointerGuiX(int guiWidth) {
		return (pointerNdcX * 0.5 + 0.5) * guiWidth;
	}

	public static double pointerGuiY(int guiHeight) {
		return (0.5 - pointerNdcY * 0.5) * guiHeight;
	}

	/**
	 * 当前实际渲染用的垂直 FOV（度）。
	 *
	 * <p>取 {@code Camera#getFov()} 而不是 {@code options.fov()}：
	 * 前者已经过疾跑/水下/死亡修正，也被 {@code CameraMixin} 乘上了变焦倍率，
	 * 因此按它换算“一像素对应多少世界长度”才能与屏幕所见自洽。</p>
	 */
	private static double currentFovDeg(Minecraft mc) {
		Camera camera = Platform.mainCamera(mc);
		return camera != null && camera.getFov() > 0.0F ? camera.getFov() : mc.options.fov().get();
	}

	/** 取当前指针位置对应的世界方向（单位向量） */
	public static Vec3 pointerRay() {
		Minecraft mc = Minecraft.getInstance();
		Camera camera = Platform.mainCamera(mc);
		float fov = (float) currentFovDeg(mc);
		Camera.NearPlane plane = camera.getNearPlane(fov);
		return plane.getPointOnPlane((float) pointerNdcX, (float) pointerNdcY).normalize();
	}

	/** 由鼠标在窗口中的物理位置换算 NDC */
	private static void updatePointer(Minecraft mc) {
		Window window = mc.getWindow();
		MouseHandler mouse = mc.mouseHandler;
		if (window == null || mouse == null) {
			pointerNdcX = 0.0;
			pointerNdcY = 0.0;
			return;
		}
		double width = Math.max(window.getScreenWidth(), 1);
		double height = Math.max(window.getScreenHeight(), 1);
		pointerNdcX = (mouse.xpos() / width) * 2.0 - 1.0;
		pointerNdcY = 1.0 - (mouse.ypos() / height) * 2.0;
	}


	/** 记录本帧的原版相机姿态，作为退出过渡时的插值起点 */
	public static void recordVanillaPose(Vec3 position, float yaw, float pitch) {
		vanillaPos = position;
		vanillaYaw = yaw;
		vanillaPitch = pitch;
		vanillaReady = true;
	}

	/**
	 * 兜底检测世界/玩家是否已经换过：只要对不上上一帧的对象就关闭轨道相机。
	 *
	 * <p>覆盖维度切换、进出存档与死亡重生（重生必然重建 LocalPlayer，
	 * 而同维度重生并不会安装新的 ClientLevel，所以不能只看 {@code mc.level}）。</p>
	 */
	private static void detectWorldChange(Minecraft mc) {
		if (mc.level != lastLevel) {
			lastLevel = mc.level;
			stopForWorldChange("世界实例变化");
		}
		LocalPlayer player = mc.player;
		if (player != lastPlayer) {
			lastPlayer = player;
			stopForWorldChange("玩家实体重建");
		}
		// 死亡瞬间立即关闭，不等到重生；否则死亡界面与重生后都会停在轨道视角
		if (engaged && player != null && player.isDeadOrDying()) {
			stopForWorldChange("玩家死亡");
		}
	}

	/** 推进“轨道 ↔ 原版”的姿态混合权重 */
	private static void updateBlend(double delta, boolean playerTurned) {
		if (engaged) {
			blend = 1.0F;
			return;
		}
		if (blend <= 0.0F) {
			return;
		}
		blend = (float) (blend * Math.exp(-delta / BLEND_TAU));
		// 玩家自己转了视角就直接终止过渡，避免退出时画面被“拉回去”的感觉
		if (blend < 0.002F || playerTurned) {
			blend = 0.0F;
		}
	}

	/**
	 * 世界即将切换（维度传送 / 重生 / 进入或退出存档）时调用：静默、立即地关闭轨道相机。
	 *
	 * <p>由 {@code Minecraft#setLevel} 与 {@code Minecraft#disconnect} 的 HEAD 注入触发，
	 * 因此发生在切换动作“开始”时，而不是等新世界的第一帧渲染出来之后。
	 * 这样新世界里不会残留 engaged、smartCull、释放的鼠标以及旧的相机姿态快照。</p>
	 */
	public static void stopForWorldChange(String reason) {
		if (engaged) {
			disengage();
			// 只在真的处于轨道模式时打日志，方便排查是哪条触发路径生效
			OrbitCamMod.LOGGER.info("[state] orbitcam 已自动关闭 ({})", reason);
		}
		// 旧世界的原版姿态快照在新世界里已无意义，强制下一帧重新采集
		blend = 0.0F;
		vanillaReady = false;
		lastTime = -1.0;
		// 服务端线程循环可能已经停止，排队中的同步任务不会再执行，这里兜底清掉标记
		serverSyncQueued = false;
		clearHybridPresses();
	}

	/**
	 * 每帧的主更新入口（由 CameraMixin 在原版相机计算完成后调用）。
	 * 负责：处理开关键、推进过渡混合、刷新指针与鼠标指针状态、推进放大镜、
	 * 以及按输入更新轴心点与相机朝向。
	 */
	public static void update() {
		Minecraft mc = Minecraft.getInstance();
		detectWorldChange(mc);

		double now = Blaze3D.getTime();
		if (lastTime < 0.0) {
			lastTime = now;
		}
		// 限制单帧步长，避免卡顿/切后台回来时运动量爆炸
		double delta = Mth.clamp(now - lastTime, 0.0, 0.25);
		lastTime = now;

		if (toggleKey != null) {
			boolean down = toggleKey.isDown();
			if (down && !prevToggleDown) {
				toggle();
			}
			prevToggleDown = down;
		}

		// 世界已卸载（退出存档等）时自动退出轨道相机
		if (engaged && !isWorldReady()) {
			disengage();
		}

		updateBlend(delta, OrbitZoom.takePlayerTurn());

		refreshModifier();
		updatePointer(mc);
		updateCursor(mc);
		if (!engaged) {
			clearHybridPresses();
		}

		OrbitZoom.updateZoomKey(OrbitCamKeys.ZOOM.isDown() && canOrbit());
		OrbitZoom.stepZoom(delta);

		LocalPlayer player = mc.player;
		if (engaged && player != null) {
			if (OrbitZoom.isRestoring()) {
				// 放大镜退出期间由 OrbitZoom 独占姿态插值，这里不再叠加运动
				stopMotion();
			} else {
				if (!isBodyFollowing()) {
					// 身体不跟随相机时冻结玩家位移，避免惯性把玩家甩出去
					player.setDeltaMovement(Vec3.ZERO);
				}
				moveByKeys(delta);
				syncBodyToCamera(mc);
				stepMotion(delta);
			}
		} else {
			stopMotion();
		}
	}

	/** WASD / 空格 / Shift 驱动轴心点“飞行”，带可配置的加减速惯性 */
	private static void moveByKeys(double delta) {
		Options options = Minecraft.getInstance().options;
		float forward = (options.keyUp.isDown() ? 1.0F : 0.0F) - (options.keyDown.isDown() ? 1.0F : 0.0F);
		float strafe = (options.keyRight.isDown() ? 1.0F : 0.0F) - (options.keyLeft.isDown() ? 1.0F : 0.0F);
		float vertical = (options.keyJump.isDown() ? 1.0F : 0.0F) - (options.keyShift.isDown() ? 1.0F : 0.0F);

		// 以当前视线在水平面的投影作为前向基向量；视线接近垂直时退化为按 yaw 构造
		Vec3 look = OrbitZoom.effectiveLook();
		Vec3 flat = new Vec3(look.x, 0.0, look.z);
		if (flat.lengthSqr() < 1.0E-8) {
			flat = new Vec3(-Mth.sin(viewYaw * Mth.DEG_TO_RAD), 0.0, Mth.cos(viewYaw * Mth.DEG_TO_RAD));
		}
		flat = flat.normalize();
		Vec3 right = flat.cross(WORLD_UP).normalize();

		OrbitConfig config = OrbitConfig.INSTANCE;
		boolean sprinting = options.keySprint.isDown();
		double horizontalSpeed = config.horizontalSpeed * (sprinting ? config.sprintHorizontalMultiplier : 1.0);
		double verticalSpeed = config.verticalSpeed * (sprinting ? config.sprintVerticalMultiplier : 1.0);

		Vec3 horizontal = flat.scale(forward).add(right.scale(strafe));
		Vec3 offset = Vec3.ZERO;
		if (horizontal.lengthSqr() > 1.0E-8) {
			// 归一化后再缩放，避免斜向移动比直线更快
			offset = horizontal.normalize().scale(horizontalSpeed);
		}
		if (vertical != 0.0F) {
			offset = offset.add(WORLD_UP.scale(vertical * verticalSpeed));
		}

		// 加速与减速使用不同的惯性系数，起步跟手、松手顺滑
		boolean speedingUp = offset.lengthSqr() > flightVel.lengthSqr() + 1.0E-8;
		double inertia = Mth.clamp(speedingUp ? config.flightAccel : config.flightDecel, 0.0, 1.0);
		if (inertia <= 0.0) {
			flightVel = offset;
		} else {
			double tau = Math.max(inertia * FLIGHT_INERTIA_TAU, 0.01);
			flightVel = flightVel.add(offset.subtract(flightVel).scale(1.0 - Math.exp(-delta / tau)));
		}
		if (flightVel.lengthSqr() > 1.0E-8) {
			pivot = pivot.add(flightVel.scale(delta));
		}
	}

	/**
	 * 推进旋转与平移。
	 *
	 * <p>两者用的是同一套机制：鼠标增量不立刻全部生效，而是每帧只“吃掉”一部分，
	 * 剩下的留作后续帧的滞后量。于是“拖拽平滑”与“松手惯性”是同一件事——
	 * 松手只是不再有新输入，残留量继续按指数衰减输出而已（与 OrbitControls 的 enableDamping 一致）。</p>
	 */
	private static void stepMotion(double delta) {
		OrbitConfig config = OrbitConfig.INSTANCE;

		// 关键在于这里操作的是“增量”而不是“角度”：增量不做 wrap，
		// 所以不管拖多快都不会因为取最短路径而反向，也不存在追赶速度上限。
		double rotateK = dampingFactor(delta, smoothingTau(config.rotateSmoothing));
		double dYaw = pendingYaw * rotateK;
		double dPitch = pendingPitch * rotateK;
		// 夹的是“累加器本身”而不是“本帧输出”：只夹输出的话累加器仍会无限增长，
		// 一次极端甩动就会留下一条好几圈的尾巴（松手后一直转）。
		pendingYaw = Mth.clamp(pendingYaw - dYaw, -MAX_PENDING_ROTATE, MAX_PENDING_ROTATE);
		pendingPitch = Mth.clamp(pendingPitch - dPitch, -MAX_PENDING_ROTATE, MAX_PENDING_ROTATE);
		if (Math.abs(pendingYaw) < PENDING_EPSILON) {
			pendingYaw = 0.0;
		}
		if (Math.abs(pendingPitch) < PENDING_EPSILON) {
			pendingPitch = 0.0;
		}
		if (dYaw != 0.0 || dPitch != 0.0) {
			applyOrbitDelta(dYaw, dPitch);
		}

		double panK = dampingFactor(delta, smoothingTau(config.panSmoothing));
		Vec3 dPan = pendingPan.scale(panK);
		pendingPan = clampLength(pendingPan.subtract(dPan), MAX_PENDING_PAN);
		if (pendingPan.lengthSqr() < PENDING_PAN_EPSILON) {
			pendingPan = Vec3.ZERO;
		}
		if (dPan.lengthSqr() > 1.0E-12) {
			pivot = pivot.add(dPan);
		}
	}

	/** 同时推进“输入目标角度”与“渲染角度”，并对俯仰做钳制 */
	private static void applyOrbitDelta(double dYaw, double dPitch) {
		orbitYaw = Mth.wrapDegrees(orbitYaw + (float) dYaw);
		orbitPitch = Mth.clamp(orbitPitch + (float) dPitch, -MAX_PITCH, MAX_PITCH);
		viewYaw = Mth.wrapDegrees(viewYaw + (float) dYaw);
		viewPitch = Mth.clamp(viewPitch + (float) dPitch, -MAX_PITCH, MAX_PITCH);
	}

	/**
	 * 本帧“吃掉”多少待应用的鼠标增量：1 = 关闭平滑（鼠标 1:1 直接生效），越小越滞后。
	 *
	 * <p>与 OrbitControls 的 {@code dampingFactor} 同构，只是用 {@code 1 - exp(-delta / tau)}
	 * 换算成与帧率无关的形式：{@code tau = 0.15}、60fps 时约等于它默认的 0.1。</p>
	 *
	 * @param tau 时间常数（秒）；0 或非法值表示关闭阻尼，鼠标 1:1 直接生效
	 */
	private static double dampingFactor(double delta, double tau) {
		if (!(tau > 0.0)) {
			return 1.0;
		}
		return 1.0 - Math.exp(-delta / tau);
	}

	/** 把配置里的无量纲平滑倍率换算成时间常数（秒）：1 → {@link #SMOOTHING_UNIT} */
	private static double smoothingTau(double multiplier) {
		if (!(multiplier > 0.0)) {
			return 0.0;
		}
		return Mth.clamp(multiplier, 0.0, SMOOTHING_MAX) * SMOOTHING_UNIT;
	}

	/** 把向量长度限制在 max 以内 */
	private static Vec3 clampLength(Vec3 v, double max) {
		double len = v.length();
		return len > max && len > 1.0E-12 ? v.scale(max / len) : v;
	}

	/** 清空所有待处理输入与飞行速度 */
	private static void stopMotion() {
		pendingYaw = 0.0;
		pendingPitch = 0.0;
		pendingPan = Vec3.ZERO;
		flightVel = Vec3.ZERO;
	}

	/** 维护“是否释放鼠标、是否显示指针”，让自由指针与相机控制共存 */
	private static void updateCursor(Minecraft mc) {
		if (mc.mouseHandler == null || mc.getWindow() == null) {
			return;
		}

		if (Platform.hasScreen(mc)) {
			setCursorVisible(mc, true);
			return;
		}

		if (!engaged) {
			// 有 overlay 时把鼠标交给界面自己管理（overlay 关闭后的下一帧会重新抓取）
			if (Platform.hasOverlay(mc)) {
				cursorMode = -1;
				return;
			}
			if (!mc.mouseHandler.isMouseGrabbed()) {
				mc.mouseHandler.grabMouse();
			}
			cursorMode = -1;
			return;
		}

		if (mc.mouseHandler.isMouseGrabbed()) {
			mc.mouseHandler.releaseMouse();
		}
		setCursorVisible(mc, isCursorShown());
	}

	/**
	 * 计算本帧最终相机姿态：
	 * 位置 = 轴心点沿视线反方向退开 {@link #distance}；
	 * 朝向 = 放大镜锚点修正后的视线；再按 {@link #blend} 与原版姿态做插值。
	 */
	public static CameraPose computePose() {
		// 机位必须用“渲染角度”反推，否则开启拖拽平滑后机位会跑到视线前面，相机不再看向轴心点
		Vec3 dir = direction(viewYaw, viewPitch);
		Vec3 target = pivot.subtract(dir.scale(distance));

		float b = blend;
		Vec3 position = vanillaPos.lerp(target, b);
		Vec3 look = OrbitZoom.effectiveLook();
		float outYaw = vanillaYaw + Mth.wrapDegrees(yawOf(look) - vanillaYaw) * b;
		float outPitch = vanillaPitch + (pitchOf(look) - vanillaPitch) * b;
		cameraEyePos = position;
		return new CameraPose(position, outYaw, outPitch);
	}

	/** 供 EntityMixin 覆写玩家眼睛位置；未接管相机时返回 null 表示走原版逻辑 */
	public static Vec3 cameraEye() {
		return engaged && vanillaReady ? cameraEyePos : null;
	}

	/** 供 EntityMixin 覆写玩家视线方向；未接管时返回 null */
	public static Vec3 pointerViewRay() {
		return engaged ? pointerRay() : null;
	}


	/** 身体跟随相机时，玩家头部其实在相机位置，此时应隐藏“卡在墙里”的遮挡贴图 */
	public static boolean shouldHideInWallOverlay() {
		return engaged && isBodyFollowing();
	}

	/** 是否启用“玩家身体跟随相机移动”（仅单人 + 创造模式，避免影响生存存档逻辑） */
	public static boolean isBodyFollowing() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		return OrbitConfig.INSTANCE.bodyFollowsCamera && player != null && player.isCreative()
				&& mc.getSingleplayerServer() != null;
	}

	/** 当前单人世界是否允许作弊（决定能否修改服务端侧交互距离） */
	private static boolean commandsAllowed() {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		return server != null && server.getWorldData().isAllowCommands();
	}

	public static double serverBlockReach(Player player) {
		return serverReach(player, OrbitConfig.INSTANCE.clientBlockReach);
	}

	public static double serverEntityReach(Player player) {
		return serverReach(player, OrbitConfig.INSTANCE.clientEntityReach);
	}

	/**
	 * 方块交互距离的统一出口（供 {@code Player#blockInteractionRange} 使用）。
	 *
	 * <p>原实现只在 {@code Player#isWithinBlockInteractionRange} 里放大距离，那样只有服务端
	 * “校验”知道了新距离：客户端拾取射线、物品的天生视角计算、第三方模组自算的射线，长度都取自
	 * {@code blockInteractionRange()}，仍然是原版值，于是它们在本模组能放到的地方却“够不着”。
	 * 这里把距离来源本身放宽，任何读取方拿到的口径就都一致了。</p>
	 */
	public static double blockRange(Player player) {
		return interactionRange(player, OrbitConfig.INSTANCE.clientBlockReach);
	}

	/** 实体交互距离的统一出口，见 {@link #blockRange} */
	public static double entityRange(Player player) {
		return interactionRange(player, OrbitConfig.INSTANCE.clientEntityReach);
	}

	/**
	 * @return 配置的距离；返回 -1 表示不干预（交给原版距离）
	 */
	private static double interactionRange(Player player, double configured) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return -1.0;
		}
		if ((Object) player == mc.player) {
			// 客户端侧的本地玩家：只决定“能瞄到哪里”，跟随配置即可
			return engaged && configured >= 0.0 ? configured : -1.0;
		}
		// 其余实例（单人世界里与之对应的 ServerPlayer 等）涉及实际判定，仍受作弊开关约束
		return serverReach(player, configured);
	}

	/**
	 * 计算服务端侧生效的交互距离。
	 *
	 * @return 配置的距离；返回 -1 表示不干预（交给原版距离）
	 */
	private static double serverReach(Player player, double configured) {
		if (!OrbitConfig.INSTANCE.singleplayerServerReach || !engaged || configured <= 0.0) {
			return -1.0;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getSingleplayerServer() == null) {
			return -1.0;
		}
		if (!commandsAllowed()) {
			return -1.0;
		}
		// 只对本地玩家生效，避免影响其它玩家（含服务端线程上其它实体的判定）
		return player.getUUID().equals(mc.player.getUUID()) ? configured : -1.0;
	}

	/** 把玩家（含单人服务端上的 ServerPlayer）传送到相机眼睛对应的脚部位置 */
	public static void syncBodyToCamera(Minecraft mc) {
		if (!engaged || !isBodyFollowing()) {
			return;
		}
		LocalPlayer player = mc.player;
		if (player == null || mc.getSingleplayerServer() == null) {
			return;
		}
		Vec3 eye = computePose().position();
		double feetY = eye.y - player.getEyeHeight();
		if (player.distanceToSqr(eye.x, feetY, eye.z) < 1.0E-8) {
			return;
		}
		// 目标位置会把玩家卡进方块里时跳过本次同步，避免退出轨道相机后被困在地形中
		if (wouldCollide(mc, eye.x, feetY, eye.z)) {
			return;
		}
		player.setPos(eye.x, feetY, eye.z);
		player.setDeltaMovement(Vec3.ZERO);

		ServerPlayer serverPlayer = mc.getSingleplayerServer().getPlayerList().getPlayer(player.getUUID());
		if (serverPlayer != null && !serverSyncQueued) {
			// 服务端实体由服务端线程独占维护，绝不能在渲染线程上改它的坐标：
			// setPos 会触发 EntitySection 迁移，与服务端 tick 并发修改同一份 ClassInstanceMultiMap，
			// 表现为偶发的 ArrayIndexOutOfBoundsException: Index -1。
			// 因此这里只排队，真正写入放到服务端线程。
			ServerPlayer target = serverPlayer;
			double tx = eye.x;
			double ty = feetY;
			double tz = eye.z;
			serverSyncQueued = true;
			mc.getSingleplayerServer().execute(() -> {
				serverSyncQueued = false;
				// 任务排队到执行之间可能已经退出存档 / 玩家下线
				if (target.isRemoved() || target.connection == null) {
					return;
				}
				target.setPos(tx, ty, tz);
				target.setDeltaMovement(Vec3.ZERO);
			});
		}
	}

	/** 判断玩家移动至该脚部位置后，其包围盒是否会与地形发生碰撞 */
	private static boolean wouldCollide(Minecraft mc, double x, double y, double z) {
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) {
			return true;
		}
		AABB box = player.getBoundingBox().move(x - player.getX(), y - player.getY(), z - player.getZ());
		return !level.noCollision(player, box);
	}

	/**
	 * 覆写原版准星命中结果：从轨道相机位置沿“指针射线”重新拾取，使准星与鼠标指针一致。
	 * 打开界面/有 overlay 时退化为 MISS，避免误交互。
	 *
	 * <p>由 {@code LocalPlayer#raycastHitResult} 的出口注入调用：在这儿就被替换掉，
	 * {@code Minecraft#pick} 写入 {@code hitResult} 时用的已经是轨道相机的结果，
	 * 因此其它在 {@code pick} 返回点注入的第三方模组不会因为注入先后顺序读到旧值。</p>
	 *
	 * @return 命中结果；返回 null 表示不干预（沿用原版结果）
	 */
	public static HitResult overrideHitResult(Minecraft mc) {
		if (!engaged || mc.player == null || mc.level == null) {
			return null;
		}
		if (!vanillaReady) {
			LocalPlayer fallback = mc.player;
			recordVanillaPose(fallback.getEyePosition(1.0F), fallback.getViewYRot(1.0F), fallback.getViewXRot(1.0F));
		}
		CameraPose pose = computePose();
		HitResult hit = raycast(mc, pose.position(), pointerRay());

		if (Platform.hasScreen(mc) || Platform.hasOverlay(mc)) {
			Vec3 at = hit.getLocation();
			Direction direction = Direction.getApproximateNearest(
					at.x - pose.position().x, at.y - pose.position().y, at.z - pose.position().z);
			return BlockHitResult.miss(at, direction, BlockPos.containing(at));
		}
		return hit;
	}


	private static void engage() {
		if (!initFromLook()) {
			return;
		}
		engaged = true;
		blend = 1.0F;
		// 关闭洞穴剔除，否则相机穿到实体内部时会被原版剔除导致画面异常
		disableCaveCulling();
	}

	private static void disengage() {
		OrbitZoom.cancelZoom();
		engaged = false;
		cameraMode = false;
		stopMotion();
		restoreCaveCulling();
	}

	private static void disableCaveCulling() {
		Minecraft mc = Minecraft.getInstance();
		smartCullBeforeEngage = mc.smartCull;
		mc.smartCull = false;
	}

	private static void restoreCaveCulling() {
		Minecraft.getInstance().smartCull = smartCullBeforeEngage;
	}

	/**
	 * 进入轨道相机时的初始轨道距离。
	 *
	 * <p>开启“身体跟随”时优先用准星指向的方块：从玩家眼睛沿视线做一次方块拾取，
	 * 命中就把这段视线长度当作轨道距离（轴心点正好落在准星指的那个方块上，
	 * 相机随后就在玩家头部所在位置附近）；没指向方块（看向天空或超出范围）时，
	 * 退回与“未开启身体跟随”一致的、配置里的默认轨道距离。</p>
	 *
	 * <p>只做方块拾取、不受交互距离限制，这样看向远处的地形也能把轴心点放到视线终点。</p>
	 */
	private static double initialOrbitDistance(Minecraft mc, Vec3 eye, Vec3 look) {
		double fallback = Mth.clamp(OrbitConfig.INSTANCE.defaultOrbitDistance, MIN_DISTANCE, MAX_DISTANCE);
		// 轨道距离本身就以 MAX_DISTANCE 为上限，射线打更远没有意义
		double hit = blockDistanceAlong(mc, eye, look, MAX_DISTANCE);
		return hit > 0.0 ? Mth.clamp(hit, MIN_DISTANCE, MAX_DISTANCE) : fallback;
	}

	/**
	 * 沿视线做一次“无视交互距离”的方块拾取，返回起点到命中点的距离（格）。
	 *
	 * <p>{@link #raycast} 会按客户端交互距离把超距命中降级成 MISS，而 MISS 的位置又落在
	 * 交互距离的末端而不是真实地形上，所以“看到的东西有多远”这类问题不能问它；
	 * 这里直接用 {@code level.clip}，只看方块，不看流体与实体。</p>
	 *
	 * @param maxDistance 射线长度上限（格）
	 * @return 命中距离；没命中方块时返回 -1
	 */
	static double blockDistanceAlong(Minecraft mc, Vec3 eye, Vec3 look, double maxDistance) {
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) {
			return -1.0;
		}
		Vec3 to = eye.add(look.scale(maxDistance));
		BlockHitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK ? Math.sqrt(hit.getLocation().distanceToSqr(eye)) : -1.0;
	}

	/** 进入轨道相机时，以当前视线方向初始化轴心点与朝向 */
	private static boolean initFromLook() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;

		Vec3 eye = vanillaReady ? vanillaPos : player.getEyePosition(1.0F);
		Vec3 look = vanillaReady ? direction(vanillaYaw, vanillaPitch) : player.getViewVector(1.0F);
		if (look.lengthSqr() < 1.0E-8) {
			return false;
		}

		double start = isBodyFollowing()
				? initialOrbitDistance(mc, eye, look)
				: Mth.clamp(OrbitConfig.INSTANCE.defaultOrbitDistance, MIN_DISTANCE, MAX_DISTANCE);
		pivot = eye.add(look.scale(start));
		distance = start;
		viewYaw = orbitYaw = yawOf(look);
		viewPitch = orbitPitch = Mth.clamp(pitchOf(look), -MAX_PITCH, MAX_PITCH);
		stopMotion();
		return true;
	}

	/** 从相机位置沿视线做一次“方块 + 实体”的联合拾取，并按各自的距离上限过滤 */
	private static HitResult raycast(Minecraft mc, Vec3 from, Vec3 look) {
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;

		OrbitConfig config = OrbitConfig.INSTANCE;
		double blockRange = config.clientBlockReach >= 0.0 ? config.clientBlockReach : player.blockInteractionRange();
		double entityRange = config.clientEntityReach >= 0.0 ? config.clientEntityReach : player.entityInteractionRange();
		double maxDistance = Math.max(blockRange, entityRange);
		double maxDistanceSq = maxDistance * maxDistance;

		Vec3 to = from.add(look.scale(maxDistance));
		HitResult blockHit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		double blockDistanceSq = blockHit.getLocation().distanceToSqr(from);
		if (blockHit.getType() != HitResult.Type.MISS) {
			// 命中方块后，实体只需在更近的范围内搜索
			maxDistanceSq = blockDistanceSq;
			maxDistance = Math.sqrt(maxDistanceSq);
			to = from.add(look.scale(maxDistance));
		}

		AABB searchBox = player.getBoundingBox()
				.expandTowards(look.scale(maxDistance))
				.minmax(new AABB(from, to))
				.inflate(1.0, 1.0, 1.0);
		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(player, from, to, searchBox, EntitySelector.CAN_BE_PICKED, maxDistanceSq);
		return entityHit != null && entityHit.getLocation().distanceToSqr(from) < blockDistanceSq
				? filterHitResult(entityHit, from, entityRange)
				: filterHitResult(blockHit, from, blockRange);
	}

	/** 超出该类型交互距离时，把命中结果降级为 MISS（保留位置与朝向信息） */
	private static HitResult filterHitResult(HitResult hitResult, Vec3 from, double maxRange) {
		Vec3 location = hitResult.getLocation();
		if (!location.closerThan(from, maxRange)) {
			Direction direction = Direction.getApproximateNearest(location.x - from.x, location.y - from.y, location.z - from.z);
			return BlockHitResult.miss(location, direction, BlockPos.containing(location));
		}
		return hitResult;
	}

	/** 是否允许进入轨道相机：世界就绪且没有打开任何界面 */
	private static boolean canOrbit() {
		Minecraft mc = Minecraft.getInstance();
		return isWorldReady() && !Platform.hasScreen(mc) && !Platform.hasOverlay(mc);
	}

	private static boolean isWorldReady() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.level != null && mc.gui != null;
	}

	/** 缓存指针显示状态，避免每帧重复调用底层（GLFW/SDL）接口 */
	private static void setCursorVisible(Minecraft mc, boolean visible) {
		int mode = visible ? 1 : 0;
		if (mode == cursorMode) {
			return;
		}
		Platform.setCursorVisible(mc, visible);
		cursorMode = mode;
	}

	private static boolean isMouseButtonDown(int button) {
		return Platform.isMouseButtonDown(Minecraft.getInstance(), button);
	}

	/** 由 yaw/pitch（角度）构造单位视线向量（与 MC 的约定一致） */
	static Vec3 direction(float yaw, float pitch) {
		float yawRad = yaw * Mth.DEG_TO_RAD;
		float pitchRad = pitch * Mth.DEG_TO_RAD;
		float cosPitch = Mth.cos(pitchRad);
		return new Vec3(-Mth.sin(yawRad) * cosPitch, -Mth.sin(pitchRad), Mth.cos(yawRad) * cosPitch);
	}

	private static float yawOf(Vec3 vec) {
		return (float) (Mth.atan2(-vec.x, vec.z) * Mth.RAD_TO_DEG);
	}

	static float pitchOf(Vec3 vec) {
		double length = vec.length();
		return (float) (-Math.asin(Mth.clamp(vec.y / length, -1.0, 1.0)) * Mth.RAD_TO_DEG);
	}
}
