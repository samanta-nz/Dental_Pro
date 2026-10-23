-- Complete only unambiguous legacy transfer references. Original rows/amounts are retained.
create or replace function public.dental_refresh_movement() returns trigger language plpgsql security definer
set search_path=public,pg_temp as $$
declare old_bank text; new_bank text; uid text; source_key text;
begin
 if tg_op<>'INSERT' then old_bank:=old.bank_account_sync_id; uid:=old.owner_id; source_key:=old.sync_id; end if;
 if tg_op<>'DELETE' then new_bank:=new.bank_account_sync_id; uid:=new.owner_id; source_key:=new.sync_id; end if;
 perform public.dental_project_source(tg_table_name,source_key,uid);
 update public.bank_accounts b set balance=(b.opening_balance::numeric+public.dental_bank_cash_total(b.owner_id,b.sync_id))::bigint,
   updated_at=(extract(epoch from clock_timestamp())*1000)::bigint
 where b.owner_id=uid and b.sync_id in (old_bank,new_bank)
   and b.balance is distinct from (b.opening_balance::numeric+public.dental_bank_cash_total(b.owner_id,b.sync_id))::bigint;
 if tg_op='DELETE' then return old; else return new; end if;
end $$;

revoke all on function public.dental_refresh_movement() from public,anon,authenticated;
update public.payments p set transfer_party_payment_sync_id=(
 select min(e.sync_id) from public.expense_payments e where e.owner_id=p.owner_id
 and nullif(e.cheque_number,'')=nullif(p.cheque_number,'') and e.amount=p.amount
) where p.cheque_status='TRANSFERRED' and p.transfer_party_payment_id is not null and p.transfer_party_payment_sync_id=''
 and (select count(*) from public.expense_payments e where e.owner_id=p.owner_id and nullif(e.cheque_number,'')=nullif(p.cheque_number,'') and e.amount=p.amount)=1;
update public.payments p set transfer_transaction_sync_id=(
 select min(t.sync_id) from public.personal_transactions t where t.owner_id=p.owner_id
 and nullif(t.cheque_number,'')=nullif(p.cheque_number,'') and t.amount=p.amount
) where p.cheque_status='TRANSFERRED' and p.transfer_transaction_id is not null and p.transfer_transaction_sync_id=''
 and (select count(*) from public.personal_transactions t where t.owner_id=p.owner_id and nullif(t.cheque_number,'')=nullif(p.cheque_number,'') and t.amount=p.amount)=1;
update public.payments p set transfer_expense_sync_id=e.clinic_expense_sync_id from public.expense_payments e
 where p.transfer_expense_id is not null and p.transfer_expense_sync_id=''
 and p.transfer_expense_payment_sync_id=e.sync_id and e.owner_id=p.owner_id and e.clinic_expense_sync_id<>'';
notify pgrst,'reload schema';
