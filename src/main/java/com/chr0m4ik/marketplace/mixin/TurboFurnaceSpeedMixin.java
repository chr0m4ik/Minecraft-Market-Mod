package com.chr0m4ik.marketplace.mixin;

import com.chr0m4ik.marketplace.turbo.TurboFurnaceBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Скорость плавки «Турбо печки».
 *
 * <p>В 26.3 время плавки у печи уже умножается: {@code AbstractFurnaceBlockEntity.getTotalCookTime}
 * делит время рецепта на поле {@code speedMultiplier}, а само поле берётся из компонента топлива
 * ({@code getSpeedMultiplier}). Переопределить это подклассом нельзя: {@code serverTick} —
 * {@code private static}, а поле {@code speedMultiplier} — {@code private}, поэтому класс-наследник
 * до них не достаёт. Зато сам {@code getSpeedMultiplier} — {@code protected} и вызывается
 * виртуально, так что достаточно подставить свой множитель в самом начале метода.
 *
 * <p>Для ванильных печи, коптильни и доменной печи {@code instanceof} не сработает, и метод
 * отработает как обычно — поведение других блоков не меняется.
 *
 * <p>Миксин серверный (в одиночной игре он встаёт и на встроенный сервер) и поэтому лежит в
 * <b>общем</b> конфиге {@code marketplace.common.mixins.json}: в клиентском конфиге на выделенном
 * сервере он бы не загрузился, и печка молча плавила бы с обычной скоростью.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class TurboFurnaceSpeedMixin {
	/** Подменяет множитель скорости, который ваниль берёт из топлива. */
	@Inject(method = "getSpeedMultiplier", at = @At("HEAD"), cancellable = true)
	private void marketplace$turboSpeed(ServerLevel level, ItemStack fuel, CallbackInfoReturnable<Float> cir) {
		if ((Object) this instanceof TurboFurnaceBlockEntity turbo) {
			cir.setReturnValue(turbo.speedMultiplier());
		}
	}
}
