CREATE OR REPLACE FUNCTION public._assert_admin_unlocked()
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
begin
return public._assert_daily_task_admin();
end;
$function$
