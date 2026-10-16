package com.chr0m4ik.marketplace.client.mixin;

import com.chr0m4ik.marketplace.turbo.TurboUpgrade;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Подпись стоимости в окне наковальни для «Турбо печки».
 *
 * <p>Ваниль показывает красное «Слишком дорого!» при цене от 40 уровней — и сама гасит результат.
 * Наше соединение печек (25/50/100 уровней) в ванильную ветку не попадает: результат остаётся,
 * а подпись была бы «Слишком дорого!», хотя операцию взять можно. Поэтому для результата из
 * «Турбо печки» порог 40 в отрисовке отключаем — тогда видно настоящую цену («Стоимость: 100»).
 *
 * <p>Постоянная {@code 40} в {@code AnvilScreen.extractLabels} ровно одна, а для всех остальных
 * предметов метод возвращает её как есть — обычная наковальня не меняется.
 *
 * <p>Миксин клиентский: он правит только подпись и лежит в клиентском конфиге миксинов.
 */
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin {
	/** Отключает порог «слишком дорого» для результата из «Турбо печки». */
	@ModifyConstant(method = "extractLabels", constant = @Constant(intValue = 40))
	private int marketplace$turboCostLabel(int original) {
		AbstractContainerMenu menu = ((AbstractContainerScreen<?>) (Object) this).getMenu();

		if (menu instanceof AnvilMenu anvil
				&& TurboUpgrade.isTurbo(anvil.getSlot(AnvilMenu.RESULT_SLOT).getItem())) {
			return Integer.MAX_VALUE;
		}

		return original;
	}
}
