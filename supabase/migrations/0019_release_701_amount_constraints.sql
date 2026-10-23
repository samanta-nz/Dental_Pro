-- All monetary values are integer tomans; reversals use explicit states/directions.
do $$ declare item record; begin
 for item in select * from (values
  ('payments','payments_nonnegative_amount','amount>=0'),
  ('expense_payments','expense_payments_nonnegative_amount','amount>=0'),
  ('personal_transactions','personal_transactions_nonnegative_amount','amount>=0'),
  ('clinic_expenses','clinic_expenses_nonnegative_amount','amount>=0'),
  ('treatments','treatments_valid_amounts','cost>=0 and discount>=0 and discount<=cost')
 ) rules(table_name,constraint_name,expression) loop
  if not exists(select 1 from pg_constraint where conname=item.constraint_name and conrelid=('public.'||item.table_name)::regclass) then
   execute format('alter table public.%I add constraint %I check(%s) not valid',item.table_name,item.constraint_name,item.expression);
  end if;
  execute format('alter table public.%I validate constraint %I',item.table_name,item.constraint_name);
 end loop;
end $$;
