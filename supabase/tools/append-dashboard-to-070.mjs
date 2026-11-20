import { readFileSync, writeFileSync } from 'node:fs';

const path062 = new URL('../migrations/062_consolidated_baseline.sql', import.meta.url);
const path070 = new URL('../migrations/070_admin_management.sql', import.meta.url);

const sql062 = readFileSync(path062, 'utf8');
let sql070 = readFileSync(path070, 'utf8');

// Find start and end of get_my_dashboard in 062
const startMarker = 'CREATE OR REPLACE FUNCTION public.get_my_dashboard(p_include_leaderboard boolean DEFAULT true)';
const startIndex = sql062.indexOf(startMarker);
if (startIndex === -1) throw new Error('Could not find start of get_my_dashboard in 062');

const endMarker = '$function$;';
const endIndex = sql062.indexOf(endMarker, startIndex);
if (endIndex === -1) throw new Error('Could not find end of get_my_dashboard in 062');

let fnDef = sql062.slice(startIndex, endIndex + endMarker.length);

// Assert baseline markers exist in fnDef
const unreadOld = `where w.user_id = v_uid and w.acknowledged = false;`;
const weeklyOld = `from public.user_warnings\n    where user_id = v_uid and warning_date >= v_week_ago;`;

if (!fnDef.includes(unreadOld)) throw new Error('Could not find unread warnings query in fnDef');
if (!fnDef.includes(weeklyOld)) throw new Error('Could not find weekly warnings query in fnDef');

// Replace with precise revoked warning exclusion
const unreadNew = `where w.user_id = v_uid and w.acknowledged = false\n      and not exists (\n          select 1 from public.user_warning_revocations r\n          where r.warning_id = w.id\n      );`;

const weeklyNew = `from public.user_warnings w\n    where w.user_id = v_uid and w.warning_date >= v_week_ago\n      and not exists (\n          select 1 from public.user_warning_revocations r\n          where r.warning_id = w.id\n      );`;

fnDef = fnDef.replace(unreadOld, unreadNew).replace(weeklyOld, weeklyNew);

const appendBlock = `
-- ----------------------------------------------------------------------------
-- 9c. Override get_my_dashboard to exclude revoked warnings
-- ----------------------------------------------------------------------------
${fnDef}

REVOKE ALL ON FUNCTION public.get_my_dashboard(boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_my_dashboard(boolean) TO authenticated, service_role;
`;

// Insert before COMMIT; in 070
if (!sql070.includes('COMMIT;')) throw new Error('Could not find COMMIT in 070');
if (sql070.includes('Override get_my_dashboard to exclude revoked warnings')) {
  console.log('get_my_dashboard already present in 070, updating it...');
  const blockStart = sql070.indexOf('-- 9c. Override get_my_dashboard to exclude revoked warnings');
  const commitIndex = sql070.lastIndexOf('COMMIT;');
  sql070 = sql070.slice(0, blockStart - 1) + appendBlock + '\nCOMMIT;\n';
} else {
  const commitIndex = sql070.lastIndexOf('COMMIT;');
  sql070 = sql070.slice(0, commitIndex) + appendBlock + '\nCOMMIT;\n';
}

writeFileSync(path070, sql070, 'utf8');
console.log('Successfully appended reviewed get_my_dashboard definition to 070_admin_management.sql');
