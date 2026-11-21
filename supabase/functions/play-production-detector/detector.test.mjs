import test from "node:test";
import assert from "node:assert/strict";
import {
  classifyPlayStoreHtml,
  createPlayDetectorHandler,
  probePlayStorePackage,
} from "./index.ts";

function makeEnv(overrides = {}) {
  return {
    SUPABASE_URL: "https://demo.supabase.co",
    SUPABASE_SERVICE_ROLE_KEY: "service-role-secret-key",
    SUPABASE_ANON_KEY: "anon-key",
    PLAY_DETECTOR_CRON_SECRET: "cron-secret-123456",
    ...overrides,
  };
}

function buildDs5Script({ availCode = 2, released = "Oct 31, 2026", title = "Demo App", desc = "Demo desc" } = {}) {
  const app = [];
  app[0] = [title];
  app[10] = released === undefined ? null : [released];
  app[18] = availCode === undefined ? null : [availCode];
  app[72] = [[null, desc]];
  const payload = [null, [null, null, app]];
  return `<script>AF_initDataCallback({key: 'ds:5', hash: '1', data:${JSON.stringify(payload)}, sideChannel: {}});</script>`;
}

function buildPlayHtml({
  name = "Demo App",
  description = "A test utility app.",
  offers = { "@type": "Offer", price: "0", priceCurrency: "USD", availability: "https://schema.org/InStock" },
  includeSoftwareLd = true,
  ds5 = { availCode: 2, released: "Oct 31, 2026", title: name, desc: description },
  extraBodyHtml = "",
} = {}) {
  const ldObj = {
    "@context": "https://schema.org",
    "@type": "SoftwareApplication",
    name,
    description,
    ...(offers !== undefined && offers !== null ? { offers } : {}),
  };
  const ldScript = includeSoftwareLd
    ? `<script type="application/ld+json">${JSON.stringify(ldObj)}</script>`
    : "";
  const ds5Script = ds5 ? buildDs5Script(ds5) : "";
  return `<!doctype html><html><head><title>${name}</title>${ldScript}${ds5Script}</head><body><h1>${name}</h1><div data-g-id="description">${description}</div>${extraBodyHtml}</body></html>`;
}

test("GET returns healthcheck OK", async () => {
  const handler = createPlayDetectorHandler({ envProvider: () => makeEnv() });
  const res = await handler(
    new Request("https://demo.supabase.co/functions/v1/play-production-detector", { method: "GET" }),
  );
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.ok, true);
});

test("POST batch rejects missing or invalid X-Cron-Secret with 401", async () => {
  const handler = createPlayDetectorHandler({ envProvider: () => makeEnv() });
  const resMissing = await handler(
    new Request("https://demo.supabase.co/functions/v1/play-production-detector", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({}),
    }),
  );
  assert.equal(resMissing.status, 401);

  const resWrong = await handler(
    new Request("https://demo.supabase.co/functions/v1/play-production-detector", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Cron-Secret": "wrong-secret" },
      body: JSON.stringify({}),
    }),
  );
  assert.equal(resWrong.status, 401);
});

test("probePlayStorePackage stops immediately on HEAD 404 (not_public)", async () => {
  const calls = [];
  const fakeFetch = async (url, init = {}) => {
    calls.push({ url: String(url), method: init.method || "GET" });
    return new Response(null, { status: 404 });
  };

  const res = await probePlayStorePackage("com.example.closedtest", fakeFetch);
  assert.equal(res.state, "not_public");
  assert.equal(res.rateLimited, false);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].method, "HEAD");
});

test("probePlayStorePackage detects 0-review free Production and paid Production apps", async () => {
  const freeHtml = buildPlayHtml({
    name: "Zero Review App",
    offers: { "@type": "Offer", price: "0", availability: "https://schema.org/InStock" },
    ds5: { availCode: 2, released: "Nov 01, 2026" },
  });
  const paidHtml = buildPlayHtml({
    name: "Paid Pro App",
    offers: [{ "@type": "Offer", price: "2.99", priceCurrency: "USD", availability: "https://schema.org/InStock" }],
    ds5: { availCode: 2, released: "Oct 10, 2026" },
  });

  const makeMock = (html) => async (_url, init = {}) => {
    if (init.method === "HEAD") return new Response(null, { status: 200 });
    return new Response(html, { status: 200 });
  };

  const freeRes = await probePlayStorePackage("com.example.zeroreview", makeMock(freeHtml));
  assert.equal(freeRes.state, "production");
  assert.equal(freeRes.releasedDate, "Nov 01, 2026");

  const paidRes = await probePlayStorePackage("com.example.paidpro", makeMock(paidHtml));
  assert.equal(paidRes.state, "production");
  assert.equal(paidRes.releasedDate, "Oct 10, 2026");
});

test("probePlayStorePackage falls back to gl=VN for geo-restricted VN Production apps", async () => {
  const usHtml = buildPlayHtml({
    name: "VN Banking Helper",
    offers: { "@type": "Offer", price: "0", availability: "https://schema.org/InStock" },
    ds5: { availCode: 3, released: null },
  });
  const vnHtml = buildPlayHtml({
    name: "VN Banking Helper",
    offers: { "@type": "Offer", price: "0", availability: "https://schema.org/InStock" },
    ds5: { availCode: 2, released: "Oct 31, 2026" },
  });

  const urls = [];
  const fakeFetch = async (url, init = {}) => {
    urls.push(`${init.method || "GET"} ${String(url)}`);
    if (init.method === "HEAD") return new Response(null, { status: 200 });
    if (String(url).includes("gl=VN")) return new Response(vnHtml, { status: 200 });
    return new Response(usHtml, { status: 200 });
  };

  const res = await probePlayStorePackage("vn.example.app", fakeFetch);
  assert.equal(res.state, "production");
  assert.equal(res.releasedDate, "Oct 31, 2026");
  assert.ok(urls.some((u) => u.includes("GET ") && u.includes("gl=US")));
  assert.ok(urls.some((u) => u.includes("GET ") && u.includes("gl=VN")));
});

test("classifyPlayStoreHtml handles pre_registration (missing offers and availCode=1)", () => {
  const noOffersHtml = buildPlayHtml({
    offers: null,
    ds5: null,
  });
  assert.equal(classifyPlayStoreHtml(noOffersHtml, "US").state, "pre_registration");

  const avail1Html = buildPlayHtml({
    offers: { "@type": "Offer", price: "0", availability: "https://schema.org/PreOrder" },
    ds5: { availCode: 1, released: null },
  });
  assert.equal(classifyPlayStoreHtml(avail1Html, "US").state, "pre_registration");
});

test("classifyPlayStoreHtml flags open_testing banners and ignores 'Early access' in dev title/description", () => {
  const openTestingBannerHtml = buildPlayHtml({
    name: "Beta Notes",
    description: "Take quick notes.",
    ds5: { availCode: 2, released: null },
    extraBodyHtml: `<div class="system-banner">This app is in development. Private feedback to developer.</div>`,
  });
  assert.equal(classifyPlayStoreHtml(openTestingBannerHtml, "US").state, "open_testing");

  const noReleaseNoAvail2Html = buildPlayHtml({
    name: "Unconfirmed App",
    ds5: { availCode: 0, released: null },
  });
  assert.equal(classifyPlayStoreHtml(noReleaseNoAvail2Html, "VN").state, "open_testing");

  // Production app whose dev title & description mention "Early access" and "This app is in development"
  const prodWithDevEarlyAccessText = buildPlayHtml({
    name: "Photo Studio - Early access Edition",
    description: "Includes Early access filters! Note: this app is in development for v2 features.",
    ds5: { availCode: 2, released: "Sep 15, 2026" },
  });
  const prodRes = classifyPlayStoreHtml(prodWithDevEarlyAccessText, "US");
  assert.equal(prodRes.state, "production");
  assert.equal(prodRes.releasedDate, "Sep 15, 2026");
});

test("POST batch mode uses sequential jitter and stops on 2 consecutive 429 rate-limits (circuit breaker)", async () => {
  const recordedCalls = [];
  const sleepDurations = [];
  const prodHtml = buildPlayHtml({ ds5: { availCode: 2, released: "Oct 31, 2026" } });

  const fakeFetch = async (url, init = {}) => {
    const u = String(url);
    if (u.includes("/rest/v1/rpc/system_list_campaigns_for_play_check")) {
      return Response.json([
        { id: "11111111-1111-1111-1111-111111111111", package_name: "com.pkg.one" },
        { id: "22222222-2222-2222-2222-222222222222", package_name: "com.pkg.two" },
        { id: "33333333-3333-3333-3333-333333333333", package_name: "com.pkg.three" },
        { id: "44444444-4444-4444-4444-444444444444", package_name: "com.pkg.four" },
      ]);
    }
    if (u.includes("/rest/v1/rpc/system_record_play_store_check")) {
      const payload = JSON.parse(init.body);
      recordedCalls.push(payload);
      return Response.json({
        success: true,
        play_production_detected: false,
        play_prod_confirm_streak: payload.p_detected_state === "production" ? 1 : 0,
      });
    }
    if (u.includes("com.pkg.one")) {
      if (init.method === "HEAD") return new Response(null, { status: 200 });
      return new Response(prodHtml, { status: 200 });
    }
    if (u.includes("com.pkg.two") || u.includes("com.pkg.three")) {
      return new Response(null, { status: 429 });
    }
    if (u.includes("com.pkg.four")) {
      throw new Error("Circuit breaker should have stopped before scanning com.pkg.four");
    }
    return new Response(null, { status: 404 });
  };

  const handler = createPlayDetectorHandler({
    envProvider: () => makeEnv(),
    fetchImpl: fakeFetch,
    sleepImpl: async (ms) => {
      sleepDurations.push(ms);
    },
    randomImpl: () => 0.5,
  });

  const res = await handler(
    new Request("https://demo.supabase.co/functions/v1/play-production-detector", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Cron-Secret": "cron-secret-123456",
      },
      body: JSON.stringify({ limit: 10 }),
    }),
  );

  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.mode, "batch");
  assert.equal(body.queued, 4);
  assert.equal(body.scanned, 3);
  assert.equal(body.stopped_by_circuit_breaker, true);
  assert.equal(recordedCalls.length, 3);
  assert.equal(recordedCalls[0].p_detected_state, "production");
  assert.equal(recordedCalls[1].p_detected_state, "unknown");
  assert.equal(recordedCalls[2].p_detected_state, "unknown");
  assert.equal(sleepDurations.length, 2);
  assert.ok(sleepDurations.every((ms) => ms >= 900 && ms <= 1600));
});

test("POST single-campaign mode enforces auth, owner/admin check, and 10-min cooldown", async () => {
  const campaignId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  const ownerId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  const strangerId = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  const nowMs = Date.parse("2026-11-20T12:00:00Z");

  let currentTokenUser = strangerId;
  let campaignLastChecked = null;
  let playStoreCalls = 0;

  const fakeFetch = async (url, init = {}) => {
    const u = String(url);
    if (u.includes("/auth/v1/user")) {
      return Response.json({ id: currentTokenUser }, { status: 200 });
    }
    if (u.includes("/rest/v1/campaigns")) {
      return Response.json([
        {
          id: campaignId,
          owner_id: ownerId,
          package_name: "com.example.single",
          status: "open",
          play_checked_at: campaignLastChecked,
          play_classification_locked: false,
        },
      ]);
    }
    if (u.includes("/rest/v1/profiles")) {
      return Response.json([{ role: "member", is_locked: false }]);
    }
    if (u.includes("/rest/v1/rpc/system_record_play_store_check")) {
      return Response.json({ success: true, play_production_detected: false, play_prod_confirm_streak: 0 });
    }
    if (u.includes("play.google.com")) {
      playStoreCalls++;
      return new Response(null, { status: 404 });
    }
    return new Response("not found", { status: 404 });
  };

  const handler = createPlayDetectorHandler({
    envProvider: () => makeEnv(),
    fetchImpl: fakeFetch,
    nowImpl: () => nowMs,
  });

  // 1. Unauthenticated -> 401
  const resNoAuth = await handler(
    new Request("https://demo.supabase.co/functions/v1/play-production-detector", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ campaign_id: campaignId }),
    }),
  );
  assert.equal(resNoAuth.status, 401);

  // 2. Non-owner non-admin -> 403
  currentTokenUser = strangerId;
  const resForbidden = await handler(
    new Request("https://demo.supabase.co/functions/v1/play-production-detector", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer user-jwt",
      },
      body: JSON.stringify({ campaign_id: campaignId }),
    }),
  );
  assert.equal(resForbidden.status, 403);

  // 3. Owner authorized & no prior check -> probes Play Store (404 -> not_public)
  currentTokenUser = ownerId;
  const resOwner = await handler(
    new Request("https://demo.supabase.co/functions/v1/play-production-detector", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer owner-jwt",
      },
      body: JSON.stringify({ campaign_id: campaignId }),
    }),
  );
  assert.equal(resOwner.status, 200);
  const ownerBody = await resOwner.json();
  assert.equal(ownerBody.state, "not_public");
  assert.equal(playStoreCalls, 1);

  // 4. Within 10-min cooldown -> skipped without hitting Play Store
  campaignLastChecked = "2026-11-20T11:55:00Z";
  const resCooldown = await handler(
    new Request("https://demo.supabase.co/functions/v1/play-production-detector", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer owner-jwt",
      },
      body: JSON.stringify({ campaign_id: campaignId }),
    }),
  );
  assert.equal(resCooldown.status, 200);
  const cooldownBody = await resCooldown.json();
  assert.equal(cooldownBody.skipped, "cooldown");
  assert.equal(playStoreCalls, 1);
});

test("classifyPlayStoreHtml and probePlayStorePackage safely handle CAPTCHA /sorry/, malformed ds:5, and unreleased ds:5", async () => {
  const sorryHtml = `<html><head><title>Sorry...</title></head><body>Our systems have detected unusual traffic from your computer network.</body></html>`;
  const sorryRes = classifyPlayStoreHtml(sorryHtml, "US");
  assert.equal(sorryRes.state, "unknown");
  assert.equal(sorryRes.rateLimited, true);

  const malformedDs5Html = `<html><head><script type="application/ld+json">{"@type":"SoftwareApplication","name":"SafeApp","offers":{"@type":"Offer","availability":"https://schema.org/InStock"}}</script><script>AF_initDataCallback({key:'ds:5', data:[BROKEN_JSON});</script></head><body></body></html>`;
  const malformedRes = classifyPlayStoreHtml(malformedDs5Html, "US");
  assert.equal(malformedRes.state, "unknown");

  // ds:5 present with availCode=2 but released=null (no English banner) => open_testing
  const unreleasedAvail2Html = buildPlayHtml({
    name: "Silent Beta",
    ds5: { availCode: 2, released: null },
  });
  assert.equal(classifyPlayStoreHtml(unreleasedAvail2Html, "US").state, "open_testing");

  // ds:5 present with released!=null and availCode=null => production
  const releasedNullAvailHtml = buildPlayHtml({
    name: "Released App",
    ds5: { availCode: undefined, released: "Nov 15, 2026" },
  });
  const releasedNullAvailRes = classifyPlayStoreHtml(releasedNullAvailHtml, "VN");
  assert.equal(releasedNullAvailRes.state, "production");
  assert.equal(releasedNullAvailRes.releasedDate, "Nov 15, 2026");

  // VN geo-restricted app omitting both offers and availCode on gl=US falls back to gl=VN
  const usOmittedHtml = buildPlayHtml({
    name: "VN Local App",
    offers: null,
    ds5: { availCode: undefined, released: null },
  });
  const vnFullHtml = buildPlayHtml({
    name: "VN Local App",
    ds5: { availCode: 2, released: "Nov 18, 2026" },
  });
  const vnFallbackRes = await probePlayStorePackage("vn.local.only", async (url, init = {}) => {
    if (init.method === "HEAD") return new Response(null, { status: 200 });
    if (String(url).includes("gl=VN")) return new Response(vnFullHtml, { status: 200 });
    return new Response(usOmittedHtml, { status: 200 });
  });
  assert.equal(vnFallbackRes.state, "production");
  assert.equal(vnFallbackRes.releasedDate, "Nov 18, 2026");

  const sorryRedirectFetch = async () => ({
    status: 200,
    ok: true,
    url: "https://www.google.com/sorry/index?continue=...",
    text: async () => "",
  });
  const headSorry = await probePlayStorePackage("com.example.sorry", sorryRedirectFetch);
  assert.equal(headSorry.state, "unknown");
  assert.equal(headSorry.rateLimited, true);
});

test("POST single-campaign skips non-open & owner skips profile query; batch guards null params", async () => {
  const campaignId = "dddddddd-dddd-dddd-dddd-dddddddddddd";
  const ownerId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
  let profileQueries = 0;
  let lastQueuePayload = null;

  const handler = createPlayDetectorHandler({
    envProvider: () => makeEnv(),
    fetchImpl: async (url, init = {}) => {
      const u = String(url);
      if (u.includes("/auth/v1/user")) {
        return Response.json({ id: ownerId }, { status: 200 });
      }
      if (u.includes("/rest/v1/campaigns")) {
        return Response.json([
          {
            id: campaignId,
            owner_id: ownerId,
            package_name: "com.example.paused",
            status: "paused",
            play_checked_at: null,
            play_classification_locked: false,
          },
        ]);
      }
      if (u.includes("/rest/v1/profiles")) {
        profileQueries++;
        return Response.json([{ role: "member", is_locked: false }]);
      }
      if (u.includes("/rest/v1/rpc/system_list_campaigns_for_play_check")) {
        lastQueuePayload = JSON.parse(init.body);
        return Response.json([]);
      }
      return new Response("unexpected", { status: 500 });
    },
  });

  const singleRes = await handler(
    new Request("https://demo.supabase.co/functions/v1/play-production-detector", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer owner-jwt",
      },
      body: JSON.stringify({ campaign_id: campaignId }),
    }),
  );
  assert.equal(singleRes.status, 200);
  const singleBody = await singleRes.json();
  assert.equal(singleBody.skipped, "not_open");
  assert.equal(profileQueries, 0);

  const batchRes = await handler(
    new Request("https://demo.supabase.co/functions/v1/play-production-detector", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Cron-Secret": "cron-secret-123456",
      },
      body: JSON.stringify({ recheck_hours: null, reverify_prod_hours: "", limit: null }),
    }),
  );
  assert.equal(batchRes.status, 200);
  assert.equal(lastQueuePayload.p_recheck_hours, 6);
  assert.equal(lastQueuePayload.p_reverify_prod_hours, 24);
  assert.equal(lastQueuePayload.p_limit, 25);
});
