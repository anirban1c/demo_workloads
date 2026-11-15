create table processed_events (
    event_id     varchar(64) primary key,
    processed_at timestamptz not null default now()
);

create table funds_transactions (
    id              varchar(64) primary key,
    request_id      varchar(64) not null,
    account_id      varchar(64) not null,
    user_id         varchar(64),
    amount          numeric(18, 2) not null,
    currency        varchar(3) not null,
    status          varchar(16) not null,
    charge_id       varchar(64),
    ledger_entry_id varchar(64),
    error           text,
    created_at      timestamptz not null default now()
);
create index funds_transactions_account on funds_transactions(account_id);
