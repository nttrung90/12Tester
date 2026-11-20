-- Run the bonus lifecycle mutation AFTER the client financial guard has
-- validated the original write. PostgreSQL runs BEFORE triggers by name:
-- the old "...bonus..." trigger ran before "...guard_financial...", so even
-- {status: 'production', paused_reason: null} was rejected as bonus tampering.
-- Keep the guard unchanged: clients still cannot write bonus summary fields.
BEGIN;

CREATE OR REPLACE FUNCTION public.trg_campaigns_bonus_round_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions', 'pg_catalog'
AS $function$
begin
    if new.status in ('completed', 'production', 'closed')
       and old.status not in ('completed', 'production', 'closed') then
        update public.campaign_tester_bonus_rounds
        set status = 'closing',
            stopped_at = coalesce(stopped_at, clock_timestamp())
        where campaign_id = new.id
          and status = 'active';

        -- Apps without an active round must retain their existing summary.
        -- In particular, do not invent a closing round for apps with no bonus.
        if found then
            new.tester_bonus_status := 'closing';
            new.tester_bonus_remaining_slots := 0;
        end if;
    end if;
    return new;
end;
$function$;

DROP TRIGGER IF EXISTS trg_campaigns_bonus_round_lifecycle ON public.campaigns;
DROP TRIGGER IF EXISTS trg_campaigns_lifecycle_bonus_after_guard ON public.campaigns;
CREATE TRIGGER trg_campaigns_lifecycle_bonus_after_guard
BEFORE UPDATE OF status ON public.campaigns
FOR EACH ROW EXECUTE FUNCTION public.trg_campaigns_bonus_round_lifecycle();

COMMIT;
