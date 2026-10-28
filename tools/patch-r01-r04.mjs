import fs from 'node:fs';

function updateFile(filePath, targetText, replacementText) {
  let content = fs.readFileSync(filePath, 'utf8');
  const eol = content.includes('\r\n') ? '\r\n' : '\n';
  const normalizedTarget = targetText.replace(/\r\n|\n/g, eol);
  const normalizedReplacement = replacementText.replace(/\r\n|\n/g, eol);

  if (!content.includes(normalizedTarget)) {
    throw new Error(`Target not found in ${filePath}:\n${normalizedTarget.slice(0, 100)}...`);
  }
  content = content.replace(normalizedTarget, normalizedReplacement);
  fs.writeFileSync(filePath, content, 'utf8');
  console.log(`Updated ${filePath}`);
}

// 1. CampaignRepository.kt
updateFile(
  'app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt',
`                    for ((statId, stat) in stats) {
                        statsCache[statId] = CacheEntry(stat, at, gen, mutVer, currentUid, businessDate = requestDate)
                    }`,
`                    for ((statId, stat) in stats) {
                        if (stat.statsDate == null || stat.statsDate == requestDate.toString()) {
                            statsCache[statId] = CacheEntry(stat, at, gen, mutVer, currentUid, businessDate = requestDate)
                        }
                    }`
);

updateFile(
  'app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt',
`            val now = monotonicNow()
            if (gen == sessionGeneration.get() && dashboardMutVer == getMutationVersion("dashboard") && currentUid == supabase.auth.currentUserOrNull()?.id) {
                cachedHomeFeed = CacheEntry(result, now, gen, dashboardMutVer, currentUid, businessDate = requestDate)
            }
            if (gen == sessionGeneration.get() && campMutVer == getMutationVersion("campaigns")) {
                cachedOpenCampaigns = CacheEntry(result.open, now, gen, campMutVer, null, isPublic = true)
                cachedPausedCampaigns = CacheEntry(result.paused, now, gen, campMutVer, null, isPublic = true)
                cachedCompletedCampaigns = CacheEntry(result.completed, now, gen, campMutVer, null, isPublic = true)
            }
            if (gen == sessionGeneration.get() && statsMutVer == getMutationVersion("stats") && currentUid == supabase.auth.currentUserOrNull()?.id) {
                if (result.stats.isNotEmpty()) {
                    for ((statId, stat) in result.stats) {
                        statsCache[statId] = CacheEntry(stat, now, gen, statsMutVer, currentUid, businessDate = requestDate)
                    }
                    evictOldEntries(statsCache)
                }
            }`,
`            val now = monotonicNow()
            val hasStaleStats = result.stats.values.any { it.statsDate != null && it.statsDate != requestDate.toString() }
            if (!hasStaleStats && gen == sessionGeneration.get() && dashboardMutVer == getMutationVersion("dashboard") && currentUid == supabase.auth.currentUserOrNull()?.id) {
                cachedHomeFeed = CacheEntry(result, now, gen, dashboardMutVer, currentUid, businessDate = requestDate)
            }
            if (gen == sessionGeneration.get() && campMutVer == getMutationVersion("campaigns")) {
                cachedOpenCampaigns = CacheEntry(result.open, now, gen, campMutVer, null, isPublic = true)
                cachedPausedCampaigns = CacheEntry(result.paused, now, gen, campMutVer, null, isPublic = true)
                cachedCompletedCampaigns = CacheEntry(result.completed, now, gen, campMutVer, null, isPublic = true)
            }
            if (!hasStaleStats && gen == sessionGeneration.get() && statsMutVer == getMutationVersion("stats") && currentUid == supabase.auth.currentUserOrNull()?.id) {
                if (result.stats.isNotEmpty()) {
                    for ((statId, stat) in result.stats) {
                        statsCache[statId] = CacheEntry(stat, now, gen, statsMutVer, currentUid, businessDate = requestDate)
                    }
                    evictOldEntries(statsCache)
                }
            }`
);
