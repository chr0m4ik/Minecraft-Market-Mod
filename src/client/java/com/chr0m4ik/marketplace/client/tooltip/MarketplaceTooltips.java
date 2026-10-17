package com.chr0m4ik.marketplace.client.tooltip;

import com.chr0m4ik.marketplace.client.ClientMarketplaceStatus;
import com.chr0m4ik.marketplace.client.screen.MarketplaceScreen;
import com.chr0m4ik.marketplace.economy.EconomyConfig;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.economy.TraderService;
import com.chr0m4ik.marketplace.economy.TraderPrices;
import com.chr0m4ik.marketplace.event.EventService;
import com.chr0m4ik.marketplace.event.MarketEvent;
import com.chr0m4ik.marketplace.event.MarketEvents;
import com.chr0m4ik.marketplace.market.CustomKits;
import com.chr0m4ik.marketplace.market.KitCatalog;
import com.chr0m4ik.marketplace.market.MarketService;
import com.chr0m4ik.marketplace.market.VirtualEnderChest;
import com.chr0m4ik.marketplace.market.MarketService;
import com.chr0m4ik.marketplace.market.SpecialCatalog;
import com.chr0m4ik.marketplace.menu.MarketplaceMenu;
import com.chr0m4ik.marketplace.menu.WindowKind;
import com.chr0m4ik.marketplace.price.ItemPrices;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.OptionalDouble;

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

		if (!MarketService.listingId(stack).isEmpty()) {
			return;
		}

		Screen screen = Minecraft.getInstance().gui.screen();

		if (screen instanceof MarketplaceScreen marketplace) {
			MarketplaceMenu menu = marketplace.getMenu();

			if (marketplace.isHoveringButton()) {

				if (menu.getKind() == WindowKind.BUY_ITEM) {
					addBuyLines(lines, marketplace, menu, stack);
					return;
				}

				if (menu.getKind() == WindowKind.TRADER && stack.is(Items.PAPER)) {
					addTraderSummary(lines, menu);
				} else if (menu.getKind() == WindowKind.SELL && stack.is(Items.PAPER)) {
					addMarketSummary(lines, menu);
				} else if (menu.getKind() == WindowKind.BUY_SLOT && stack.is(Items.PAPER)) {
					lines.add(Component.translatable("marketplace.tooltip.slot.price",
							money(menu.getNextSlotPrice())).withStyle(ChatFormatting.GOLD));
				} else if (menu.getKind() == WindowKind.ENDER_CHEST && stack.is(Items.PAPER)) {

					lines.add(Component.translatable("marketplace.tooltip.ender.desc")
							.withStyle(ChatFormatting.GRAY));
					lines.add(Component.translatable("marketplace.tooltip.ender.price",
							money(VirtualEnderChest.PRICE)).withStyle(ChatFormatting.GOLD));
					lines.add(Component.translatable("marketplace.tooltip.ender.hint")
							.withStyle(ChatFormatting.DARK_GRAY));
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

		if (!CustomKits.nameOf(stack).isEmpty()) {
			return;
		}

		if (addKitPrice(lines, stack)) {
			return;
		}

		// по обычной платят рынок и торговец.
		addSpecialPrice(lines, stack);
		addBasePriceLines(lines, stack);
	}

	private static boolean addKitPrice(List<Component> lines, ItemStack stack) {
		if (KitCatalog.kitId(stack).isEmpty()) {
			return false;
		}

		ClientLevel level = Minecraft.getInstance().level;

		if (level == null) {
			return false;
		}

		KitCatalog.setRegistriesIfMissing(level.registryAccess());

		return KitCatalog.kitOf(stack).map(kit -> {
			lines.add(Component.translatable("marketplace.tooltip.buy.kit",
					money(MoneyFormat.round(KitCatalog.priceOf(kit)))).withStyle(ChatFormatting.GOLD));
			return true;
		}).orElse(false);
	}

	private static boolean addSpecialPrice(List<Component> lines, ItemStack stack) {

		if (SpecialCatalog.isLevelledBlock(stack)) {
			return false;
		}

		OptionalDouble special = SpecialCatalog.priceOf(stack);

		if (special.isEmpty()) {
			return false;
		}

		lines.add(Component.translatable("marketplace.tooltip.buy.special",
				money(MoneyFormat.round(special.getAsDouble()))).withStyle(ChatFormatting.GOLD));
		return true;
	}

	private static void addMarketCellLines(List<Component> lines, MarketplaceScreen screen,
			MarketplaceMenu menu, ItemStack stack) {
		int cell = screen.getHoveredSlotIndex();

		if (!menu.isCellUnlocked(cell)) {
			lines.add(Component.translatable("marketplace.gui.slot.locked").withStyle(ChatFormatting.RED));
			return;
		}

		if (menu.isCellOrder(cell)) {

			lines.add(Component.translatable("marketplace.tooltip.market.order")
					.withStyle(ChatFormatting.AQUA));
		} else if (!stack.isEmpty()) {
			lines.add(Component.translatable("marketplace.tooltip.market.staged").withStyle(ChatFormatting.GRAY));
		}

		addBasePriceLines(lines, stack);

		boolean waiting = menu.isCellOrder(cell) && menu.isCellWaiting(cell);

		if (waiting) {
			lines.add(Component.translatable("marketplace.tooltip.market.waiting")
					.withStyle(ChatFormatting.GOLD));
		}

		double markup = menu.getCellMarkup(cell);

		if (!waiting && markup > 1.001) {
			lines.add(Component.translatable("marketplace.tooltip.market.markedUp", markup(markup),
					money(ItemPrices.priceOf(stack) * markup)).withStyle(ChatFormatting.GOLD));
		}

		if (menu.isCellOrder(cell) && !waiting) {

			double fixed = menu.getCellPayout(cell);

			if (Math.abs(fixed - ItemPrices.priceOf(stack) * markup) > 0.005) {
				lines.add(Component.translatable("marketplace.tooltip.market.fixed", money(fixed))
						.withStyle(ChatFormatting.GOLD));
			}
		}

		if (menu.isCellOrder(cell)) {

			lines.add(Component.translatable(menu.isTargetMode()
					? "marketplace.tooltip.market.cancel.on" : "marketplace.tooltip.market.cancel.off")
					.withStyle(menu.isTargetMode() ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY));
		} else if (!stack.isEmpty()) {
			lines.add(Component.translatable("marketplace.tooltip.market.take").withStyle(ChatFormatting.DARK_GRAY));
		}
	}

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

		addEventLines(lines, stack);
	}

	private static void addEventLines(List<Component> lines, ItemStack stack) {
		MarketEvent event = EventService.current().orElse(null);

		if (event == null) {
			return;
		}

		double multiplier = EventService.multiplier(stack.getItem());

		if (multiplier == 1.0) {
			return;
		}

		lines.add(Component.translatable("marketplace.tooltip.event", event.name().title(),
				Component.literal(MarketEvents.percentText(multiplier)))
				.withStyle(ChatFormatting.LIGHT_PURPLE));
	}

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

		addEventLines(lines, stack);
	}

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

	private static boolean addBuyLines(List<Component> lines, MarketplaceScreen screen,
			MarketplaceMenu menu, ItemStack stack) {
		if (screen.getHoveredSlotIndex() != MarketplaceMenu.BUY_ITEM_PREVIEW || stack.isEmpty()) {
			return false;
		}

		if (addKitPrice(lines, stack)) {
			return true;
		}

		int maxStack = Math.max(1, stack.getItem().getDefaultMaxStackSize());
		int index = Math.max(0, Math.min(menu.getShopQuantityIndex(), MarketService.QUANTITIES.length - 1));
		int count = MarketService.QUANTITIES[index];

		OptionalDouble special = SpecialCatalog.priceOf(stack);

		if (special.isPresent()) {
			lines.add(Component.translatable("marketplace.tooltip.buy.special",
					money(MoneyFormat.round(special.getAsDouble() * maxStack))).withStyle(ChatFormatting.GOLD));
			lines.add(Component.translatable("marketplace.tooltip.buy.total",
					money(MoneyFormat.round(special.getAsDouble() * count))).withStyle(ChatFormatting.GREEN));
			return true;
		}

		lines.add(Component.translatable("marketplace.tooltip.buy.unit",
				money(MoneyFormat.round(ItemPrices.priceOf(stack) * maxStack * EconomyConfig.buyMultiplier())))
				.withStyle(ChatFormatting.GOLD));
		lines.add(Component.translatable("marketplace.tooltip.buy.total",
				money(MoneyFormat.round(ItemPrices.priceOf(stack) * count * EconomyConfig.buyMultiplier())))
				.withStyle(ChatFormatting.GREEN));
		return true;
	}

	private static void addMarketSummary(List<Component> lines, MarketplaceMenu menu) {
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

	private static Component stacks(double amount) {
		return Component.literal(String.format("%.1f", amount));
	}

	private static Component percent(double fraction) {

		return Component.literal(String.format("+%.1f%%", fraction * 100.0));
	}
}
