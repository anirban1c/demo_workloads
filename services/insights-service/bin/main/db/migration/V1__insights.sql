create table processed_events (
    event_id     varchar(64) primary key,
    processed_at timestamptz not null default now()
);

create table insights (
    request_id varchar(64) primary key,
    user_id    varchar(64) not null,
    period     varchar(10) not null,
    status     varchar(16) not null,
    summary    text,
    categories text,
    created_at timestamptz not null default now()
);
create index insights_user on insights(user_id, created_at desc);
