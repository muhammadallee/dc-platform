-- 19c-safe (HD13). A slow statement on demand for the timeout tests.
-- pdt_sleep: wall-clock wait (select pdt_sleep(5) from dual).
create or replace function pdt_sleep(seconds in number) return number as
begin
    dbms_session.sleep(seconds);
    return 1;
end;
/
