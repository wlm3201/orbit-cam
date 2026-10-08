package com.example.orbitcam.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取 {@link KeyMapping} 当前绑定的键。
 * 需要它才能判断某个按键映射是绑在鼠标还是键盘上、以及具体是哪一个键。
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

	@Accessor("key")
	InputConstants.Key orbitcam$getKey();
}
