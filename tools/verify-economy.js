// Проверка экономики мода без запуска игры.
//
// Что проверяет:
//   1. «купил — продал» для всех предметов прайса: покупка по buy_multiplier с самым
//      невыгодным дрейфом против самой выгодной продажи (рынок x2 и торговец с надбавкой).
//      Результат должен быть убыточным — иначе предмет печатает деньги.
//   2. «фабрика»: купить материалы → скрафтить или переплавить → продать на рынке x2.
//      Цепочки считаются по тем же рецептам игры, что и генератор прайса.
//   3. наборы: цена набора против выручки от распродажи его содержимого (рынок x1 и x2).
//
// Формулы повторяют Java: price/ItemPrices.java (цена + микро-дрейф),
// price/EnchantmentPrices.java, price/PotionPrices.java, market/MarketService.java (MARKUPS),
// market/KitCatalog.java (цена набора). Если Java изменится — поправить и здесь.
//
// Запуск: node tools/verify-economy.js
// Данные Minecraft должны быть распакованы в Z:/gradle-home/_mcdata (recipe, enchantment).

import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '..');
const DATA = process.env.MC_DATA || 'Z:/gradle-home/_mcdata';
const RESOURCES = join(ROOT, 'src', 'main', 'resources', 'data', 'marketplace');

// --- Экономические константы: берём из economy.json, если он есть (как Java) ---
const FALLBACK = {
	buy_multiplier: 2.2,
	trader_multiplier: 0.75,
	trader_max_bonus: 0.10,
	market_markups: [1.0, 1.25, 1.5, 1.75, 2.0],
	drift_amplitude: 0.03,
	craft_markup: 1.25,
	smelt_markup: 1.15,
};
const economyPath = join(RESOURCES, 'economy.json');
const ECONOMY = existsSync(economyPath)
	? { ...FALLBACK, ...JSON.parse(readFileSync(economyPath, 'utf8')) }
	: FALLBACK;

const BUY = ECONOMY.buy_multiplier;
const TRADER = ECONOMY.trader_multiplier;
const TRADER_BONUS = ECONOMY.trader_max_bonus ?? FALLBACK.trader_max_bonus;
const MARKUPS = ECONOMY.market_markups;
const MAX_MARKUP = Math.max(...MARKUPS);
const DRIFT = ECONOMY.drift_amplitude;

const prices = JSON.parse(readFileSync(join(RESOURCES, 'prices.json'), 'utf8'));
const PRICE = prices.prices;
const NOT_BUYABLE = new Set(prices.notBuyable);

const money = (value) => {
	if (!Number.isFinite(value)) return String(value);

	if (Math.abs(value) >= 1e9) return (value / 1e9).toFixed(2) + 'B';
	if (Math.abs(value) >= 1e6) return (value / 1e6).toFixed(2) + 'M';
	if (Math.abs(value) >= 1e3) return (value / 1e3).toFixed(2) + 'K';

	return value.toFixed(2);
};
const percent = (fraction) => (fraction >= 0 ? '+' : '') + (fraction * 100).toFixed(2) + '%';

/** Базовая цена предмета из прайса (без дрейфа). */
const basePrice = (id) => PRICE[id] ?? 1.0;

// ---------------------------------------------------------------------------
// Зачарования: price/EnchantmentPrices.java.
// ---------------------------------------------------------------------------
const ENCH_BASE = 400_000.0;
const ENCH_COMMON_WEIGHT = 10.0;
const ENCH_LEVEL_STEP = 2.2;
const ENCH_COMPLEXITY_STEP = 0.25;
const ENCH_SPECIAL = {
	'minecraft:binding_curse': 100_000.0,
	'minecraft:vanishing_curse': 250_000.0,
	'minecraft:bane_of_arthropods': 150_000.0,
	'minecraft:thorns': 300_000.0,
	'minecraft:channeling': 300_000.0,
	'minecraft:aqua_affinity': 150_000.0,
};
const ENCH_TREASURE = new Set([
	'minecraft:mending', 'minecraft:frost_walker', 'minecraft:soul_speed',
	'minecraft:swift_sneak', 'minecraft:wind_burst',
]);

const weightCache = new Map();

/** Вес зачарования из данных игры. */
function enchantWeight(id) {
	if (weightCache.has(id)) return weightCache.get(id);

	const path = join(DATA, 'enchantment', id.replace('minecraft:', '') + '.json');
	const data = existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : {};
	const weight = Math.max(1, data.weight ?? 1);
	weightCache.set(id, weight);

	return weight;
}

/** Цена всех зачарований предмета (без цены самого предмета). */
function enchantmentsPrice(enchantments) {
	const ids = Object.keys(enchantments ?? {});
	if (ids.length === 0) return 0.0;

	let sum = 0.0;

	for (const id of ids) {
		const special = ENCH_SPECIAL[id];

		if (special !== undefined) {
			sum += special;
			continue;
		}

		const level = Math.max(1, enchantments[id]);
		let price = ENCH_BASE * (ENCH_COMMON_WEIGHT / enchantWeight(id)) * Math.pow(ENCH_LEVEL_STEP, level - 1);
		if (ENCH_TREASURE.has(id)) price *= 2.0;
		sum += price;
	}

	return sum * (1.0 + (ids.length - 1) * ENCH_COMPLEXITY_STEP);
}

// ---------------------------------------------------------------------------
// Зелья: price/PotionPrices.java (цепочка варки).
// ---------------------------------------------------------------------------
const POTION_WATER_BOTTLE = 250.0;
const POTION_MARKUP = 1.2;
const POTION_FUEL_DIVISOR = 8.0;
const POTION_LUCK = 500_000.0;
const POTION_RECIPES = {
	awkward: ['water', 'minecraft:nether_wart'],
	thick: ['water', 'minecraft:glowstone_dust'],
	mundane: ['water', 'minecraft:stone'],
	night_vision: ['awkward', 'minecraft:golden_carrot'],
	long_night_vision: ['night_vision', 'minecraft:redstone'],
	invisibility: ['night_vision', 'minecraft:fermented_spider_eye'],
	long_invisibility: ['invisibility', 'minecraft:redstone'],
	leaping: ['awkward', 'minecraft:rabbit_foot'],
	long_leaping: ['leaping', 'minecraft:redstone'],
	strong_leaping: ['leaping', 'minecraft:glowstone_dust'],
	fire_resistance: ['awkward', 'minecraft:magma_cream'],
	long_fire_resistance: ['fire_resistance', 'minecraft:redstone'],
	swiftness: ['awkward', 'minecraft:sugar'],
	long_swiftness: ['swiftness', 'minecraft:redstone'],
	strong_swiftness: ['swiftness', 'minecraft:glowstone_dust'],
	slowness: ['swiftness', 'minecraft:fermented_spider_eye'],
	long_slowness: ['long_swiftness', 'minecraft:fermented_spider_eye'],
	strong_slowness: ['slowness', 'minecraft:glowstone_dust'],
	turtle_master: ['awkward', 'minecraft:turtle_helmet'],
	long_turtle_master: ['turtle_master', 'minecraft:redstone'],
	strong_turtle_master: ['turtle_master', 'minecraft:glowstone_dust'],
	water_breathing: ['awkward', 'minecraft:pufferfish'],
	long_water_breathing: ['water_breathing', 'minecraft:redstone'],
	healing: ['awkward', 'minecraft:glistering_melon_slice'],
	strong_healing: ['healing', 'minecraft:glowstone_dust'],
	harming: ['poison', 'minecraft:fermented_spider_eye'],
	strong_harming: ['harming', 'minecraft:glowstone_dust'],
	poison: ['awkward', 'minecraft:spider_eye'],
	long_poison: ['poison', 'minecraft:redstone'],
	strong_poison: ['poison', 'minecraft:glowstone_dust'],
	regeneration: ['awkward', 'minecraft:ghast_tear'],
	long_regeneration: ['regeneration', 'minecraft:redstone'],
	strong_regeneration: ['regeneration', 'minecraft:glowstone_dust'],
	strength: ['awkward', 'minecraft:blaze_powder'],
	long_strength: ['strength', 'minecraft:redstone'],
	strong_strength: ['strength', 'minecraft:glowstone_dust'],
	weakness: ['water', 'minecraft:fermented_spider_eye'],
	long_weakness: ['weakness', 'minecraft:redstone'],
	slow_falling: ['awkward', 'minecraft:phantom_membrane'],
	long_slow_falling: ['slow_falling', 'minecraft:redstone'],
	wind_charged: ['awkward', 'minecraft:breeze_rod'],
	weaving: ['awkward', 'minecraft:cobweb'],
	oozing: ['awkward', 'minecraft:slime_block'],
	infested: ['awkward', 'minecraft:stone'],
};

/** Сумма цен ингредиентов цепочки варки до бутылочки воды. */
function potionChain(path) {
	const recipe = POTION_RECIPES[path];
	if (!recipe) return 0.0;

	return potionChain(recipe[0]) + basePrice(recipe[1]);
}

/** Цена зелья (в стаке) по предмету и id зелья. */
function potionPrice(itemId, potionId) {
	const splash = itemId === 'minecraft:splash_potion';
	const lingering = itemId === 'minecraft:lingering_potion';
	const path = potionId?.replace('minecraft:', '') ?? 'water';

	if (path === 'luck') return POTION_LUCK;
	if (path === 'water' && !splash && !lingering) return POTION_WATER_BOTTLE;

	let sum = POTION_WATER_BOTTLE + potionChain(path) + basePrice('minecraft:blaze_powder') / POTION_FUEL_DIVISOR;
	if (splash) sum += basePrice('minecraft:gunpowder');
	if (lingering) sum += basePrice('minecraft:gunpowder') + basePrice('minecraft:dragon_breath');

	return sum * POTION_MARKUP;
}

/**
 * Цена предмета набора: price/ItemPrices.java. Дрейф по умолчанию нейтральный (1.0) —
 * так числа точные и не зависят от зерна мира; вторым аргументом его можно задать.
 */
function stackPrice(entry, drift = 1.0) {
	const base = entry.potion !== undefined
		? potionPrice(entry.item, entry.potion)
		: basePrice(entry.item);

	return base * drift * (entry.count ?? 1) + enchantmentsPrice(entry.enchantments);
}

// ---------------------------------------------------------------------------
// 1. «Купил — продал»: худший случай по всем предметам прайса.
// ---------------------------------------------------------------------------
console.log(`=== 1. «Купил — продал»: худший случай с дрейфом ±${(DRIFT * 100).toFixed(1)}% ===`);
console.log(`покупка = цена × ${BUY} × ${(1 - DRIFT).toFixed(2)};`
	+ ` продажа на рынке до × ${MAX_MARKUP} × ${(1 + DRIFT).toFixed(2)};`
	+ ` торговцу × ${TRADER} × ${(1 + TRADER_BONUS).toFixed(2)} × ${(1 + DRIFT).toFixed(2)}`);

const buyable = Object.keys(PRICE).filter((id) => !NOT_BUYABLE.has(id));
const marketWorst = (MAX_MARKUP * (1 + DRIFT)) / (BUY * (1 - DRIFT)) - 1;
const traderWorst = (TRADER * (1 + TRADER_BONUS) * (1 + DRIFT)) / (BUY * (1 - DRIFT)) - 1;
// Ищем самый мягкий случай (минимальный убыток) и самую большую потерю в деньгах.
let softest = { id: '', loss: -Infinity };
let biggest = { id: '', loss: 0 };

for (const id of buyable) {
	const price = basePrice(id);
	const loss = price * (MAX_MARKUP * (1 + DRIFT) - BUY * (1 - DRIFT));

	if (loss > softest.loss) softest = { id, loss };
	if (loss < biggest.loss) biggest = { id, loss };
}

console.log(`предметов можно купить: ${buyable.length} из ${Object.keys(PRICE).length}`);
console.log(`худший случай на рынке:   ${percent(marketWorst)} у любого предмета`
	+ ` (например ${buyable[0]}: купить ${money(basePrice(buyable[0]) * BUY * (1 - DRIFT))},`
	+ ` продать ${money(basePrice(buyable[0]) * MAX_MARKUP * (1 + DRIFT))})`);
console.log(`  самая большая потеря в деньгах: ${money(-biggest.loss)} на ${biggest.id}`
	+ `; самый мягкий случай: ${money(-softest.loss)} на ${softest.id}`);
console.log(`худший случай у торговца: ${percent(traderWorst)}`);
console.log(`ИТОГ: ${marketWorst < 0 && traderWorst < 0 ? 'ВЫПОЛНЕНО (перепродажа всегда в минус)' : 'НАРУШЕНО'}`);

// ---------------------------------------------------------------------------
// Рецепты игры — как в tools/generate-prices.js (нужны для «фабрики»).
// ---------------------------------------------------------------------------
const recipes = readdirSync(join(DATA, 'recipe'))
	.map((file) => JSON.parse(readFileSync(join(DATA, 'recipe', file), 'utf8')));

const rawTags = new Map();
const tagCache = new Map();

function collectTags(dir, prefix) {
	for (const entry of readdirSync(dir, { withFileTypes: true })) {
		if (entry.isDirectory()) {
			collectTags(join(dir, entry.name), prefix + entry.name + '/');
		} else {
			const data = JSON.parse(readFileSync(join(dir, entry.name), 'utf8'));
			rawTags.set('#' + prefix + entry.name.replace(/\.json$/, ''), data.values ?? []);
		}
	}
}
collectTags(join(DATA, 'tags'), 'minecraft:');

function resolveTag(id, seen = new Set()) {
	if (tagCache.has(id)) return tagCache.get(id);
	if (seen.has(id)) return [];
	seen.add(id);

	const result = new Set();

	for (const value of rawTags.get(id) ?? []) {
		const entry = typeof value === 'string' ? value : value.id;

		if (entry.startsWith('#')) {
			for (const item of resolveTag(entry, seen)) result.add(item);
		} else {
			result.add(entry);
		}
	}

	tagCache.set(id, result);

	return result;
}

/** Самый дешёвый вариант ингредиента (для тега — самый дешёвый предмет тега). */
function ingredientPrice(ingredient) {
	if (Array.isArray(ingredient)) {
		let best = null;

		for (const option of ingredient) {
			const cost = ingredientPrice(option);
			if (cost !== null && (best === null || cost < best)) best = cost;
		}

		return best;
	}

	if (typeof ingredient !== 'string') return null;

	if (ingredient.startsWith('#')) {
		let best = null;

		for (const member of resolveTag(ingredient)) {
			const price = PRICE[member];
			if (price !== undefined && (best === null || price < best)) best = price;
		}

		return best;
	}

	return PRICE[ingredient] ?? null;
}

/** Ингредиенты рецепта списком. null — если рецепт посчитать нельзя. */
function ingredientsOf(recipe) {
	switch (recipe.type) {
		case 'minecraft:crafting_shapeless':
			return { list: recipe.ingredients, count: recipe.result?.count ?? 1 };

		case 'minecraft:crafting_shaped': {
			const counts = new Map();

			for (const row of recipe.pattern) {
				for (const symbol of row) {
					if (symbol === ' ') continue;
					counts.set(symbol, (counts.get(symbol) ?? 0) + 1);
				}
			}

			const list = [];

			for (const [symbol, count] of counts) {
				const ingredient = recipe.key[symbol];
				if (ingredient === undefined) return null;
				for (let i = 0; i < count; i++) list.push(ingredient);
			}

			return { list, count: recipe.result?.count ?? 1 };
		}

		case 'minecraft:stonecutting':
			return { list: [recipe.ingredient], count: recipe.result?.count ?? 1 };

		case 'minecraft:smelting':
		case 'minecraft:blasting':
		case 'minecraft:smoking':
		case 'minecraft:campfire_cooking':
			return { list: [recipe.ingredient], count: recipe.result?.count ?? 1, smelt: true };

		case 'minecraft:crafting_transmute':
		case 'minecraft:crafting_dye':
			return { list: [recipe.input, recipe.material], count: recipe.result?.count ?? 1 };

		case 'minecraft:crafting_imbue':
			return { list: [recipe.source, recipe.material], count: recipe.result?.count ?? 1 };

		case 'minecraft:crafting_decorated_pot':
			return { list: [recipe.back, recipe.front, recipe.left, recipe.right], count: recipe.result?.count ?? 1 };

		case 'minecraft:smithing_transform':
			return { list: [recipe.template, recipe.base, recipe.addition], count: recipe.result?.count ?? 1 };

		default:
			return null;
	}
}

/** Самый дешёвый рецепт предмета: стоимость ингредиентов на одну штуку. */
function bestRecipe(item) {
	let best = null;

	for (const recipe of recipes) {
		if (recipe.result?.id !== item) continue;
		const info = ingredientsOf(recipe);
		if (!info) continue;

		let total = 0;
		let ok = true;

		for (const ingredient of info.list) {
			const price = ingredientPrice(ingredient);

			if (price === null) {
				ok = false;
				break;
			}

			total += price;
		}

		if (!ok) continue;

		const perItem = total / Math.max(1, info.count);

		if (best === null || perItem < best.cost) best = { cost: perItem, smelt: info.smelt === true };
	}

	return best;
}

// ---------------------------------------------------------------------------
// 2. Фабрика: купить материалы → скрафтить → продать на рынке x2.
// ---------------------------------------------------------------------------
const CHAINS = [
	'minecraft:iron_ingot', 'minecraft:iron_block', 'minecraft:iron_door', 'minecraft:hopper',
	'minecraft:minecart', 'minecraft:cauldron', 'minecraft:bucket', 'minecraft:anvil',
	'minecraft:rail', 'minecraft:gold_ingot', 'minecraft:gold_block', 'minecraft:golden_apple',
	'minecraft:golden_carrot', 'minecraft:clock', 'minecraft:powered_rail',
	'minecraft:diamond_block', 'minecraft:diamond_sword', 'minecraft:diamond_pickaxe',
	'minecraft:diamond_chestplate', 'minecraft:netherite_ingot', 'minecraft:netherite_block',
	'minecraft:bread', 'minecraft:cookie', 'minecraft:pumpkin_pie', 'minecraft:cooked_beef',
	'minecraft:baked_potato', 'minecraft:oak_planks', 'minecraft:stick', 'minecraft:chest',
	'minecraft:oak_door', 'minecraft:oak_stairs', 'minecraft:torch', 'minecraft:ladder',
	'minecraft:barrel', 'minecraft:redstone_block', 'minecraft:repeater', 'minecraft:comparator',
	'minecraft:piston', 'minecraft:observer', 'minecraft:dispenser', 'minecraft:note_block',
	'minecraft:redstone_torch', 'minecraft:glass', 'minecraft:stone_bricks', 'minecraft:paper',
];

console.log('\n=== 2. Фабрика: купить материалы → скрафтить → продать на рынке x2 ===');
console.log(`материалы покупаем по × ${BUY}; продаём по × ${MAX_MARKUP}. Столбцы: обычный случай (дрейф 1,0)`
	+ ` и худший (купили на пике × ${(1 + DRIFT).toFixed(2)}, продали на дне × ${(1 - DRIFT).toFixed(2)})`);

const chains = [];

for (const item of CHAINS) {
	const recipe = bestRecipe(item);

	if (!recipe) {
		console.log(`  ${item}: рецепт не найден — пропуск`);
		continue;
	}

	const buyCost = recipe.cost * BUY;
	const revenue = basePrice(item) * MAX_MARKUP;
	chains.push({
		item,
		buyCost,
		revenue,
		profit: revenue / buyCost - 1,
		worst: (revenue * (1 - DRIFT)) / (buyCost * (1 + DRIFT)) - 1,
		smelt: recipe.smelt,
	});
}

chains.sort((a, b) => a.profit - b.profit);

for (const chain of chains) {
	console.log(`  ${chain.item.padEnd(34)} покупка ${money(chain.buyCost).padStart(10)}`
		+ ` → продажа x2 ${money(chain.revenue).padStart(10)}  ${percent(chain.profit).padStart(8)}`
		+ `  худший ${percent(chain.worst).padStart(8)}` + (chain.smelt ? '  (переплавка)' : ''));
}

const medianOf = (values) => {
	const sorted = [...values].sort((a, b) => a - b);

	return sorted.length % 2 === 1
		? sorted[(sorted.length - 1) / 2]
		: (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
};

const profits = chains.map((chain) => chain.profit);
const worstProfits = chains.map((chain) => chain.worst);
console.log(`цепочек: ${chains.length}; медиана обычная: ${percent(medianOf(profits))}, худшая: ${percent(medianOf(worstProfits))}`);
console.log(`в обычном случае убыточных: ${profits.filter((value) => value < 0).length},`
	+ ` в худшем: ${worstProfits.filter((value) => value < 0).length}`);
console.log(`минимум обычный: ${percent(Math.min(...profits))} (${chains[0].item}), максимум: ${percent(Math.max(...profits))}`);

// ---------------------------------------------------------------------------
// 3. Наборы: цена набора против распродажи содержимого.
// ---------------------------------------------------------------------------
const KIT_DISCOUNT_OLD = 0.95;
const KIT_MARKUP_NEW = 1.02;
const SHULKER_COLORS = {
	pvp_pro: 'light_blue', pvp_extra: 'blue', pvp_crystal: 'purple', miner_dream: 'gray',
	lumberjack_dream: 'green', builder: 'yellow', glutton: 'orange', pro_set: 'cyan',
	legendary_set: 'red',
};

const kits = JSON.parse(readFileSync(join(RESOURCES, 'kits.json'), 'utf8')).kits;

/** Цена всего содержимого набора (и шлякеров, если считать их отдельно) при нейтральном дрейфе. */
function kitContents(kit) {
	let sum = 0;

	for (const entry of [...(kit.items ?? []), ...(kit.inventory ?? [])]) {
		sum += stackPrice(entry);
	}

	return sum;
}

console.log('\n=== 3. Наборы: цена набора против распродажи содержимого ===');
console.log('дрейф нейтральный; шлякеры — те, что реально выдаются игроку');

const kitRows = [];

for (const kit of kits) {
	const contents = kitContents(kit);
	const coloredId = 'minecraft:' + (SHULKER_COLORS[kit.id] ?? 'white') + '_shulker_box';
	const coloredShulker = basePrice(coloredId);
	const shulkerSum = kit.shulkers * coloredShulker;
	const oldPrice = (contents + kit.shulkers * basePrice('minecraft:shulker_box')) * KIT_DISCOUNT_OLD;
	const newPrice = (contents + shulkerSum) * KIT_MARKUP_NEW;
	const resaleX1 = contents + shulkerSum;

	kitRows.push({ kit, contents, coloredId, coloredShulker, oldPrice, newPrice, resaleX1, resaleX2: resaleX1 * MAX_MARKUP });
}

for (const row of kitRows) {
	console.log(`\n  ${row.kit.id}: шлякеров ${row.kit.shulkers}`
		+ (row.kit.shulkers > 0 ? `, ${row.coloredId} = ${money(row.coloredShulker)}` : ''));
	console.log(`    содержимое:          ${money(row.contents)}`);
	console.log(`    цена была (×0,95):   ${money(row.oldPrice)}`);
	console.log(`    цена стала (×1,02):  ${money(row.newPrice)}`);
	console.log(`    распродажа x1:       ${money(row.resaleX1)}  → итог ${percent(row.resaleX1 / row.newPrice - 1)}`);
	console.log(`    распродажа x2:       ${money(row.resaleX2)}  → итог ${percent(row.resaleX2 / row.newPrice - 1)}`
		+ ` (ордеров на рынке: ${row.kit.shulkers + (row.kit.items ?? []).length})`);
}

console.log('\n--- три показательных набора ---');

for (const row of kitRows.filter((value) => ['pvp_pro', 'pvp_extra', 'legendary_set'].includes(value.kit.id))) {
	console.log(`  ${row.kit.id}: цена ${money(row.newPrice)}, распродажа x1 ${money(row.resaleX1)}`
		+ ` (${percent(row.resaleX1 / row.newPrice - 1)}), x2 ${money(row.resaleX2)} (${percent(row.resaleX2 / row.newPrice - 1)})`);
}

// ---------------------------------------------------------------------------
// Вариант, который закрывает дыру на x2: набор продаётся как покупка, то есть
// его цена идёт через buy_multiplier (и та же наценка 2% сверху). Показан для сравнения —
// в моде НЕ включён, это предложение.
// ---------------------------------------------------------------------------
console.log('\n--- вариант «цена набора = содержимое × buy_multiplier × 1,02» (в моде не включён) ---');

for (const row of kitRows.filter((value) => ['pvp_pro', 'pvp_extra', 'legendary_set'].includes(value.kit.id))) {
	const price = row.resaleX1 * BUY * KIT_MARKUP_NEW;
	console.log(`  ${row.kit.id}: цена ${money(price)} — распродажа x1 ${percent(row.resaleX1 / price - 1)},`
		+ ` x2 ${percent(row.resaleX2 / price - 1)}`);
}
