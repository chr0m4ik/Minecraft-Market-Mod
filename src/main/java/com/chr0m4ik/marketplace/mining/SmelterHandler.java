package com.chr0m4ik.marketplace.mining;

import com.chr0m4ik.marketplace.MarketplaceMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;

import java.util.Optional;

/**
 * Зачарование «Плавильня»: дроп добытой <b>руды</b> сразу превращается в результат её переплавки
 * (железная руда → слиток, древние обломки → незеритовый лом и так далее).
 *
 * <p><b>Результат берётся из рецептов игры</b> ({@link RecipeType#SMELTING}, реестр рецептов
 * сервера), а не из таблицы в коде: так мод не разойдётся с игрой и с датапаками, а «нет рецепта —
 * предмет остаётся как есть» получается само собой. Поэтому, например, угольная руда (у неё в
 * ванили нет переплавки) и алмазная (переплавки нет) падают как обычно.
 *
 * <p><b>Только руды.</b> Проверяем сам сломанный блок ({@link VeinMinerHandler#isOre}), а не
 * предмет дропа: иначе плавильня стала бы «печкой в кармане» и жарила бы песок в стекло, а еду —
 * в готовую. Блок берём из {@link BreakContext}, потому что у {@code Block.popResource} его нет.
 *
 * <p><b>Опыт не начисляется.</b> Опыт за блок выдаёт ваниль в {@code Block.dropResources}
 * ({@code spawnAfterBreak}); плавильня в эту часть не вмешивается, поэтому его ровно столько же,
 * сколько при ломании без зачарования (у печки, в отличие от неё, опыт был бы отдельный).
 *
 * <p><b>Прочность</b> тратит ванильный цикл ломания по одной единице за блок — плавильня
 * ничего не добавляет.
 *
 * <p>Порядок с «Магнитом» важен: сначала плавим, потом магнит кладёт уже переплавленный предмет
 * в инвентарь — оба перехвата стоят в одной точке, см.
 * {@link com.chr0m4ik.marketplace.mixin.BlockMixin}.
 */
public final class SmelterHandler {
	/**
	 * Ключ нашего зачарования. {@code Holder} в статике не держим: реестр зачарований в 26.3
	 * динамический и пересоздаётся при {@code /reload} — устаревший держатель перестал бы
	 * находить зачарование.
	 */
	private static final ResourceKey<Enchantment> SMELTER =
			ResourceKey.create(Registries.ENCHANTMENT, MarketplaceMod.id("smelter"));

	private SmelterHandler() {
		// Класс только со статическими методами.
	}

	/** Подключает обработчик. Вызывается один раз при загрузке мода. */
	public static void register() {
		MarketplaceMod.LOGGER.info("Плавильня: дроп руды заменяется результатом плавки, опыт не добавляется");
	}

	/**
	 * Заменяет дроп руды результатом плавки, если инструмент зачарован «Плавильней».
	 *
	 * <p>Вызывается из миксина в начале {@code Block.popResource}, то есть <b>до</b> создания
	 * сущности предмета: переплавленный предмет в мире не появится вовсе. Количество сохраняется
	 * (стак руды превращается в такой же стак результата): с «Удачей» руды падает больше, значит
	 * и слитков больше.
	 *
	 * @return тот же стек, если плавильня не применилась (нет зачарования, блок не руда или
	 *         рецепта переплавки нет)
	 */
	public static ItemStack smelt(ItemStack itemStack) {
		ServerPlayer player = BreakContext.player();
		Block block = BreakContext.block();

		// Дроп создаётся не ломанием руды игроком — плавильне тут нечего делать.
		if (player == null || block == null || itemStack.isEmpty() || !VeinMinerHandler.isOre(block)) {
			return itemStack;
		}

		if (MiningSupport.level(player, SMELTER, BreakContext.tool()) <= 0) {
			return itemStack;   // на инструменте нет плавильни — дроп обычный
		}

		ServerLevel level = (ServerLevel) player.level();
		SingleRecipeInput input = new SingleRecipeInput(itemStack);
		Optional<RecipeHolder<SmeltingRecipe>> recipe = level.getServer()
				.getRecipeManager()
				.getRecipeFor(RecipeType.SMELTING, input, level);

		if (recipe.isEmpty()) {
			// Переплавки у этого предмета нет — он падает как обычно, ничего не ломаем.
			return itemStack;
		}

		ItemStack result = recipe.get().value().assemble(input);

		if (result.isEmpty()) {
			return itemStack;
		}

		// У ванильных рецептов плавки результат всегда один, но количество всё равно умножаем:
		// так предметов не потеряется и с чужим датапаком, где из одной руды выходит несколько.
		result.setCount(result.getCount() * itemStack.getCount());
		return result;
	}
}
