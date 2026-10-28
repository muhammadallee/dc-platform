-- 19c-safe (HD13). Slow statements on demand for the timeout tests.
-- pdt_sleep: wall-clock wait (select pdt_sleep(5) from dual).
create or replace function pdt_sleep(seconds in number) return number as
begin
    dbms_session.sleep(seconds);
    return 1;
end;
/

-- pdt_heavy: SQL-engine work that runs for tens of seconds unless cancelled.
create or replace function pdt_heavy return number as
    n number;
begin
    select count(*) into n from (select level l from dual connect by level <= 20000) a,
        (select level l from dual connect by level <= 20000) b;
    return 1;
end;
/
