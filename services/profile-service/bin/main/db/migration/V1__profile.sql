create table processed_events (
    event_id     varchar(64) primary key,
    processed_at timestamptz not null default now()
);

create table profiles (
    user_id          varchar(64) primary key,
    display_name     varchar(200),
    phone            varchar(40),
    address_line     varchar(255),
    city             varchar(100),
    postcode         varchar(20),
    country          varchar(60),
    marketing_opt_in boolean not null default false,
    crm_contact_id   varchar(64),
    updated_at       timestamptz not null default now()
);
