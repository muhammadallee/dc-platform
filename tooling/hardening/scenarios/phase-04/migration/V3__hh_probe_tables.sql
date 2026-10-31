-- Consumer migration for the hardening phase-04 harness (runs as the schema owner, 19c-safe). V2 is the platform's
-- platform_outbox.sql, copied unchanged by the harness, as outbox.md tells a service to do.
create table hh_order (
    ref varchar(100) primary key
);

-- No key: a duplicate delivery is a second row, which is how the scenario counts them.
create table hh_received (
    event_id    varchar(64)  not null,
    ref         varchar(100) not null,
    traceparent varchar(100),
    principal   varchar(255),
    instance    varchar(50)
);

grant select, insert, update, delete on platform_outbox to hh_rw;
grant select, insert on hh_order to hh_rw;
grant select, insert on hh_received to hh_rw;
grant select, insert, update, delete on note to hh_rw;
