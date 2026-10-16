package com.chr0m4ik.marketplace.network;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Пакет «состояние игрока»: баланс, стоимость инвентаря и закупки торговца.
 *
 * <p>Нужен плашке на HUD и подсказкам предметов: клиент должен знать, что торговец уже покупал,
 * чтобы показывать у него те же цены, что и сервер.
 *
 * <p>Деньги передаём в копейках (целыми числами), чтобы не терять точность.
 *
 * <p>Здесь же идут зерно мира и игровое время сервера: по ним клиент считает микро-дрейф цен
 * ({@code PriceDrift}) по той же формуле, что и сервер. Иначе подсказки расходились бы с тем,
 * что игрок платит на самом деле.
 *
 * <p>И описание активного крупного ивента ({@link EventData}): сервер — единственный источник
 * истины, а клиент считает множитель ивента тем же кодом ({@code event/EventService}), поэтому
 * подсказки совпадают с оплатой. Данные ивента не зависят от времени, поэтому идут только когда
 * ивент начался или закончился.
 */
public record MarketplaceStatusPayload(
		long balanceCents,
		long inventoryCents,
		List<SaleEntry> traderSales,
		long worldSeed,
		long gameTime,
		EventData event
) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MarketplaceStatusPayload> TYPE =
			new CustomPacketPayload.Type<>(MarketplaceMod.id("status"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MarketplaceStatusPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::balanceCents,
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::inventoryCents,
			SaleEntry.CODEC.apply(ByteBufCodecs.list()), MarketplaceStatusPayload::traderSales,
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::worldSeed,
			ByteBufCodecs.VAR_LONG, MarketplaceStatusPayload::gameTime,
			EventData.CODEC, MarketplaceStatusPayload::event,
			MarketplaceStatusPayload::new
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** Одна строка закупок торговца: сколько стаков предмета он купил. */
	public record SaleEntry(String itemId, double stacks) {
		public static final StreamCodec<RegistryFriendlyByteBuf, SaleEntry> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, SaleEntry::itemId,
				ByteBufCodecs.DOUBLE, SaleEntry::stacks,
				SaleEntry::new
		);
	}

	/**
	 * Активный крупный ивент: то же описание, что лежит в сейве мира
	 * ({@code event/MarketEvent}). Клиент собирает из него ивент и считает множитель сам.
	 *
	 * @param name       id названия ивента; пустая строка — ивента нет;
	 * @param category   имя раздела магазина или пустая строка;
	 * @param items      id затронутых предметов (пусто у ивента на раздел);
	 * @param multiplier множитель цены (0.87, 1.12 и так далее).
	 */
	public record EventData(String name, String category, List<String> items, double multiplier) {
		/** Ивента нет. */
		public static final EventData NONE = new EventData("", "", List.of(), 1.0);

		public static final StreamCodec<RegistryFriendlyByteBuf, EventData> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, EventData::name,
				ByteBufCodecs.STRING_UTF8, EventData::category,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), EventData::items,
				ByteBufCodecs.DOUBLE, EventData::multiplier,
				EventData::new
		);

		/** Идёт ли ивент (иначе это {@link #NONE}). */
		public boolean active() {
			return !this.name.isEmpty();
		}
	}
}
