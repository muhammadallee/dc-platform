-- 19c-safe (HD13): no BOOLEAN type, no IF [NOT] EXISTS, NUMBER(1) for booleans.
-- Instant columns are TIMESTAMP WITH TIME ZONE: Hibernate maps Instant to its UTC timestamp type and
-- reads an OffsetDateTime; a plain TIMESTAMP column fails on read with ORA-18716 (D114).
create sequence oracle_widget_seq start with 1 increment by 50;

create table oracle_widget (
    id               number(19)    not null,
    reference_code   varchar2(64)  not null,
    active           number(1)     not null,
    happened_at      timestamp(6) with time zone,
    price            varchar2(64),
    correlation_id   varchar2(32),
    version          number(19)    not null,
    created_at       timestamp(6) with time zone,
    created_by       varchar2(128),
    last_modified_at timestamp(6) with time zone,
    last_modified_by varchar2(128),
    constraint oracle_widget_pk primary key (id),
    constraint oracle_widget_ref_uk unique (reference_code),
    constraint oracle_widget_active_ck check (active in (0, 1))
);
