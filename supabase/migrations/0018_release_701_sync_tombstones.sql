-- Deletions need a durable identity even when a collection becomes empty.
create table if not exists public.sync_tombstones (
 sync_id text primary key default gen_random_uuid()::text,
 owner_id text not null,
 collection_table text not null,
 record_sync_id text not null,
 deleted_at bigint not null default (extract(epoch from clock_timestamp())*1000)::bigint,
 unique(owner_id,collection_table,record_sync_id)
);
alter table public.sync_tombstones enable row level security;
revoke all on public.sync_tombstones from anon,authenticated;
grant select on public.sync_tombstones to authenticated;
drop policy if exists sync_tombstones_owner_rows on public.sync_tombstones;
drop policy if exists sync_tombstones_owner_read on public.sync_tombstones;
create policy sync_tombstones_owner_read on public.sync_tombstones for select to authenticated using(owner_id=(select auth.uid()::text));
create index if not exists sync_tombstones_owner_idx on public.sync_tombstones(owner_id);
create or replace function public.dental_track_deletion() returns trigger language plpgsql security definer
set search_path=public,pg_temp as $$
begin
 if tg_op='DELETE' then
  insert into public.sync_tombstones(owner_id,collection_table,record_sync_id)
  values(old.owner_id,tg_table_name,old.sync_id)
  on conflict(owner_id,collection_table,record_sync_id) do update set deleted_at=excluded.deleted_at;
  return old;
 end if;
 delete from public.sync_tombstones where owner_id=new.owner_id and collection_table=tg_table_name and record_sync_id=new.sync_id;
 return new;
end $$;
revoke all on function public.dental_track_deletion() from public,anon,authenticated;
do $$ declare t record; begin
 for t in select table_name from information_schema.columns where table_schema='public' and column_name='sync_id' and table_name not in ('sync_tombstones','api_settings','users') loop
  execute format('drop trigger if exists dental_track_deletion on public.%I',t.table_name);
  execute format('create trigger dental_track_deletion after insert or delete on public.%I for each row execute function public.dental_track_deletion()',t.table_name);
 end loop;
end $$;
notify pgrst,'reload schema';
