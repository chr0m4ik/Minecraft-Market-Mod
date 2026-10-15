package com.chr0m4ik.marketplace.client.tooltip;

import com.chr0m4ik.marketplace.client.ClientMarketplaceStatus;
import com.chr0m4ik.marketplace.client.screen.MarketplaceScreen;
import com.chr0m4ik.marketplace.economy.MarketPrices;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.economy.TraderService;
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
 * Дописывает в подсказки предметов цену с учётом состояния рынка
 * (см. {@link MarketPrices}: что продают — дешевеет, остальное дорожает).
 *
 * <p>Правила:
 * <ul>
 *     <li>обычная цена — везде вне окна торговца;</li>
 *     <li>в окне торговца — цена с уценкой ({@link ItemPrices#TRADER_MULTIPLIER});</li>
 *     <li>у кнопок цены нет, а у кнопки «итоговая цена» подсказка показывает сводку по товару.</li>
 * </ul>
 * Данные рынка клиент получает от сервера, базовые цены читает из файла мода.
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
		MarketPrices market = ClientMarketplaceStatus.market();

		if (screen instanceof MarketplaceScreen marketplace) {
			MarketplaceMenu menu = marketplace.getMenu();

			if (marketplace.isHoveringButton()) {
				// Кнопка «итоговая цена» — показываем сводку по выставленному товару.
				if (menu.getKind() == WindowKind.TRADER && stack.is(Items.PAPER)) {
					addTraderSummary(lines, menu, market);
				}

				return;
			}

			// В окне торговца цены показываем с уценкой.
			double multiplier = menu.getKind() == WindowKind.TRADER ? ItemPrices.TRADER_MULTIPLIER : 1.0;
			addPriceLines(lines, stack, market, multiplier, menu.getKind() == WindowKind.TRADER);

			return;
		}

		addPriceLines(lines, stack, market, 1.0, false);
	}

	private static void addPriceLines(List<Component> lines, ItemStack stack, MarketPrices market,
			double multiplier, boolean withMarketInfo) {
		double unit = market.saleProceeds(stack.copyWithCount(1), multiplier);

		if (unit <= 0.0) {
			return;
		}

		lines.add(Component.translatable("marketplace.tooltip.price.unit", money(unit))
				.withStyle(ChatFormatting.GREEN));

		if (stack.getCount() > 1) {
			lines.add(Component.translatable("marketplace.tooltip.price.stack",
					money(market.saleProceeds(stack, multiplier))).withStyle(ChatFormatting.GOLD));
		}

		// Объясняем, почему цена необычная: этот предмет много продавали или рынок в целом активен.
		double sold = market.soldStacks(stack.getItem());

		if (sold > 0.01) {
			lines.add(Component.translatable("marketplace.tooltip.market.sold", stacks(sold))
					.withStyle(ChatFormatting.RED));
		}

		if (withMarketInfo) {
			double bonus = market.bonus(stack.getItem());

			if (bonus > 0.0001) {
				lines.add(Component.translatable("marketplace.tooltip.market.bonus", percent(bonus))
						.withStyle(ChatFormatting.AQUA));
			}
		}
	}

	/** Подсказка кнопки «итоговая цена»: сколько товара, обычная цена и цена с уценкой. */
	private static void addTraderSummary(List<Component> lines, MarketplaceMenu menu, MarketPrices market) {
		TraderService.Offer offer = TraderService.evaluate(market, menu.getTradeContainer(), menu.tradeSlotCount());

		lines.add(Component.translatable("marketplace.tooltip.trader.count",
				Component.literal(String.valueOf(offer.itemCount()))).withStyle(ChatFormatting.GRAY));
		lines.add(Component.translatable("marketplace.tooltip.trader.full", money(offer.marketValue()))
				.withStyle(ChatFormatting.GOLD));
		lines.add(Component.translatable("marketplace.tooltip.trader.discounted",
				money(MoneyFormat.round(offer.proceeds()))).withStyle(ChatFormatting.GREEN));
	}

	private static Component money(double amount) {
		return Component.literal(MoneyFormat.format(amount));
	}

	private static Component stacks(double amount) {
		return Component.literal(String.format("%.1f", amount));
	}

	private static Component percent(double fraction) {
		// Знак «%» в строке формата удваиваем — иначе он считается началом подстановки.
		return Component.literal(String.format("+%.1f%%", fraction * 100.0));
	}
}
