-- The platform outbox table. Copy this into your own migration sequence (for example V7__platform_outbox.sql);
-- the platform never creates it, because a runtime user has no DDL rights and a migration inside a platform jar
-- would collide with your version numbers. Oracle 19c-safe; also runs on H2 for local runs.
create table platform_outbox (
    event_id        varchar(64)  not null primary key,
    event_type      varchar(255) not null,
    event_version   numeric(10)  not null,
    destination     varchar(255) not null,
    msg_key         varchar(255),
    payload         blob         not null,
    content_type    varchar(100) not null,
    headers         clob         not null,
    principal       varchar(255),
    created_at      timestamp with time zone not null,
    next_attempt_at timestamp with time zone not null,
    attempts        numeric(10)  not null,
    rejections      numeric(10)  not null,
    status          varchar(10)  not null,
    sent_at         timestamp with time zone,
    last_error      varchar(255)
);

create index platform_outbox_due on platform_outbox (status, next_attempt_at);

-- With a separate runtime user, grant through the role it holds:
-- grant select, insert, update, delete on platform_outbox to <runtime role>;
