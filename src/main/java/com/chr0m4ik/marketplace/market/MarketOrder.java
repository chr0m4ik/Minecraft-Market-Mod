package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.minecraft.world.item.ItemStack;

/**
 * Один ордер на рынке: что продаём, с какой наценкой, когда продажа закончится и сколько за него
 * заплатят.
 *
 * <p>Ордер живёт в сейве мира, поэтому продажа идёт и когда игрок вышел из игры.
 *
 * <p><b>Цена выплаты фиксируется в момент выставления</b> (решение заказчика): {@link #fixedPayout}
 * считается один раз в {@link MarketService#placeOrder} и дальше не меняется. Иначе микро-дрейф
 * и крупные ивенты меняли бы выплату уже висящего ордера — игрок выставлял бы товар за одну сумму,
 * а получал другую. Старые сейвы, где этого поля ещё нет, читаются по-старому: выплата считается
 * от текущей цены (см. {@link #payout()} и {@link #LEGACY_PAYOUT}).
 *
 * @param stack       что продаётся (целый стак);
 * @param markup      наценка: x1…x2;
 * @param readyAt     игровое время (в тиках), когда продажа завершится;
 * @param fixedPayout сколько заплатят за ордер (зафиксировано при выставлении);
 *                    {@link #LEGACY_PAYOUT} — в сейве поля не было, считаем по-старому.
 */
public record MarketOrder(ItemStack stack, double markup, long readyAt, double fixedPayout) {
	/** Признак старого сейва: зафиксированной выплаты в ордере нет. */
	public static final double LEGACY_PAYOUT = -1.0;

	/** Ордер из старого сейва: выплата ещё не зафиксирована, считается как раньше. */
	public MarketOrder(ItemStack stack, double markup, long readyAt) {
		this(stack, markup, readyAt, LEGACY_PAYOUT);
	}

	/** Сколько дадут за ордер: зафиксированная при выставлении сумма (или старая формула для старых сейвов). */
	public double payout() {
		if (this.fixedPayout >= 0.0) {
			return MoneyFormat.round(this.fixedPayout);
		}

		return MoneyFormat.round(ItemPrices.priceOf(this.stack) * this.markup);
	}

	/** Зафиксирована ли выплата (в старых сейвах — нет). */
	public boolean hasFixedPayout() {
		return this.fixedPayout >= 0.0;
	}

	/** Сколько тиков осталось ждать. */
	public long ticksLeft(long now) {
		return Math.max(0L, this.readyAt - now);
	}

	/** Готов ли ордер к продаже. */
	public boolean isReady(long now) {
		return now >= this.readyAt;
	}

	public boolean isEmpty() {
		return this.stack.isEmpty();
	}
}
