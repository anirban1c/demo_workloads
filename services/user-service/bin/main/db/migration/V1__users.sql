create table processed_events (
    event_id     varchar(64) primary key,
    processed_at timestamptz not null default now()
);

create table users (
    id            varchar(64) primary key,
    email         varchar(255) not null,
    first_name    varchar(100),
    last_name     varchar(100),
    date_of_birth varchar(10),
    status        varchar(16) not null,
    idp_user_id   varchar(64),
    kyc_status    varchar(16),
    account_id    varchar(80),
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);
