package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.economy.MoneyFormat;
import net.minecraft.world.item.ItemStack;

/**
 * Лот на рынке: что продаём, за сколько, когда его купит виртуальный рынок и кто продавец.
 *
 * <p>Лот живёт в сейве мира, поэтому продажа идёт и когда игрок вышел.
 *
 * <p><b>Цена — главное поле.</b> Раньше выплата считалась из наценки ×1…×2, теперь игрок может
 * задать любую цену командой {@code /ah sell} (решение заказчика). Цена фиксируется при
 * выставлении: ни микро-дрейф, ни крупные ивенты её потом не меняют — покупатель видит ровно то,
 * что заплатит.
 *
 * <p><b>Кто покупает.</b> Если цена не выше потолка виртуального рынка
 * ({@code max(market_markups)} от базовой), лот в конце концов купит рынок — как и раньше.
 * Если дороже, {@code auto = false}: такой лот стоит и ждёт живого покупателя (категория
 * «Торговая площадка»), иначе можно было бы выставить грязь за миллиард и получить деньги
 * из воздуха.
 *
 * @param stack   что продаётся (целый стак — или его часть, если так указал продавец);
 * @param price   сколько платят за лот целиком (фиксируется при выставлении);
 * @param markup  цена / базовая цена — только для показа («×1,35»);
 * @param readyAt игровое время, когда виртуальный рынок купит лот; {@link Long#MAX_VALUE} — никогда;
 * @param auto    покупает ли лот виртуальный рынок (иначе ждёт игрока);
 * @param seller  ник продавца — показывается покупателю в «Торговой площадке».
 */
public record MarketOrder(ItemStack stack, double price, double markup, long readyAt, boolean auto, String seller) {
	/** Сколько дадут за лот: продавец получает ровно свою цену, комиссии нет. */
	public double payout() {
		return MoneyFormat.round(this.price);
	}

	/** Готов ли лот к продаже виртуальному рынку. */
	public boolean isReady(long now) {
		return this.auto && now >= this.readyAt;
	}

	/** Лот ждёт живого покупателя (виртуальный рынок его не купит). */
	public boolean waitingForBuyer() {
		return !this.auto;
	}

	/** Сколько тиков осталось до покупки рынком (0 — уже готов или ждёт игрока). */
	public long ticksLeft(long now) {
		return this.auto ? Math.max(0L, this.readyAt - now) : 0L;
	}

	public boolean isEmpty() {
		return this.stack.isEmpty();
	}
}
