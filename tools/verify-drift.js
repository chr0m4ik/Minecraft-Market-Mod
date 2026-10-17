// Проверка микро-дрейфа цен (price/PriceDrift.java) без запуска игры.
//
// Зачем: формула дрейфа должна (1) всегда оставаться в пределах ±3% и (2) давать
// одинаковые числа на сервере и на клиенте. В игре её считает один и тот же класс,
// поэтому достаточно проверить саму формулу на больших объёмах.
//
// Важно: ниже формула повторена по строкам из PriceDrift.java (drift + itemPhase + itemIdHash).
// Если PriceDrift.java изменится, эту функцию нужно поправить так же — в конце есть
// проверка контрольных значений (REAL_VALUES), она это заметит.
//
// Запуск: node tools/verify-drift.js
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const RESOURCES = join(here, '..', 'src', 'main', 'resources', 'data', 'marketplace');

// --- Настройки: берём из data/marketplace/economy.json, как Java (EconomyConfig) ---
const economy = JSON.parse(readFileSync(join(RESOURCES, 'economy.json'), 'utf8'));
const AMPLITUDE = economy.drift_amplitude ?? 0.03;
const BUY_MULTIPLIER = economy.buy_multiplier ?? 2.2;
const TRADER_MULTIPLIER = economy.trader_multiplier ?? 0.75;
const WAVES = [
	{ weight: 0.50, period: 24_000, phaseSalt: 0x9E37 },
	{ weight: 0.30, period: 8_000, phaseSalt: 0x85EB },
	{ weight: 0.20, period: 2_400, phaseSalt: 0xC2B2 },
];
const TOTAL_WEIGHT = WAVES.reduce((sum, wave) => sum + wave.weight, 0);

/** Сколько тиков в игровой секунде: множитель обновляется раз в секунду (PriceDrift.update). */
const TICKS_PER_SECOND = 20;

/** Округление игрового времени вниз до целой секунды — как PriceDrift.quantize. */
const quantize = (gameTime) => Math.floor(gameTime / TICKS_PER_SECOND) * TICKS_PER_SECOND;

/** Хеш строки в Java: s[0]*31^(n-1) + … + s[n-1], с переполнением как у int. */
function javaStringHash(text) {
	let hash = 0;

	for (let i = 0; i < text.length; i++) {
		hash = (Math.imul(hash, 31) + text.charCodeAt(i)) | 0;
	}

	return hash;
}

/** Сдвиг фазы предмета — повторяет PriceDrift.itemIdHash + itemPhase. */
function itemPhase(id, wave, seed) {
	// Зерно мира в Java — long, а в формуле участвуют только младшие 32 бита.
	const world = Number(BigInt.asIntN(32, BigInt(seed)));
	let mixed = Math.imul(world, 0x27D4EB2D) + wave.phaseSalt;
	mixed ^= mixed >>> 15;
	mixed = Math.imul(mixed, 0x2545F491);
	mixed ^= mixed >>> 13;

	return (mixed + Math.imul(javaStringHash(id), 0x27D4EB2D)) | 0;
}

/** Множитель дрейфа — повторяет PriceDrift.drift + multiplier (с округлением времени до секунды). */
function multiplier(id, gameTime, seed) {
	const time = quantize(gameTime);
	let sum = 0;

	for (const wave of WAVES) {
		const phase = ((time % wave.period) + wave.phaseSalt + itemPhase(id, wave, seed)) | 0;
		sum += wave.weight * Math.sin(2 * Math.PI * phase / wave.period);
	}

	return 1 + AMPLITUDE * sum / TOTAL_WEIGHT;
}

// Контрольные значения, снятые с настоящего сервера командой /market debug driftdump
// на мире с зерном -8485521596771069071 при амплитуде 0,03 (то есть при текущей).
// Если формула здесь разойдётся с PriceDrift.java (волны, веса, соли, округление времени
// до секунды), проверка сразу это покажет.
//
// Поправка на амплитуду: волнистая часть формулы не зависит от амплитуды, а сама амплитуда —
// общий множитель, поэтому при другой амплитуде значения пересчитываются точно:
// ожидаемое = 1 + (записанное − 1) × амплитуда / 0,03.
const RECORDED_AMPLITUDE = 0.03;
const REAL_SEED = -8485521596771069071n;
const REAL_VALUES = [
	{ id: 'minecraft:diamond', time: 0, expected: 1.004718 },
	{ id: 'minecraft:apple', time: 0, expected: 1.018301 },
	{ id: 'minecraft:netherite_ingot', time: 0, expected: 0.981085 },
	{ id: 'minecraft:coal', time: 0, expected: 0.986206 },
	{ id: 'minecraft:shulker_box', time: 0, expected: 0.987657 },
	{ id: 'minecraft:diamond', time: 72_000, expected: 1.004718 },
	{ id: 'minecraft:apple', time: 72_000, expected: 1.018301 },
	{ id: 'minecraft:netherite_ingot', time: 72_000, expected: 0.981085 },
	{ id: 'minecraft:coal', time: 72_000, expected: 0.986206 },
	{ id: 'minecraft:shulker_box', time: 72_000, expected: 0.987657 },
	{ id: 'minecraft:diamond', time: 583_780, expected: 0.985498 },
	{ id: 'minecraft:apple', time: 583_780, expected: 0.998451 },
	{ id: 'minecraft:netherite_ingot', time: 583_780, expected: 1.002388 },
	{ id: 'minecraft:coal', time: 583_780, expected: 1.008993 },
	{ id: 'minecraft:shulker_box', time: 583_780, expected: 1.009646 },
];

/** Пересчёт записанного значения на текущую амплитуду. */
const scale = (recorded) => 1 + (recorded - 1) * AMPLITUDE / RECORDED_AMPLITUDE;

const seed = REAL_SEED;
const items = [
	{ id: 'minecraft:diamond', price: 5000 },
	{ id: 'minecraft:apple', price: 250 },
	{ id: 'minecraft:netherite_ingot', price: 105513 },
	{ id: 'minecraft:coal', price: 50 },
	{ id: 'minecraft:shulker_box', price: 100000000 },
];
const times = [0, 72_000, 583_780];

console.log('=== Множители дрейфа (5 предметов × 3 момента времени) ===');
console.log(`зерно мира: ${seed}, амплитуда ±${(AMPLITUDE * 100).toFixed(1)}%`);
console.log(`предмет                     тик      множитель    отклонение      покупка x${BUY_MULTIPLIER}     торговец x${TRADER_MULTIPLIER}`);

for (const time of times) {
	for (const item of items) {
		const m = multiplier(item.id, time, seed);
		const buy = item.price * m * BUY_MULTIPLIER;
		const trader = item.price * m * TRADER_MULTIPLIER;

		console.log(`${item.id.padEnd(27)} ${String(time).padStart(6)}  x${m.toFixed(6)}  `
			+ `${((m - 1) * 100).toFixed(3).padStart(8)}%  ${buy.toFixed(2).padStart(16)}  ${trader.toFixed(2).padStart(16)}`);
	}
}

console.log('\n=== Проверка контрольных значений с сервера ===');
console.log(`записаны при амплитуде ±${(RECORDED_AMPLITUDE * 100).toFixed(1)}%, пересчитаны на текущую`);
let exact = true;

for (const check of REAL_VALUES) {
	const value = multiplier(check.id, check.time, REAL_SEED);
	const expected = scale(check.expected);
	const ok = Math.abs(value - expected) < 5e-7; // на сервере числа печатались с 6 знаками
	exact = exact && ok;
	console.log(`${check.id} @ ${check.time}: здесь x${value.toFixed(6)}, ожидается x${expected.toFixed(6)} — ${ok ? 'совпало' : 'РАСХОЖДЕНИЕ'}`);
}

console.log('\n=== Полный перебор: все предметы прайса × полный цикл волн ===');
const prices = JSON.parse(readFileSync(join(here, '..', 'src', 'main', 'resources', 'data', 'marketplace', 'prices.json'), 'utf8'));
const ids = Object.keys(prices.prices);
const seeds = [REAL_SEED, 0n, -1n, 987654321987n];
let min = Infinity;
let max = -Infinity;
let minAt = '';
let maxAt = '';
let sum = 0;
let count = 0;

for (const currentSeed of seeds) {
	// Самая быстрая волна повторяется каждые 2400 тиков: 3 игровых дня покрывают её целиком.
	// Шаг 100 тиков кратен секунде (20 тиков), поэтому проверяются те же значения, что и в игре.
	for (const id of ids) {
		for (let time = 0; time < 24_000 * 3; time += 100) {
			const m = multiplier(id, time, currentSeed);

			if (m < min) { min = m; minAt = `${id} @ t=${time}, зерно ${currentSeed}`; }
			if (m > max) { max = m; maxAt = `${id} @ t=${time}, зерно ${currentSeed}`; }			sum += m;
			count++;
		}
	}
}

const limitLow = 1 - AMPLITUDE;
const limitHigh = 1 + AMPLITUDE;
console.log(`предметов: ${ids.length}, зёрен: ${seeds.length}, замеров: ${count}`);
console.log(`минимум:  x${min.toFixed(6)} (${((min - 1) * 100).toFixed(4)}%)  ${minAt}`);
console.log(`максимум: x${max.toFixed(6)} (${((max - 1) * 100).toFixed(4)}%)  ${maxAt}`);
console.log(`средний множитель: x${(sum / count).toFixed(6)}`);
console.log(`допустимо: x${limitLow.toFixed(4)} … x${limitHigh.toFixed(4)} — `
	+ `${min >= limitLow - 1e-12 && max <= limitHigh + 1e-12 ? 'ВЫПОЛНЕНО' : 'НАРУШЕНО'}`);

if (!exact) {
	console.error('\nОШИБКА: формула разошлась с PriceDrift.java — обновите её здесь.');
	process.exit(1);
}
