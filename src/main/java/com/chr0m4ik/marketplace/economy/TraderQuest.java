package com.chr0m4ik.marketplace.economy;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Заказ торговца: «принеси столько-то таких предметов — заплачу больше обычного».
 *
 * <p>Заказ хранится в сейве мира строками: id предмета, количество, надбавка к цене, признак
 * «выполнен» и сколько уже сдано. Сдавать можно частями: {@link #delivered} растёт с каждой
 * сдачей, а деньги начисляются только когда сдано всё (сдача — {@link TraderQuests#deliver}).
 * Заказы выдаются на игровой день, поэтому выполненные до конца дня остаются на месте с пометкой
 * «выполнено», а на следующий день приходят новые; вместе с ними сбрасывается и прогресс —
 * недосданные предметы при этом пропадают (так решил заказчик: это риск игрока).
 *
 * @param itemId    id предмета, который просят принести (например, {@code minecraft:diamond});
 * @param count     сколько штук нужно;
 * @param bonus     во сколько раз больше базовой цены заплатит торговец (1.10 … 1.90);
 * @param done      заказ уже выполнен в текущем игровом дне;
 * @param delivered сколько предметов уже сдано (0 … {@code count}).
 */
public record TraderQuest(String itemId, int count, double bonus, boolean done, int delivered) {
	/** Новый заказ без сдачи. */
	public TraderQuest(String itemId, int count, double bonus) {
		this(itemId, count, bonus, false, 0);
	}

	/** Этот же заказ, но выполненный: сдано всё. */
	public TraderQuest markDone() {
		return new TraderQuest(this.itemId, this.count, this.bonus, true, this.count);
	}

	/** Этот же заказ с новым прогрессом сдачи. */
	public TraderQuest withDelivered(int value) {
		int clamped = Math.max(0, Math.min(this.count, value));
		return new TraderQuest(this.itemId, this.count, this.bonus, this.done, clamped);
	}

	/** Сколько ещё нужно принести. */
	public int remaining() {
		return Math.max(0, this.count - this.delivered);
	}

	/** Заказ закрыт: сдано всё (или уже помечен выполненным). */
	public boolean complete() {
		return this.done || this.delivered >= this.count;
	}

	/**
	 * Предмет заказа. Пусто — если в сейве лежит id, которого больше нет в игре
	 * (например, мод с предметом удалили): такой заказ просто не показывается.
	 */
	public Optional<Item> item() {
		try {
			return BuiltInRegistries.ITEM.getOptional(Identifier.parse(this.itemId));
		} catch (RuntimeException exception) {
			// Битую запись сейва не прячем: пишем в лог, что именно не так.
			MarketplaceMod.LOGGER.warn("В заказе торговца некорректный id предмета, запись пропущена: {}",
					this.itemId);
			return Optional.empty();
		}
	}

	/** Предмет-иконка заказа: то, что просят принести (количество — не больше стака). */
	public ItemStack icon() {
		return item()
				.map(value -> new ItemStack(value, Math.max(1,
						Math.min(this.count, Math.max(1, value.getDefaultMaxStackSize())))))
				.orElse(ItemStack.EMPTY);
	}

	/** Заказ можно показать и выполнить (id найден, количество и надбавка разумные). */
	public boolean isValid() {
		return this.count > 0 && this.bonus > 0.0 && item().isPresent();
	}
}
