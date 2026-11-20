-- ============================================================================
-- 12 Tester — Supabase migration 004: Owner App Management, Tip & Reports
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- 1. Expand campaign status to include 'paused' and 'production'
alter table public.campaigns drop constraint if exists campaigns_status_check;
alter table public.campaigns add constraint campaigns_status_check
    check (status in ('open', 'closed', 'paused', 'production'));

-- 2. Add tip_credits and is_boosted columns to campaigns
alter table public.campaigns
    add column if not exists tip_credits int not null default 0,
    add column if not exists is_boosted boolean not null default false,
    add column if not exists auto_accept_hours int not null default 24;

-- 3. Create tester_reports table for reports submitted by testers
create table if not exists public.tester_reports (
    id             uuid primary key default gen_random_uuid(),
    campaign_id    uuid not null references public.campaigns(id) on delete cascade,
    user_id        uuid not null references public.profiles(id) on delete cascade,
    report_type    text not null default 'feedback' check (report_type in ('daily_test', 'bug_report', 'feedback')),
    content        text not null default '',
    screenshot_url text,
    rating         int check (rating between 1 and 5),
    status         text not null default 'pending' check (status in ('pending', 'approved', 'rejected')),
    bonus_awarded  int not null default 0,
    created_at     timestamptz not null default now(),
    reviewed_at    timestamptz
);

create index if not exists idx_tester_reports_campaign on public.tester_reports(campaign_id);
create index if not exists idx_tester_reports_status on public.tester_reports(status);

-- RLS policies for tester_reports
alter table public.tester_reports enable row level security;

drop policy if exists "reports_select_own_or_owner" on public.tester_reports;
create policy "reports_select_own_or_owner" on public.tester_reports
    for select to authenticated
    using (
        user_id = auth.uid() or
        exists (select 1 from public.campaigns c where c.id = campaign_id and c.owner_id = auth.uid())
    );

drop policy if exists "reports_insert_tester" on public.tester_reports;
create policy "reports_insert_tester" on public.tester_reports
    for insert to authenticated with check (user_id = auth.uid());

drop policy if exists "reports_update_owner" on public.tester_reports;
create policy "reports_update_owner" on public.tester_reports
    for update to authenticated
    using (exists (select 1 from public.campaigns c where c.id = campaign_id and c.owner_id = auth.uid()));
