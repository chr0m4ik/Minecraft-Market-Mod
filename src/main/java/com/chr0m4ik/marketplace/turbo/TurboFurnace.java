package com.chr0m4ik.marketplace.turbo;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;

import java.util.Set;

/**
 * «Турбо печка» — блок с четырьмя уровнями ускорения плавки.
 *
 * <p>Это <b>свой блок</b>, а не подмена ванильной печи: только у своего блока есть свой
 * {@code id} (и своя loot table), поэтому уровень печки можно сохранить в выпавшем предмете.
 * Всё остальное поведение — ванильное: {@link TurboFurnaceBlock} наследуется от
 * {@code AbstractFurnaceBlock}, а {@link TurboFurnaceBlockEntity} — от
 * {@code AbstractFurnaceBlockEntity} (рецепты, топливо, воронки, опыт, редстоун, книга рецептов,
 * сохранение содержимого в сейв мира достаются бесплатно).
 *
 * <p>Почему свой {@code BlockEntityType}: у ванильной {@code FurnaceBlockEntity} конструктор
 * «прибит» к {@code BlockEntityTypes.FURNACE}, поэтому со своим типом наследоваться нужно именно
 * от {@code AbstractFurnaceBlockEntity} и передавать свой тип в {@code super(...)}.
 *
 * <p>Почему скорость задаётся миксином: {@code AbstractFurnaceBlockEntity.serverTick} —
 * {@code private static}, а поле {@code speedMultiplier} — {@code private}, поэтому переопределить
 * множитель подклассом нельзя. Миксин {@code TurboFurnaceSpeedMixin} подставляет множитель в
 * единственном месте, где игра его читает (см. {@code AbstractFurnaceBlockEntity.getSpeedMultiplier}).
 */
public final class TurboFurnace {
	/** Уровней у печки: I, II, III, IV. */
	public static final int MAX_LEVEL = 4;

	/**
	 * Ускорение по уровням — как согласовано с заказчиком (см. {@code docs/special-items.md} §5):
	 * уровень 1 — ×1.25, 2 — ×1.7, 3 — ×2.5, 4 — ×5. Время плавки делится на это число,
	 * поэтому железная руда (ванильные 200 тиков) плавится 160 / 118 / 80 / 40 тиков.
	 * Чем выше уровень, тем быстрее печка. Индекс — уровень печки, поэтому нулевой элемент
	 * таблицы не используется.
	 */
	private static final float[] SPEED = {1.0F, 1.25F, 1.7F, 2.5F, 5.0F};

	private static final ResourceKey<Block> BLOCK_KEY =
			ResourceKey.create(Registries.BLOCK, MarketplaceMod.id("turbo_furnace"));

	private static final ResourceKey<Item> ITEM_KEY =
			ResourceKey.create(Registries.ITEM, MarketplaceMod.id("turbo_furnace"));

	/**
	 * Блок печки. Свойства скопированы у ванильной печи один в один
	 * ({@code Blocks.FURNACE}: камень, кирка, прочность 3.5, свет 13 при горении), чтобы
	 * поведение и добыча совпадали. {@code requiresCorrectToolForDrops} без тега
	 * {@code minecraft:mineable/pickaxe} означал бы «кирка не считается нужным инструментом»,
	 * поэтому блок добавлен в этот тег в {@code data/minecraft/tags/block/mineable/pickaxe.json}.
	 */
	public static final Block BLOCK = Registry.register(BuiltInRegistries.BLOCK, BLOCK_KEY,
			new TurboFurnaceBlock(BlockBehaviour.Properties.of()
					.setId(BLOCK_KEY)
					.mapColor(MapColor.STONE)
					.instrument(NoteBlockInstrument.BASEDRUM)
					.requiresCorrectToolForDrops()
					.strength(3.5F)
					.lightLevel(state -> state.getValue(TurboFurnaceBlock.LIT) ? 13 : 0)));

	/**
	 * Предмет блока. Блеск «как зачарованный» задан прототипом предмета
	 * ({@code ENCHANTMENT_GLINT_OVERRIDE}), поэтому он есть у любой копии — и в магазине,
	 * и в предмете, выпавшем из сломанной печки. Настоящего зачарования тут нет: это только вид.
	 */
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
			new BlockItem(BLOCK, new Item.Properties()
					.setId(ITEM_KEY)
					.useBlockDescriptionPrefix()
					.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)));

	/** Своя блочная сущность: хранит уровень печки и тикает ванильным печным тикером. */
	public static final BlockEntityType<TurboFurnaceBlockEntity> BLOCK_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			MarketplaceMod.id("turbo_furnace"),
			new BlockEntityType<>(TurboFurnaceBlockEntity::new, Set.of(BLOCK)));

	/**
	 * Вкладка креатива «Функциональные блоки» — та, в которой лежит ванильная печь.
	 * В 26.3 ключи вкладок в {@code CreativeModeTabs} приватные, поэтому ключ собираем сами:
	 * имя вкладки — {@code functional_blocks} (видно в {@code CreativeModeTabs.createKey}).
	 */
	private static final ResourceKey<CreativeModeTab> FUNCTIONAL_BLOCKS = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("functional_blocks"));

	private TurboFurnace() {
		// Класс только со статическими полями и методами.
	}

	/**
	 * Регистрирует блок, предмет и блочную сущность. Вызывается при старте мода: реестры игры
	 * замораживаются после загрузки модов, поэтому регистрация не может быть «ленивой».
	 */
	public static void register() {
		// Ваниль делает это в Items.registerItem. Без этой строки Item.byBlock(наш блок) вернёт
		// воздух: pick block и Block.asItem() работали бы неправильно.
		if (ITEM instanceof BlockItem blockItem) {
			blockItem.registerBlocks(Item.BY_BLOCK, ITEM);
		}

		// В креативном меню печка стоит сразу после ванильной печи. Это только удобство для
		// тестов и построек: покупается печка по-прежнему в магазине.
		CreativeModeTabEvents.modifyOutputEvent(FUNCTIONAL_BLOCKS)
				.register(output -> output.insertAfter(Items.FURNACE, new ItemStack(ITEM)));
	}

	/** Множитель скорости уровня: время плавки ваниль делит на это число. */
	public static float speedOf(int level) {
		return SPEED[Mth.clamp(level, 1, MAX_LEVEL)];
	}
}
