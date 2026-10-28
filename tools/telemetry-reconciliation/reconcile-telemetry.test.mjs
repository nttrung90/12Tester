import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { reconcileCohortTelemetry } from './reconcile-telemetry.mjs';

describe('Telemetry Reconciliation Tool Tests (E00 Rollout)', () => {
    test('Đối chiếu chính xác khi lượng chênh lệch trong biên độ cho phép (dưới 20%)', () => {
        const clientLogs = [
            { clientVersion: '1.2.0', cohort: 'v12_optimized', requestCount: 1000, bodyBytesRead: 85000000, memoryHits: 450 },
            { clientVersion: '1.1.0', cohort: 'v11_legacy', requestCount: 200, bodyBytesRead: 10000000, memoryHits: 10 },
        ];
        // Cloud báo 100 MB, client báo 95 MB -> chênh lệch 5% (do headers & overhead)
        const cloudMetrics = { sentBytesCount: 100000000, requestCount: 1250 };

        const res = reconcileCohortTelemetry({ clientLogs, cloudStorageMetrics: cloudMetrics });

        assert.equal(res.summary.clientReportedBodyBytes, 95000000);
        assert.equal(res.summary.unclassifiedExternalBytes, 5000000);
        assert.equal(res.summary.unclassifiedPercent, 5.0);
        assert.equal(res.summary.isAlertTriggered, false);
        assert.equal(res.cohortBreakdown.length, 2);
    });

    test('Phát cảnh báo khi lưu lượng ngoài app (unclassified) vượt quá 20%', () => {
        const clientLogs = [
            { clientVersion: '1.2.0', cohort: 'v12_optimized', requestCount: 500, bodyBytesRead: 40000000, memoryHits: 200 },
        ];
        // Cloud báo 100 MB nhưng client chỉ báo 40 MB -> 60% chênh lệch (lộ token / tải ngoài app)
        const cloudMetrics = { sentBytesCount: 100000000, requestCount: 1500 };

        const res = reconcileCohortTelemetry({ clientLogs, cloudStorageMetrics: cloudMetrics });

        assert.equal(res.summary.unclassifiedPercent, 60.0);
        assert.equal(res.summary.isAlertTriggered, true);
        assert.match(res.explanation.actionRequired, /ALERT/);
    });
});
