-- Read-only preflight, available on schema 066-078 before installing 079.
-- Include missing predecessors OUTSIDE coverage: these require verified
-- checkpoints rather than being silently omitted by the covered-day gap query.
WITH bounds AS (
  SELECT (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS today,
         (((now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date - 1)::timestamp
           AT TIME ZONE 'Asia/Ho_Chi_Minh') AS yesterday_start
)
SELECT p.id AS user_id, p.role, p.daily_missed_streak AS profile_streak,
       b.today - 1 AS missing_predecessor_date,
       m.marker_time AS coverage_start,
       latest.task_date AS last_finalized_date,
       latest.missed_streak AS last_finalized_streak,
       latest.carry_out_tests AS last_known_carry_out,
       CASE WHEN m.marker_time IS NULL OR m.marker_time > b.yesterday_start
         THEN 'NEEDS_VERIFIED_LEGACY_CHECKPOINT'
         ELSE 'NEEDS_CHRONOLOGICAL_BACKFILL'
       END AS required_review
FROM bounds b
CROSS JOIN public.profiles p
LEFT JOIN public.system_provenance_markers m ON m.marker_key = 'coverage_start'
LEFT JOIN public.user_daily_tasks previous
  ON previous.user_id = p.id AND previous.task_date = b.today - 1
LEFT JOIN LATERAL (
  SELECT t.task_date, t.missed_streak, t.carry_out_tests
  FROM public.user_daily_tasks t
  WHERE t.user_id = p.id AND t.finalized_at IS NOT NULL AND t.task_date < b.today
  ORDER BY t.task_date DESC LIMIT 1
) latest ON true
WHERE p.created_at < (b.today::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')
  AND previous.finalized_at IS NULL
ORDER BY p.id;

-- NULL last_known_carry_out means unknown debt, never proof of zero debt.
-- This query does not mutate, seal, waive, reopen, or refund anything.
