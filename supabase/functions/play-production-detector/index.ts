// Supabase Edge Function: Google Play Production Detector
// Zero external dependencies — compatible with both `npx supabase functions deploy`
// and copy-pasting directly into the Supabase Dashboard Edge Function editor.
//
// IMPORTANT: Deploy with JWT verification DISABLED (--no-verify-jwt / "Enforce JWT" OFF)
// because scheduled cron callers authenticate via `X-Cron-Secret`, while single-campaign
// triggers may authenticate via `X-Cron-Secret` OR a verified owner/admin Bearer JWT.

declare const Deno: {
  env: { get(key: string): string | undefined };
  serve(handler: (req: Request) => Response | Promise<Response>): unknown;
} | undefined;

export type PlayStoreState =
  | "unknown"
  | "not_public"
  | "pre_registration"
  | "open_testing"
  | "production";

export interface EdgeEnv {
  SUPABASE_URL?: string;
  SUPABASE_SERVICE_ROLE_KEY?: string;
  SUPABASE_ANON_KEY?: string;
  PLAY_DETECTOR_CRON_SECRET?: string;
}

export interface HtmlClassification {
  state: PlayStoreState;
  releasedDate: string | null;
  rateLimited: boolean;
  needRegionFallback: boolean;
  evidence: {
    region: string;
    layer: string;
    hasSoftwareApplication: boolean;
    hasInStockOffer: boolean;
    availCode: number | null;
    released: string | null;
    openTestingBanner: boolean;
  };
}

export interface ProbeResult {
  state: PlayStoreState;
  releasedDate: string | null;
  rateLimited: boolean;
  evidence: Record<string, unknown>;
}

const HEAD_TIMEOUT_MS = 8000;
const GET_TIMEOUT_MS = 10000;
const DEFAULT_BATCH_LIMIT = 25;
const MAX_BATCH_LIMIT = 50;
const DEFAULT_RECHECK_HOURS = 6;
const DEFAULT_REVERIFY_PROD_HOURS = 24;
const SINGLE_CAMPAIGN_COOLDOWN_MS = 10 * 60 * 1000;
const CIRCUIT_BREAKER_RATE_LIMIT_STREAK = 2;
const JITTER_MIN_MS = 900;
const JITTER_MAX_MS = 1600;

const PACKAGE_NAME_RE = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const OPEN_TESTING_BANNERS = [
  "this app is in development",
  "private feedback to developer",
  "app is currently in early access",
  "this app may be unstable",
  "you're a beta tester",
  "you are a beta tester",
];

function getDefaultEnv(): EdgeEnv {
  if (typeof Deno !== "undefined" && Deno?.env) {
    return {
      SUPABASE_URL: Deno.env.get("SUPABASE_URL"),
      SUPABASE_SERVICE_ROLE_KEY: Deno.env.get("SUPABASE_SERVICE_ROLE_KEY"),
      SUPABASE_ANON_KEY: Deno.env.get("SUPABASE_ANON_KEY"),
      PLAY_DETECTOR_CRON_SECRET: Deno.env.get("PLAY_DETECTOR_CRON_SECRET"),
    };
  }
  return {};
}

export function sanitizeLogMessage(err: unknown, extraSecrets: string[] = []): string {
  let msg = typeof err === "string"
    ? err
    : err instanceof Error
      ? err.message
      : String(err ?? "Unknown error");

  msg = msg.replace(/bearer\s+[a-z0-9._~+/-]+/gi, "Bearer [REDACTED]");
  for (const secret of extraSecrets) {
    if (typeof secret === "string" && secret.length >= 4) {
      msg = msg.split(secret).join("[SECRET_REDACTED]");
    }
  }
  return msg;
}

export async function timingSafeEqualSecret(
  provided: string | null,
  expected: string,
): Promise<boolean> {
  if (!provided || !expected) return false;
  const enc = new TextEncoder();
  const [hashA, hashB] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(provided)),
    crypto.subtle.digest("SHA-256", enc.encode(expected)),
  ]);
  const viewA = new Uint8Array(hashA);
  const viewB = new Uint8Array(hashB);
  let diff = 0;
  for (let i = 0; i < viewA.length; i++) {
    diff |= viewA[i] ^ viewB[i];
  }
  return diff === 0;
}

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function randomJitterMs(randomImpl: () => number): number {
  const r = Math.min(0.999999, Math.max(0, randomImpl()));
  return Math.floor(JITTER_MIN_MS + r * (JITTER_MAX_MS - JITTER_MIN_MS + 1));
}

function decodeHtmlEntities(input: string): string {
  return input
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/&nbsp;/gi, " ");
}

function normalizeText(input: string): string {
  return decodeHtmlEntities(input.replace(/<[^>]+>/g, " "))
    .replace(/\s+/g, " ")
    .trim();
}

function findSoftwareApplicationNode(node: unknown): Record<string, unknown> | null {
  if (!node || typeof node !== "object") return null;
  if (Array.isArray(node)) {
    for (const item of node) {
      const found = findSoftwareApplicationNode(item);
      if (found) return found;
    }
    return null;
  }
  const obj = node as Record<string, unknown>;
  const nodeType = obj["@type"];
  if (
    nodeType === "SoftwareApplication" ||
    (Array.isArray(nodeType) && nodeType.includes("SoftwareApplication"))
  ) {
    return obj;
  }
  if (Array.isArray(obj["@graph"])) {
    return findSoftwareApplicationNode(obj["@graph"]);
  }
  return null;
}

function extractSoftwareApplicationJsonLd(html: string): {
  found: boolean;
  hasInStockOffer: boolean;
  devTitle: string | null;
  devDescription: string | null;
} {
  const scriptRe = /<script\b[^>]*type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi;
  let match: RegExpExecArray | null;
  while ((match = scriptRe.exec(html)) !== null) {
    const rawJson = match[1]?.trim();
    if (!rawJson) continue;
    try {
      const parsed = JSON.parse(rawJson);
      const appNode = findSoftwareApplicationNode(parsed);
      if (!appNode) continue;

      const rawOffers = appNode.offers;
      const offerList = Array.isArray(rawOffers)
        ? rawOffers
        : rawOffers && typeof rawOffers === "object"
          ? [rawOffers]
          : [];

      const hasInStockOffer = offerList.some((offer) => {
        if (!offer || typeof offer !== "object") return false;
        const availability = (offer as Record<string, unknown>).availability;
        return typeof availability === "string" &&
          (availability.endsWith("/InStock") || availability === "InStock");
      });

      const devTitle = typeof appNode.name === "string" && appNode.name.trim()
        ? appNode.name.trim()
        : null;
      const devDescription = typeof appNode.description === "string" && appNode.description.trim()
        ? appNode.description.trim()
        : null;

      return {
        found: true,
        hasInStockOffer,
        devTitle,
        devDescription,
      };
    } catch {
      // Ignore malformed ld+json script blocks and continue scanning.
    }
  }
  return {
    found: false,
    hasInStockOffer: false,
    devTitle: null,
    devDescription: null,
  };
}

function extractBalancedJsonArray(source: string, startIndex: number): string | null {
  if (startIndex < 0 || source[startIndex] !== "[") return null;
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = startIndex; i < source.length; i++) {
    const ch = source[i];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (ch === "\\") {
        escaped = true;
      } else if (ch === '"') {
        inString = false;
      }
      continue;
    }
    if (ch === '"') {
      inString = true;
      continue;
    }
    if (ch === "[") {
      depth++;
    } else if (ch === "]") {
      depth--;
      if (depth === 0) {
        return source.slice(startIndex, i + 1);
      }
    }
  }
  return null;
}

function extractDs5Metadata(html: string): {
  ds5Found: boolean;
  availCode: number | null;
  released: string | null;
  devStrings: string[];
} {
  try {
    const keyRe = /AF_initDataCallback\s*\(\s*\{[\s\S]{0,300}?key\s*:\s*['"]ds:5['"]/g;
    let match: RegExpExecArray | null;
    while ((match = keyRe.exec(html)) !== null) {
      const fromKey = html.slice(match.index, match.index + 500000);
      const dataMatch = /\bdata\s*:\s*(?:function\s*\(\s*\)\s*\{\s*return\s*)?\[/.exec(fromKey);
      if (!dataMatch) continue;
      const arrayStart = dataMatch.index + dataMatch[0].length - 1;
      const arrayJson = extractBalancedJsonArray(fromKey, arrayStart);
      if (!arrayJson) continue;

      const ds5 = JSON.parse(arrayJson);
      const app = Array.isArray(ds5?.[1]?.[2]) ? ds5[1][2] : null;
      if (!app) {
        return { ds5Found: true, availCode: null, released: null, devStrings: [] };
      }

      const rawAvail = app?.[18]?.[0];
      const availCode = typeof rawAvail === "number" ? rawAvail : null;
      const rawReleased = app?.[10]?.[0];
      const released = typeof rawReleased === "string" && rawReleased.trim()
        ? rawReleased.trim()
        : null;

      const devStrings: string[] = [];
      const dsTitle = app?.[0]?.[0];
      if (typeof dsTitle === "string" && dsTitle.trim()) devStrings.push(dsTitle.trim());
      const dsDesc = app?.[72]?.[0]?.[1];
      if (typeof dsDesc === "string" && dsDesc.trim()) devStrings.push(dsDesc.trim());

      return {
        ds5Found: true,
        availCode,
        released,
        devStrings,
      };
    }
  } catch {
    // ds:5 parsing must never throw on unexpected Google Play payloads.
  }
  return {
    ds5Found: false,
    availCode: null,
    released: null,
    devStrings: [],
  };
}

function hasSystemOpenTestingBanner(html: string, devStrings: Array<string | null>): boolean {
  // Remove scripts, styles, meta/title, headings, and description containers first
  // so developer-authored app names or descriptions can never trigger a false positive.
  let cleaned = html
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, " ")
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, " ")
    .replace(/<noscript\b[^>]*>[\s\S]*?<\/noscript>/gi, " ")
    .replace(/<title\b[^>]*>[\s\S]*?<\/title>/gi, " ")
    .replace(/<meta\b[^>]*>/gi, " ")
    .replace(/<h1\b[^>]*>[\s\S]*?<\/h1>/gi, " ")
    .replace(/<div\b[^>]*data-g-id=["']description["'][^>]*>[\s\S]*?<\/div>/gi, " ")
    .replace(/<div\b[^>]*itemprop=["']description["'][^>]*>[\s\S]*?<\/div>/gi, " ");

  let visibleText = normalizeText(cleaned).toLowerCase();

  for (const rawDev of devStrings) {
    if (!rawDev) continue;
    const normalizedDev = normalizeText(rawDev).toLowerCase();
    if (normalizedDev.length >= 3) {
      visibleText = visibleText.split(normalizedDev).join(" ");
    }
  }

  return OPEN_TESTING_BANNERS.some((banner) => visibleText.includes(banner));
}

function looksLikeSorryOrCaptchaHtml(html: string): boolean {
  const lower = html.toLowerCase();
  if (lower.includes("unusual traffic from your computer network")) return true;
  if (lower.includes("/sorry/") && lower.includes("recaptcha")) return true;
  if (/<title>\s*sorry\b/i.test(html)) return true;
  return false;
}

export function classifyPlayStoreHtml(
  html: string,
  region = "US",
): HtmlClassification {
  if (!html || typeof html !== "string") {
    return {
      state: "unknown",
      releasedDate: null,
      rateLimited: false,
      needRegionFallback: false,
      evidence: {
        region,
        layer: "empty_html",
        hasSoftwareApplication: false,
        hasInStockOffer: false,
        availCode: null,
        released: null,
        openTestingBanner: false,
      },
    };
  }

  if (looksLikeSorryOrCaptchaHtml(html)) {
    return {
      state: "unknown",
      releasedDate: null,
      rateLimited: true,
      needRegionFallback: false,
      evidence: {
        region,
        layer: "captcha_sorry",
        hasSoftwareApplication: false,
        hasInStockOffer: false,
        availCode: null,
        released: null,
        openTestingBanner: false,
      },
    };
  }

  const ld = extractSoftwareApplicationJsonLd(html);
  if (!ld.found) {
    return {
      state: "unknown",
      releasedDate: null,
      rateLimited: false,
      needRegionFallback: false,
      evidence: {
        region,
        layer: "missing_software_application_jsonld",
        hasSoftwareApplication: false,
        hasInStockOffer: false,
        availCode: null,
        released: null,
        openTestingBanner: false,
      },
    };
  }

  const ds5 = extractDs5Metadata(html);
  const baseEvidence = {
    region,
    hasSoftwareApplication: true,
    hasInStockOffer: ld.hasInStockOffer,
    availCode: ds5.availCode,
    released: ds5.released,
  };

  // Layer 2 explicit pre-registration availCode
  if (ds5.availCode === 1) {
    return {
      state: "pre_registration",
      releasedDate: ds5.released,
      rateLimited: false,
      needRegionFallback: false,
      evidence: {
        ...baseEvidence,
        layer: "ds5_avail_code_1",
        openTestingBanner: false,
      },
    };
  }

  // Layer 3: Open Testing / Early Access banner outside dev title/description
  const devStrings = [ld.devTitle, ld.devDescription, ...ds5.devStrings];
  const openTestingBanner = hasSystemOpenTestingBanner(html, devStrings);
  if (openTestingBanner) {
    return {
      state: "open_testing",
      releasedDate: ds5.released,
      rateLimited: false,
      needRegionFallback: false,
      evidence: {
        ...baseEvidence,
        layer: "system_open_testing_banner",
        openTestingBanner: true,
      },
    };
  }

  // Geo-restricted check: in US, ds:5 may indicate app is not available in US
  // or omits offers/availCode in US while available in VN.
  if (
    region === "US" &&
    ds5.ds5Found &&
    (ds5.availCode !== 2 || !ld.hasInStockOffer)
  ) {
    return {
      state: "unknown",
      releasedDate: ds5.released,
      rateLimited: false,
      needRegionFallback: true,
      evidence: {
        ...baseEvidence,
        layer: "ds5_need_vn_fallback",
        openTestingBanner: false,
      },
    };
  }

  // Layer 1: Missing InStock Offer => pre_registration
  if (!ld.hasInStockOffer) {
    return {
      state: "pre_registration",
      releasedDate: ds5.released,
      rateLimited: false,
      needRegionFallback: false,
      evidence: {
        ...baseEvidence,
        layer: "jsonld_no_instock_offer",
        openTestingBanner: false,
      },
    };
  }

  // Missing or malformed ds:5 (without an open-testing banner): return unknown
  // so transient HTML/script shifts never wipe confirmed Production state.
  if (!ds5.ds5Found) {
    return {
      state: "unknown",
      releasedDate: null,
      rateLimited: false,
      needRegionFallback: false,
      evidence: {
        ...baseEvidence,
        layer: "missing_ds5",
        openTestingBanner: false,
      },
    };
  }

  // Unreleased app in ds:5 (released === null) => open_testing
  if (ds5.released === null) {
    return {
      state: "open_testing",
      releasedDate: null,
      rateLimited: false,
      needRegionFallback: false,
      evidence: {
        ...baseEvidence,
        layer: "ds5_unreleased",
        openTestingBanner: false,
      },
    };
  }

  // Valid production signal requires ds:5 present, released != null, and availCode === 2 (or null)
  if (ds5.availCode === 2 || ds5.availCode === null) {
    return {
      state: "production",
      releasedDate: ds5.released,
      rateLimited: false,
      needRegionFallback: false,
      evidence: {
        ...baseEvidence,
        layer: "confirmed_production",
        openTestingBanner: false,
      },
    };
  }

  return {
    state: "unknown",
    releasedDate: ds5.released,
    rateLimited: false,
    needRegionFallback: false,
    evidence: {
      ...baseEvidence,
      layer: "unexpected_ds5_avail_code",
      openTestingBanner: false,
    },
  };
}

async function fetchWithTimeout(
  fetchImpl: typeof fetch,
  url: string,
  init: RequestInit,
  timeoutMs: number,
): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetchImpl(url, {
      ...init,
      signal: controller.signal,
    });
  } finally {
    clearTimeout(timer);
  }
}

function buildPlayStoreUrl(packageName: string, region: string): string {
  return `https://play.google.com/store/apps/details?id=${encodeURIComponent(packageName)}&hl=en&gl=${encodeURIComponent(region)}`;
}

function playStoreHeaders(): Record<string, string> {
  return {
    "User-Agent":
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    "Accept-Language": "en-US,en;q=0.9",
    Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
  };
}

function isRateLimitedResponse(res: Response): boolean {
  if (res.status === 429) return true;
  if (typeof res.url === "string" && res.url.includes("/sorry/")) return true;
  return false;
}

export async function probePlayStorePackage(
  packageName: string,
  fetchImpl: typeof fetch = fetch,
): Promise<ProbeResult> {
  const cleanPkg = typeof packageName === "string" ? packageName.trim() : "";
  if (!PACKAGE_NAME_RE.test(cleanPkg)) {
    return {
      state: "unknown",
      releasedDate: null,
      rateLimited: false,
      evidence: { step: "validate", reason: "invalid_package_name" },
    };
  }

  const usUrl = buildPlayStoreUrl(cleanPkg, "US");

  // Step 1: Anonymous HEAD check (0-byte fast 404 exit)
  try {
    const headRes = await fetchWithTimeout(
      fetchImpl,
      usUrl,
      {
        method: "HEAD",
        headers: playStoreHeaders(),
        credentials: "omit",
        redirect: "follow",
      },
      HEAD_TIMEOUT_MS,
    );

    if (headRes.status === 404) {
      return {
        state: "not_public",
        releasedDate: null,
        rateLimited: false,
        evidence: { step: "HEAD", region: "US", http_status: 404 },
      };
    }

    if (isRateLimitedResponse(headRes)) {
      return {
        state: "unknown",
        releasedDate: null,
        rateLimited: true,
        evidence: { step: "HEAD", region: "US", http_status: headRes.status, rate_limited: true },
      };
    }

    if (headRes.status >= 500 || !headRes.ok) {
      return {
        state: "unknown",
        releasedDate: null,
        rateLimited: false,
        evidence: { step: "HEAD", region: "US", http_status: headRes.status },
      };
    }
  } catch (err) {
    return {
      state: "unknown",
      releasedDate: null,
      rateLimited: false,
      evidence: { step: "HEAD", region: "US", error: sanitizeLogMessage(err) },
    };
  }

  // Step 2: Anonymous GET (US primary, VN fallback for geo-restricted apps)
  const runGet = async (region: string): Promise<{
    res?: Response;
    html?: string;
    earlyResult?: ProbeResult;
  }> => {
    const url = buildPlayStoreUrl(cleanPkg, region);
    try {
      const res = await fetchWithTimeout(
        fetchImpl,
        url,
        {
          method: "GET",
          headers: playStoreHeaders(),
          credentials: "omit",
          redirect: "follow",
        },
        GET_TIMEOUT_MS,
      );

      if (res.status === 404) {
        return {
          earlyResult: {
            state: "not_public",
            releasedDate: null,
            rateLimited: false,
            evidence: { step: "GET", region, http_status: 404 },
          },
        };
      }

      if (isRateLimitedResponse(res)) {
        return {
          earlyResult: {
            state: "unknown",
            releasedDate: null,
            rateLimited: true,
            evidence: { step: "GET", region, http_status: res.status, rate_limited: true },
          },
        };
      }

      if (res.status >= 500 || !res.ok) {
        return {
          earlyResult: {
            state: "unknown",
            releasedDate: null,
            rateLimited: false,
            evidence: { step: "GET", region, http_status: res.status },
          },
        };
      }

      const html = await res.text();
      return { res, html };
    } catch (err) {
      return {
        earlyResult: {
          state: "unknown",
          releasedDate: null,
          rateLimited: false,
          evidence: { step: "GET", region, error: sanitizeLogMessage(err) },
        },
      };
    }
  };

  const usGet = await runGet("US");
  if (usGet.earlyResult) return usGet.earlyResult;

  const usClassified = classifyPlayStoreHtml(usGet.html || "", "US");
  if (usClassified.rateLimited) {
    return {
      state: "unknown",
      releasedDate: null,
      rateLimited: true,
      evidence: { step: "GET", http_status: usGet.res?.status ?? 200, ...usClassified.evidence },
    };
  }

  if (!usClassified.needRegionFallback) {
    return {
      state: usClassified.state,
      releasedDate: usClassified.releasedDate,
      rateLimited: false,
      evidence: { step: "GET", http_status: usGet.res?.status ?? 200, ...usClassified.evidence },
    };
  }

  // Fallback GET with gl=VN for geo-restricted apps unavailable in US
  const vnGet = await runGet("VN");
  if (vnGet.earlyResult) {
    return vnGet.earlyResult;
  }

  const vnClassified = classifyPlayStoreHtml(vnGet.html || "", "VN");
  return {
    state: vnClassified.rateLimited ? "unknown" : vnClassified.state,
    releasedDate: vnClassified.releasedDate,
    rateLimited: vnClassified.rateLimited,
    evidence: {
      step: "GET_VN_FALLBACK",
      http_status: vnGet.res?.status ?? 200,
      us_avail_code: usClassified.evidence.availCode,
      ...vnClassified.evidence,
    },
  };
}

function serviceHeaders(serviceRoleKey: string, extra: Record<string, string> = {}): Record<string, string> {
  return {
    "Content-Type": "application/json",
    apikey: serviceRoleKey,
    Authorization: `Bearer ${serviceRoleKey}`,
    ...extra,
  };
}

async function callRpc<T>(
  supabaseUrl: string,
  serviceRoleKey: string,
  rpcName: string,
  payload: Record<string, unknown>,
  fetchImpl: typeof fetch,
): Promise<T> {
  const baseUrl = supabaseUrl.replace(/\/+$/, "");
  const res = await fetchImpl(`${baseUrl}/rest/v1/rpc/${rpcName}`, {
    method: "POST",
    headers: serviceHeaders(serviceRoleKey),
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    throw new Error(`RPC ${rpcName} failed (${res.status}): ${await res.text()}`);
  }
  return (await res.json()) as T;
}

async function fetchCampaignForSingleCheck(
  supabaseUrl: string,
  serviceRoleKey: string,
  campaignId: string,
  fetchImpl: typeof fetch,
): Promise<{
  id: string;
  owner_id: string;
  package_name: string;
  status: string;
  play_checked_at: string | null;
  play_classification_locked: boolean;
} | null> {
  const baseUrl = supabaseUrl.replace(/\/+$/, "");
  const res = await fetchImpl(
    `${baseUrl}/rest/v1/campaigns?id=eq.${encodeURIComponent(campaignId)}&select=id,owner_id,package_name,status,play_checked_at,play_classification_locked`,
    {
      method: "GET",
      headers: serviceHeaders(serviceRoleKey),
    },
  );
  if (!res.ok) {
    throw new Error(`Fetch campaign failed (${res.status}): ${await res.text()}`);
  }
  const rows = (await res.json()) as Array<Record<string, unknown>>;
  if (!Array.isArray(rows) || rows.length === 0) return null;
  const r = rows[0];
  return {
    id: String(r.id),
    owner_id: String(r.owner_id),
    package_name: String(r.package_name || ""),
    status: String(r.status || ""),
    play_checked_at: typeof r.play_checked_at === "string" ? r.play_checked_at : null,
    play_classification_locked: Boolean(r.play_classification_locked),
  };
}

async function verifyBearerUser(
  supabaseUrl: string,
  anonOrServiceKey: string,
  jwt: string,
  fetchImpl: typeof fetch,
): Promise<string | null> {
  const baseUrl = supabaseUrl.replace(/\/+$/, "");
  const res = await fetchImpl(`${baseUrl}/auth/v1/user`, {
    method: "GET",
    headers: {
      apikey: anonOrServiceKey,
      Authorization: `Bearer ${jwt}`,
    },
  });
  if (!res.ok) return null;
  const data = (await res.json()) as Record<string, unknown>;
  return typeof data?.id === "string" ? data.id : null;
}

async function isUnlockedAdminUser(
  supabaseUrl: string,
  serviceRoleKey: string,
  userId: string,
  fetchImpl: typeof fetch,
): Promise<boolean> {
  const baseUrl = supabaseUrl.replace(/\/+$/, "");
  const res = await fetchImpl(
    `${baseUrl}/rest/v1/profiles?id=eq.${encodeURIComponent(userId)}&select=role,is_locked`,
    {
      method: "GET",
      headers: serviceHeaders(serviceRoleKey),
    },
  );
  if (!res.ok) return false;
  const rows = (await res.json()) as Array<Record<string, unknown>>;
  if (!Array.isArray(rows) || rows.length === 0) return false;
  const role = typeof rows[0].role === "string" ? rows[0].role.toLowerCase() : "";
  const locked = Boolean(rows[0].is_locked);
  return role === "admin" && !locked;
}

function parseOptionalInt(
  value: unknown,
  fallback: number,
  min: number,
  max?: number,
): number {
  if (value === null || value === undefined || (typeof value === "string" && value.trim() === "")) {
    return fallback;
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  const truncated = Math.trunc(parsed);
  if (truncated < min) return min;
  if (max !== undefined && truncated > max) return max;
  return truncated;
}

export function createPlayDetectorHandler({
  envProvider = getDefaultEnv,
  fetchImpl = fetch,
  sleepImpl = defaultSleep,
  randomImpl = Math.random,
  nowImpl = Date.now,
}: {
  envProvider?: () => EdgeEnv;
  fetchImpl?: typeof fetch;
  sleepImpl?: (ms: number) => Promise<void>;
  randomImpl?: () => number;
  nowImpl?: () => number;
} = {}) {
  return async function handleRequest(req: Request): Promise<Response> {
    if (req.method === "GET") {
      return Response.json({ ok: true, mode: "play-production-detector" }, { status: 200 });
    }
    if (req.method !== "POST") {
      return Response.json({ ok: false, error: "Method Not Allowed" }, { status: 405 });
    }

    const env = envProvider();
    const supabaseUrl = env.SUPABASE_URL?.trim() || "";
    const serviceRoleKey = env.SUPABASE_SERVICE_ROLE_KEY?.trim() || "";
    const anonKey = env.SUPABASE_ANON_KEY?.trim() || serviceRoleKey;
    const cronSecret = env.PLAY_DETECTOR_CRON_SECRET?.trim() || "";

    if (!supabaseUrl || !serviceRoleKey) {
      return Response.json({ ok: false, error: "Server Misconfigured" }, { status: 500 });
    }

    let body: Record<string, unknown> = {};
    const rawBody = await req.text();
    if (rawBody.trim().length > 0) {
      try {
        const parsed = JSON.parse(rawBody);
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
          return Response.json({ ok: false, error: "Invalid JSON body" }, { status: 400 });
        }
        body = parsed as Record<string, unknown>;
      } catch {
        return Response.json({ ok: false, error: "Invalid JSON" }, { status: 400 });
      }
    }

    const providedCronSecret = req.headers.get("x-cron-secret");
    const hasCronHeader = providedCronSecret !== null;
    const isCronAuthorized = cronSecret.length > 0 &&
      await timingSafeEqualSecret(providedCronSecret, cronSecret);

    const campaignIdRaw = typeof body.campaign_id === "string" ? body.campaign_id.trim() : "";

    try {
      // ------------------------------------------------------------------
      // MODE 1: Single-campaign check ({ campaign_id })
      // ------------------------------------------------------------------
      if (campaignIdRaw.length > 0) {
        if (!UUID_RE.test(campaignIdRaw)) {
          return Response.json({ ok: false, error: "Invalid campaign_id" }, { status: 400 });
        }

        if (hasCronHeader && !isCronAuthorized) {
          return Response.json({ ok: false, error: "Unauthorized" }, { status: 401 });
        }

        let callerUserId: string | null = null;
        if (!isCronAuthorized) {
          const authHeader = req.headers.get("authorization") || "";
          const bearerMatch = /^Bearer\s+(.+)$/i.exec(authHeader.trim());
          if (!bearerMatch?.[1]) {
            return Response.json({ ok: false, error: "Unauthorized" }, { status: 401 });
          }
          callerUserId = await verifyBearerUser(supabaseUrl, anonKey, bearerMatch[1].trim(), fetchImpl);
          if (!callerUserId) {
            return Response.json({ ok: false, error: "Unauthorized" }, { status: 401 });
          }
        }

        const campaign = await fetchCampaignForSingleCheck(
          supabaseUrl,
          serviceRoleKey,
          campaignIdRaw,
          fetchImpl,
        );
        if (!campaign) {
          return Response.json({ ok: false, error: "Campaign not found" }, { status: 404 });
        }

        if (!isCronAuthorized) {
          const isOwner = campaign.owner_id === callerUserId;
          const isAdmin = isOwner
            ? false
            : callerUserId
              ? await isUnlockedAdminUser(supabaseUrl, serviceRoleKey, callerUserId, fetchImpl)
              : false;
          if (!isOwner && !isAdmin) {
            return Response.json({ ok: false, error: "Forbidden" }, { status: 403 });
          }
        }

        if (campaign.status !== "open") {
          return Response.json({
            ok: true,
            mode: "single",
            campaign_id: campaign.id,
            skipped: "not_open",
            status: campaign.status,
          }, { status: 200 });
        }

        if (campaign.play_classification_locked) {
          return Response.json({
            ok: true,
            mode: "single",
            campaign_id: campaign.id,
            skipped: "classification_locked",
          }, { status: 200 });
        }

        if (campaign.play_checked_at) {
          const lastCheckedMs = Date.parse(campaign.play_checked_at);
          if (Number.isFinite(lastCheckedMs) && (nowImpl() - lastCheckedMs) < SINGLE_CAMPAIGN_COOLDOWN_MS) {
            return Response.json({
              ok: true,
              mode: "single",
              campaign_id: campaign.id,
              skipped: "cooldown",
              play_checked_at: campaign.play_checked_at,
            }, { status: 200 });
          }
        }

        const probe = await probePlayStorePackage(campaign.package_name, fetchImpl);
        const rpcResult = await callRpc<Record<string, unknown>>(
          supabaseUrl,
          serviceRoleKey,
          "system_record_play_store_check",
          {
            p_campaign_id: campaign.id,
            p_detected_state: probe.state,
            p_released_date: probe.releasedDate,
            p_evidence: probe.evidence,
          },
          fetchImpl,
        );

        return Response.json({
          ok: true,
          mode: "single",
          campaign_id: campaign.id,
          package_name: campaign.package_name,
          state: probe.state,
          released_date: probe.releasedDate,
          rate_limited: probe.rateLimited,
          rpc_result: rpcResult,
        }, { status: 200 });
      }

      // ------------------------------------------------------------------
      // MODE 2: Batch queue scan (X-Cron-Secret required)
      // ------------------------------------------------------------------
      if (!cronSecret) {
        return Response.json({ ok: false, error: "Server Misconfigured" }, { status: 500 });
      }
      if (!isCronAuthorized) {
        return Response.json({ ok: false, error: "Unauthorized" }, { status: 401 });
      }

      const limit = parseOptionalInt(body.limit, DEFAULT_BATCH_LIMIT, 1, MAX_BATCH_LIMIT);
      const recheckHours = parseOptionalInt(body.recheck_hours, DEFAULT_RECHECK_HOURS, 0);
      const reverifyProdHours = parseOptionalInt(
        body.reverify_prod_hours,
        DEFAULT_REVERIFY_PROD_HOURS,
        0,
      );

      const queue = await callRpc<Array<Record<string, unknown>>>(
        supabaseUrl,
        serviceRoleKey,
        "system_list_campaigns_for_play_check",
        {
          p_limit: limit,
          p_recheck_hours: recheckHours,
          p_reverify_prod_hours: reverifyProdHours,
        },
        fetchImpl,
      );

      const items = Array.isArray(queue) ? queue : [];
      const results: Array<Record<string, unknown>> = [];
      let consecutiveRateLimits = 0;
      let stoppedByCircuitBreaker = false;

      for (let i = 0; i < items.length; i++) {
        if (i > 0) {
          await sleepImpl(randomJitterMs(randomImpl));
        }

        const item = items[i];
        const campaignId = String(item.id || "");
        const packageName = String(item.package_name || "");
        if (!campaignId || !packageName) continue;

        const probe = await probePlayStorePackage(packageName, fetchImpl);

        if (probe.rateLimited) {
          consecutiveRateLimits++;
        } else {
          consecutiveRateLimits = 0;
        }

        const rpcResult = await callRpc<Record<string, unknown>>(
          supabaseUrl,
          serviceRoleKey,
          "system_record_play_store_check",
          {
            p_campaign_id: campaignId,
            p_detected_state: probe.state,
            p_released_date: probe.releasedDate,
            p_evidence: probe.evidence,
          },
          fetchImpl,
        );

        results.push({
          campaign_id: campaignId,
          package_name: packageName,
          state: probe.state,
          released_date: probe.releasedDate,
          rate_limited: probe.rateLimited,
          play_production_detected: Boolean(rpcResult?.play_production_detected),
          play_prod_confirm_streak: rpcResult?.play_prod_confirm_streak ?? null,
        });

        if (consecutiveRateLimits >= CIRCUIT_BREAKER_RATE_LIMIT_STREAK) {
          stoppedByCircuitBreaker = true;
          break;
        }
      }

      return Response.json({
        ok: true,
        mode: "batch",
        queued: items.length,
        scanned: results.length,
        stopped_by_circuit_breaker: stoppedByCircuitBreaker,
        results,
      }, { status: 200 });
    } catch (err) {
      console.error(
        "[play-production-detector] Error:",
        sanitizeLogMessage(err, [serviceRoleKey, cronSecret]),
      );
      return Response.json({ ok: false, error: "Internal Error" }, { status: 500 });
    }
  };
}

if (typeof Deno !== "undefined" && typeof Deno.serve === "function") {
  Deno.serve(createPlayDetectorHandler());
}
