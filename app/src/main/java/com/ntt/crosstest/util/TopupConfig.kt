package com.ntt.crosstest.util

import com.ntt.crosstest.R
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.TopupPackage

/**
 * Configuration and constants for the Credit Top-up feature.
 */
object TopupConfig {
    /**
     * Feature flag controlling visibility of the Top-up flow.
     * Set to `false` to hide top-up buttons and flows completely (safe for Play Store audits).
     * Set to `true` to activate the top-up flow next to the Credit balance on the home screen.
     */
    const val IS_TOPUP_FEATURE_ENABLED = false

    /**
     * Exchange rate: 1 USD = 20 Credits ($0.05 / Credit).
     */
    const val EXCHANGE_RATE_USD_TO_CREDIT = 20

    /**
     * Fallback / Default in-app credit packages matching Google Play product IDs.
     */
    val DEFAULT_PACKAGES = listOf(
        TopupPackage(
            id = "credit_pack_starter_20",
            title = "Gói Khởi Đầu",
            credits = 20,
            bonusCredits = 0,
            priceUsd = 0.99,
            priceVnd = 25_000L,
            badgeText = null,
            isPopular = false,
            sortOrder = 1,
        ),
        TopupPackage(
            id = "credit_pack_basic_50",
            title = "Gói Tiêu Chuẩn",
            credits = 50,
            bonusCredits = 0,
            priceUsd = 2.49,
            priceVnd = 62_000L,
            badgeText = null,
            isPopular = false,
            sortOrder = 2,
        ),
        TopupPackage(
            id = "credit_pack_popular_100",
            title = "Gói Phổ Biến",
            credits = 100,
            bonusCredits = 5,
            priceUsd = 4.99,
            priceVnd = 125_000L,
            badgeText = "Phổ biến (+5%)",
            isPopular = true,
            sortOrder = 3,
        ),
        TopupPackage(
            id = "credit_pack_pro_200",
            title = "Gói Nâng Cao",
            credits = 200,
            bonusCredits = 20,
            priceUsd = 9.99,
            priceVnd = 250_000L,
            badgeText = "Tiết kiệm (+10%)",
            isPopular = false,
            sortOrder = 4,
        ),
        TopupPackage(
            id = "credit_pack_master_400",
            title = "Gói Doanh Nghiệp",
            credits = 400,
            bonusCredits = 80,
            priceUsd = 19.99,
            priceVnd = 500_000L,
            badgeText = "Siêu ưu đãi (+20%)",
            isPopular = false,
            sortOrder = 5,
        ),
    )

    fun getLocalizedTitle(packageId: String, localizer: Localizer): String {
        return when (packageId) {
            "credit_pack_starter_20" -> localizer.get(R.string.topup_pack_starter_title)
            "credit_pack_basic_50" -> localizer.get(R.string.topup_pack_basic_title)
            "credit_pack_popular_100" -> localizer.get(R.string.topup_pack_popular_title)
            "credit_pack_pro_200" -> localizer.get(R.string.topup_pack_pro_title)
            "credit_pack_master_400" -> localizer.get(R.string.topup_pack_master_title)
            else -> packageId
        }
    }

    fun getLocalizedBadge(packageId: String, localizer: Localizer): String? {
        return when (packageId) {
            "credit_pack_popular_100" -> localizer.get(R.string.topup_badge_popular)
            "credit_pack_pro_200" -> localizer.get(R.string.topup_badge_saving)
            "credit_pack_master_400" -> localizer.get(R.string.topup_badge_super_deal)
            else -> null
        }
    }
}
