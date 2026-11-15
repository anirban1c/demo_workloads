create table processed_events (
    event_id     varchar(64) primary key,
    processed_at timestamptz not null default now()
);

create table balances (
    account_id varchar(64) primary key,
    user_id    varchar(64),
    available  numeric(18, 2) not null,
    currency   varchar(3) not null,
    source     varchar(16) not null,
    updated_at timestamptz not null default now()
);
create index balances_user on balances(user_id);
