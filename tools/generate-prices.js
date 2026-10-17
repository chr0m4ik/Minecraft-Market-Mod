// Генератор прайс-листа мода Minecraft Marketplace.
//
// Что делает:
//   1. берёт список всех предметов и все рецепты прямо из jar-файлов Minecraft;
//   2. расставляет цены: сначала явные (BASE и PATTERNS ниже), потом считает остальные
//      из рецептов по правилу «сумма ингредиентов + наценка крафта» (CRAFT_MARKUP), как
//      просил заказчик (чтобы постройка ферм и крафт оставались выгодными, но не ломали экономику);
//   3. пишет готовый прайс в src/main/resources/data/marketplace/prices.json.
//
// Запуск:  node tools/generate-prices.js
// Данные Minecraft должны быть распакованы в Z:/gradle-home/_mcdata (recipe, tags, items).
//
// ВАЖНО: цены в долларах за 1 предмет. Покупатель платит price * 2,2 (buy_multiplier),
// продажа на рынке даёт price…price * 2,0 (наценка x1…x2), торговец — 75%. Поэтому купить
// предмет и перепродать его же невыгодно: 2,2 > 2,0.

const fs = require('fs');
const path = require('path');

const DATA = process.env.MC_DATA || 'Z:/gradle-home/_mcdata';
const OUT = process.argv[2] || 'src/main/resources/data/marketplace/prices.json';

/** Наценка на крафт: собранный предмет стоит на 25% дороже своих ингредиентов. */
const CRAFT_MARKUP = 1.25;

/** Наценка на переплавку (печка, коптильня и так далее): +15%, как просил заказчик. */
const SMELT_MARKUP = 1.15;

/**
 * Цены, которые расчёт из рецептов НЕ понижает и НЕ повышает: значение берётся из BASE
 * и остаётся таким, каким его задал заказчик. Внимание: если предмет можно скрафтить
 * дешевле его цены, он попадёт в tools/craft-cheats.txt — это значит, что на нём можно
 * зарабатывать.
 */
const LOCKED = new Set([
	// Шаблон незеритового апгрейда: заказчик просил 100 000, чтобы наценка была, но небольшая
	// (дублирование шаблона выходит около 84 375 при наценке крафта 1,25, прибыль около 15 600).
	'minecraft:netherite_upgrade_smithing_template',
	// Шалкер: заказчик закрепил 100 000 000 — из рецепта (2 панциря по 50M) с наценкой крафта
	// 1,25 вышло бы 125M, а это подняло бы и цену наборов, и цену цветных шлякеров.
	'minecraft:shulker_box',
	// Древесный уголь в LOCKED не входит: его цена (переплавка бревна) считается сама.
	// Узор флага Моджанга: крафтится из зачарованного золотого яблока, и расчёт дал бы 6,25M
	// за косметический узор. Держим на уровне остальных узоров; наживы на крафте нет
	// (яблоко дороже), поэтому закрепление безопасно.
	'minecraft:mojang_banner_pattern',
]);

/** Шаблоны отделки брони дорогие и одинаковые: цена 250 000 (шаблон заказчика), но расчёт
 * дублирования всё равно опускает её до стоимости крафта — так на них нельзя заработать. */
const LOCKED_PATTERNS = [];

const isLocked = (id) => LOCKED.has(id) || LOCKED_PATTERNS.some((pattern) => pattern.test(id));

/** Сколько стаков предмета торговец готов купить за один игровой день. */
const TRADER_DEFAULT_LIMIT = 30;
const TRADER_LIMIT_TIERS = [
	{ maxPrice: 100_000, stacks: 30 },
	{ maxPrice: 1_000_000, stacks: 10 },
	{ maxPrice: 10_000_000, stacks: 5 },
	{ maxPrice: 100_000_000, stacks: 2 },
	{ maxPrice: Infinity, stacks: 1 },
];

/** Цена предмета, для которого ничего не нашлось (креативные и служебные вещи). */
const JUNK_PRICE = 1;

// ---------------------------------------------------------------------------
// 1. Явные цены. Всё, что здесь указано, из рецептов НЕ пересчитывается.
// ---------------------------------------------------------------------------
const BASE = {
	// --- рядовые блоки и сырьё: 1–5$ ---
	'minecraft:dirt': 1, 'minecraft:coarse_dirt': 1, 'minecraft:rooted_dirt': 1, 'minecraft:farmland': 1,
	'minecraft:dirt_path': 1, 'minecraft:grass_block': 2, 'minecraft:podzol': 2, 'minecraft:mycelium': 3,
	'minecraft:moss_block': 3, 'minecraft:mud': 1, 'minecraft:packed_mud': 2, 'minecraft:muddy_mangrove_roots': 1,
	'minecraft:sand': 1, 'minecraft:red_sand': 1, 'minecraft:suspicious_sand': 1, 'minecraft:suspicious_gravel': 1,
	'minecraft:gravel': 1, 'minecraft:clay': 2, 'minecraft:clay_ball': 1, 'minecraft:flint': 3,
	'minecraft:cobblestone': 2, 'minecraft:stone': 2, 'minecraft:andesite': 2, 'minecraft:diorite': 2,
	'minecraft:granite': 2, 'minecraft:tuff': 2, 'minecraft:deepslate': 2, 'minecraft:cobbled_deepslate': 2,
	'minecraft:charcoal': 6,
	'minecraft:calcite': 2, 'minecraft:dripstone_block': 2, 'minecraft:pointed_dripstone': 2,
	'minecraft:netherrack': 1, 'minecraft:soul_sand': 2, 'minecraft:soul_soil': 2, 'minecraft:basalt': 1,
	'minecraft:smooth_basalt': 1, 'minecraft:blackstone': 2, 'minecraft:magma_block': 5, 'minecraft:glowstone': 20,
	'minecraft:end_stone': 2, 'minecraft:obsidian': 25, 'minecraft:crying_obsidian': 40,
	'minecraft:snow_block': 1, 'minecraft:snowball': 1, 'minecraft:ice': 2, 'minecraft:packed_ice': 5,	'minecraft:blue_ice': 15, 'minecraft:glass': 2, 'minecraft:terracotta': 2, 'minecraft:sculk': 5,
	'minecraft:sculk_vein': 5, 'minecraft:vine': 1, 'minecraft:glow_lichen': 1, 'minecraft:moss_carpet': 1,
	'minecraft:hanging_roots': 1, 'minecraft:big_dripleaf': 2, 'minecraft:small_dripleaf': 1,
	'minecraft:sponge': 500, 'minecraft:wet_sponge': 400, 'minecraft:cobweb': 5, 'minecraft:bedrock': 1,

	// --- обычные ресурсы: 5–50$ ---
	// Цены на ископаемые подняты в 10 раз (просьба заказчика). Слитков здесь нет намеренно:
	// они считаются переплавкой от руды, а переплавка дороже сырья на 15% (см. SMELT_MARKUP).
	'minecraft:coal': 50, 'minecraft:coal_ore': 80, 'minecraft:deepslate_coal_ore': 80,
	'minecraft:raw_iron': 200, 'minecraft:iron_ore': 220, 'minecraft:deepslate_iron_ore': 220,
	'minecraft:raw_copper': 80, 'minecraft:copper_ore': 90, 'minecraft:deepslate_copper_ore': 90,
	'minecraft:raw_gold': 350, 'minecraft:gold_ore': 380, 'minecraft:deepslate_gold_ore': 380,
	'minecraft:nether_gold_ore': 380, 'minecraft:redstone': 80, 'minecraft:redstone_ore': 100,
	'minecraft:deepslate_redstone_ore': 100, 'minecraft:lapis_lazuli': 150, 'minecraft:lapis_ore': 180,
	'minecraft:deepslate_lapis_ore': 180, 'minecraft:quartz': 120, 'minecraft:nether_quartz_ore': 140,
	'minecraft:amethyst_shard': 200, 'minecraft:amethyst_block': 800, 'minecraft:echo_shard': 20000,
	'minecraft:glowstone_dust': 10, 'minecraft:blaze_powder': 1000, 'minecraft:gunpowder': 6,
	'minecraft:flint': 3, 'minecraft:paper': 3, 'minecraft:string': 3, 'minecraft:feather': 2,
	'minecraft:leather': 5, 'minecraft:rabbit_hide': 3, 'minecraft:bone': 3, 'minecraft:slime_ball': 8,
	'minecraft:spider_eye': 10, 'minecraft:rotten_flesh': 10, 'minecraft:ink_sac': 3, 'minecraft:glow_ink_sac': 15,
	'minecraft:honeycomb': 15, 'minecraft:honey_bottle': 200, 'minecraft:sugar_cane': 2, 'minecraft:bamboo': 1,
	'minecraft:kelp': 10, 'minecraft:dried_kelp': 20, 'minecraft:sea_pickle': 5, 'minecraft:sweet_berries': 20,
	'minecraft:glow_berries': 50, 'minecraft:nether_wart': 10, 'minecraft:wheat': 20, 'minecraft:wheat_seeds': 1,
	'minecraft:beetroot': 8, 'minecraft:beetroot_seeds': 1, 'minecraft:carrot': 20, 'minecraft:potato': 20,
	'minecraft:poisonous_potato': 10, 'minecraft:melon_seeds': 1, 'minecraft:pumpkin_seeds': 1,
	'minecraft:pumpkin': 3, 'minecraft:carved_pumpkin': 3, 'minecraft:melon': 3, 'minecraft:melon_slice': 10,
	'minecraft:apple': 250, 'minecraft:cocoa_beans': 3, 'minecraft:cactus': 2, 'minecraft:dead_bush': 1,
	'minecraft:egg': 2, 'minecraft:turtle_egg': 5000, 'minecraft:armadillo_scute': 500,
	'minecraft:nautilus_shell': 100000, 'minecraft:prismarine_shard': 50, 'minecraft:prismarine_crystals': 80,
	'minecraft:heart_of_the_sea': 500000, 'minecraft:scute': 150, 'minecraft:phantom_membrane': 800,
	'minecraft:rabbit_foot': 300, 'minecraft:magma_cream': 25, 'minecraft:blaze_rod': 2000,
	'minecraft:ender_pearl': 400, 'minecraft:chorus_fruit': 500, 'minecraft:popped_chorus_fruit': 550,
	'minecraft:shulker_shell': 50000000,
	// Шалкер закреплён на 100M (см. LOCKED) — иначе наценка крафта 1,25 подняла бы его до 125M.
	'minecraft:shulker_box': 100000000, 'minecraft:dragon_breath': 20000, 'minecraft:ghast_tear': 30000,
	'minecraft:wither_skeleton_skull': 600000, 'minecraft:trident': 250000, 'minecraft:breeze_rod': 60000,
	'minecraft:heavy_core': 5000000, 'minecraft:ominous_bottle': 100000,
	// Узор флага Моджанга закреплён в LOCKED (расчёт из зачарованного яблока дал бы миллионы).
	'minecraft:mojang_banner_pattern': 5000,
	// Ключи испытаний: их выдают испытательные камеры, поэтому цена умеренная.
	'minecraft:trial_key': 250000, 'minecraft:ominous_trial_key': 500000,
	// Пузырёк опыта — нужен для починки предметов, но в сундуках он попадается часто
	// (61% сундуков аванпоста), поэтому не 100 000, а 30 000.
	'minecraft:experience_bottle': 30000,
	// Зачарованное золотое яблоко: раньше 50M — это давало «джекпот» в 1,5% сундуков порталов.
	'minecraft:enchanted_golden_apple': 5000000,
	'minecraft:name_tag': 5000, 'minecraft:saddle': 10000, 'minecraft:elytra': 20000000000,
	'minecraft:heart_of_the_sea': 150000,
	'minecraft:totem_of_undying': 100000000, 'minecraft:nether_star': 2000000000,
	'minecraft:dragon_egg': 5000000000, 'minecraft:spawner': 10000000000,
	'minecraft:trial_spawner': 2000000000, 'minecraft:vault': 2000000000, 'minecraft:end_portal_frame': 10000000,
	'minecraft:budding_amethyst': 1, 'minecraft:reinforced_deepslate': 1, 'minecraft:petrified_oak_slab': 1,

	// --- редкие ископаемые: 1000–50000$ ---
	'minecraft:diamond': 5000, 'minecraft:diamond_ore': 5200, 'minecraft:deepslate_diamond_ore': 5200,
	// Изумруды заказчик просил не трогать.
	'minecraft:emerald': 3500, 'minecraft:emerald_ore': 3700, 'minecraft:deepslate_emerald_ore': 3700,
	// Незерит и всё, что из него делают, дороже примерно втрое. Раньше слиток стоил 30 000,
	// значит руда должна стоить так, чтобы после переплавки и крафта вышло около 90 000.
	'minecraft:ancient_debris': 18000,
	'minecraft:raw_iron_block': 1800, 'minecraft:raw_copper_block': 720, 'minecraft:raw_gold_block': 3150,

	// --- еда и прочее, что нельзя вывести из рецептов ---
	'minecraft:potion': 250, 'minecraft:splash_potion': 400,
	'minecraft:lingering_potion': 600, 'minecraft:tipped_arrow': 100, 'minecraft:enchanted_book': 5000,
	'minecraft:written_book': 300, 'minecraft:writable_book': 250, 'minecraft:knowledge_book': 1,
	'minecraft:firework_rocket': 100, 'minecraft:firework_star': 50, 'minecraft:debug_stick': 1,
	'minecraft:barrier': 1, 'minecraft:light': 1, 'minecraft:structure_void': 1,
	'minecraft:structure_block': 1, 'minecraft:jigsaw': 1, 'minecraft:command_block': 1,
	'minecraft:chain_command_block': 1, 'minecraft:repeating_command_block': 1,
	'minecraft:command_block_minecart': 1, 'minecraft:air': 1, 'minecraft:water': 1, 'minecraft:lava': 1,
	'minecraft:fire': 1, 'minecraft:soul_fire': 1, 'minecraft:portal': 1, 'minecraft:end_portal': 1,
	'minecraft:end_gateway': 1, 'minecraft:nether_portal': 1, 'minecraft:bubble_column': 1,
	'minecraft:moving_piston': 1, 'minecraft:piston_head': 1, 'minecraft:tall_seagrass': 1,
	'minecraft:attached_pumpkin_stem': 1, 'minecraft:attached_melon_stem': 1, 'minecraft:big_dripleaf_stem': 1,
	'minecraft:frogspawn': 1, 'minecraft:infested_stone': 1, 'minecraft:infested_cobblestone': 1,
	'minecraft:infested_stone_bricks': 1, 'minecraft:infested_mossy_stone_bricks': 1,
	'minecraft:infested_cracked_stone_bricks': 1, 'minecraft:infested_chiseled_stone_bricks': 1,
	'minecraft:infested_deepslate': 1, 'minecraft:budding_amethyst': 1,
	'minecraft:test_block': 1, 'minecraft:test_instance_block': 1,

	// --- природа: цветы, трава, грибы, кусты: 1–5$ ---
	'minecraft:dandelion': 2, 'minecraft:poppy': 2, 'minecraft:blue_orchid': 2, 'minecraft:allium': 2,
	'minecraft:azure_bluet': 2, 'minecraft:red_tulip': 2, 'minecraft:orange_tulip': 2, 'minecraft:white_tulip': 2,
	'minecraft:pink_tulip': 2, 'minecraft:oxeye_daisy': 2, 'minecraft:cornflower': 2,
	'minecraft:lily_of_the_valley': 2, 'minecraft:sunflower': 3, 'minecraft:lilac': 3, 'minecraft:rose_bush': 3,
	'minecraft:peony': 3, 'minecraft:pink_petals': 1, 'minecraft:wildflowers': 2, 'minecraft:firefly_bush': 2,
	'minecraft:red_shrub': 2, 'minecraft:open_eyeblossom': 3, 'minecraft:closed_eyeblossom': 3,
	'minecraft:short_grass': 1, 'minecraft:tall_grass': 1, 'minecraft:short_dry_grass': 1,
	'minecraft:tall_dry_grass': 1, 'minecraft:fern': 1, 'minecraft:large_fern': 1, 'minecraft:seagrass': 1,
	'minecraft:lily_pad': 1, 'minecraft:azalea': 2, 'minecraft:flowering_azalea': 3, 'minecraft:bush': 1,
	'minecraft:mangrove_roots': 1, 'minecraft:shelf_mushroom': 3, 'minecraft:pale_hanging_moss': 2,
	'minecraft:pale_moss_block': 3, 'minecraft:brown_mushroom': 3, 'minecraft:red_mushroom': 3,
	'minecraft:brown_mushroom_block': 5, 'minecraft:red_mushroom_block': 5, 'minecraft:crimson_fungus': 5,
	'minecraft:warped_fungus': 5, 'minecraft:crimson_roots': 2, 'minecraft:warped_roots': 2,
	'minecraft:nether_sprouts': 2, 'minecraft:twisting_vines': 2, 'minecraft:weeping_vines': 2,
	'minecraft:crimson_nylium': 3, 'minecraft:warped_nylium': 3, 'minecraft:warped_wart_block': 5,
	'minecraft:shroomlight': 15, 'minecraft:chorus_plant': 60, 'minecraft:creaking_heart': 5000,
	'minecraft:gilded_blackstone': 40, 'minecraft:torchflower': 50, 'minecraft:torchflower_seeds': 25,
	'minecraft:pitcher_plant': 50, 'minecraft:pitcher_pod': 25,
	'minecraft:small_amethyst_bud': 25, 'minecraft:medium_amethyst_bud': 35, 'minecraft:large_amethyst_bud': 45,
	'minecraft:amethyst_cluster': 60, 'minecraft:sculk_sensor': 50, 'minecraft:sculk_catalyst': 200,
	'minecraft:sculk_shrieker': 250, 'minecraft:ochre_froglight': 50, 'minecraft:verdant_froglight': 50,
	'minecraft:pearlescent_froglight': 50, 'minecraft:bee_nest': 200, 'minecraft:bell': 500,

	// --- еда, рыба и ведра: 5–100$ ---
	// Еда подорожала в 10 раз (просьба заказчика). Яблоко — отдельная цена: оно редкое
	// и нужно для золотых яблок.
	'minecraft:beef': 200, 'minecraft:porkchop': 200, 'minecraft:chicken': 150, 'minecraft:mutton': 200,
	'minecraft:rabbit': 200, 'minecraft:cod': 150, 'minecraft:salmon': 200, 'minecraft:tropical_fish': 250,
	'minecraft:pufferfish': 300,
	// Ведро с содержимым = пустое ведро (862,5$) + содержимое. Иначе «купил полное ведро,
	// вылил воду, сдал пустое» было бесконечной наживой: пустое ведро дороже полного.
	'minecraft:water_bucket': 962, 'minecraft:milk_bucket': 912, 'minecraft:lava_bucket': 1012,
	'minecraft:powder_snow_bucket': 962, 'minecraft:turtle_scute': 200,
	'minecraft:turtle_helmet': 5000, 'minecraft:sniffer_egg': 500000,

	// --- броня и конская сбруя, которую нельзя скрафтить ---
	// Кольчуга хуже железа (её нельзя скрафтить, только найти), поэтому и дешевле.
	'minecraft:chainmail_helmet': 1000, 'minecraft:chainmail_chestplate': 1600,
	'minecraft:chainmail_leggings': 1400, 'minecraft:chainmail_boots': 800,
	'minecraft:leather_horse_armor': 500, 'minecraft:copper_horse_armor': 1500,
	'minecraft:iron_horse_armor': 3000, 'minecraft:golden_horse_armor': 4500,
	'minecraft:diamond_horse_armor': 55000, 'minecraft:netherite_horse_armor': 300000,
	'minecraft:copper_nautilus_armor': 2000, 'minecraft:iron_nautilus_armor': 3500,
	'minecraft:golden_nautilus_armor': 5000, 'minecraft:diamond_nautilus_armor': 60000,
	'minecraft:netherite_nautilus_armor': 320000,

	// --- карты, шаблоны и редкие находки ---
	'minecraft:map': 250, 'minecraft:filled_map': 3000, 'minecraft:buried_treasure_map': 10000,
	'minecraft:ocean_monument_map': 15000, 'minecraft:woodland_mansion_map': 20000,
	'minecraft:buried_mineshaft_map': 8000, 'minecraft:buried_ancient_city_map': 25000,
	'minecraft:buried_trial_chambers_map': 20000, 'minecraft:abandoned_camp_map': 8000,
	'minecraft:desert_pyramid_map': 10000, 'minecraft:jungle_pyramid_map': 10000,
	'minecraft:desert_village_map': 6000, 'minecraft:plains_village_map': 6000,
	'minecraft:savanna_village_map': 6000, 'minecraft:taiga_village_map': 6000,
	'minecraft:snowy_village_map': 6000, 'minecraft:swamp_hut_map': 12000,
	'minecraft:warm_ocean_ruins_map': 8000, 'minecraft:disc_fragment_5': 20000,
	'minecraft:goat_horn': 15000, 'minecraft:netherite_upgrade_smithing_template': 100000,
	'minecraft:flow_banner_pattern': 5000, 'minecraft:globe_banner_pattern': 5000,
	'minecraft:guster_banner_pattern': 5000, 'minecraft:piglin_banner_pattern': 5000,
	'minecraft:disc_fragment_5': 2000,
	// Золотой одуванчик крафтится из 8 золотых самородков, остальные три — редкие находки,
	// которые раньше попадали под дешёвый шаблон «цветы».
	'minecraft:golden_dandelion': 561, 'minecraft:chorus_flower': 200,
	'minecraft:spore_blossom': 100, 'minecraft:wither_rose': 100,
	// Факел: один уголь на четыре штуки, поэтому цена ниже «угольной» втрое.
	'minecraft:torch': 5,

	// --- медь в разных состояниях (окисляется сама, поэтому цена как у блока меди) ---
	'minecraft:exposed_copper': 95, 'minecraft:weathered_copper': 95, 'minecraft:oxidized_copper': 95,

	// --- новые материалы 26.3: киноварь, сера, смола ---
	'minecraft:cinnabar': 150, 'minecraft:sulfur': 120, 'minecraft:potent_sulfur': 2000,
	'minecraft:sulfur_spike': 100, 'minecraft:sulfur_cube_bucket': 3000,
	'minecraft:resin_clump': 25, 'minecraft:resin_block': 60, 'minecraft:resin_brick': 30,

	// --- то, что получают не крафтом, а в мире ---
	'minecraft:brown_egg': 5, 'minecraft:blue_egg': 5, 'minecraft:chipped_anvil': 500,
	'minecraft:damaged_anvil': 300, 'minecraft:copper_golem_statue': 5000,
	'minecraft:cod_bucket': 1012, 'minecraft:salmon_bucket': 1062, 'minecraft:pufferfish_bucket': 1162,
	'minecraft:tropical_fish_bucket': 1062, 'minecraft:axolotl_bucket': 1362, 'minecraft:tadpole_bucket': 1062,
};

// ---------------------------------------------------------------------------
// Яйца призыва: в выживании их не получить, поэтому цена зависит от того, насколько
// полезен моб. Ориентиры заказчика: курица — 1B, визер-скелет — несколько десятков
// миллиардов. Чтобы поменять цену, достаточно перенести id в другую группу.
// ---------------------------------------------------------------------------
const SPAWN_EGG_TIERS = [
	{
		price: 1_000_000_000,
		what: 'обычные мирные мобы',
		ids: [
			'chicken', 'cow', 'pig', 'sheep', 'rabbit', 'mooshroom', 'cod', 'salmon', 'pufferfish',
			'tropical_fish', 'bat', 'parrot', 'cat', 'ocelot', 'wolf', 'fox', 'bee', 'squid',
			'glow_squid', 'dolphin', 'turtle', 'tadpole', 'frog', 'goat', 'camel', 'llama', 'donkey',
			'mule', 'horse', 'panda', 'polar_bear', 'snow_golem', 'skeleton_horse', 'zombie_horse',
			'trader_llama', 'axolotl', 'armadillo', 'allay', 'sniffer', 'strider',
		],
	},
	{
		price: 3_000_000_000,
		what: 'простые враждебные мобы',
		ids: [
			'zombie', 'skeleton', 'creeper', 'spider', 'cave_spider', 'silverfish', 'endermite', 'slime',
			'husk', 'stray', 'drowned', 'phantom', 'magma_cube', 'zombie_villager', 'bogged', 'parched',
			'sulfur_cube', 'creaking',
		],
	},
	{
		price: 8_000_000_000,
		what: 'мобы, с которых идут ценные фермы',
		ids: [
			'witch', 'pillager', 'vindicator', 'vex', 'guardian', 'blaze', 'ghast', 'enderman', 'ravager',
			'hoglin', 'zoglin', 'piglin', 'piglin_brute', 'zombified_piglin', 'breeze', 'copper_golem',
			'iron_golem', 'camel_husk', 'nautilus', 'zombie_nautilus', 'happy_ghast', 'villager',
			'wandering_trader',
		],
	},
	{
		price: 25_000_000_000,
		what: 'очень ценные мобы',
		ids: ['shulker', 'elder_guardian', 'evoker', 'warden'],
	},
	{
		price: 40_000_000_000,
		what: 'визер-скелет (черепа для маяка)',
		ids: ['wither_skeleton'],
	},
	{
		price: 200_000_000_000,
		what: 'визер (босс)',
		ids: ['wither'],
	},
	{
		price: 500_000_000_000,
		what: 'дракон Края (босс)',
		ids: ['ender_dragon'],
	},
];

for (const tier of SPAWN_EGG_TIERS) {
	for (const mob of tier.ids) {
		BASE['minecraft:' + mob + '_spawn_egg'] = tier.price;
	}
}

// ---------------------------------------------------------------------------
// Предметы, которых в выживании не получить: продать можно только за 1$,
// купить нельзя вообще. Спавнеры и яйца призыва сюда НЕ входят — они дорогие и покупаются.
// ---------------------------------------------------------------------------
const NOT_BUYABLE = [
	'minecraft:air', 'minecraft:water', 'minecraft:lava', 'minecraft:fire', 'minecraft:soul_fire',
	'minecraft:portal', 'minecraft:end_portal', 'minecraft:end_gateway', 'minecraft:nether_portal',
	'minecraft:bubble_column', 'minecraft:moving_piston', 'minecraft:piston_head', 'minecraft:tall_seagrass',
	'minecraft:attached_pumpkin_stem', 'minecraft:attached_melon_stem', 'minecraft:big_dripleaf_stem',
	'minecraft:frogspawn', 'minecraft:infested_stone', 'minecraft:infested_cobblestone',
	'minecraft:infested_stone_bricks', 'minecraft:infested_mossy_stone_bricks',
	'minecraft:infested_cracked_stone_bricks', 'minecraft:infested_chiseled_stone_bricks',
	'minecraft:infested_deepslate', 'minecraft:budding_amethyst', 'minecraft:reinforced_deepslate',
	'minecraft:petrified_oak_slab', 'minecraft:bedrock', 'minecraft:barrier', 'minecraft:light',
	'minecraft:structure_void', 'minecraft:structure_block', 'minecraft:jigsaw', 'minecraft:command_block',
	'minecraft:chain_command_block', 'minecraft:repeating_command_block', 'minecraft:command_block_minecart',
	'minecraft:debug_stick', 'minecraft:knowledge_book', 'minecraft:test_block', 'minecraft:test_instance_block',
];

// Шаблоны: семейства предметов с одинаковой ценой (проверяются, если нет явной цены).
const PATTERNS = [
	{ what: 'брёвна и древесина', match: /(_log|_wood|_stem|_hyphae)$/, price: 4 },
	{ what: 'обтёсанное дерево', match: /^minecraft:stripped_/, price: 4 },
	{ what: 'саженцы и листва', match: /(_sapling|_leaves|_propagule)$/, price: 1 },
	{ what: 'шерсть', match: /_wool$/, price: 3 },
	{ what: 'ковры', match: /_carpet$/, price: 1 },
	{ what: 'бетон', match: /_concrete(_powder)?$/, price: 3 },
	{ what: 'красители', match: /_dye$/, price: 5 },
	{ what: 'глазурованная керамика', match: /_glazed_terracotta$/, price: 6 },
	{ what: 'керамика', match: /_terracotta$/, price: 3 },
	{ what: 'черепки', match: /_pottery_sherd$/, price: 500 },
	{ what: 'шаблоны кузнеца', match: /_armor_trim_smithing_template$/, price: 250000 },
	{ what: 'пластинки', match: /^minecraft:music_disc_/, price: 25000 },
	{ what: 'книги зачарований', match: /^minecraft:enchanted_book$/, price: 5000 },
	{ what: 'черепа черепов', match: /_head$|_skull$/, price: 25000 },
	{ what: 'флаги', match: /_banner$/, price: 20 },
	{ what: 'кровати', match: /_bed$/, price: 20 },
	{ what: 'цветы', match: /(_flower|_tulip|_orchid|_blossom|_lily|_daisy|_rose|_dandelion|_poppy|_allium|_violet)$/, price: 2 },
	{ what: 'кораллы', match: /(_coral|_coral_block|_coral_fan)$/, price: 5 },
];

// ---------------------------------------------------------------------------
// 2. Читаем данные Minecraft.
// ---------------------------------------------------------------------------
const items = fs.readdirSync(path.join(DATA, 'items'))
	.map((file) => 'minecraft:' + file.replace(/\.json$/, ''));

const recipes = fs.readdirSync(path.join(DATA, 'recipe'))
	.map((file) => JSON.parse(fs.readFileSync(path.join(DATA, 'recipe', file), 'utf8')));

// Теги: имя -> массив значений (значение = предмет или вложенный тег).
const rawTags = new Map();
const tagRoot = path.join(DATA, 'tags');
const collectTags = (dir, prefix) => {
	for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
		if (entry.isDirectory()) {
			collectTags(path.join(dir, entry.name), prefix + entry.name + '/');
		} else {
			const id = '#' + prefix + entry.name.replace(/\.json$/, '');
			const data = JSON.parse(fs.readFileSync(path.join(dir, entry.name), 'utf8'));
			rawTags.set(id, data.values ?? []);
		}
	}
};
collectTags(tagRoot, 'minecraft:');

const tagCache = new Map();
/** Разворачивает тег в список предметов (вложенные теги раскрываются). */
const resolveTag = (id, seen = new Set()) => {
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
};

// ---------------------------------------------------------------------------
// 3. Считаем цены.
// ---------------------------------------------------------------------------
const prices = new Map();
/** Явные цены и цены по шаблонам: из рецептов они НЕ пересчитываются. */
const explicit = new Set();

for (const item of items) {
	if (BASE[item] !== undefined) {
		prices.set(item, BASE[item]);
		explicit.add(item);
	}
}
// Шаблоны применяем только к предметам без явной цены.
for (const item of items) {
	if (prices.has(item)) continue;
	for (const pattern of PATTERNS) {
		if (pattern.match.test(item)) {
			prices.set(item, pattern.price);
			explicit.add(item);
			break;
		}
	}
}

/** Цена ингредиента: предмет, список вариантов или самый дешёвый предмет из тега. */
const ingredientCost = (ingredient) => {
	// Вариант «или то, или это» — берём самый дешёвый.
	if (Array.isArray(ingredient)) {
		let best = null;
		for (const option of ingredient) {
			const cost = ingredientCost(option);
			if (cost !== null && (best === null || cost < best)) best = cost;
		}
		return best;
	}
	if (typeof ingredient !== 'string') return null;
	if (ingredient.startsWith('#')) {
		let best = null;
		for (const member of resolveTag(ingredient)) {
			const price = prices.get(member);
			if (price !== undefined && (best === null || price < best)) best = price;
		}
		return best;
	}
	return prices.get(ingredient) ?? null;
};

/** Стоимость ингредиентов рецепта и его результат. null — если посчитать нельзя. */
const evaluate = (recipe) => {
	const sum = (list) => {
		let total = 0;
		for (const ingredient of list) {
			const cost = ingredientCost(ingredient);
			if (cost === null) return null;
			total += cost;
		}
		return total;
	};

	switch (recipe.type) {
		case 'minecraft:crafting_shapeless':
			return wrap(recipe, sum(recipe.ingredients));

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
			return wrap(recipe, sum(list));
		}

		case 'minecraft:stonecutting':
			return wrap(recipe, sum([recipe.ingredient]));

		case 'minecraft:smelting':
		case 'minecraft:blasting':
		case 'minecraft:smoking':
		case 'minecraft:campfire_cooking':
			// Переплавка дороже сырья на 15%, а не на 5%.
			return wrap(recipe, sum([recipe.ingredient]), SMELT_MARKUP);

		case 'minecraft:crafting_transmute':
		case 'minecraft:crafting_dye':
			return wrap(recipe, sum([recipe.input, recipe.material]));

		case 'minecraft:crafting_imbue':
			return wrap(recipe, sum([recipe.source, recipe.material]));

		case 'minecraft:crafting_decorated_pot':
			return wrap(recipe, sum([recipe.back, recipe.front, recipe.left, recipe.right]));

		case 'minecraft:smithing_transform':
			return wrap(recipe, sum([recipe.template, recipe.base, recipe.addition]));

		default:
			return null; // зелья, украшения брони и прочие особые рецепты считаем вручную
	}
};

const wrap = (recipe, cost, markup) => {
	if (cost === null || !recipe.result || !recipe.result.id) return null;
	return {
		cost,
		id: recipe.result.id,
		count: recipe.result.count ?? 1,
		markup: markup ?? CRAFT_MARKUP,
	};
};

// Несколько проходов: сначала считаются предметы из базовых, потом из уже посчитанных.
const MAX_PASSES = 40;
let passes = 0;
let converged = false;
let lastChangedItems = [];
for (let pass = 0; pass < MAX_PASSES; pass++) {
	passes = pass + 1;
	const changedItems = new Set();
	for (const recipe of recipes) {
		const info = evaluate(recipe);
		if (!info) continue;
		// Явную цену не поднимаем, но можем опустить: если предмет дешевле скрафтить,
		// чем купить его ингредиенты, оставлять высокую цену нельзя — игрок начнёт
		// покупать сырьё, крафтить и продавать, то есть печатать деньги.
		if (isLocked(info.id)) continue;
		const candidate = (info.cost * info.markup) / info.count;
		const current = prices.get(info.id);
		// Считаем улучшением только заметное изменение: некоторые рецепты (например,
		// дублирование шаблонов брони) сходятся бесконечно медленно, и без порога
		// расчёт никогда не закончится.
		const better = current === undefined || candidate < current - Math.max(1e-9, current * 1e-4);
		if (better) {
			prices.set(info.id, candidate);
			changedItems.add(info.id);
		}
	}
	lastChangedItems = [...changedItems];
	if (changedItems.size === 0) {
		converged = true;
		break;
	}
}

// Окисленная и вощёная медь получается без рецепта (предмет «стареет» сам),
// поэтому такие предметы стоят столько же, сколько обычный.
const OXIDIZED = /^minecraft:(?:waxed_)?(?:exposed_|weathered_|oxidized_)/;
for (const item of items) {
	if (prices.has(item)) continue;
	const plain = item.replace(OXIDIZED, 'minecraft:');
	if (plain !== item && prices.has(plain)) {
		prices.set(item, prices.get(plain));
		explicit.add(item);
	}
}

// ---------------------------------------------------------------------------
// Проверка на «печать денег»: если предмет можно продать дороже, чем стоит
// купить его ингредиенты, игрок сможет бесконечно покупать сырьё, крафтить
// и продавать готовое. Такие случаи надо править вручную.
//
// Порог CHEAT_MARGIN = 1,25: в отчёт попадает предмет, у которого крафт дешевле
// собственной цены более чем на четверть (то есть запас больше, чем обычная
// наценка крафта CRAFT_MARKUP). У всех обычных предметов цена и есть «стоимость
// крафта × 1,25», поэтому в отчёт попадают только закреплённые (LOCKED) предметы.
// ---------------------------------------------------------------------------
const CHEAT_MARGIN = 1.25;
const cheats = [];
for (const [item, price] of prices) {
	let cheapest = null;
	for (const recipe of recipes) {
		const info = evaluate(recipe);
		if (!info || info.id !== item) continue;
		const perItem = info.cost / info.count;
		if (cheapest === null || perItem < cheapest) cheapest = perItem;
	}
	if (cheapest !== null && cheapest * CHEAT_MARGIN < price * 0.999) {
		cheats.push({ item, price, craft: cheapest });
	}
}
cheats.sort((a, b) => b.price / b.craft - a.price / a.craft);
fs.writeFileSync('tools/craft-cheats.txt',
	cheats.map((c) => `${c.item}  цена=${Math.round(c.price)}  крафт=${Math.round(c.craft * 100) / 100}`).join('\n') + '\n', 'utf8');

// ---------------------------------------------------------------------------
// 4. Пишем результат.
// ---------------------------------------------------------------------------
const round = (value) => {
	if (value >= 1000) return Math.round(value);
	if (value >= 10) return Math.round(value * 10) / 10;
	return Math.round(value * 100) / 100;
};

const sorted = {};
const leftovers = [];
for (const item of [...items].sort()) {
	let price = prices.get(item);
	if (price === undefined) {
		price = JUNK_PRICE;
		if (BASE[item] === undefined) leftovers.push(item);
	}
	sorted[item] = round(price);
}

// Проверяем списки: id, которых нет в игре, в файл не попадают (иначе мод будет ругаться).
const itemSet = new Set(items);
const missingNotBuyable = NOT_BUYABLE.filter((id) => !itemSet.has(id));
const notBuyable = NOT_BUYABLE.filter((id) => itemSet.has(id));

const result = {
	prices: sorted,
	notBuyable: notBuyable,
};

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(result, null, '\t') + '\n', 'utf8');

// ---------------------------------------------------------------------------
// Лимиты торговца: почти все предметы — 30 стаков в день. Чем дороже предмет,
// тем он реже, поэтому торговец берёт его меньше. В файл пишем только отличия
// от общего лимита, чтобы список было удобно читать.
// ---------------------------------------------------------------------------
const limits = {};
for (const [item, price] of Object.entries(sorted)) {
	const tier = TRADER_LIMIT_TIERS.find((candidate) => price <= candidate.maxPrice);

	if (tier.stacks !== TRADER_DEFAULT_LIMIT) {
		limits[item] = tier.stacks;
	}
}

const LIMITS_OUT = path.join(path.dirname(OUT), 'trader_limits.json');
fs.writeFileSync(LIMITS_OUT, JSON.stringify({ default: TRADER_DEFAULT_LIMIT, limits }, null, '\t') + '\n', 'utf8');

// ---------------------------------------------------------------------------
// Время продажи на рынке: сколько секунд продаётся ОДНА штука предмета.
// Формула заказчика на словах: земля — пара секунд за штуку (стак — пара минут),
// элитры — несколько реальных часов. Разброс времени (от ×0,05 до ×10) и замедление
// от наценки считаются уже в моде — см. market/SaleTimes.java.
// ---------------------------------------------------------------------------
const SALE_TIME_BASE = 2.0;
const SALE_TIME_POWER = 0.35;
const SALE_TIME_MIN = 1.0;
const SALE_TIME_MAX = 6 * 60 * 60;

const marketTimes = {};
for (const [item, price] of Object.entries(sorted)) {
	const seconds = Math.min(SALE_TIME_MAX, Math.max(SALE_TIME_MIN, SALE_TIME_BASE * Math.pow(price, SALE_TIME_POWER)));
	marketTimes[item] = Math.round(seconds * 100) / 100;
}

const TIMES_OUT = path.join(path.dirname(OUT), 'market_times.json');
fs.writeFileSync(TIMES_OUT, JSON.stringify(marketTimes, null, '\t') + '\n', 'utf8');

// ---------------------------------------------------------------------------
// 5. Отчёт.
// ---------------------------------------------------------------------------
const byPrice = [...prices.values()];
console.log('предметов всего:      ' + items.length);
console.log('проходов расчёта:     ' + passes + (converged ? ' (сошлось)' : ' (НЕ сошлось!)'));
if (!converged) {
	console.log('  цены продолжают меняться у: ' + lastChangedItems.slice(0, 10).join(', '));
}
console.log('с явной ценой:        ' + explicit.size);
console.log('посчитано рецептами:  ' + (prices.size - explicit.size));
console.log('без цены (получили 1$): ' + leftovers.length);
console.log('нельзя купить:         ' + notBuyable.length);
if (missingNotBuyable.length > 0) {
	console.log('  в игре нет таких предметов (убраны из списка): ' + missingNotBuyable.join(', '));
}
console.log('выгодных перепродаж крафтом: ' + cheats.length + ' (см. tools/craft-cheats.txt)');
console.log('лимиты торговца: ' + Object.keys(limits).length + ' предметов с уменьшенным лимитом (файл ' + LIMITS_OUT + ')');
console.log('время продажи на рынке: ' + Object.keys(marketTimes).length + ' предметов (файл ' + TIMES_OUT + ')');
console.log('файл: ' + OUT);

const show = (title, list) => {
	console.log('\n=== ' + title + ' ===');
	for (const item of list) {
		console.log('  ' + item.padEnd(52) + sorted[item] + '$');
	}
};

show('ключевые цены', [
	'minecraft:dirt', 'minecraft:cobblestone', 'minecraft:oak_log', 'minecraft:oak_planks',
	'minecraft:coal', 'minecraft:iron_ingot', 'minecraft:gold_ingot', 'minecraft:diamond',
	'minecraft:diamond_block', 'minecraft:emerald', 'minecraft:netherite_ingot',
	'minecraft:netherite_block', 'minecraft:beacon', 'minecraft:end_crystal', 'minecraft:ender_pearl',
	'minecraft:shulker_shell', 'minecraft:shulker_box', 'minecraft:totem_of_undying',
	'minecraft:nether_star', 'minecraft:dragon_egg', 'minecraft:elytra', 'minecraft:spawner',
	'minecraft:zombie_spawn_egg', 'minecraft:stone_bricks', 'minecraft:stick', 'minecraft:torch',
	'minecraft:diamond_sword', 'minecraft:iron_pickaxe', 'minecraft:anvil', 'minecraft:enchanting_table',
]);

console.log('\n=== без цены (проверить: должны быть только креативные) ===');
console.log('  ' + leftovers.join('\n  '));
fs.writeFileSync('tools/leftovers.txt', leftovers.join('\n') + '\n', 'utf8');

const top = Object.entries(sorted).sort((a, b) => b[1] - a[1]).slice(0, 15);
console.log('\n=== самые дорогие ===');
for (const [item, price] of top) console.log('  ' + item.padEnd(52) + price + '$');
