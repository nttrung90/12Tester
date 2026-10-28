import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { calculateMonthlyEgressAndCost, OFFICIAL_PRICING_2026_10 } from './calculate-tco.mjs';

describe('TCO Calculator Tests (E05)', () => {
    test('Bảng giá đầu vào chính thức có ngày xác định và không bị rỗng', () => {
        assert.equal(OFFICIAL_PRICING_2026_10.sourceDate, '2026-10-28');
        assert.ok(OFFICIAL_PRICING_2026_10.gcsEgressPerGb > 0);
        assert.ok(OFFICIAL_PRICING_2026_10.cdnEdgeEgressPerGb > 0);
    });

    test('E01 và E02 chứng minh giảm egress so với baseline', () => {
        const res = calculateMonthlyEgressAndCost({
            reportsPerDay: 2000,
            iconViewsPerDay: 15000,
            fullOpenRatio_p: 0.20,
        });

        assert.ok(res.optimizedE01.monthlyEgressGb < res.baseline.monthlyEgressGb, 'E01 phải giảm egress');
        assert.ok(res.optimizedE02.monthlyEgressGb < res.optimizedE01.monthlyEgressGb, 'E02 thumbnail phải giảm tiếp egress');
        assert.ok(res.optimizedE02.reductionPercent > 30, 'E02 phải giảm trên 30% egress so với baseline');
    });

    test('Quyết định Go/No-Go cho CDN: Traffic thấp -> NO_GO', () => {
        const lowTraffic = calculateMonthlyEgressAndCost({
            reportsPerDay: 100,
            iconViewsPerDay: 500, // Rất ít request icon
        });

        assert.equal(lowTraffic.e05CdnAnalysis.isRoiPositive, false, 'Traffic icon thấp chưa bù được phí request/fill của CDN');
        assert.match(lowTraffic.e05CdnAnalysis.recommendation, /NO_GO/);
    });

    test('Quyết định Go/No-Go cho CDN: Traffic cao và kích thước lớn -> GO_CONDITIONAL', () => {
        const highTrafficLargeAsset = calculateMonthlyEgressAndCost({
            reportsPerDay: 5000,
            iconViewsPerDay: 100000,
            iconImageKb: 120, // Asset kích thước lớn (banners/badges 120KB)
        });

        assert.equal(highTrafficLargeAsset.e05CdnAnalysis.isRoiPositive, true, 'Asset lớn đủ để bù đắp phí request CDN');
        assert.match(highTrafficLargeAsset.e05CdnAnalysis.recommendation, /GO_CONDITIONAL/);
    });
});
