-- ============================================================================
-- 12 Tester — Supabase migration 002: Guest mode & Leaderboard read access
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- Allow unauthenticated guests (anon) to read open campaigns
create policy "campaigns_select_anon"
    on public.campaigns for select to anon
    using (status = 'open');

-- Allow unauthenticated guests (anon) to read profiles (display name & avatar for leaderboard and campaign details)
create policy "profiles_select_anon"
    on public.profiles for select to anon
    using (true);

-- Allow unauthenticated guests (anon) to read campaign members
create policy "members_select_anon"
    on public.campaign_members for select to anon
    using (true);

-- Allow unauthenticated guests (anon) to read checkins (needed for leaderboard rankings and progress tracking)
create policy "checkins_select_anon"
    on public.checkins for select to anon
    using (true);

-- Allow unauthenticated guests (anon) to read feedbacks (reviews on campaigns)
create policy "feedbacks_select_anon"
    on public.feedbacks for select to anon
    using (true);
