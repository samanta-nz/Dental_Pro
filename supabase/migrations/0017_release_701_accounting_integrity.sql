-- Stable links, consistent posted amounts, and bank balances derived from source movements.
-- Opening baselines preserve each existing bank balance. Raw source records are retained.
alter table public.payments add column if not exists transfer_expense_payment_sync_id text not null default '';
alter table public.payments add column if not exists transfer_expense_sync_id text not null default '';
alter table public.payments add column if not exists transfer_transaction_sync_id text not null default '';
alter table public.payments add column if not exists transfer_children_expense_sync_id text not null default '';
alter table public.payments add column if not exists transfer_party_payment_sync_id text not null default '';

-- Only infer a legacy transfer when its cheque identity and owner identify one target.
update public.payments p set transfer_expense_payment_sync_id=(
  select min(e.sync_id) from public.expense_payments e
  where e.owner_id=p.owner_id and nullif(e.cheque_number,'')=nullif(p.cheque_number,'') and e.amount=p.amount
  having count(*)=1
) where p.type='CHEQUE' and p.cheque_status='TRANSFERRED' and p.transfer_expense_payment_id is not null
  and exists(select 1 from public.expense_payments e where e.owner_id=p.owner_id and nullif(e.cheque_number,'')=nullif(p.cheque_number,'') and e.amount=p.amount)
  and (select count(*) from public.expense_payments e where e.owner_id=p.owner_id and nullif(e.cheque_number,'')=nullif(p.cheque_number,'') and e.amount=p.amount)=1;

create or replace function public.dental_bank_cash_total(p_owner text,p_bank text) returns bigint
language sql volatile set search_path=public,pg_temp as $$
 select coalesce(sum(delta),0)::bigint from (
   select amount::numeric as delta from public.payments
    where owner_id=p_owner and bank_account_sync_id=p_bank and not is_deleted
      and (type<>'CHEQUE' or cheque_status='CLEARED')
   union all
   select -amount::numeric from public.expense_payments
    where owner_id=p_owner and bank_account_sync_id=p_bank and not is_deleted and method<>'CHEQUE_TRANSFER'
      and (method<>'CHEQUE' or cheque_status='CLEARED')
   union all
   select (case when type='INCOME' then amount::numeric else -amount::numeric end) from public.personal_transactions
    where owner_id=p_owner and bank_account_sync_id=p_bank and not is_deleted and payment_method<>'CHEQUE_TRANSFER'
 ) movements
$$;
revoke all on function public.dental_bank_cash_total(text,text) from public,anon,authenticated;

-- Freeze a baseline before enabling projections; this never rewrites the original amounts.
update public.bank_accounts b set opening_balance=b.balance-public.dental_bank_cash_total(b.owner_id,b.sync_id)
 where opening_balance is null;

create or replace function public.dental_bank_derive_balance() returns trigger language plpgsql security definer
set search_path=public,pg_temp as $$
begin
 new.opening_balance := coalesce(new.opening_balance,new.balance-public.dental_bank_cash_total(new.owner_id,new.sync_id));
 new.balance := (new.opening_balance::numeric+public.dental_bank_cash_total(new.owner_id,new.sync_id))::bigint;
 return new;
end $$;
revoke all on function public.dental_bank_derive_balance() from public,anon,authenticated;
drop trigger if exists dental_derive_balance on public.bank_accounts;
create trigger dental_derive_balance before insert or update on public.bank_accounts for each row execute function public.dental_bank_derive_balance();

create or replace function public.dental_lock_movement_accounts() returns trigger language plpgsql security definer
set search_path=public,pg_temp as $$
declare old_bank text; new_bank text; uid text;
begin
 if tg_op<>'INSERT' then old_bank:=old.bank_account_sync_id; uid:=old.owner_id; end if;
 if tg_op<>'DELETE' then new_bank:=new.bank_account_sync_id; uid:=new.owner_id; end if;
 -- Lock before changing a source, so concurrent devices cannot lose one another's movements.
 perform 1 from public.bank_accounts where owner_id=uid and sync_id in (old_bank,new_bank) order by sync_id for update;
 if tg_op='DELETE' then return old; else return new; end if;
end $$;
revoke all on function public.dental_lock_movement_accounts() from public,anon,authenticated;

create or replace function public.dental_project_source(p_table text,p_sync text,p_owner text) returns void language plpgsql security definer
set search_path=public,pg_temp as $$
declare r jsonb; kind text; posted boolean; delta bigint; entry_status text;
begin
 if p_table not in ('payments','expense_payments','personal_transactions') then raise exception 'Invalid projection source'; end if;
 execute format('select to_jsonb(x) from public.%I x where sync_id=$1 and owner_id=$2',p_table) into r using p_sync,p_owner;
 kind:=case p_table when 'payments' then 'PATIENT_PAYMENT' when 'expense_payments' then 'EXPENSE_PAYMENT' else 'PERSONAL_TRANSACTION' end;
 if r is null then
   delete from public.financial_ledger where owner_id=p_owner and source_type=kind and source_sync_id=p_sync;
   return;
 end if;
 posted:=not coalesce((r->>'is_deleted')::boolean,false);
 if p_table='payments' then
   posted:=posted and (r->>'type'<>'CHEQUE' or r->>'cheque_status' in ('CLEARED','TRANSFERRED'));
   delta:=case when posted then (r->>'amount')::bigint else 0 end;
 elsif p_table='expense_payments' then
   posted:=posted and (r->>'method'<>'CHEQUE' or r->>'cheque_status' in ('CLEARED','TRANSFERRED'));
   delta:=case when posted then -(r->>'amount')::bigint else 0 end;
 else
   delta:=case when not posted then 0 when r->>'type'='INCOME' then (r->>'amount')::bigint else -(r->>'amount')::bigint end;
 end if;
 entry_status:=case when (r->>'is_deleted')::boolean then 'REVERSED' when (r->>'type'='CHEQUE' or r->>'method'='CHEQUE') and r->>'cheque_status'='BOUNCED' then 'BOUNCED' when posted then 'POSTED' else 'PENDING' end;
 insert into public.financial_ledger(sync_id,owner_id,source_type,source_id,source_sync_id,patient_id,patient_sync_id,bank_account_id,bank_account_sync_id,source_sms_native_id,amount_delta,status,occurred_at,description,created_at,updated_at,is_deleted)
 values('ledger:'||kind||':'||p_sync,p_owner,kind,0,p_sync,(r->>'patient_id')::bigint,coalesce(r->>'patient_sync_id',''),(r->>'bank_account_id')::bigint,coalesce(r->>'bank_account_sync_id',''),coalesce(r->>'source_sms_native_id',''),delta,entry_status,coalesce((r->>'date')::bigint,0),coalesce(r->>'description',r->>'note',''),coalesce((r->>'date')::bigint,0),coalesce((r->>'updated_at')::bigint,0),coalesce((r->>'is_deleted')::boolean,false))
 on conflict(sync_id) do update set source_sync_id=excluded.source_sync_id,patient_id=excluded.patient_id,patient_sync_id=excluded.patient_sync_id,bank_account_id=excluded.bank_account_id,bank_account_sync_id=excluded.bank_account_sync_id,amount_delta=excluded.amount_delta,status=excluded.status,occurred_at=excluded.occurred_at,updated_at=excluded.updated_at,is_deleted=excluded.is_deleted;
end $$;
revoke all on function public.dental_project_source(text,text,text) from public,anon,authenticated;

create or replace function public.dental_refresh_movement() returns trigger language plpgsql security definer
set search_path=public,pg_temp as $$
declare old_bank text; new_bank text; uid text; source_key text;
begin
 if tg_op<>'INSERT' then old_bank:=old.bank_account_sync_id; uid:=old.owner_id; source_key:=old.sync_id; end if;
 if tg_op<>'DELETE' then new_bank:=new.bank_account_sync_id; uid:=new.owner_id; source_key:=new.sync_id; end if;
 perform public.dental_project_source(tg_table_name,source_key,uid);
 update public.bank_accounts b set balance=(b.opening_balance::numeric+public.dental_bank_cash_total(b.owner_id,b.sync_id))::bigint,
   updated_at=(extract(epoch from clock_timestamp())*1000)::bigint
 where b.owner_id=uid and b.sync_id in (old_bank,new_bank);
 if tg_op='DELETE' then return old; else return new; end if;
end $$;
revoke all on function public.dental_refresh_movement() from public,anon,authenticated;

do $$ declare t text; r record; begin
 -- Keep historical projections as superseded records, with originals also in the private snapshot.
 update public.financial_ledger set status='SUPERSEDED',is_deleted=true where source_sync_id='';
 foreach t in array array['payments','expense_payments','personal_transactions'] loop
   execute format('drop trigger if exists dental_lock_accounts on public.%I',t);
   execute format('create trigger dental_lock_accounts before insert or update or delete on public.%I for each row execute function public.dental_lock_movement_accounts()',t);
   execute format('drop trigger if exists dental_refresh_accounts on public.%I',t);
   execute format('create trigger dental_refresh_accounts after insert or update or delete on public.%I for each row execute function public.dental_refresh_movement()',t);
   for r in execute format('select sync_id,owner_id from public.%I',t) loop
     perform public.dental_project_source(t,r.sync_id,r.owner_id);
   end loop;
 end loop;
end $$;
notify pgrst,'reload schema';

-- Clients read the projection; writes must come from source triggers.
drop policy if exists financial_ledger_owner_rows on public.financial_ledger;
drop policy if exists financial_ledger_owner_read on public.financial_ledger;
create policy financial_ledger_owner_read on public.financial_ledger for select to authenticated using(owner_id=(select auth.uid()::text));
