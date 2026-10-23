-- DentalPro 7.0.1: additive schema parity and private media. No records or objects are deleted.
alter table public.appointments add column if not exists duration_minutes integer not null default 30;
alter table public.clinic_expenses add column if not exists expense_group text not null default '';
alter table public.bank_accounts add column if not exists opening_balance bigint;
alter table public.personal_sub_accounts add column if not exists grand_parent_name text not null default '';
alter table public.personal_sub_accounts add column if not exists opening_balance bigint not null default 0;
alter table public.clinic_settings add column if not exists active_doctor_sync_id text not null default '';
alter table public.clinic_settings add column if not exists active_assistant_sync_id text not null default '';
alter table public.clinic_settings add column if not exists dashboard_card_order text not null default '';
alter table public.clinic_settings add column if not exists settings_card_visibility text not null default '';
alter table public.clinic_settings add column if not exists clinic_map_link text not null default '';
alter table public.clinic_settings add column if not exists avatar_file_id text;
alter table public.financial_ledger add column if not exists source_sync_id text not null default '';

alter table public.app_updates add column if not exists apk_sha256 text not null default '';
alter table public.app_updates add column if not exists apk_size_bytes bigint not null default 0;
create unique index if not exists app_updates_version_unique on public.app_updates(version_code);
-- Local Room IDs are not globally unique, even across two devices owned by the same user.
drop index if exists public.idx_financial_ledger_source;
create unique index if not exists idx_financial_ledger_stable_source
    on public.financial_ledger(owner_id,source_type,source_sync_id) where source_sync_id <> '';

update storage.buckets set public=false where id in ('patient_media','clinic_assets');
drop policy if exists dental_media_read on storage.objects;
create policy dental_media_read on storage.objects for select to authenticated
using(bucket_id in ('patient_media','clinic_assets') and (owner=(select auth.uid()) or (storage.foldername(name))[1]=(select auth.uid()::text)));
drop policy if exists dental_media_insert on storage.objects;
create policy dental_media_insert on storage.objects for insert to authenticated
with check(bucket_id in ('patient_media','clinic_assets') and (storage.foldername(name))[1]=(select auth.uid()::text));
drop policy if exists dental_media_update on storage.objects;
create policy dental_media_update on storage.objects for update to authenticated
using(bucket_id in ('patient_media','clinic_assets') and (owner=(select auth.uid()) or (storage.foldername(name))[1]=(select auth.uid()::text)))
with check(bucket_id in ('patient_media','clinic_assets') and (storage.foldername(name))[1]=(select auth.uid()::text));
drop policy if exists dental_media_delete on storage.objects;
create policy dental_media_delete on storage.objects for delete to authenticated
using(bucket_id in ('patient_media','clinic_assets') and (owner=(select auth.uid()) or (storage.foldername(name))[1]=(select auth.uid()::text)));
-- APK publication is an administrative action, not a right of every signed-in user.
drop policy if exists app_releases_insert on storage.objects;
revoke all on public.otp_codes from anon,authenticated;

do $$ declare t record; begin
  for t in select tablename from pg_tables where schemaname='public' and tablename not in ('users','otp_codes','app_updates','sync_tombstones') loop
    if exists(select 1 from information_schema.columns where table_schema='public' and table_name=t.tablename and column_name='owner_id') then
      execute format('drop policy if exists %I on public.%I',t.tablename||'_owner_rows',t.tablename);
      execute format('create policy %I on public.%I for all to authenticated using(owner_id=(select auth.uid()::text)) with check(owner_id=(select auth.uid()::text))',t.tablename||'_owner_rows',t.tablename);
    end if;
  end loop;
  if to_regclass('public.storage_duplicate_candidates') is not null then
    alter view public.storage_duplicate_candidates set (security_invoker=true);
  end if;
  if to_regprocedure('public.touch_api_settings_updated_at()') is not null then
    alter function public.touch_api_settings_updated_at() set search_path=public,pg_temp;
  end if;
  if to_regprocedure('public.rls_auto_enable()') is not null then
    revoke execute on function public.rls_auto_enable() from public,anon,authenticated;
  end if;
end $$;

-- Only called by the authenticated delete-account Edge Function. Files are removed via
-- the Storage API and the auth user via the Auth Admin API, not storage metadata SQL.
create or replace function public.delete_my_account() returns void language plpgsql security definer
set search_path=public,pg_temp as $$
declare uid text := auth.uid()::text; t record;
begin
  if uid is null then raise exception 'NOT_AUTHENTICATED'; end if;
  for t in select table_name from information_schema.columns where table_schema='public' and column_name='owner_id' loop
    execute format('delete from public.%I where owner_id=$1', t.table_name) using uid;
  end loop;
end $$;
revoke all on function public.delete_my_account() from public,anon,authenticated;
grant execute on function public.delete_my_account() to authenticated;
comment on column public.financial_ledger.source_sync_id is 'Stable source row identity; source_id is legacy device-local metadata.';
notify pgrst,'reload schema';
