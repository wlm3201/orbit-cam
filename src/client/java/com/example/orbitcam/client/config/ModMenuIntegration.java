package com.example.orbitcam.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;

/** 让 Mod Menu 的“配置”按钮直接打开 YACL 配置界面 */
public class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<Screen> getModConfigScreenFactory() {
		return OrbitConfig::createScreen;
	}
}
