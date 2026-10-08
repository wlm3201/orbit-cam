package com.example.orbitcam.client.mixin;

import com.example.orbitcam.client.OrbitCam;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 服务端侧的交互距离判定。
 *
 * <p>客户端侧可以改准星射线，但服务端仍会用玩家自己的位置去校验距离，
 * 因此这里把“本地玩家”的方块/实体/攻击距离一并放宽（仅单人且开启作弊时生效）。</p>
 *
 * <p>方块与实体距离不再在每个判定方法里分别改，而是直接放宽距离来源本身：
 * {@code blockInteractionRange()} / {@code entityInteractionRange()} 既是服务端校验的基准，
 * 也是客户端拾取射线长度、第三方模组自算射线的基准，统一在这里放大才能口径一致。</p>
 */
@Mixin(Player.class)
public class PlayerMixin {

	/** 方块交互距离：返回配置值，未启用时保持原版距离 */
	@Inject(method = "blockInteractionRange", at = @At("RETURN"), cancellable = true)
	private void orbitcam$blockRange(CallbackInfoReturnable<Double> cir) {
		double reach = OrbitCam.blockRange((Player) (Object) this);
		if (reach > 0.0) {
			cir.setReturnValue(reach);
		}
	}

	/** 实体交互距离：同上 */
	@Inject(method = "entityInteractionRange", at = @At("RETURN"), cancellable = true)
	private void orbitcam$entityRange(CallbackInfoReturnable<Double> cir) {
		double reach = OrbitCam.entityRange((Player) (Object) this);
		if (reach > 0.0) {
			cir.setReturnValue(reach);
		}
	}

	/**
	 * 攻击距离：保留原版 {@link AttackRange} 的全部语义（最小距离、包围盒余量、生物系数、
	 * 武器攻击距离组件等），只把“最大距离”替换成本模组的配置值。
	 *
	 * <p>原实现是在 {@code isWithinAttackRange} 的 HEAD 处把整个方法替换成
	 * “眼睛到包围盒的距离 < 配置值”，那样会丢掉上述语义；
	 * 这里改为 Redirect 距离的来源，其余判定仍走原版。</p>
	 */
	@Redirect(method = "isWithinAttackRange",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getAttackRangeWith("
					+ "Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/component/AttackRange;"))
	private AttackRange orbitcam$serverAttackRange(Player self, ItemStack weaponItem) {
		AttackRange vanilla = self.getAttackRangeWith(weaponItem);
		double reach = OrbitCam.serverEntityReach(self);
		if (reach <= 0.0) {
			return vanilla;
		}
		float expanded = (float) reach;
		return new AttackRange(vanilla.minReach(), expanded, vanilla.minCreativeReach(), expanded,
				vanilla.hitboxMargin(), vanilla.mobFactor());
	}
}
