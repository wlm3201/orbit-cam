package com.example.orbitcam;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 模组公共（common）入口：仅初始化日志器，真正的功能都在客户端侧 */
public class OrbitCamMod implements ModInitializer {
	public static final String MOD_ID = "orbitcam";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("OrbitCam loaded");
	}
}
