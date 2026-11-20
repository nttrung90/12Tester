-- ============================================================================
-- 12 Tester — Supabase migration 003: App submission fields (icon & test account)
-- Run this in the Supabase Dashboard: SQL Editor > New query > paste > Run.
-- ============================================================================

-- Add icon_url, test_username, test_password to campaigns table
alter table public.campaigns
    add column if not exists icon_url text not null default '',
    add column if not exists test_username text default '',
    add column if not exists test_password text default '';

-- Allow app_name to be empty if user leaves it optional as designed
alter table public.campaigns alter column app_name set default '';
