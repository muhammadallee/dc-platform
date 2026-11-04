-- Consumer migration for the Phase 8 all-capability smoke matrix (runs as the schema owner, 19c-safe). V2 is the platform's
-- platform_outbox.sql, copied unchanged by the harness.
create table all_order (
    ref      varchar2(100) primary key,
    owner    varchar2(100) not null,
    trace_id varchar2(32)  not null
);

-- No key: a duplicate delivery is a second row.
create table all_received (
    event_id      varchar2(64)  not null,
    ref           varchar2(100) not null,
    handler_trace varchar2(64),
    principal     varchar2(100),
    downstream    varchar2(100)
);

grant select, insert, update, delete on platform_outbox to hh_rw;
grant select, insert on all_order to hh_rw;
grant select, insert on all_received to hh_rw;
grant select, insert, update, delete on note to hh_rw;
