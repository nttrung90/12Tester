package com.ntt.crosstest.data.help

import android.content.Context
import android.content.res.AssetManager

object HelpContentRepository {
    const val GUIDE_ASSET_NAME = "HUONG_DAN_SU_DUNG_12_CROSS_TESTERS.md"
    const val FAQ_ASSET_NAME = "CAU_HOI_THUONG_GAP_12_CROSS_TESTERS.md"

    @Volatile
    private var cachedGuide: GuideDocument? = null

    @Volatile
    private var cachedFaq: FaqDocument? = null

    fun getGuideDocument(context: Context): GuideDocument {
        cachedGuide?.let { return it }
        return synchronized(this) {
            cachedGuide ?: loadGuide(context.assets).also { cachedGuide = it }
        }
    }

    fun getFaqDocument(context: Context): FaqDocument {
        cachedFaq?.let { return it }
        return synchronized(this) {
            cachedFaq ?: loadFaq(context.assets).also { cachedFaq = it }
        }
    }

    fun loadGuide(assets: AssetManager): GuideDocument {
        val markdown = assets.open(GUIDE_ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return HelpDocumentParser.parseGuideDocument(markdown)
    }

    fun loadFaq(assets: AssetManager): FaqDocument {
        val markdown = assets.open(FAQ_ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return HelpDocumentParser.parseFaqDocument(markdown)
    }

    fun clearCache() {
        synchronized(this) {
            cachedGuide = null
            cachedFaq = null
        }
    }
}
