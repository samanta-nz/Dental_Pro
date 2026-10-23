-- A user-requested account deletion must also remove that user's rows from retained snapshots.
-- Defining this function does not delete any existing account, record, or object.
create or replace function public.delete_my_account() returns void language plpgsql security definer
set search_path=public,pg_temp as $$
declare uid text:=auth.uid()::text; t record; snap record; remaining jsonb;
begin
 if uid is null then raise exception 'NOT_AUTHENTICATED'; end if;
 for t in select c.table_name from information_schema.columns c join pg_tables p
  on p.schemaname=c.table_schema and p.tablename=c.table_name
  where c.table_schema='public' and c.column_name='owner_id' and c.table_name<>'sync_tombstones' loop
  execute format('delete from public.%I where owner_id=$1',t.table_name) using uid;
 end loop;
 delete from public.users where id=uid;
 delete from public.sync_tombstones where owner_id=uid;
 if to_regclass('dentalpro_recovery.release_snapshots') is not null then
  for snap in select release,table_name,payload from dentalpro_recovery.release_snapshots loop
   select coalesce(jsonb_agg(item),'[]'::jsonb) into remaining from jsonb_array_elements(snap.payload) item
    where coalesce(item->>'owner_id','')<>uid and coalesce(item->>'owner','')<>uid
    and not (snap.table_name='users' and item->>'id'=uid);
   update dentalpro_recovery.release_snapshots set payload=remaining,row_count=jsonb_array_length(remaining)
    where release=snap.release and table_name=snap.table_name;
  end loop;
 end if;
end $$;
revoke all on function public.delete_my_account() from public,anon,authenticated;
grant execute on function public.delete_my_account() to authenticated;
