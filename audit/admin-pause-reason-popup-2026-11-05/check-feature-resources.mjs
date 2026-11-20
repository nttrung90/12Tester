import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';

const root = new URL('../../', import.meta.url);
function resources(relative) {
  const xml = readFileSync(new URL(relative, root), 'utf8');
  return new Map([...xml.matchAll(/<string\b[^>]*\bname="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)]
    .map(match => [match[1], match[2]]));
}
const locales = ['values', 'values-vi', 'values-en'];
const data = locales.map(locale => {
  const path = `app/src/main/res/${locale}/strings.xml`;
  const current = resources(path);
  const before = resources(`audit/admin-pause-reason-popup-2026-11-05/before/${path}`);
  const added = [...current.keys()].filter(key => !before.has(key)).sort();
  return { locale, current, added };
});
const baseline = data[0];
for (const entry of data) {
  assert.deepEqual(entry.added, baseline.added, `Added keys differ for ${entry.locale}`);
  for (const key of baseline.added) {
    const signature = text => [...text.matchAll(/%(?:(\d+)\$)?[sd]/g)].map(match => match[0]).sort();
    assert.deepEqual(signature(entry.current.get(key)), signature(baseline.current.get(key)),
      `Formatting placeholders differ for ${entry.locale}/${key}`);
  }
}
const result = { status: 'passed', locales, addedKeys: baseline.added, count: baseline.added.length };
writeFileSync(new URL('./resource-validation.json', import.meta.url), JSON.stringify(result, null, 2));
console.log(`PASS: ${result.count} added resource keys and placeholders match across ${locales.join(', ')}.`);
