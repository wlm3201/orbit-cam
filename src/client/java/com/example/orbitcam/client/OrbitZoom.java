package com.example.orbitcam.client;

import com.example.orbitcam.client.compat.Platform;
import com.example.orbitcam.client.config.OrbitConfig;
import com.example.orbitcam.client.mixin.KeyMappingAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 放大镜（Zoom）模块。
 *
 * <p>按住放大镜键时缩小 FOV；为了让“缩小前鼠标指针指着的那个点”在缩小后仍停在指针下方，
 * 这里额外解算一个“锚点旋转”四元数叠加到相机朝向之上：
 * 把 {@link OrbitCam#viewYaw}/{@link OrbitCam#viewPitch} 与锚点所需朝向之间的差，
 * 用一个受速度上限约束的插值（slerp）平滑地应用到 {@link #effectiveLook()}。</p>
 */
public final class OrbitZoom {

	/** 缩放过渡 blend 的基础时间常数（再除以配置里的过渡速度倍率） */
	private static final double ZOOM_TAU = 0.10;
	/** 锚点解算允许的最大角度误差（度），超过则认为该帧解算不可靠 */
	private static final double ANCHOR_TOLERANCE = 5.0;
	/** 锚点旋转跟踪的最大角速度（度/秒），避免快速甩动时画面跳变 */
	private static final double ANCHOR_MAX_SPEED = 600.0;
	/** 打开“锚点跟随”时，锚点方向平滑跟随的时间常数 */
	private static final double ZOOM_TRACK_TAU = 0.12;
	/** 接近天顶/天底时锚点修正的淡出安全角（度），避免极点处解算抖动 */
	private static final double ANCHOR_POLE_SAFE = 3.0;
	/**
	 * 锚点解算结果里 pitch 的硬上限（度）：给 {@link #ANCHOR_POLE_SAFE} 留出安全角，
	 * 保证镜头不会真的被转进天顶/天底（那里 {@code WORLD_UP × forward} 退化、yaw 也极度敏感）。
	 */
	private static final double ANCHOR_MAX_PITCH = 90.0 - ANCHOR_POLE_SAFE;
	/**
	 * 变焦倍率的滚轮系数：每格倍率乘 {@code exp(-rate)}。
	 * 与 {@link OrbitCam#SCALE_RATE}（轨道距离）是两件不同的事，各自独立取值。
	 */
	private static final double ZOOM_RATE = 0.0513;

	/** 放大倍率的取值范围（越小放得越大） */
	private static final double ZOOM_FACTOR_MIN = 0.05;
	private static final double ZOOM_FACTOR_MAX = 0.95;
	/**
	 * 自动倍率的“等效观察距离”（格）。
	 *
	 * <p>按下放大镜后，让锚点（指针指向的方块）在屏幕上看起来，
	 * 就像站在 {@code ZOOM_REFERENCE_DISTANCE} 格外用原版 FOV 观察它一样大；
	 * 于是看得越远自动放得越大，近处则几乎不放大。</p>
	 */
	private static final double ZOOM_REFERENCE_DISTANCE = 8.0;

	/** 放大镜键是否按住 */
	private static boolean zoomHeld;
	/** 是否处于“松键后回到缩放前状态”的还原过程中 */
	private static boolean zoomRestoring;
	/** 本帧玩家是否被原版鼠标输入转过视角（用于打断退出过渡） */
	private static boolean playerTurned;

	public static void notePlayerTurn() {
		playerTurned = true;
	}

	/** 取出并清除“玩家是否转过视角”的标记（一次性消费） */
	public static boolean takePlayerTurn() {
		boolean was = playerTurned;
		playerTurned = false;
		return was;
	}
	/** 缩放进度：0 = 未缩放，1 = 完全放大 */
	private static double zoomBlend;
	/**
	 * 放大倍率（FOV 缩放系数，越小看得越远）。
	 *
	 * <p>每次按下放大镜时由锚点距离经 {@link #autoZoomFactor(double)} 自动推算，
	 * 按住期间可用滚轮临时微调，但不再写入配置文件。</p>
	 */
	private static double zoomFactor = 0.25;
	/** 当前生效的锚点旋转（相对于基准朝向） */
	private static final Quaternionf zoomAnchorRot = new Quaternionf();
	/** 锚点旋转是否参与相机朝向计算 */
	private static boolean zoomAnchorRotActive;
	/** 是否已保存“缩放前”的相机状态快照 */
	private static boolean zoomHasSnapshot;
	/** 快照时的锚点世界坐标（开启锚点跟随时持续指向它） */
	private static Vec3 zoomAnchorPoint = Vec3.ZERO;
	/** 快照时指针射线的方向 */
	private static Vec3 zoomAnchorDir = new Vec3(0.0, 0.0, 1.0);
	/** 开启锚点跟随后的平滑跟随方向 */
	private static Vec3 zoomTrackDir = new Vec3(0.0, 0.0, 1.0);
	/** 快照时指针在屏幕上的 NDC 位置 */
	private static double zoomAnchorNdcX;
	private static double zoomAnchorNdcY;
	/** 快照时的相机朝向，作为锚点旋转的基准 */
	private static float zoomBaseYaw;
	private static float zoomBasePitch;
	/** 快照：缩放前的轴心点、距离与朝向，用于还原 */
	private static Vec3 zoomSnapPivot = Vec3.ZERO;
	private static double zoomSnapDistance;
	private static float zoomSnapViewYaw;
	private static float zoomSnapViewPitch;
	private static float zoomSnapOrbitYaw;
	private static float zoomSnapOrbitPitch;

	/** 当前应作用于 FOV 的缩放系数（1 = 不变） */
	public static double zoomFovScale() {
		return zoomBlend <= 0.0 ? 1.0 : Math.pow(zoomFactor, zoomBlend);
	}

	/**
	 * 由“锚点到相机的距离”自动推算放大倍率。
	 *
	 * <p>透视投影下物体在屏幕上的大小与 {@code 1/(d·tan(fov/2))} 成正比，
	 * 令缩放前后 {@code d·tan(fov/2)} 相等，即可解出目标 FOV：
	 * {@code tan(fov'/2) = tan(fov/2) · refDist / d}，再换算成 MC 的线性 FOV 倍率。</p>
	 *
	 * <p>结果仍会被限制在 {@link #ZOOM_FACTOR_MIN} ~ {@link #ZOOM_FACTOR_MAX}，
	 * 因此极近（几乎不放大）与极远（放大到上限）都不会失控。</p>
	 */
	private static double autoZoomFactor(double distance) {
		double baseHalf = Math.toRadians(Math.max(vanillaFovDeg, 1.0F)) / 2.0;
		double zoomedHalf = Math.atan(Math.tan(baseHalf) * (ZOOM_REFERENCE_DISTANCE / Math.max(distance, 1.0E-3)));
		return Mth.clamp(zoomedHalf / baseHalf, ZOOM_FACTOR_MIN, ZOOM_FACTOR_MAX);
	}

	/**
	 * 取叠加锚点修正后的实际视线方向（相机朝向的真正来源）。
	 *
	 * <p>只有当“转完之后”镜头会贴到天顶/天底时，才在最后 {@link #ANCHOR_POLE_SAFE} 度里淡出修正：
	 * 极点附近 {@code WORLD_UP × forward} 退化成零向量、yaw 也极度敏感，解算会抖。</p>
	 *
	 * <p>判据必须是<b>旋转后的 pitch</b>，而不能是旋转量的大小：
	 * 一段纯水平的 yaw 修正转多少度都不会靠近极点，按旋转量衰减会把它误伤成“转动不足”。</p>
	 */
	static Vec3 effectiveLook() {
		Vec3 forward = OrbitCam.direction(OrbitCam.viewYaw, OrbitCam.viewPitch);
		if (!zoomAnchorRotActive) {
			return forward;
		}
		Quaternionf src = new Quaternionf(zoomAnchorRot);
		// 统一到“短弧”半球，保证 slerp 走最近路径
		if (src.w < 0.0F) {
			src.set(-src.x, -src.y, -src.z, -src.w);
		}
		double poleMargin = 90.0 - Math.abs(OrbitCam.pitchOf(rotate(src, forward)));
		double fade = Mth.clamp(poleMargin / ANCHOR_POLE_SAFE, 0.0, 1.0);
		if (fade <= 0.0) {
			return forward;
		}
		Quaternionf probe = new Quaternionf().identity().slerp(src, (float) fade);
		return rotate(probe, forward);
	}

	/**
	 * 用四元数旋转一个向量（Vec3 ↔ Vector3f 转换）。
	 * 复用临时对象，避免每帧产生短命对象。
	 */
	private static Vec3 rotate(Quaternionf rotation, Vec3 vec) {
		Vector3f in = SCRATCH_VEC_IN.set((float) vec.x, (float) vec.y, (float) vec.z);
		Vector3f out = rotation.transform(in, SCRATCH_VEC_OUT);
		return new Vec3(out.x, out.y, out.z);
	}

	/** rotate() 使用的临时向量（仅在客户端渲染线程调用，无需考虑线程安全） */
	private static final Vector3f SCRATCH_VEC_IN = new Vector3f();
	private static final Vector3f SCRATCH_VEC_OUT = new Vector3f();

	/**
	 * 锚点修正是否参与相机朝向计算。
	 * 外部（如相机姿态缓存）可据此判断 {@link #effectiveLook()} 是否只由 viewYaw/viewPitch 决定。
	 */
	static boolean isAnchorActive() {
		return zoomAnchorRotActive;
	}

	/** 由 yaw/pitch 构造“相机朝向四元数”（YXZ 顺序，与 MC 的朝向约定对齐） */
	private static Quaternionf orientationQuat(float yaw, float pitch) {
		return new Quaternionf().rotationYXZ((float) Math.PI - yaw * Mth.DEG_TO_RAD,
				-pitch * Mth.DEG_TO_RAD, 0.0F);
	}



	/** 记录原版计算出的 FOV（放大镜/速度等效果之后）作为缩放基准 */
	private static float vanillaFovDeg = 70.0F;

	public static void noteVanillaFov(float fov) {
		if (fov > 0.0F) {
			vanillaFovDeg = fov;
		}
	}

	/**
	 * 估算“若 blend 推进到某个值，锚点旋转需要多少度”，
	 * 用于在 {@link #stepZoom(double)} 中限制每帧的旋转角速度。
	 */
	private static double requiredAnchorAngle(double blend) {
		Minecraft mc = Minecraft.getInstance();
		Window window = mc.getWindow();
		if (window == null) {
			return 0.0;
		}
		double aspect = (double) Math.max(window.getScreenWidth(), 1) / Math.max(window.getScreenHeight(), 1);
		double fov = vanillaFovDeg * (blend <= 0.0 ? 1.0 : Math.pow(zoomFactor, blend));
		Vec3 dir = OrbitConfig.INSTANCE.zoomTrackAnchor ? zoomTrackDir : zoomAnchorDir;
		double[] solved = solveAnchor(dir.normalize(), zoomAnchorNdcX, zoomAnchorNdcY, fov, aspect);
		if (solved == null) {
			return 0.0;
		}
		Quaternionf rot = anchorRotation(solved[0], solved[1]);
		return 2.0 * Math.toDegrees(Math.acos(Mth.clamp(Math.abs(rot.w), -1.0, 1.0)));
	}

	/**
	 * 由解算出的 yaw/pitch 构造“相对基准朝向”的锚点旋转。
	 *
	 * <p>只夹 pitch：把它限制在 {@link #ANCHOR_MAX_PITCH} 以内，防止镜头被转进极点；
	 * yaw 不夹——绕竖直轴转多少度都不会靠近极点，夹它只会造成“该转的没转过去”。</p>
	 */
	private static Quaternionf anchorRotation(double yaw, double pitch) {
		float limited = (float) Mth.clamp(pitch, -ANCHOR_MAX_PITCH, ANCHOR_MAX_PITCH);
		return new Quaternionf(orientationQuat((float) yaw, limited))
				.mul(orientationQuat(zoomBaseYaw, zoomBasePitch).invert());
	}

	/** 当前实际渲染使用的 FOV（度） */
	private static double renderedFovDeg() {
		return vanillaFovDeg * zoomFovScale();
	}

	/**
	 * 捕获放大镜按键（在 KeyboardHandler 中注入），使其不被原版按键逻辑消费。
	 *
	 * @return true 表示已接管该按键，应取消原版处理
	 */
	public static boolean consumeZoomKey(int action, KeyEvent event) {
		if (!OrbitCam.engaged || OrbitCamKeys.ZOOM == null) {
			return false;
		}
		InputConstants.Key pressed = InputConstants.getKey(event);
		if (!pressed.equals(((KeyMappingAccessor) OrbitCamKeys.ZOOM).orbitcam$getKey())) {
			return false;
		}
		KeyMapping.set(pressed, action != 0);
		return true;
	}

	public static boolean isRestoring() {
		return zoomRestoring;
	}

	/**
	 * 滚轮事件：放大镜按下时用来调整倍率，并把倍率写回配置（便于持久化）。
	 *
	 * @return true 表示事件已被消费
	 */
	public static boolean onScroll(double yOffset) {
		if (zoomHeld) {
			// 只在本次按住期间生效：下次按下会重新按锚点距离推算，不写回配置
			zoomFactor = Mth.clamp(zoomFactor * Math.exp(-yOffset * ZOOM_RATE
							* OrbitConfig.INSTANCE.zoomSensitivity),
					ZOOM_FACTOR_MIN, ZOOM_FACTOR_MAX);
			updateZoomAnchor(1.0 / 60.0);
			return true;
		}
		return zoomRestoring;
	}

	/** 放大镜键状态变化：按下则开始缩放，松开则进入还原流程 */
	public static void updateZoomKey(boolean down) {
		if (down == zoomHeld) {
			return;
		}
		if (down) {
			startZoom();
		} else {
			zoomHeld = false;
			zoomRestoring = true;
		}
	}

	/** 开始放大：保存相机状态快照与锚点信息 */
	private static void startZoom() {
		Minecraft mc = Minecraft.getInstance();
		if (!OrbitCam.engaged || zoomRestoring || zoomBlend > zoomBlendEpsilon()
				|| Platform.hasScreen(mc) || Platform.hasOverlay(mc)) {
			return;
		}
		zoomSnapPivot = OrbitCam.pivot;
		zoomSnapDistance = OrbitCam.distance;
		zoomSnapViewYaw = OrbitCam.viewYaw;
		zoomSnapViewPitch = OrbitCam.viewPitch;
		zoomSnapOrbitYaw = OrbitCam.orbitYaw;
		zoomSnapOrbitPitch = OrbitCam.orbitPitch;
		zoomBaseYaw = OrbitCam.viewYaw;
		zoomBasePitch = OrbitCam.viewPitch;
		// 锚点方向：优先用指针射线，否则退化为当前视线方向
		Vec3 dir = OrbitCam.pointerRay();
		Vec3 eye = OrbitCam.vanillaReady ? OrbitCam.cameraEyePos : OrbitCam.computePose().position();
		zoomAnchorDir = dir != null && dir.lengthSqr() > 1.0E-8
				? dir.normalize()
				: OrbitCam.direction(OrbitCam.viewYaw, OrbitCam.viewPitch);
		// 命中点在交互距离内（方块或实体）时直接用它；够不着时 mc.hitResult 会被降级成 MISS，
		// 此时改用“无视交互距离”的方块拾取——否则远处的目标反而比够得着时放得小。
		boolean aimed = mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS;
		double blockDistance = aimed
				? -1.0
				: OrbitCam.blockDistanceAlong(mc, eye, zoomAnchorDir, OrbitCam.AIM_RAY_MAX_DISTANCE);
		double anchorDistance = aimed
				? mc.hitResult.getLocation().distanceTo(eye)
				: (blockDistance > 0.0 ? blockDistance : OrbitCam.distance);
		// 锚点始终落在指针射线上，与上面取到的距离保持一致
		zoomAnchorPoint = eye.add(zoomAnchorDir.scale(anchorDistance));
		zoomTrackDir = zoomAnchorDir;
		// 倍率按锚点距离自动推算：看得越远放得越大，近处几乎不放大
		zoomFactor = autoZoomFactor(anchorDistance);
		zoomAnchorNdcX = OrbitCam.pointerNdcX;
		zoomAnchorNdcY = OrbitCam.pointerNdcY;
		zoomHasSnapshot = true;
		zoomHeld = true;
		if (zoomBlend <= zoomBlendEpsilon()) {
			zoomAnchorRot.identity();
			zoomAnchorRotActive = false;
		}
	}

	/** 立即取消放大并把相机状态强行还原到快照（退出轨道相机时调用） */
	static void cancelZoom() {
		if (!zoomHasSnapshot) {
			return;
		}
		OrbitCam.pivot = zoomSnapPivot;
		OrbitCam.distance = zoomSnapDistance;
		OrbitCam.viewYaw = zoomSnapViewYaw;
		OrbitCam.viewPitch = zoomSnapViewPitch;
		OrbitCam.orbitYaw = zoomSnapOrbitYaw;
		OrbitCam.orbitPitch = zoomSnapOrbitPitch;
		zoomHeld = false;
		zoomRestoring = false;
		zoomBlend = 0.0;
		zoomAnchorRot.identity();
		zoomAnchorRotActive = false;
		zoomHasSnapshot = false;
	}

	/** blend 的“可视为 0”阈值：随倍率强弱缩放，弱倍率下容忍更大残值 */
	private static double zoomBlendEpsilon() {
		return 3.0E-4 / Math.max(Math.abs(Math.log(
				Mth.clamp(zoomFactor, ZOOM_FACTOR_MIN, ZOOM_FACTOR_MAX))), 1.0);
	}

	/**
	 * 每帧推进放大状态：
	 * 按时间常数推进 blend（并限制锚点旋转角速度），
	 * 松键后把轴心点/距离/朝向平滑插值回快照值，到达后结束还原。
	 */
	static void stepZoom(double delta) {
		double tau = ZOOM_TAU / Mth.clamp(OrbitConfig.INSTANCE.zoomTransitionSpeed, 0.1, 4.0);
		double k = 1.0 - Math.exp(-delta / tau);
		double want = zoomHeld ? 1.0 : 0.0;
		double proposed = zoomBlend + (want - zoomBlend) * k;

		// 用“本帧锚点需要转多少度”来限制 blend 推进速度，保证画面不跳变
		double needNow = requiredAnchorAngle(zoomBlend);
		double needNext = requiredAnchorAngle(proposed);
		double need = Math.abs(needNext - needNow);
		double budget = ANCHOR_MAX_SPEED * Math.max(delta, 1.0 / 240.0);
		if (need > budget && need > 1.0E-9) {
			proposed = zoomBlend + (proposed - zoomBlend) * (budget / need);
		}
		zoomBlend = proposed;

		if (zoomHeld || zoomBlend > 0.0) {
			updateZoomAnchor(delta);
		} else {
			zoomAnchorRot.identity();
			zoomAnchorRotActive = false;
		}

		if (!zoomRestoring) {
			if (zoomBlend < zoomBlendEpsilon() && !zoomHeld) {
				zoomBlend = 0.0;
			}
			return;
		}

		OrbitCam.pivot = OrbitCam.pivot.add(zoomSnapPivot.subtract(OrbitCam.pivot).scale(k));
		OrbitCam.distance += (zoomSnapDistance - OrbitCam.distance) * k;
		OrbitCam.viewYaw += Mth.wrapDegrees(zoomSnapViewYaw - OrbitCam.viewYaw) * (float) k;
		OrbitCam.viewPitch += (zoomSnapViewPitch - OrbitCam.viewPitch) * (float) k;
		OrbitCam.orbitYaw += Mth.wrapDegrees(zoomSnapOrbitYaw - OrbitCam.orbitYaw) * (float) k;
		OrbitCam.orbitPitch += (zoomSnapOrbitPitch - OrbitCam.orbitPitch) * (float) k;

		boolean arrived = OrbitCam.pivot.distanceToSqr(zoomSnapPivot) < 1.0E-6
				&& Math.abs(OrbitCam.distance - zoomSnapDistance) < 1.0E-3
				&& Math.abs(Mth.wrapDegrees(zoomSnapViewYaw - OrbitCam.viewYaw)) < 0.01
				&& Math.abs(zoomSnapViewPitch - OrbitCam.viewPitch) < 0.01;
		if (arrived) {
			finishZoomRestore();
		}
	}

	/** 还原到位：写入精确快照值并结束还原流程 */
	private static void finishZoomRestore() {
		if (!zoomHasSnapshot) {
			return;
		}
		OrbitCam.pivot = zoomSnapPivot;
		OrbitCam.distance = zoomSnapDistance;
		OrbitCam.viewYaw = zoomSnapViewYaw;
		OrbitCam.viewPitch = zoomSnapViewPitch;
		OrbitCam.orbitYaw = zoomSnapOrbitYaw;
		OrbitCam.orbitPitch = zoomSnapOrbitPitch;
		zoomRestoring = false;
		zoomHasSnapshot = false;
	}

	/**
	 * 更新锚点旋转：解算“让锚点仍落在指针下方”的目标四元数，
	 * 并以 {@link #ANCHOR_MAX_SPEED} 为上限朝目标 slerp。
	 */
	static void updateZoomAnchor(double delta) {
		Minecraft mc = Minecraft.getInstance();
		Window window = mc.getWindow();
		Quaternionf targetRot = new Quaternionf();
		if (window != null) {
			Vec3 eye = OrbitCam.computePose().position();
			Vec3 dir;
			if (OrbitConfig.INSTANCE.zoomTrackAnchor) {
				// 锚点跟随：相机移动后仍让锚点保持在指针下
				Vec3 toPoint = zoomAnchorPoint.subtract(eye);
				Vec3 wanted = toPoint.lengthSqr() > 1.0E-8 ? toPoint.normalize() : zoomAnchorDir;
				double smooth = 1.0 - Math.exp(-delta / ZOOM_TRACK_TAU);
				zoomTrackDir = zoomTrackDir.lerp(wanted, smooth).normalize();
				dir = zoomTrackDir;
			} else {
				dir = zoomAnchorDir;
			}
			dir = dir.normalize();
			double aspect = (double) Math.max(window.getScreenWidth(), 1) / Math.max(window.getScreenHeight(), 1);
			double[] solved = solveAnchor(dir, zoomAnchorNdcX, zoomAnchorNdcY, renderedFovDeg(), aspect);
			if (solved != null) {
				targetRot = anchorRotation(solved[0], solved[1]);
			}
		}

		double maxStepRad = Mth.DEG_TO_RAD * ANCHOR_MAX_SPEED * Math.max(delta, 1.0 / 240.0);
		Quaternionf oriented = new Quaternionf(targetRot);
		double rawDot = zoomAnchorRot.dot(oriented);
		// 取短弧方向
		if (rawDot < 0.0) {
			oriented.set(-oriented.x, -oriented.y, -oriented.z, -oriented.w);
			rawDot = -rawDot;
		}
		double dot = Mth.clamp(rawDot, -1.0, 1.0);
		double angle = 2.0 * Math.acos(dot);
		if (angle > 1.0E-6) {
			float t = (float) Mth.clamp(maxStepRad / angle, 0.0, 1.0);
			zoomAnchorRot.slerp(oriented, t);
		} else {
			zoomAnchorRot.set(oriented);
		}
		zoomAnchorRotActive = zoomHeld || zoomBlend > 0.0;
	}

	/**
	 * 解算锚点所需的 yaw/pitch：
	 * 已知“希望世界方向 dir 投影到屏幕 NDC (px, py)”，反解相机朝向。
	 * 方程有两个分支（±acos），逐个用正向射线验证，取误差更小者。
	 *
	 * @return {yaw, pitch}（度）；误差超过 {@link #ANCHOR_TOLERANCE} 时返回 null
	 */
	private static double[] solveAnchor(Vec3 dir, double px, double py, double fov, double aspect) {
		double h = Math.tan(Math.toRadians(fov) / 2.0);
		double w = h * aspect;
		// 先构造“相机空间”中由 NDC 决定的单位向量 (ex, ey, ez)
		double a = 1.0 / Math.sqrt(1.0 + px * px * w * w + py * py * h * h);
		double b = -a * px * w;
		double c = a * py * h;
		double ex = -b;
		double ey = c;
		double ez = -a;

		double r = Math.sqrt(ey * ey + ez * ez);
		double psi = Math.atan2(ez, ey);
		double acos = Math.acos(Mth.clamp(dir.y / r, -1.0, 1.0));

		double bestYaw = 0.0;
		double bestPitch = 0.0;
		double bestErr = Double.MAX_VALUE;
		for (int sign = 0; sign < 2; sign++) {
			double bb = -psi + (sign == 0 ? acos : -acos);
			double u = ex;
			double ww = ey * Math.sin(bb) + ez * Math.cos(bb);
			double denom = u * u + ww * ww;
			if (denom < 1.0E-12) {
				continue;
			}
			double cosA = (u * dir.x + ww * dir.z) / denom;
			double sinA = (ww * dir.x - u * dir.z) / denom;
			double yaw = Math.toDegrees(Math.PI - Math.atan2(sinA, cosA));
			double pitch = Mth.clamp(-Math.toDegrees(bb), -OrbitCam.MAX_PITCH, OrbitCam.MAX_PITCH);
			Vec3 got = rayFromNdc(yaw, pitch, px, py, fov, aspect);
			double err = Math.toDegrees(Math.acos(Mth.clamp(got.dot(dir), -1.0, 1.0)));
			if (err < bestErr) {
				bestErr = err;
				bestYaw = yaw;
				bestPitch = pitch;
			}
		}
		return bestErr > ANCHOR_TOLERANCE ? null : new double[] {bestYaw, bestPitch};
	}

	/** 正向验证：给定朝向与 NDC，算出该 NDC 对应的世界射线方向 */
	private static Vec3 rayFromNdc(double yaw, double pitch, double px, double py, double fov, double aspect) {
		Vec3 forward = OrbitCam.direction((float) yaw, (float) pitch);
		Vec3 left = OrbitCam.WORLD_UP.cross(forward).normalize();
		Vec3 up = forward.cross(left);
		double h = Math.tan(Math.toRadians(fov) / 2.0);
		double w = h * aspect;
		return forward.subtract(left.scale(px * w)).add(up.scale(py * h)).normalize();
	}

}
