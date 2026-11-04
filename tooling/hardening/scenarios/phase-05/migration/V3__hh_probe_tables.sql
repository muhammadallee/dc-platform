-- Consumer migration for the hardening phase-05 harness (runs as the schema owner, 19c-safe). V2 is the platform's
-- platform_outbox.sql, copied unchanged by the harness.
create table hh_order (
    ref varchar(100) primary key
);

-- No key: a duplicate delivery is a second row.
create table hh_received (
    event_id      varchar(64)  not null,
    ref           varchar(100) not null,
    traceparent   varchar(100),
    handler_trace varchar(64),
    producer      varchar(100)
);

-- One row per handler attempt.
create table hh_attempt (
    ref varchar(100) not null
);

-- The naive consumer's effect, and the deduplicating one's: a duplicate shows up as a second row in hh_effect.
create table hh_effect (
    consumer varchar(20)  not null,
    event_id varchar(64)  not null
);

-- The documented pattern: the consumer's own processed-events table.
create table hh_processed (
    event_id varchar(64) primary key
);

grant select, insert, update, delete on platform_outbox to hh_rw;
grant select, insert on hh_order to hh_rw;
grant select, insert on hh_received to hh_rw;
grant select, insert on hh_attempt to hh_rw;
grant select, insert on hh_effect to hh_rw;
grant select, insert on hh_processed to hh_rw;
grant select, insert, update, delete on note to hh_rw;
