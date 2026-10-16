package com.chr0m4ik.marketplace.event;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Уведомления о крупных ивентах: чат, title на экране и слухи.
 *
 * <p>Формат согласован с заказчиком:
 * <ul>
 *     <li>в чате название ивента — <b>золотым</b>, строкой ниже <b>фиолетовым</b> — что именно
 *     затронуто (список предметов или название раздела);</li>
 *     <li>на экране — только название ивента, крупно и золотым, как обычный title;</li>
 *     <li>слух приходит заранее и <b>не всегда</b>: «По рынку слух: скоро подорожает редстоун».
 *     Слух говорит про тот же ивент, который потом начнётся: он берётся из его описания,
 *     а не выдумывается отдельно, поэтому не может соврать.</li>
 * </ul>
 *
 * <p>Названия предметов и разделов передаются компонентами, поэтому каждый игрок видит их
 * на своём языке (русский или английский).
 */
public final class EventMessages {
	/** Сколько тиков title появляется, висит и исчезает (10 / 70 / 20 — как у ванильных title). */
	private static final int TITLE_FADE_IN = 10;
	private static final int TITLE_STAY = 70;
	private static final int TITLE_FADE_OUT = 20;

	private EventMessages() {
		// Класс только со статическими методами.
	}

	/** Ивент начался: чат (золотое название + фиолетовая цель) и title на экране. */
	public static void announce(MinecraftServer server, MarketEvent event) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.sendSystemMessage(Component.translatable("marketplace.event.chat.title", event.name().title())
					.withStyle(ChatFormatting.GOLD));
			player.sendSystemMessage(targetLine(event));
			showTitle(player, event.name().title().copy().withStyle(ChatFormatting.GOLD));
		}
	}

	/** Ивент закончился — одна серая строка в чат. */
	public static void announceEnd(MinecraftServer server, MarketEvent event) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.sendSystemMessage(Component.translatable("marketplace.event.chat.end", event.name().title())
					.withStyle(ChatFormatting.GRAY));
		}
	}

	/**
	 * Слух про запланированный ивент: про один случайный предмет или про раздел целиком.
	 * Если предмета из старого сейва в игре уже нет, слух просто не приходит.
	 */
	public static void rumor(MinecraftServer server, MarketEvent event) {
		Component line = rumorLine(server, event);

		if (line == null) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.sendSystemMessage(line);
		}
	}

	/** Цель ивента текстом для лога: «раздел Блоки» или список id предметов. */
	public static String targetText(MarketEvent event) {
		if (event.isCategory()) {
			return "раздел " + event.category();
		}

		return String.join(", ", event.itemIds());
	}

	/**
	 * Что затронуто ивентом — компонентом: название раздела или список названий предметов.
	 * Нужно команде {@code /market event list} и подсказкам, чтобы не повторять разбор цели.
	 */
	public static Component targetNames(MarketEvent event) {
		return categoryTitle(event).orElseGet(() -> itemsText(event));
	}

	private static Component targetLine(MarketEvent event) {
		Optional<Component> category = categoryTitle(event);

		if (category.isPresent()) {
			return Component.translatable("marketplace.event.chat.category", category.get())
					.withStyle(ChatFormatting.LIGHT_PURPLE);
		}

		return Component.translatable("marketplace.event.chat.items", itemsText(event))
				.withStyle(ChatFormatting.LIGHT_PURPLE);
	}

	/** Название затронутых предметов через запятую — каждый на языке игрока. */
	private static Component itemsText(MarketEvent event) {
		MutableComponent text = Component.empty();
		boolean first = true;

		for (String id : event.itemIds()) {
			Optional<Item> item = resolve(id);

			if (item.isEmpty()) {
				continue;
			}

			if (!first) {
				text.append(Component.literal(", "));
			}

			text.append(new ItemStack(item.get()).getHoverName());
			first = false;
		}

		return first ? Component.translatable("marketplace.event.chat.unknown") : text;
	}

	private static Component rumorLine(MinecraftServer server, MarketEvent event) {
		boolean up = event.rises();
		Optional<Component> category = categoryTitle(event);

		if (category.isPresent()) {
			return Component.translatable(up ? "marketplace.event.rumor.category.up"
							: "marketplace.event.rumor.category.down", category.get())
					.withStyle(ChatFormatting.GRAY);
		}

		List<String> ids = event.itemIds();

		if (ids.isEmpty()) {
			return null;
		}

		// Слух называет один предмет из списка — этого достаточно, чтобы игрок понял, о чём речь.
		String id = ids.get(server.overworld().getRandom().nextInt(ids.size()));
		Optional<Item> item = resolve(id);

		if (item.isEmpty()) {
			return null;
		}

		return Component.translatable(up ? "marketplace.event.rumor.up" : "marketplace.event.rumor.down",
				new ItemStack(item.get()).getHoverName()).withStyle(ChatFormatting.GRAY);
	}

	/** Название раздела ивента, если ивент на раздел, — компонентом, чтобы переводилось. */
	private static Optional<Component> categoryTitle(MarketEvent event) {
		return event.categoryOrEmpty()
				.map(category -> (Component) Component.translatable(category.titleKey()));
	}

	/**
	 * Предмет по id из сейва. Битую запись молча пропускаем: ивент из-за неё ломать нельзя,
	 * а игрок просто не увидит этот предмет в списке.
	 */
	private static Optional<Item> resolve(String id) {
		try {
			return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	/** Title на экране: только название ивента, крупно и золотым. */
	private static void showTitle(ServerPlayer player, Component title) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(TITLE_FADE_IN, TITLE_STAY,
				TITLE_FADE_OUT));
		player.connection.send(new ClientboundSetTitleTextPacket(title));
	}
}
