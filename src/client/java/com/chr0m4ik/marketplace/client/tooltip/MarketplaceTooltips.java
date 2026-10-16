package com.chr0m4ik.marketplace.client.tooltip;

import com.chr0m4ik.marketplace.client.ClientMarketplaceStatus;
import com.chr0m4ik.marketplace.client.screen.MarketplaceScreen;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.economy.TraderService;
import com.chr0m4ik.marketplace.economy.TraderPrices;
import com.chr0m4ik.marketplace.market.MarketService;
import com.chr0m4ik.marketplace.menu.MarketplaceMenu;
import com.chr0m4ik.marketplace.menu.WindowKind;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Дописывает в подсказки предметов их цену.
 *
 * <p>Правила:
 * <ul>
 *     <li>везде, кроме окна торговца, цена обычная (базовая). Динамика цен — особенность только
 *     торговца, на рынок и покупку она не влияет;</li>
 *     <li>в окне торговца показывается его цена: 75% от базовой с его надбавками и уценками
 *     (см. {@link TraderPrices});</li>
 *     <li>у кнопок цены нет, а у кнопки «итоговая цена» подсказка показывает сводку по товару.</li>
 * </ul>
 */
public final class MarketplaceTooltips {
	private MarketplaceTooltips() {
		// Класс только со статическими методами.
	}

	public static void register() {
		ItemTooltipCallback.EVENT.register(MarketplaceTooltips::addPrice);
	}

	private static void addPrice(ItemStack stack, Item.TooltipContext context, TooltipFlag flag, List<Component> lines) {
		if (stack.isEmpty()) {
			return;
		}

		Screen screen = Minecraft.getInstance().gui.screen();

		if (screen instanceof MarketplaceScreen marketplace) {
			MarketplaceMenu menu = marketplace.getMenu();

			if (marketplace.isHoveringButton()) {
				// Предмет по центру окна покупки лежит в слоте-кнопке, поэтому цену показываем здесь.
				if (menu.getKind() == WindowKind.BUY_ITEM) {
					addBuyLines(lines, marketplace, menu, stack);
					return;
				}

				// У кнопок своя подсказка: у бумаги — сводка, у остальных её просто нет.
				if (menu.getKind() == WindowKind.TRADER && stack.is(Items.PAPER)) {
					addTraderSummary(lines, menu);
				} else if (menu.getKind() == WindowKind.SELL && stack.is(Items.PAPER)) {
					addMarketSummary(lines, menu);
				} else if (menu.getKind() == WindowKind.BUY_SLOT && stack.is(Items.PAPER)) {
					lines.add(Component.translatable("marketplace.tooltip.slot.price",
							money(menu.getNextSlotPrice())).withStyle(ChatFormatting.GOLD));
				}

				return;
			}

			if (menu.getKind() == WindowKind.SELL) {
				addMarketCellLines(lines, marketplace, menu, stack);
				return;
			}

			if (menu.getKind() == WindowKind.BUY_ITEM && addBuyLines(lines, marketplace, menu, stack)) {
				return;
			}

			if (menu.getKind() == WindowKind.TRADER) {
				addTraderPriceLines(lines, stack);
				return;
			}
		}

		addBasePriceLines(lines, stack);
	}

	/** Подсказка для ячейки рынка: обычная цена, наценка и время до продажи. */
	private static void addMarketCellLines(List<Component> lines, MarketplaceScreen screen,
			MarketplaceMenu menu, ItemStack stack) {
		int cell = screen.getHoveredSlotIndex();

		if (!menu.isCellUnlocked(cell)) {
			lines.add(Component.translatable("marketplace.gui.slot.locked").withStyle(ChatFormatting.RED));
			return;
		}

		int minutes = menu.getCellMinutes(cell);

		if (minutes >= 0) {
			lines.add(Component.translatable("marketplace.tooltip.market.time", time(minutes))
					.withStyle(ChatFormatting.AQUA));
		} else if (!stack.isEmpty()) {
			lines.add(Component.translatable("marketplace.tooltip.market.staged").withStyle(ChatFormatting.GRAY));
		}

		addBasePriceLines(lines, stack);

		// Если предмет выставлен с наценкой — показываем и обычную цену, и итоговую.
		double markup = menu.getCellMarkup(cell);

		if (markup > 1.001) {
			lines.add(Component.translatable("marketplace.tooltip.market.markedUp", markup(markup),
					money(ItemPrices.priceOf(stack) * markup)).withStyle(ChatFormatting.GOLD));
		}

		lines.add(Component.translatable("marketplace.tooltip.market.take").withStyle(ChatFormatting.DARK_GRAY));
	}

	/** Обычная цена: так платят рынок и покупка. */
	private static void addBasePriceLines(List<Component> lines, ItemStack stack) {
		double unit = ItemPrices.priceOf(stack.copyWithCount(1));

		if (unit <= 0.0) {
			return;
		}

		lines.add(Component.translatable("marketplace.tooltip.price.unit", money(unit))
				.withStyle(ChatFormatting.GREEN));

		if (stack.getCount() > 1) {
			lines.add(Component.translatable("marketplace.tooltip.price.stack", money(ItemPrices.priceOf(stack)))
					.withStyle(ChatFormatting.GOLD));
		}
	}

	/** Цена торговца: его надбавки, уценки и доля 75%. */
	private static void addTraderPriceLines(List<Component> lines, ItemStack stack) {
		TraderPrices trader = ClientMarketplaceStatus.traderPrices();
		double unit = trader.payout(stack.copyWithCount(1));

		if (unit <= 0.0) {
			return;
		}

		lines.add(Component.translatable("marketplace.tooltip.price.unit", money(unit))
				.withStyle(ChatFormatting.GREEN));

		if (stack.getCount() > 1) {
			lines.add(Component.translatable("marketplace.tooltip.price.stack", money(trader.payout(stack)))
					.withStyle(ChatFormatting.GOLD));
		}

		// Объясняем, почему торговец даёт не как обычно.
		double sold = trader.soldStacks(stack.getItem());

		if (sold > 0.01) {
			lines.add(Component.translatable("marketplace.tooltip.trader.sold", stacks(sold))
					.withStyle(ChatFormatting.RED));
		}

		double bonus = trader.bonus(stack.getItem());

		if (bonus > 0.0001) {
			lines.add(Component.translatable("marketplace.tooltip.trader.bonus", percent(bonus))
					.withStyle(ChatFormatting.AQUA));
		}
	}

	/** Подсказка кнопки «итоговая цена»: сколько товара, обычная цена и сколько дадут на руки. */
	private static void addTraderSummary(List<Component> lines, MarketplaceMenu menu) {
		TraderService.Offer offer = TraderService.evaluate(ClientMarketplaceStatus.traderPrices(),
				menu.getTradeContainer(), menu.tradeSlotCount());

		lines.add(Component.translatable("marketplace.tooltip.trader.count",
				Component.literal(String.valueOf(offer.itemCount()))).withStyle(ChatFormatting.GRAY));
		lines.add(Component.translatable("marketplace.tooltip.trader.full", money(offer.normalValue()))
				.withStyle(ChatFormatting.GOLD));
		lines.add(Component.translatable("marketplace.tooltip.trader.discounted",
				money(MoneyFormat.round(offer.payout()))).withStyle(ChatFormatting.GREEN));
	}

	/**
	 * Подсказка в окне покупки: цена за один стак и за всё выбранное количество.
	 *
	 * @return {@code false}, если курсор не на предмете по центру — тогда цена показывается
	 *         как обычно (это случай предметов в инвентаре игрока)
	 */
	private static boolean addBuyLines(List<Component> lines, MarketplaceScreen screen,
			MarketplaceMenu menu, ItemStack stack) {
		if (screen.getHoveredSlotIndex() != MarketplaceMenu.BUY_ITEM_PREVIEW || stack.isEmpty()) {
			return false;
		}

		int maxStack = Math.max(1, stack.getItem().getDefaultMaxStackSize());
		int index = Math.max(0, Math.min(menu.getShopQuantityIndex(), MarketService.QUANTITIES.length - 1));
		int count = MarketService.QUANTITIES[index];

		lines.add(Component.translatable("marketplace.tooltip.buy.unit",
				money(MoneyFormat.round(ItemPrices.priceOf(stack) * maxStack * ItemPrices.BUY_MULTIPLIER)))
				.withStyle(ChatFormatting.GOLD));
		lines.add(Component.translatable("marketplace.tooltip.buy.total",
				money(MoneyFormat.round(ItemPrices.priceOf(stack) * count * ItemPrices.BUY_MULTIPLIER)))
				.withStyle(ChatFormatting.GREEN));
		return true;
	}

	/** Подсказка кнопки-справки на рынке: сколько дадут за ордера и за неподтверждённые предметы. */
	private static void addMarketSummary(List<Component> lines, MarketplaceMenu menu) {
		// ВРЕМЕННАЯ диагностика: печатаем то, что видит клиент (сравниваем с логом сервера).
		com.chr0m4ik.marketplace.MarketplaceMod.LOGGER.info(
				"Рынок (клиент): ордера {} шт -> {} (текст {}), неподтверждённые {} шт -> {} (текст {}), наценка {}",
				menu.getOrdersCount(), menu.getOrdersPayout(), MoneyFormat.format(menu.getOrdersPayout()),
				menu.getStagedCount(), menu.getStagedPayout(), MoneyFormat.format(menu.getStagedPayout()),
				menu.getMarkup());
		lines.add(Component.translatable("marketplace.tooltip.market.orders",
				money(menu.getOrdersPayout()), count(menu.getOrdersCount())).withStyle(ChatFormatting.GOLD));
		lines.add(Component.translatable("marketplace.tooltip.market.stagedMoney",
				money(menu.getStagedPayout()), count(menu.getStagedCount())).withStyle(ChatFormatting.GREEN));
		lines.add(Component.translatable("marketplace.tooltip.market.markup", markup(menu.getMarkup()))
				.withStyle(ChatFormatting.YELLOW));
		lines.add(Component.translatable("marketplace.tooltip.market.take").withStyle(ChatFormatting.GRAY));
	}

	private static Component money(double amount) {
		return Component.literal(MoneyFormat.format(amount));
	}

	private static Component count(int value) {
		return Component.literal(String.valueOf(value));
	}

	private static Component markup(double value) {
		return Component.literal(value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(value));
	}

	/** Время до конца продажи: минуты или часы с минутами. */
	private static Component time(int minutes) {
		if (minutes < 60) {
			return Component.translatable("marketplace.time.minutes", count(minutes));
		}

		return Component.translatable("marketplace.time.hours", count(minutes / 60), count(minutes % 60));
	}

	private static Component stacks(double amount) {
		return Component.literal(String.format("%.1f", amount));
	}

	private static Component percent(double fraction) {
		// Знак «%» в строке формата удваиваем — иначе он считается началом подстановки.
		return Component.literal(String.format("+%.1f%%", fraction * 100.0));
	}
}
