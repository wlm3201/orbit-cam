package com.example.orbitcam.client.mixin;

import com.example.orbitcam.client.OrbitCamKeys;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 {@code Options.load()} 之前把本模组的按键注册进去，
 * 这样读档时能一并加载玩家改过的按键，也不会被存档覆盖掉。
 */
@Mixin(Options.class)
public class OptionsMixin {

	@Inject(method = "load", at = @At("HEAD"))
	private void orbitcam$registerBeforeLoad(CallbackInfo ci) {
		OrbitCamKeys.register((Options) (Object) this);
	}
}
