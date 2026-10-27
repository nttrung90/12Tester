-- Only used by the isolated local runner, inside a transaction it rolls back.
-- Suppress unrelated signup/media/credit triggers while constructing history.
set local session_replication_role = replica;
insert into auth.users (id, email)
select md5('ratio:' || name)::uuid, name || '@ratio-test.invalid'
from (values ('owner'), ('provider'), ('member'), ('admin'), ('moderator'), ('fresh')) u(name);
insert into auth.users (id, email)
select md5('ratio:provider:' || s)::uuid, 'provider' || s || '@ratio-test.invalid'
from generate_series(1,181) s;
insert into public.profiles (id, email, role, credits, created_at, last_login_date)
select id, email,
    case when email like 'provider%' or email like 'admin@%' then 'admin'
         when email like 'moderator@%' then 'moderator' else 'member' end,
    100,
    case when email like 'fresh@%' then now() else now() - interval '10 days' end,
    (now() at time zone 'Asia/Ho_Chi_Minh')::date
from auth.users;
insert into public.user_slots (user_id, slot_number, expires_at)
select md5('ratio:owner')::uuid, s, now() + interval '40 days'
from generate_series(2, 6) s;
insert into public.campaigns (id, owner_id, package_name, app_name, slot_number, created_at)
select md5('ratio:own:' || s)::uuid, md5('ratio:owner')::uuid,
       'ratio.own.' || s, 'Own ' || s, s, now() - interval '2 days'
from generate_series(1, 6) s;
insert into public.campaigns (id, owner_id, package_name, app_name, created_at)
select md5('ratio:other:' || s)::uuid, md5('ratio:provider:' || s)::uuid,
       'ratio.other.' || s, 'Other ' || s, now() - interval '2 days'
from generate_series(1, 181) s;
set local session_replication_role = origin;

create function pg_temp.ratio_counts(p_k integer, p_a integer) returns void
language plpgsql as $$
begin
    update public.campaigns set status = 'closed', paused_reason = null;
    update public.campaigns set status = 'open'
    where id in (select md5('ratio:own:' || s)::uuid from generate_series(1, p_k) s);
    update public.campaigns set status = 'open'
    where id in (select md5('ratio:other:' || s)::uuid from generate_series(1, p_a) s);
end;
$$;
