package com.chr0m4ik.marketplace.market;

import com.chr0m4ik.marketplace.MarketplaceMod;
import com.chr0m4ik.marketplace.economy.EconomyConfig;
import com.chr0m4ik.marketplace.economy.MarketplaceEconomy;
import com.chr0m4ik.marketplace.economy.MarketplaceState;
import com.chr0m4ik.marketplace.economy.MoneyFormat;
import com.chr0m4ik.marketplace.price.ItemPrices;
import com.chr0m4ik.marketplace.sound.MarketSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MarketService {

	public static final int SLOTS = 45;

	public static final int FREE_SLOTS = 5;

	public static double[] markups() {
		return EconomyConfig.marketMarkups();
	}

	private static final double SLOT_BASE_PRICE = 200.0;

	private static final double SLOT_PRICE_STEP = 1.72;

	private MarketService() {
		// Класс только со статическими методами.
	}

	public static int unlockedSlots(MarketplaceState state, UUID playerId) {
		return Math.min(SLOTS, FREE_SLOTS + state.boughtSlots(playerId));
	}

	public static double slotPrice(MarketplaceState state, UUID playerId) {
		return slotPriceFor(unlockedSlots(state, playerId));
	}

	public static double slotPriceFor(int unlockedSlots) {
		int bought = Math.max(0, unlockedSlots - FREE_SLOTS);
		return MoneyFormat.round(SLOT_BASE_PRICE * Math.pow(SLOT_PRICE_STEP, bought));
	}

	public static boolean buySlot(MinecraftServer server, UUID playerId) {
		MarketplaceState state = MarketplaceEconomy.state(server);

		if (unlockedSlots(state, playerId) >= SLOTS) {
			return false;
		}

		if (!MarketplaceEconomy.trySpend(server, playerId, slotPrice(state, playerId))) {
			return false;
		}

		state.setBoughtSlots(playerId, state.boughtSlots(playerId) + 1);
		return true;
	}

	public static final double MIN_LIST_PRICE = 1.0;
	public static final double MAX_LIST_PRICE = 1_000_000_000_000.0;

	private static final String AH_TAG = "marketplace:ah_lot";

	public static double maxAutoMarkup() {
		double max = 1.0;

		for (double markup : markups()) {
			max = Math.max(max, markup);
		}

		return max;
	}

	public enum ListStatus {

		LISTED,

		NO_SLOT,

		BAD_PRICE,

		BAD_COUNT,

		NO_ITEM,

		FORBIDDEN
	}

	public record ListResult(ListStatus status, int slot, MarketOrder order) {
	}

	public enum BuyStatus {

		BOUGHT,

		MISSING,

		OWN_LOT,

		NO_MONEY
	}

	public record BuyResult(BuyStatus status, MarketOrder order, double paid) {
	}

	public record Listing(UUID sellerId, int slot, MarketOrder order) {
	}

	public static int freeSlot(MarketplaceState state, UUID playerId) {
		Map<Integer, ItemStack> staged = state.staging(playerId);
		int unlocked = unlockedSlots(state, playerId);

		for (int slot = 0; slot < unlocked; slot++) {
			if (state.order(playerId, slot) == null && staged.get(slot) == null) {
				return slot;
			}
		}

		return -1;
	}

	public static ListResult listForPrice(MarketplaceState state, UUID playerId, ItemStack hand, int count,
			double price, long now, RandomSource random, String seller) {
		if (hand.isEmpty()) {
			return new ListResult(ListStatus.NO_ITEM, -1, null);
		}

		if (count < 1 || count > hand.getCount()) {
			return new ListResult(ListStatus.BAD_COUNT, -1, null);
		}

		if (!Double.isFinite(price) || price < MIN_LIST_PRICE || price > MAX_LIST_PRICE) {
			return new ListResult(ListStatus.BAD_PRICE, -1, null);
		}

		if (ItemPrices.isBlacklisted(hand.getItem())) {
			return new ListResult(ListStatus.FORBIDDEN, -1, null);
		}

		int slot = freeSlot(state, playerId);

		if (slot < 0) {
			return new ListResult(ListStatus.NO_SLOT, -1, null);
		}

		MarketOrder order = orderForPrice(hand.copyWithCount(count), price, now, random, seller);
		state.setOrder(playerId, slot, order);
		return new ListResult(ListStatus.LISTED, slot, order);
	}

	public static MarketOrder orderForPrice(ItemStack lot, double price, long now, RandomSource random,
			String seller) {
		double rounded = MoneyFormat.round(price);

		double base = ItemPrices.basePriceOf(lot) * lot.getCount();
		double ratio = base > 0.0 ? rounded / base : Double.POSITIVE_INFINITY;
		boolean auto = base > 0.0 && ratio <= maxAutoMarkup() + 1.0E-9;
		long readyAt = auto ? now + SaleTimes.ticksForPrice(lot, rounded, random) : Long.MAX_VALUE;

		return new MarketOrder(lot, rounded, ratio, readyAt, auto, seller);
	}

	public static ItemStack listingIcon(MarketOrder order, UUID sellerId, int slot) {
		ItemStack icon = order.stack().copy();

		CustomData.update(DataComponents.CUSTOM_DATA, icon,
				tag -> tag.putString(AH_TAG, sellerId + ":" + slot));

		List<Component> lore = new ArrayList<>();
		lore.add(plain(Component.translatable("marketplace.tooltip.auction.price",
				Component.literal(MoneyFormat.format(order.payout()))).withStyle(ChatFormatting.GOLD)));
		lore.add(plain(Component.translatable("marketplace.tooltip.auction.seller",
				Component.literal(order.seller())).withStyle(ChatFormatting.GRAY)));
		lore.add(plain(Component.translatable(order.waitingForBuyer()
						? "marketplace.tooltip.auction.waiting" : "marketplace.tooltip.auction.auto")
				.withStyle(ChatFormatting.DARK_GRAY)));
		icon.set(DataComponents.LORE, new ItemLore(lore));
		return icon;
	}

	public static String listingId(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? "" : data.copyTag().getStringOr(AH_TAG, "");
	}

	public static Optional<Listing> listing(MarketplaceState state, ItemStack icon) {
		String id = listingId(icon);
		int separator = id.lastIndexOf(':');

		if (separator <= 0 || separator == id.length() - 1) {
			return Optional.empty();
		}

		try {
			UUID sellerId = UUID.fromString(id.substring(0, separator));
			int slot = Integer.parseInt(id.substring(separator + 1));
			MarketOrder order = state.order(sellerId, slot);
			return order == null ? Optional.empty() : Optional.of(new Listing(sellerId, slot, order));
		} catch (IllegalArgumentException exception) {

			// MarketplaceMod.LOGGER.warn("У лота торговой площадки некорректная метка: {}", id);
			return Optional.empty();
		}
	}

	public static List<Listing> allListings(MarketplaceState state) {
		List<Listing> result = new ArrayList<>();

		for (UUID sellerId : state.playersWithOrders()) {
			for (Map.Entry<Integer, MarketOrder> entry : state.orders(sellerId).entrySet()) {
				MarketOrder order = entry.getValue();

				if (order != null && !order.isEmpty()) {
					result.add(new Listing(sellerId, entry.getKey(), order));
				}
			}
		}

		result.sort(Comparator.comparingDouble(found -> found.order().payout()));
		return List.copyOf(result);
	}

	public static BuyResult buyListing(MinecraftServer server, ServerPlayer buyer, ItemStack icon, boolean drop) {
		MarketplaceState state = MarketplaceEconomy.state(server);
		Optional<Listing> found = listing(state, icon);

		if (found.isEmpty()) {
			return new BuyResult(BuyStatus.MISSING, null, 0.0);
		}

		Listing listing = found.get();
		MarketOrder order = listing.order();

		if (listing.sellerId().equals(buyer.getUUID())) {
			return new BuyResult(BuyStatus.OWN_LOT, order, 0.0);
		}

		double price = order.payout();

		if (price <= 0.0) {
			return new BuyResult(BuyStatus.MISSING, order, 0.0);
		}

		if (!buyer.isCreative() && !MarketplaceEconomy.trySpend(server, buyer.getUUID(), price)) {
			return new BuyResult(BuyStatus.NO_MONEY, order, 0.0);
		}

		if (state.removeOrder(listing.sellerId(), listing.slot()) == null) {
			MarketplaceEconomy.addBalance(server, buyer.getUUID(), price);
			return new BuyResult(BuyStatus.MISSING, order, 0.0);
		}

		MarketplaceEconomy.addBalance(server, listing.sellerId(), price);
		give(buyer, drop, order.stack().copy());
		notifySeller(server, state, listing.sellerId(), buyer, order, price);

		// MarketplaceMod.LOGGER.info("Торговая площадка: {} купил лот «{}» × {} у {} за {}",
				// buyer.getScoreboardName(), order.stack().getHoverName().getString(), order.stack().getCount(),
				// order.seller(), MoneyFormat.format(price));

		return new BuyResult(BuyStatus.BOUGHT, order, price);
	}

	public static MarketOrder cancelListing(MarketplaceState state, UUID playerId, int slot) {
		return state.removeOrder(playerId, slot);
	}

	private static void notifySeller(MinecraftServer server, MarketplaceState state, UUID sellerId,
			ServerPlayer buyer, MarketOrder order, double price) {
		ServerPlayer seller = server.getPlayerList().getPlayer(sellerId);

		if (seller == null) {
			state.addPendingSale(sellerId, price);
			return;
		}

		seller.sendSystemMessage(Component.translatable("marketplace.gui.market.boughtBy",
				Component.literal(buyer.getScoreboardName()),
				order.stack().getHoverName(),
				Component.literal(MoneyFormat.format(price))).withStyle(ChatFormatting.GREEN));
		MarketSounds.orderSold(seller);
	}

	private static MutableComponent plain(MutableComponent text) {
		return text.withStyle(Style.EMPTY.withItalic(false));
	}

	public static MarketOrder placeOrder(MarketplaceState state, UUID playerId, int slot, ItemStack stack,
			double markup, long now, RandomSource random, String seller) {
		if (stack.isEmpty() || slot < 0 || slot >= SLOTS || slot >= unlockedSlots(state, playerId)) {
			return null;
		}

		if (state.order(playerId, slot) != null) {
			return null;
		}

		double price = payoutFor(stack, markup);

		MarketOrder order = new MarketOrder(stack.copy(), price, markup,
				now + SaleTimes.ticksFor(stack, markup, random), true, seller);
		state.setOrder(playerId, slot, order);
		return order;
	}

	public static List<MarketOrder> completeReady(MinecraftServer server, MarketplaceState state,
			UUID playerId, long now) {
		Map<Integer, MarketOrder> playerOrders = state.orders(playerId);

		if (playerOrders.isEmpty()) {
			return List.of();
		}

		List<MarketOrder> sold = new ArrayList<>();

		for (int slot : List.copyOf(playerOrders.keySet())) {
			MarketOrder order = playerOrders.get(slot);

			if (order == null || !order.isReady(now)) {
				continue;
			}

			state.removeOrder(playerId, slot);
			MarketplaceEconomy.addBalance(server, playerId, order.payout());
			sold.add(order);
		}

		return sold;
	}

	public static double payoutFor(ItemStack stack, double markup) {
		return MoneyFormat.round(ItemPrices.priceOf(stack) * markup);
	}

	public static double nextMarkup(double current) {
		double[] markups = markups();

		for (double markup : markups) {
			if (markup > current + 0.001) {
				return markup;
			}
		}

		return markups.length == 0 ? 1.0 : markups[0];
	}

	// --- Магазин: выбор игрока и покупка ---

	public static final int[] QUANTITIES = {1, 4, 8, 16, 32, 64, 128, 256, 512, 1024};

	public record ShopState(int category, int page, ItemStack selected, int quantityIndex, boolean drop) {
		public static ShopState initial() {
			return new ShopState(0, 0, ItemStack.EMPTY, 0, false);
		}

		public ShopState withCategory(int value) {
			return new ShopState(value, 0, ItemStack.EMPTY, 1, false);
		}

		public ShopState withPage(int value) {
			return new ShopState(this.category, value, this.selected, this.quantityIndex, this.drop);
		}

		public ShopState withSelected(ItemStack stack) {
			return new ShopState(this.category, this.page, stack, 0, false);
		}

		public ShopState withQuantity(int value) {
			return new ShopState(this.category, this.page, this.selected, value, this.drop);
		}

		public ShopState withDrop(boolean value) {
			return new ShopState(this.category, this.page, this.selected, this.quantityIndex, value);
		}

		public int count() {
			return this.selected.isEmpty() ? 0 : quantity();
		}

		public int quantity() {
			return QUANTITIES[Math.max(0, Math.min(this.quantityIndex, QUANTITIES.length - 1))];
		}

		public String quantityText() {
			int count = quantity();
			int maxStack = this.selected.isEmpty() ? 64
					: Math.max(1, this.selected.getItem().getDefaultMaxStackSize());

			if (count >= maxStack && count % maxStack == 0) {
				int stacks = count / maxStack;
				return stacks + (stacks == 1 ? " стак" : (stacks < 5 ? " стака" : " стаков"));
			}

			return count + " шт.";
		}
	}

	private static final Map<UUID, ShopState> SHOP = new HashMap<>();

	public static ShopState shop(UUID playerId) {
		return SHOP.getOrDefault(playerId, ShopState.initial());
	}

	public static void setShop(UUID playerId, ShopState state) {
		SHOP.put(playerId, state);
	}

	public static int nextQuantity(ShopState state) {
		return (state.quantityIndex() + 1) % QUANTITIES.length;
	}

	public static double costOf(MarketplaceState world, ShopState state) {
		if (state.selected().isEmpty()) {
			return 0.0;
		}

		java.util.Optional<Listing> listing = listing(world, state.selected());

		if (listing.isPresent()) {
			return listing.get().order().payout();
		}

		java.util.Optional<CustomKit> custom = CustomKits.kitOf(world, state.selected());

		if (custom.isPresent()) {
			return MoneyFormat.round(CustomKits.priceOf(custom.get()));
		}

		java.util.Optional<KitCatalog.Kit> kit = KitCatalog.kitOf(state.selected());

		if (kit.isPresent()) {
			return MoneyFormat.round(KitCatalog.priceOf(kit.get()));
		}

		java.util.OptionalDouble special = SpecialCatalog.priceOf(state.selected());

		if (special.isPresent()) {

			return MoneyFormat.round(special.getAsDouble() * state.count());
		}

		return MoneyFormat.round(ItemPrices.priceOf(state.selected())
				* EconomyConfig.buyMultiplier() * state.count());
	}

	public static boolean buy(MinecraftServer server, ServerPlayer player, ShopState state) {
		if (state.selected().isEmpty()) {
			return false;
		}

		MarketplaceState world = MarketplaceEconomy.state(server);

		if (listing(world, state.selected()).isPresent()) {
			return buyListing(server, player, state.selected(), state.drop()).status() == BuyStatus.BOUGHT;
		}

		java.util.Optional<CustomKit> custom = CustomKits.kitOf(world, state.selected());
		int count = state.count();
		double cost = costOf(world, state);

		if (!player.isCreative() && !MarketplaceEconomy.trySpend(server, player.getUUID(), cost)) {
			return false;
		}

		if (custom.isPresent()) {

			CustomKits.deliver(player, custom.get());
			return true;
		}

		java.util.Optional<KitCatalog.Kit> kit = KitCatalog.kitOf(state.selected());

		if (kit.isPresent()) {

			for (ItemStack piece : KitCatalog.deliver(kit.get())) {
				give(player, state, piece);
			}

			return true;
		}

		int maxStack = Math.max(1, state.selected().getItem().getDefaultMaxStackSize());
		int left = count;

		while (left > 0) {
			int part = Math.min(left, maxStack);
			ItemStack stack = state.selected().copyWithCount(part);
			left -= part;
			give(player, state, stack);
		}

		return true;
	}

	private static void give(ServerPlayer player, ShopState state, ItemStack stack) {
		give(player, state.drop(), stack);
	}

	private static void give(ServerPlayer player, boolean drop, ItemStack stack) {
		if (drop) {
			player.drop(stack, false, Prediction.SERVER_ONLY);
		} else {
			player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
		}
	}

	private static boolean windowTransition;

	public static void setWindowTransition(boolean value) {
		windowTransition = value;
	}

	public static boolean isWindowTransition() {
		return windowTransition;
	}}
