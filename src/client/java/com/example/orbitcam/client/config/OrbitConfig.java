package com.example.orbitcam.client.config;

import com.example.orbitcam.OrbitCamMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.JsonAdapter;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.ColorControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.awt.Color;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 模组的全部可配置项，以及基于 YACL 的配置界面。
 *
 * <p>配置以 JSON 保存在 {@code config/orbitcam.json}，单例为 {@link #INSTANCE}。</p>
 */
public final class OrbitConfig {

	/** 鼠标工作模式 */
	public enum MouseMode {
		/** 按住修饰键才进入相机态，松手回到准常态 */
		ALT_HOLD,
		/** 按一下修饰键切换相机态 / 准常态 */
		ALT_TOGGLE,
		/** 常驻：旋转/平移键始终生效，不用按修饰键 */
		INDEPENDENT,
		/** 混合：左/右键既能操作相机又能原版交互，靠拖拽阈值区分点击与拖拽 */
		HYBRID
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("orbitcam.json");

	/** 状态文字在界面上的位置上限（像素），与配置界面滑块范围保持一致 */
	private static final double HUD_POSITION_MAX = 1000.0;

	/** 用于读取字段初始值的“默认实例”，必须先于 {@link #INSTANCE} 初始化 */
	private static final OrbitConfig DEFAULTS = new OrbitConfig();
	/** 反射得到的默认值缓存，避免每次打开配置界面都重复反射 */
	private static final Map<String, Object> DEFAULT_CACHE = new HashMap<>();

	public static final OrbitConfig INSTANCE = load();

	/** 旋转灵敏度（相对“屏幕高度 = 360°”的倍率） */
	public double rotateSensitivity = 1.0;
	/**
	 * 旋转的“拖拽阻尼”倍率：鼠标增量不会立刻全部生效，而是分批释放。
	 * 内部时间常数 = 本值 × 0.05 秒，所以 1 = 0.05 秒 ≈ 投影工坊的观感。
	 * 滞后量与松手后的惯性尾滑都由它决定，0 = 关闭。
	 */
	public double rotateSmoothing = 1.0;
	/** 平移的“拖拽阻尼”倍率，含义同 {@link #rotateSmoothing} */
	public double panSmoothing = 1.0;
	/** 平移灵敏度 */
	public double panSensitivity = 1.0;
	/** 进入轨道相机时的默认环绕距离（格） */
	public double defaultOrbitDistance = 8.0;
	/**
	 * 轨道距离的滚轮缩放灵敏度。
	 */
	public double scaleSensitivity = 1.0;
	/** 变焦倍率的滚轮灵敏度（与 {@link #scaleSensitivity} 相互独立） */
	public double zoomSensitivity = 1.0;
	/** 变焦进入/退出的过渡速度倍率 */
	public double zoomTransitionSpeed = 1.0;
	/** 轴心点水平飞行速度（格/秒） */
	public double horizontalSpeed = 20.0;
	/** 轴心点垂直飞行速度（格/秒） */
	public double verticalSpeed = 20.0;
	/** 疾跑时的水平/垂直速度倍率 */
	public double sprintHorizontalMultiplier = 2.0;
	public double sprintVerticalMultiplier = 2.0;
	/** 飞行的加速/减速惯性系数（0 = 瞬时，1 = 最迟钝） */
	public double flightAccel = 0.5;
	public double flightDecel = 0.25;
	/** 客户端侧方块/实体的拾取距离（负数表示沿用原版距离） */
	public double clientBlockReach = 128;
	public double clientEntityReach = 128;
	/** 鼠标工作模式 */
	public MouseMode mouseMode = MouseMode.HYBRID;
	/** 混合模式下判定“拖拽”而非“点击”的位移阈值（像素，0 = 立即判定为拖拽） */
	public double clickDragThreshold = 4.0;
	/** 是否同步修改单人服务端的交互距离（需要开启作弊） */
	public boolean singleplayerServerReach = true;
	/** 玩家身体是否跟随相机移动（需要创造模式 + 单人） */
	public boolean bodyFollowsCamera = true;
	/** 放大期间锚点是否持续跟随世界中的锚点（而非固定屏幕位置） */
	public boolean zoomTrackAnchor = false;
	/** 是否显示轴心点线框 */
	public boolean showPivotOutline = true;
	/** 轴心点线框颜色（ARGB，按 16 进制字符串存取） */
	@JsonAdapter(HexColor.class)
	public int pivotOutlineColor = HexColor.WHITE;
	/** 是否在 HUD 上显示当前模式文案 */
	public boolean showStatusHud = true;
	/** 状态文案在 HUD 上的位置（像素） */
	public double statusHudX = 10.0;
	public double statusHudY = 10.0;

	/** 把当前配置写入磁盘 */
	public static void save() {
		save(INSTANCE);
	}

	/**
	 * 写入指定实例。
	 * 载入配置时需要写回“补齐后的实例”，而那时 {@link #INSTANCE} 还没完成静态初始化，
	 * 所以保存逻辑不能依赖 INSTANCE。
	 */
	private static void save(OrbitConfig config) {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(config), StandardCharsets.UTF_8);
		} catch (IOException e) {
			OrbitCamMod.LOGGER.error("Failed to save OrbitCam config", e);
		}
	}

	/**
	 * 载入配置；文件不存在或解析失败时回落到默认值并立即写一份。
	 *
	 * <p>Gson 通过 Unsafe 分配实例，JSON 里没有写的字段会保持 0/false 而不是 Java 字段的初始值，
	 * 所以这里先把缺失字段用默认值补齐，再统一做一次范围兜底，
	 * 避免旧版本的配置文件把灵敏度、距离之类变成 0 导致功能失效。</p>
	 */
	private static OrbitConfig load() {
		if (Files.exists(FILE)) {
			try {
				String json = Files.readString(FILE, StandardCharsets.UTF_8);
				OrbitConfig parsed = GSON.fromJson(json, OrbitConfig.class);
				if (parsed != null) {
					JsonObject tree = parseTree(json);
					int patched = tree == null ? 0 : restoreMissing(tree, parsed);
					clampToLimits(parsed);
					// 有字段被补齐时回写一次，让配置文件始终完整
					if (patched > 0) {
						save(parsed);
					}
					return parsed;
				}
			} catch (Exception e) {
				OrbitCamMod.LOGGER.error("Failed to load OrbitCam config, using defaults", e);
			}
		}
		OrbitConfig defaults = new OrbitConfig();
		save(defaults);
		return defaults;
	}

	/** 解析配置文件的 JSON 树；解析失败返回 null（此时跳过一切基于键名的处理） */
	@Nullable
	private static JsonObject parseTree(String json) {
		try {
			return JsonParser.parseString(json).getAsJsonObject();
		} catch (Exception e) {
			OrbitCamMod.LOGGER.warn("配置文件不是合法的 JSON 对象，跳过字段补齐", e);
			return null;
		}
	}

	/** 把 JSON 中缺失的字段用 {@link #DEFAULTS} 的对应值补齐；返回补齐的字段数量 */
	private static int restoreMissing(JsonObject tree, OrbitConfig target) {
		int patched = 0;
		for (Field field : OrbitConfig.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) || tree.has(field.getName())) {
				continue;
			}
			try {
				field.setAccessible(true);
				field.set(target, field.get(DEFAULTS));
				patched++;
			} catch (ReflectiveOperationException | RuntimeException e) {
				OrbitCamMod.LOGGER.warn("配置项回填失败: " + field.getName(), e);
			}
		}
		return patched;
	}

	/**
	 * 数值范围的统一兜底：与配置界面里各滑块的范围保持一致。
	 * 越界或非法（NaN）时用默认值替换；枚举为 null 时回落到 HYBRID。
	 */
	private static void clampToLimits(OrbitConfig c) {
		if (c.mouseMode == null) {
			c.mouseMode = DEFAULTS.mouseMode;
		}
		c.rotateSensitivity = limit(c.rotateSensitivity, 0.05, 4.0, DEFAULTS.rotateSensitivity);
		c.rotateSmoothing = limit(c.rotateSmoothing, 0.0, 4.0, DEFAULTS.rotateSmoothing);
		c.panSmoothing = limit(c.panSmoothing, 0.0, 4.0, DEFAULTS.panSmoothing);
		c.panSensitivity = limit(c.panSensitivity, 0.1, 4.0, DEFAULTS.panSensitivity);
		c.scaleSensitivity = limit(c.scaleSensitivity, 0.1, 4.0, DEFAULTS.scaleSensitivity);
		c.zoomSensitivity = limit(c.zoomSensitivity, 0.1, 4.0, DEFAULTS.zoomSensitivity);
		c.defaultOrbitDistance = limit(c.defaultOrbitDistance, 3.0, 128.0, DEFAULTS.defaultOrbitDistance);
		c.zoomTransitionSpeed = limit(c.zoomTransitionSpeed, 0.25, 4.0, DEFAULTS.zoomTransitionSpeed);
		c.horizontalSpeed = limit(c.horizontalSpeed, 2.0, 40.0, DEFAULTS.horizontalSpeed);
		c.verticalSpeed = limit(c.verticalSpeed, 2.0, 40.0, DEFAULTS.verticalSpeed);
		c.sprintHorizontalMultiplier = limit(c.sprintHorizontalMultiplier, 1.0, 8.0, DEFAULTS.sprintHorizontalMultiplier);
		c.sprintVerticalMultiplier = limit(c.sprintVerticalMultiplier, 1.0, 8.0, DEFAULTS.sprintVerticalMultiplier);
		c.flightAccel = limit(c.flightAccel, 0.0, 1.0, DEFAULTS.flightAccel);
		c.flightDecel = limit(c.flightDecel, 0.0, 1.0, DEFAULTS.flightDecel);
		c.clientBlockReach = limit(c.clientBlockReach, -1.0, 256.0, DEFAULTS.clientBlockReach);
		c.clientEntityReach = limit(c.clientEntityReach, -1.0, 256.0, DEFAULTS.clientEntityReach);
		c.clickDragThreshold = limit(c.clickDragThreshold, 0.0, 20.0, DEFAULTS.clickDragThreshold);
		c.statusHudX = limit(c.statusHudX, 0.0, HUD_POSITION_MAX, DEFAULTS.statusHudX);
		c.statusHudY = limit(c.statusHudY, 0.0, HUD_POSITION_MAX, DEFAULTS.statusHudY);
	}

	/** 数值在区间内则原样返回，否则用默认值兜底 */
	private static double limit(double value, double min, double max, double fallback) {
		if (Double.isNaN(value) || Double.isInfinite(value) || value < min || value > max) {
			return fallback;
		}
		return value;
	}

	/** 构建 YACL 配置界面（Mod Menu 入口也走这里） */
	public static Screen createScreen(Screen parent) {
		return YetAnotherConfigLib.createBuilder()
				.title(Component.translatable("orbitcam.title"))
				.category(ConfigCategory.createBuilder()
						.name(Component.translatable("orbitcam.title"))
						.group(group("feel",
								slider("rotateSensitivity", () -> INSTANCE.rotateSensitivity,
										v -> INSTANCE.rotateSensitivity = v, 0.05, 4.0, 0.05),
								slider("rotateSmoothing", () -> INSTANCE.rotateSmoothing,
										v -> INSTANCE.rotateSmoothing = v, 0.0, 4.0, 0.05,
										desc("rotateSmoothing")),
								slider("panSensitivity", () -> INSTANCE.panSensitivity,
										v -> INSTANCE.panSensitivity = v, 0.05, 4.0, 0.05),
								slider("panSmoothing", () -> INSTANCE.panSmoothing,
										v -> INSTANCE.panSmoothing = v, 0.0, 4.0, 0.05,
										desc("panSmoothing")),
								slider("scaleSensitivity", () -> INSTANCE.scaleSensitivity,
										v -> INSTANCE.scaleSensitivity = v, 0.05, 4.0, 0.05,
										desc("scaleSensitivity")),
								slider("defaultOrbitDistance", () -> INSTANCE.defaultOrbitDistance,
										v -> INSTANCE.defaultOrbitDistance = v, 3.0, 128.0, 1.0,
										desc("defaultOrbitDistance"))))
						.group(group("move",
								slider("horizontalSpeed", () -> INSTANCE.horizontalSpeed,
										v -> INSTANCE.horizontalSpeed = v, 2.0, 40.0, 1.0,
										desc("horizontalSpeed")),
								slider("verticalSpeed", () -> INSTANCE.verticalSpeed,
										v -> INSTANCE.verticalSpeed = v, 2.0, 40.0, 1.0,
										desc("verticalSpeed")),
								slider("sprintHorizontalMultiplier", () -> INSTANCE.sprintHorizontalMultiplier,
										v -> INSTANCE.sprintHorizontalMultiplier = v, 1.0, 8.0, 0.1,
										desc("sprintHorizontalMultiplier")),
								slider("sprintVerticalMultiplier", () -> INSTANCE.sprintVerticalMultiplier,
										v -> INSTANCE.sprintVerticalMultiplier = v, 1.0, 8.0, 0.1,
										desc("sprintVerticalMultiplier")),
								slider("flightAccel", () -> INSTANCE.flightAccel,
										v -> INSTANCE.flightAccel = v, 0.0, 1.0, 0.05,
										desc("flightAccel")),
								slider("flightDecel", () -> INSTANCE.flightDecel,
										v -> INSTANCE.flightDecel = v, 0.0, 1.0, 0.05,
										desc("flightDecel"))))
						.group(group("zoom",
								slider("zoomTransitionSpeed", () -> INSTANCE.zoomTransitionSpeed,
										v -> INSTANCE.zoomTransitionSpeed = v, 0.25, 4.0, 0.05,
										desc("zoomTransitionSpeed")),
								slider("zoomSensitivity", () -> INSTANCE.zoomSensitivity,
										v -> INSTANCE.zoomSensitivity = v, 0.05, 4.0, 0.05,
										desc("zoomSensitivity")),
								toggle("zoomTrackAnchor", () -> INSTANCE.zoomTrackAnchor,
										v -> INSTANCE.zoomTrackAnchor = v, defaultBoolean("zoomTrackAnchor"), true,
										desc("zoomTrackAnchor"))))
						.group(group("mode",
								mouseModeOption(),
								slider("clickDragThreshold", () -> INSTANCE.clickDragThreshold,
										v -> INSTANCE.clickDragThreshold = v, 0.0, 20.0, 1,
										desc("clickDragThreshold"))))
						.group(group("render",
								toggle("showPivotOutline", () -> INSTANCE.showPivotOutline,
										v -> INSTANCE.showPivotOutline = v, defaultBoolean("showPivotOutline"), true,
										desc("showPivotOutline")),
								pivotColorOption()))
						.group(group("reach",
								slider("clientBlockReach", () -> INSTANCE.clientBlockReach,
										v -> INSTANCE.clientBlockReach = v, -1.0, 256.0, 1.0,
										desc("clientBlockReach")),
								slider("clientEntityReach", () -> INSTANCE.clientEntityReach,
										v -> INSTANCE.clientEntityReach = v, -1.0, 256.0, 1.0,
										desc("clientEntityReach"))))
						.group(group("singleplayer",
								toggle("singleplayerServerReach", () -> INSTANCE.singleplayerServerReach,
										v -> INSTANCE.singleplayerServerReach = v,
										defaultBoolean("singleplayerServerReach"),
										singleplayerReachAvailable(),
										desc("singleplayerServerReach")),
								toggle("bodyFollowsCamera", () -> INSTANCE.bodyFollowsCamera,
										v -> INSTANCE.bodyFollowsCamera = v,
										defaultBoolean("bodyFollowsCamera"),
										bodyFollowAvailable(),
										desc("bodyFollowsCamera"))))
						.group(group("hud",
								toggle("showStatusHud", () -> INSTANCE.showStatusHud,
										v -> INSTANCE.showStatusHud = v),
								field("statusHudX", () -> INSTANCE.statusHudX,
										v -> INSTANCE.statusHudX = v, 0.0, HUD_POSITION_MAX, desc("statusHudX")),
								field("statusHudY", () -> INSTANCE.statusHudY,
										v -> INSTANCE.statusHudY = v, 0.0, HUD_POSITION_MAX, desc("statusHudY"))))
						.build())
				.save(OrbitConfig::save)
				.build()
				.generateScreen(parent);
	}

	/** 按分组名构造一个配置分组（标题取 {@code orbitcam.group.<name>}） */
	private static OptionGroup group(String name, Option<?>... options) {
		OptionGroup.Builder builder = OptionGroup.createBuilder()
				.name(Component.translatable("orbitcam.group." + name));
		for (Option<?> option : options) {
			builder.option(option);
		}
		return builder.build();
	}

	/** 鼠标工作模式（枚举下拉 + 四种模式的说明） */
	private static Option<MouseMode> mouseModeOption() {
		return Option.<MouseMode>createBuilder()
				.name(Component.translatable("orbitcam.option.mouseMode"))
				.description(OptionDescription.of(
						Component.translatable("orbitcam.option.mouseMode.desc.alt_hold"),
						Component.translatable("orbitcam.option.mouseMode.desc.alt_toggle"),
						Component.translatable("orbitcam.option.mouseMode.desc.independent"),
						Component.translatable("orbitcam.option.mouseMode.desc.hybrid")))
				.binding(defaultTyped("mouseMode", MouseMode.class, MouseMode.HYBRID),
						() -> INSTANCE.mouseMode,
						v -> INSTANCE.mouseMode = v)
				.controller(opt -> EnumControllerBuilder.create(opt)
						.enumClass(MouseMode.class)
						.formatValue(mode -> Component.translatable(
								"orbitcam.option.mouseMode." + mode.name().toLowerCase(Locale.ROOT))))
				.build();
	}

	/** 轴心线框颜色（带 alpha） */
	private static Option<Color> pivotColorOption() {
		return Option.<Color>createBuilder()
				.name(Component.translatable("orbitcam.option.pivotOutlineColor"))
				.description(OptionDescription.of(desc("pivotOutlineColor")))
				.binding(new Color(HexColor.WHITE, true),
						() -> new Color(INSTANCE.pivotOutlineColor, true),
						v -> INSTANCE.pivotOutlineColor = v.getRGB())
				.controller(opt -> ColorControllerBuilder.create(opt).allowAlpha(true))
				.build();
	}

	/** 构造一个带范围与步长的数字滑块选项 */
	private static Option<Double> slider(String key, Supplier<Double> getter, Consumer<Double> setter,
			double min, double max, double step, Component... description) {
		Option.Builder<Double> builder = Option.<Double>createBuilder()
				.name(Component.translatable("orbitcam.option." + key))
				.binding(defaults(key), getter, setter)
				.controller(opt -> DoubleSliderControllerBuilder.create(opt).range(min, max).step(step));
		if (description.length > 0) {
			builder.description(OptionDescription.of(description));
		}
		return builder.build();
	}

	/**
	 * 构造一个数字输入框（适合像素坐标这类需要精确输入的项，滑块不好对齐）。
	 *
	 * @param min 允许的最小值
	 * @param max 允许的最大值（超出范围时输入框会标红并拒绝保存）
	 */
	private static Option<Double> field(String key, Supplier<Double> getter, Consumer<Double> setter,
			double min, double max, Component... description) {
		Option.Builder<Double> builder = Option.<Double>createBuilder()
				.name(Component.translatable("orbitcam.option." + key))
				.binding(defaults(key), getter, setter)
				.controller(opt -> DoubleFieldControllerBuilder.create(opt).range(min, max));
		if (description.length > 0) {
			builder.description(OptionDescription.of(description));
		}
		return builder.build();
	}

	/** 取 {@code orbitcam.option.<key>.desc} 对应的描述文本 */
	private static Component desc(String key) {
		return Component.translatable("orbitcam.option." + key + ".desc");
	}

	private static Option<Boolean> toggle(String key, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		return toggle(key, getter, setter, defaultBoolean(key), true);
	}

	/**
	 * 构造一个开关选项。
	 *
	 * @param available false 时该选项在界面上置灰（例如当前世界不允许作弊）
	 */
	private static Option<Boolean> toggle(String key, Supplier<Boolean> getter, Consumer<Boolean> setter,
			boolean defaultValue, boolean available, Component... description) {
		Option.Builder<Boolean> builder = Option.<Boolean>createBuilder()
				.name(Component.translatable("orbitcam.option." + key))
				.binding(defaultValue, getter, setter)
				.controller(BooleanControllerBuilder::create)
				.available(available);
		if (description.length > 0) {
			builder.description(OptionDescription.of(description));
		}
		return builder.build();
	}

	/** 单人且允许作弊时（或不在世界里时）才允许开启“服务端交互距离” */
	private static boolean singleplayerReachAvailable() {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			return server.getWorldData().isAllowCommands();
		}
		// 没有集成服务端但已经建立连接 => 身处远程服务器（含 Realms/直连），无法同步服务端距离
		return mc.getConnection() == null;
	}

	/** 单人世界且创造模式下（或不在世界里时）才允许开启“身体跟随相机” */
	private static boolean bodyFollowAvailable() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			// 不在世界里：保持可用，避免在主菜单被置灰
			return true;
		}
		return player.isCreative() && mc.getSingleplayerServer() != null;
	}

	/** 反射读取某个配置字段的默认值（结果会被缓存） */
	private static Object defaultOf(String key) {
		if (DEFAULT_CACHE.containsKey(key)) {
			return DEFAULT_CACHE.get(key);
		}
		Object value = null;
		try {
			Field field = OrbitConfig.class.getDeclaredField(key);
			field.setAccessible(true);
			value = field.get(DEFAULTS);
		} catch (ReflectiveOperationException | RuntimeException e) {
			OrbitCamMod.LOGGER.warn("配置项没有默认值: " + key, e);
		}
		DEFAULT_CACHE.put(key, value);
		return value;
	}

	private static double defaults(String key) {
		return defaultOf(key) instanceof Number number ? number.doubleValue() : 1.0;
	}

	private static boolean defaultBoolean(String key) {
		return Boolean.TRUE.equals(defaultOf(key));
	}

	private static <T> T defaultTyped(String key, Class<T> type, T fallback) {
		Object value = defaultOf(key);
		return type.isInstance(value) ? type.cast(value) : fallback;
	}
}
