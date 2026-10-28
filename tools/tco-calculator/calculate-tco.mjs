#!/usr/bin/env node

/**
 * Công cụ tính toán Tổng chi phí Sở hữu (TCO) và ROI tối ưu Firebase Storage Egress (E05).
 *
 * Nguồn giá chính thức đối chiếu ngày 2026-10:
 * - Google Cloud Storage Pricing: https://cloud.google.com/storage/pricing
 *   + Internet Egress (Asia-Southeast1): $0.12 / GiB
 *   + Always Free tier (nếu cấu hình bucket đủ điều kiện): 100 GiB/tháng
 *   + Class A Operations (Upload/Object Create): $0.05 / 10,000 ops
 *   + Class B Operations (Get/Download): $0.004 / 10,000 ops
 *   + Storage: $0.020 / GiB-month
 * - Cloud CDN Pricing: https://cloud.google.com/cdn/pricing
 *   + Edge Cache Data Transfer Out (to Internet): $0.08 / GiB
 *   + Cache Fill Egress (từ Cloud Storage đến CDN Edge): $0.01 / GiB (GCS transfer sang Cloud CDN được $0.00/GB)
 *   + CDN HTTP Request Fee: $0.0075 / 10,000 requests
 *   + Fixed Overhead (Forwarding rule / Load Balancer IP & SSL cert): ~$5.00 - $18.00 / tháng
 *
 * F15 Hiệu chỉnh:
 * 1. Áp dụng đúng hạn mức gcsAlwaysFreeEgressGb vào phần tính billable egress của GCS.
 * 2. Sửa công thức Cloud CDN: Miss trả phí CDN data transfer out ($0.08/GB) + Cache fill ($0.01/GB);
 *    GCS transfer sang Cloud CDN nội bộ là $0.00 (không cộng hai lần regular Internet egress).
 * 3. Tham số hóa rõ ràng các tỷ lệ và ghi nhận kết quả dưới dạng Kịch bản giả định (Hypothetical Scenario).
 */

export const OFFICIAL_PRICING_2026_10 = {
    sourceDate: '2026-10-28',
    sourceUrls: [
        'https://cloud.google.com/storage/pricing',
        'https://cloud.google.com/cdn/pricing',
    ],
    gcsAlwaysFreeEgressGb: 100, // Always Free allowance nếu áp dụng
    gcsEgressPerGb: 0.12,
    gcsClassAPer10k: 0.05,
    gcsClassBPer10k: 0.004,
    gcsStoragePerGbMonth: 0.02,
    cdnEdgeEgressPerGb: 0.08,
    cdnCacheFillEgressPerGb: 0.01,
    cdnRequestPer10k: 0.0075,
    cdnMonthlyFixedOverheadUsd: 5.0,
};

export function calculateMonthlyEgressAndCost({
    reportsPerDay = 1000,
    iconViewsPerDay = 10000,
    fullOpenRatio_p = 0.20, // 20% người dùng bấm mở ảnh full sau khi xem preview
    duplicateDHashRatio = 0.30, // 30% dHash tải trùng đồng thời
    tabSwitchRatio = 0.15, // 15% đổi tab so sánh tải lại
    cdnHitRatio = 0.85, // 85% cache hit trên edge CDN
    fullImageKb = 250,
    thumbImageKb = 35,
    iconImageKb = 15,
    pricing = OFFICIAL_PRICING_2026_10,
    daysPerMonth = 30,
    applyAlwaysFree = false, // R2-12: Mặc định KHÔNG áp dụng Always Free vì Asia-Southeast1 không thuộc diện Always Free
} = {}) {
    // 1. Thẩm định tham số đầu vào (R2-12: Chặn số âm, NaN và tỷ lệ > 1.0)
    const numericInputs = { reportsPerDay, iconViewsPerDay, fullImageKb, thumbImageKb, iconImageKb, daysPerMonth };
    for (const [key, val] of Object.entries(numericInputs)) {
        if (typeof val !== 'number' || !Number.isFinite(val) || val < 0) {
            throw new TypeError(`INVALID_ARGUMENT: ${key} phải là số dương hữu hạn, nhận được: ${val}`);
        }
    }
    const ratioInputs = { fullOpenRatio_p, duplicateDHashRatio, tabSwitchRatio, cdnHitRatio };
    for (const [key, val] of Object.entries(ratioInputs)) {
        if (typeof val !== 'number' || !Number.isFinite(val) || val < 0 || val > 1.0) {
            throw new RangeError(`INVALID_ARGUMENT: ${key} phải là tỷ lệ trong khoảng [0.0, 1.0], nhận được: ${val}`);
        }
    }

    const kbToGb = 1 / (1024 * 1024);

    function computeGcsCost(totalEgressGb, classBOps, classAOps = 0) {
        const freeAllowance = applyAlwaysFree ? Math.max(0, Number(pricing.gcsAlwaysFreeEgressGb || 0)) : 0;
        const billableEgressGb = Math.max(0, totalEgressGb - freeAllowance);
        const egressCost = billableEgressGb * pricing.gcsEgressPerGb;
        const classBCost = (classBOps / 10000) * pricing.gcsClassBPer10k;
        const classACost = (classAOps / 10000) * pricing.gcsClassAPer10k;
        return {
            totalEgressGb,
            billableEgressGb,
            freeAllowanceUsedGb: Math.min(totalEgressGb, freeAllowance),
            costUsd: egressCost + classBCost + classACost,
        };
    }

    // =========================================================================
    // 1. BASELINE (Trước tối ưu):
    // =========================================================================
    const baselineDailyReportGb = reportsPerDay * (
        fullImageKb +
        (fullOpenRatio_p * fullImageKb) +
        (duplicateDHashRatio * fullImageKb) +
        (tabSwitchRatio * fullImageKb * 2)
    ) * kbToGb;

    const baselineDailyIconGb = iconViewsPerDay * iconImageKb * kbToGb;
    const baselineMonthlyEgressGb = (baselineDailyReportGb + baselineDailyIconGb) * daysPerMonth;
    const baselineMonthlyClassBOps = (reportsPerDay * (1 + fullOpenRatio_p + duplicateDHashRatio + (tabSwitchRatio * 2)) + iconViewsPerDay) * daysPerMonth;
    const baselineCalc = computeGcsCost(baselineMonthlyEgressGb, baselineMonthlyClassBOps, 0);

    // =========================================================================
    // 2. VỚI E01 (Single-flight, RAM cache, dHash sharing, Comparison memory reuse):
    // =========================================================================
    const e01DailyReportGb = reportsPerDay * fullImageKb * kbToGb;
    const e01MonthlyEgressGb = (e01DailyReportGb + baselineDailyIconGb) * daysPerMonth;
    const e01MonthlyClassBOps = (reportsPerDay + iconViewsPerDay) * daysPerMonth;
    const e01Calc = computeGcsCost(e01MonthlyEgressGb, e01MonthlyClassBOps, 0);

    // =========================================================================
    // 3. VỚI E02 (Thumbnail file thật):
    // =========================================================================
    const e02DailyReportGb = reportsPerDay * (
        thumbImageKb +
        (fullOpenRatio_p * fullImageKb)
    ) * kbToGb;

    const e02MonthlyEgressGb = (e02DailyReportGb + baselineDailyIconGb) * daysPerMonth;
    const e02MonthlyClassBOps = (reportsPerDay * (1 + fullOpenRatio_p) + iconViewsPerDay) * daysPerMonth;
    const e02MonthlyClassAOps = reportsPerDay * daysPerMonth; // Upload thumbnail
    const e02Calc = computeGcsCost(e02MonthlyEgressGb, e02MonthlyClassBOps, e02MonthlyClassAOps);

    // =========================================================================
    // 4. VỚI E05 (Icon CDN Routing PoC):
    // Theo chuẩn Cloud CDN:
    // - Toàn bộ request qua CDN chịu CDN Request Fee ($0.0075 / 10k).
    // - Tất cả dữ liệu xuất ra Internet chịu CDN Data Transfer Out ($0.08 / GB).
    // - Với phần cache miss ((1 - hitRatio) * iconGb): Phụ phí Cache Fill từ GCS là $0.01 / GB
    //   (GCS network egress sang Google Cloud CDN là $0.00/GB, không tính phí regular $0.12/GB).
    // - Miss phát sinh Class B operation từ Cloud Storage origin.
    // - Chi phí cố định: Load Balancer forwarding rule & TLS cert (~$5.00/tháng).
    // =========================================================================
    const iconMonthlyGb = baselineDailyIconGb * daysPerMonth;
    const iconMonthlyRequests = iconViewsPerDay * daysPerMonth;
    const cdnHitGb = iconMonthlyGb * cdnHitRatio;
    const cdnMissGb = iconMonthlyGb * (1 - cdnHitRatio);
    const cdnMissOps = iconMonthlyRequests * (1 - cdnHitRatio);

    const cdnEgressCost = iconMonthlyGb * pricing.cdnEdgeEgressPerGb;
    const cdnCacheFillCost = cdnMissGb * pricing.cdnCacheFillEgressPerGb;
    const cdnOriginGcsOpsCost = (cdnMissOps / 10000) * pricing.gcsClassBPer10k;
    const cdnRequestCost = (iconMonthlyRequests / 10000) * pricing.cdnRequestPer10k;
    const cdnFixedOverhead = Number(pricing.cdnMonthlyFixedOverheadUsd || 0);

    const cdnTotalIconCost = cdnEgressCost + cdnCacheFillCost + cdnOriginGcsOpsCost + cdnRequestCost + cdnFixedOverhead;

    // Chi phí origin thuần túy cho icon qua Cloud Storage:
    // Nếu Always Free được áp dụng, phần free allowance còn dư sau khi trừ lưu lượng reports sẽ được áp dụng cho icons
    const freeAllowance = applyAlwaysFree ? Math.max(0, Number(pricing.gcsAlwaysFreeEgressGb || 0)) : 0;
    const baselineMonthlyReportGb = baselineDailyReportGb * daysPerMonth;
    const remainingFreeAllowanceForIcons = Math.max(0, freeAllowance - baselineMonthlyReportGb);
    const billableOriginIconGb = Math.max(0, iconMonthlyGb - remainingFreeAllowanceForIcons);
    const originTotalIconCost = (billableOriginIconGb * pricing.gcsEgressPerGb) +
        (iconMonthlyRequests / 10000) * pricing.gcsClassBPer10k;

    const cdnMonthlySavings = originTotalIconCost - cdnTotalIconCost;
    const isCdnRoiPositive = cdnMonthlySavings > 0;

    return {
        scenarioType: 'hypothetical_scenario',
        disclaimer: 'Kết quả dựa trên các thông số kịch bản giả định (hypothetical scenario), chưa phải kết luận theo số đo baseline production thực tế.',
        inputs: {
            reportsPerDay,
            iconViewsPerDay,
            fullOpenRatio_p,
            duplicateDHashRatio,
            tabSwitchRatio,
            cdnHitRatio,
            fullImageKb,
            thumbImageKb,
            iconImageKb,
        },
        pricing,
        baseline: {
            monthlyEgressGb: Math.round(baselineMonthlyEgressGb * 100) / 100,
            billableEgressGb: Math.round(baselineCalc.billableEgressGb * 100) / 100,
            monthlyCostUsd: Math.round(baselineCalc.costUsd * 100) / 100,
        },
        optimizedE01: {
            monthlyEgressGb: Math.round(e01MonthlyEgressGb * 100) / 100,
            billableEgressGb: Math.round(e01Calc.billableEgressGb * 100) / 100,
            monthlyCostUsd: Math.round(e01Calc.costUsd * 100) / 100,
            monthlySavingsUsd: Math.round((baselineCalc.costUsd - e01Calc.costUsd) * 100) / 100,
            reductionPercent: Math.round(((baselineMonthlyEgressGb - e01MonthlyEgressGb) / baselineMonthlyEgressGb) * 1000) / 10,
        },
        optimizedE02: {
            monthlyEgressGb: Math.round(e02MonthlyEgressGb * 100) / 100,
            billableEgressGb: Math.round(e02Calc.billableEgressGb * 100) / 100,
            monthlyCostUsd: Math.round(e02Calc.costUsd * 100) / 100,
            monthlySavingsUsd: Math.round((baselineCalc.costUsd - e02Calc.costUsd) * 100) / 100,
            reductionPercent: Math.round(((baselineMonthlyEgressGb - e02MonthlyEgressGb) / baselineMonthlyEgressGb) * 1000) / 10,
        },
        withE01: {
            monthlyEgressGb: Math.round(e01MonthlyEgressGb * 100) / 100,
            billableEgressGb: Math.round(e01Calc.billableEgressGb * 100) / 100,
            monthlyCostUsd: Math.round(e01Calc.costUsd * 100) / 100,
            monthlySavingsUsd: Math.round((baselineCalc.costUsd - e01Calc.costUsd) * 100) / 100,
            egressReductionPercent: Math.round(((baselineMonthlyEgressGb - e01MonthlyEgressGb) / baselineMonthlyEgressGb) * 1000) / 10,
        },
        withE02: {
            monthlyEgressGb: Math.round(e02MonthlyEgressGb * 100) / 100,
            billableEgressGb: Math.round(e02Calc.billableEgressGb * 100) / 100,
            monthlyCostUsd: Math.round(e02Calc.costUsd * 100) / 100,
            monthlySavingsUsd: Math.round((baselineCalc.costUsd - e02Calc.costUsd) * 100) / 100,
            egressReductionPercent: Math.round(((baselineMonthlyEgressGb - e02MonthlyEgressGb) / baselineMonthlyEgressGb) * 1000) / 10,
        },
        e05CdnAnalysis: {
            iconMonthlyGb: Math.round(iconMonthlyGb * 100) / 100,
            iconMonthlyRequests,
            originIconCostUsd: Math.round(originTotalIconCost * 100) / 100,
            cdnIconCostUsd: Math.round(cdnTotalIconCost * 100) / 100,
            cdnMonthlySavingsUsd: Math.round(cdnMonthlySavings * 100) / 100,
            isRoiPositive: isCdnRoiPositive,
            recommendation: isCdnRoiPositive ? 'GO_CONDITIONAL_SCENARIO' : 'NO_GO_SCENARIO',
            decision: isCdnRoiPositive ? 'GO_CONDITIONAL_SCENARIO' : 'NO_GO_SCENARIO',
            reason: isCdnRoiPositive
                ? 'Lưu lượng kịch bản lớn đủ bù đắp chi phí cố định và phí request CDN.'
                : 'Với asset nhỏ (15KB), phí request CDN ($0.0075/10k) và fixed overhead cao hơn chi phí Origin Storage Class B ($0.004/10k).',
        },
        cdnEconomics: {
            iconMonthlyGb: Math.round(iconMonthlyGb * 100) / 100,
            iconMonthlyRequests,
            originIconCostUsd: Math.round(originTotalIconCost * 100) / 100,
            cdnIconCostUsd: Math.round(cdnTotalIconCost * 100) / 100,
            cdnMonthlySavingsUsd: Math.round(cdnMonthlySavings * 100) / 100,
            decision: isCdnRoiPositive ? 'GO_CONDITIONAL_SCENARIO' : 'NO_GO_SCENARIO',
            reason: isCdnRoiPositive
                ? 'Lưu lượng kịch bản lớn đủ bù đắp chi phí cố định và phí request CDN.'
                : 'Với asset nhỏ (15KB), phí request CDN ($0.0075/10k) và fixed overhead cao hơn chi phí Origin Storage Class B ($0.004/10k).',
        },
    };
}

if (process.argv[1] && process.argv[1].replace(/\\/g, '/').endsWith('calculate-tco.mjs')) {
    const result = calculateMonthlyEgressAndCost();
    console.log(JSON.stringify(result, null, 2));
}
