import test from 'node:test';
import assert from 'node:assert/strict';
import { cleanupStorage, ALLOWED_BUCKET } from '../../backend/storage-cleanup/cleanup-reports-storage.mjs';

const now = new Date('2026-10-28T00:00:00Z');
const logger = { info() {}, warn() {}, error() {} };

async function run(parentAge, code) {
  let deleted = false;
  const thumb = {
    name: 'reports/user/report/thumb_upload.jpg',
    metadata: { timeCreated: '2026-10-27T00:00:00Z', generation: '17', size: '100' },
    async delete(options) { assert.equal(options.ifGenerationMatch, '17'); deleted = true; }
  };
  const storage = { bucket(name) {
    assert.equal(name, ALLOWED_BUCKET);
    return {
      async getFiles() { return [[thumb]]; },
      file(name) {
        assert.equal(name, 'reports/user/report/upload.jpg');
        return { async getMetadata() {
          if (code) throw Object.assign(new Error('parent lookup'), { code });
          return [{ timeCreated: new Date(now.getTime() - parentAge * 86400000).toISOString() }];
        }};
      }
    };
  }};
  let error;
  try { await cleanupStorage({ storage, now, dryRun: false, logger }); }
  catch (e) { error = e; }
  return { deleted, error };
}

test('orphan thumbnail is removed when parent lookup confirms 404', async () => {
  assert.equal((await run(null, 404)).deleted, true);
});
test('parent outside current listing still supplies retention age', async () => {
  assert.equal((await run(31)).deleted, true);
});
test('young parent outside current listing does not lose its thumbnail', async () => {
  assert.equal((await run(1)).deleted, false);
});
test('parent permission failure is not treated as missing parent', async () => {
  assert.equal((await run(null, 403)).deleted, false);
});
