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

public final class EventMessages {

	private static final int TITLE_FADE_IN = 10;
	private static final int TITLE_STAY = 70;
	private static final int TITLE_FADE_OUT = 20;

	private EventMessages() {
		// Класс только со статическими методами.
	}

	public static void announce(MinecraftServer server, MarketEvent event) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.sendSystemMessage(Component.translatable("marketplace.event.chat.title", event.name().title())
					.withStyle(ChatFormatting.GOLD));
			player.sendSystemMessage(targetLine(event));
			showTitle(player, event.name().title().copy().withStyle(ChatFormatting.GOLD));
		}
	}

	public static void announceEnd(MinecraftServer server, MarketEvent event) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.sendSystemMessage(Component.translatable("marketplace.event.chat.end", event.name().title())
					.withStyle(ChatFormatting.GRAY));
		}
	}

	public static void rumor(MinecraftServer server, MarketEvent event) {
		Component line = rumorLine(server, event);

		if (line == null) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.sendSystemMessage(line);
		}
	}

	public static String targetText(MarketEvent event) {
		if (event.isCategory()) {
			return "раздел " + event.category();
		}

		return String.join(", ", event.itemIds());
	}

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

		String id = ids.get(server.overworld().getRandom().nextInt(ids.size()));
		Optional<Item> item = resolve(id);

		if (item.isEmpty()) {
			return null;
		}

		return Component.translatable(up ? "marketplace.event.rumor.up" : "marketplace.event.rumor.down",
				new ItemStack(item.get()).getHoverName()).withStyle(ChatFormatting.GRAY);
	}

	private static Optional<Component> categoryTitle(MarketEvent event) {
		return event.categoryOrEmpty()
				.map(category -> (Component) Component.translatable(category.titleKey()));
	}

	private static Optional<Item> resolve(String id) {
		try {
			return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static void showTitle(ServerPlayer player, Component title) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(TITLE_FADE_IN, TITLE_STAY,
				TITLE_FADE_OUT));
		player.connection.send(new ClientboundSetTitleTextPacket(title));
	}
}
