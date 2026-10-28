import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { runTokenRevocationPreflight } from './dry-run-token-revocation.mjs';

describe('Dry-run Token Revocation Preflight Tests (E04)', () => {
    test('Chặn thu hồi nếu tỷ lệ người dùng app mới chưa đạt 95%', async () => {
        const res = await runTokenRevocationPreflight({
            minNewVersionAdoptionPercent: 95,
            currentNewVersionAdoptionPercent: 88, // Mới có 88%
            logger: { info: () => {}, warn: () => {} },
        });

        assert.equal(res.safeToRevoke, false);
        assert.match(res.reason, /Ty le client phien ban moi/);
        assert.equal(res.tokensToRevoke, 0);
    });

    test('Chặn thu hồi nếu tỷ lệ lỗi của Media Gateway vượt ngưỡng 1%', async () => {
        const res = await runTokenRevocationPreflight({
            minNewVersionAdoptionPercent: 95,
            currentNewVersionAdoptionPercent: 98,
            gatewayErrorRatePercent: 1.5, // 1.5% > 1.0%
            maxAllowedErrorRatePercent: 1.0,
            logger: { info: () => {}, warn: () => {} },
        });

        assert.equal(res.safeToRevoke, false);
        assert.match(res.reason, /Ty le loi cua Media Gateway/);
        assert.equal(res.tokensToRevoke, 0);
    });

    test('Cho phép dry-run tính toán khi tất cả tiền điều kiện đạt chuẩn', async () => {
        const manifestData = {
            generatedAt: new Date().toISOString(),
            bucket: 'cross-testers.firebasestorage.app',
            items: [
                { bucket: 'cross-testers.firebasestorage.app', name: 'reports/c1/u1/1.jpg', generation: '101', hasDownloadToken: true, isExpired: true },
                { bucket: 'cross-testers.firebasestorage.app', name: 'reports/c1/u1/2.jpg', generation: '102', hasDownloadToken: true, isExpired: false },
                { bucket: 'cross-testers.firebasestorage.app', name: 'reports/c1/u1/3.jpg', generation: '103', hasDownloadToken: false, isExpired: true },
            ]
        };
        const res = await runTokenRevocationPreflight({
            minNewVersionAdoptionPercent: 95,
            currentNewVersionAdoptionPercent: 99,
            gatewayErrorRatePercent: 0.1,
            maxAllowedErrorRatePercent: 1.0,
            dryRun: true,
            manifestData,
            logger: { info: () => {}, warn: () => {} },
        });

        assert.equal(res.safeToRevoke, true);
        assert.equal(res.dryRun, true);
        assert.equal(res.scannedTokensCount, 3);
        assert.equal(res.tokensToRevoke, 1);
    });
});
