package com.chr0m4ik.marketplace.mixin;

import com.chr0m4ik.marketplace.turbo.TurboUpgrade;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Наковальня: соединение двух «Турбо печек» одного уровня в печку уровнем выше
 * (1+1 → 2, 2+2 → 3, 3+3 → 4).
 *
 * <p>События наковальни в Fabric API нет (в {@code fabric-item-api-v1} есть только миксин,
 * который правит {@code Enchantment.canEnchant}), поэтому единственный путь — свой миксин на
 * {@code AnvilMenu.createResult}.
 *
 * <p>Почему считаем <b>после</b> ванили и только если результат пуст: ваниль две одинаковые
 * блоки-предмета не соединяет (её ветка «одинаковые предметы» требует, чтобы предмет был
 * повреждаемым, и иначе гасит результат). Мы не мешаем ванильным сценариям — переименованию,
 * починке, зачарованиям, — а добавляем свой рецепт ровно там, где ваниль ничего не предложила.
 *
 * <p>Цена ставится в ванильное поле {@code cost}: игра сама не даст забрать результат, если
 * уровней меньше ({@code AnvilMenu.mayPickup}), и сама спишет их при взятии
 * ({@code AnvilMenu.onTake}). Отдельный миксин на {@code onTake} поэтому не нужен: ванильный
 * код берёт {@code cost}, очищает первый вход и уменьшает второй.
 *
 * <p>Миксин общий (не клиентский): {@code AnvilMenu} живёт и на сервере, и на клиенте, а на
 * выделенном сервере клиентский конфиг миксинов не загружается вовсе.
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
	/** Цена операции в уровнях опыта — то самое поле, которое читают {@code mayPickup} и {@code onTake}. */
	@Shadow
	@Final
	private DataSlot cost;

	/** Сколько штук из второго входа спишется при взятии результата. */
	@Shadow
	private int repairItemCountCost;

	/** Наш рецепт: две печки одного уровня дают печку уровнем выше. */
	@Inject(method = "createResult", at = @At("RETURN"))
	private void marketplace$turboCombine(CallbackInfo ci) {
		AnvilMenu menu = (AnvilMenu) (Object) this;

		// Ваниль уже что-то предложила (переименование, починка, зачарование) — не мешаем.
		if (!menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().isEmpty()) {
			return;
		}

		ItemStack left = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
		ItemStack right = menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem();
		int level = TurboUpgrade.resultLevel(left, right);

		if (level == 0) {
			return;
		}

		menu.getSlot(AnvilMenu.RESULT_SLOT).set(TurboUpgrade.upgraded(left, level));
		// Из стака во втором входе расходуется ровно одна печка, а не весь стак: без этого
		// ванильный onTake очистил бы слот целиком.
		this.repairItemCountCost = 1;
		// Столько же уровней потребует mayPickup и столько же спишет onTake.
		this.cost.set(TurboUpgrade.levelCost(level));
		menu.broadcastChanges();
	}
}
