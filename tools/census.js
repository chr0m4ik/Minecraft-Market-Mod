// Разведка: какие бывают типы рецептов и как выглядят их поля в 26.3.
// Запуск: node tools/census.js
const fs = require('fs');
const path = require('path');

const DATA = process.env.MC_DATA || 'Z:/gradle-home/_mcdata';
const dir = path.join(DATA, 'recipe');

const byType = new Map();
const samples = new Map();

for (const file of fs.readdirSync(dir)) {
	const recipe = JSON.parse(fs.readFileSync(path.join(dir, file), 'utf8'));
	const type = recipe.type;
	byType.set(type, (byType.get(type) ?? 0) + 1);
	if (!samples.has(type)) {
		samples.set(type, { file, keys: Object.keys(recipe), recipe });
	}
}

console.log('=== типы рецептов ===');
for (const [type, count] of [...byType.entries()].sort((a, b) => b[1] - a[1])) {
	console.log(String(count).padStart(5), type);
}

console.log('\n=== пример каждого типа (поля) ===');
for (const [type, info] of samples) {
	console.log('\n--- ' + type + ' (' + info.file + ') ---');
	console.log('поля: ' + info.keys.join(', '));
	console.log(JSON.stringify(info.recipe).slice(0, 400));
}

// сколько предметов и тегов
const items = fs.readdirSync(path.join(DATA, 'items'));
console.log('\nпредметов: ' + items.length);

const tags = [];
const walk = (d, prefix) => {
	for (const e of fs.readdirSync(d, { withFileTypes: true })) {
		if (e.isDirectory()) walk(path.join(d, e.name), prefix + e.name + '/');
		else tags.push('#' + prefix + e.name.replace(/\.json$/, ''));
	}
};
walk(path.join(DATA, 'tags'), 'minecraft:');
console.log('тегов: ' + tags.length);
