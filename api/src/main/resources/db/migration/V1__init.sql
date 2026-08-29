-- The section 8 data model, in full, as amended by fixes D and E.
--
-- payments.id is the natural key: 'PXE-006' for a loaded scenario, a generated
-- id for a live QR payment. It keeps /payment/[id] URLs and eval output legible.

create table payments (
    id             text primary key,
    merchant_id    text        not null,
    amount_minor   bigint      not null,
    currency       text        not null,
    instrument     text        not null,
    rail           text        not null,
    tag            text,
    response_code  text,
    created_at     timestamptz not null,
    terminal_at    timestamptz,
    debt_open      boolean     not null default false,
    debt_opened_at timestamptz,
    debt_closed_at timestamptz
);

-- The debt queue is WHERE debt_open = true ORDER BY amount_minor DESC.
create index payments_debt_queue_idx on payments (debt_open, amount_minor desc);

create table payment_hops (
    payment_id      text        not null references payments (id) on delete cascade,
    seq             int         not null,
    stage           text        not null,
    actor           text        not null,
    occurred_at     timestamptz,           -- null means the hop did not happen: section 19
    status          text        not null,
    code            text,
    latency_ms      bigint,
    retry_of        int,
    duplicate_of    int,
    amount_minor    bigint,
    batch           text,
    cycle           text,
    cutoff_at       timestamptz,
    missed_cutoff   boolean,
    included        boolean,
    bound_reference text,
    note            text,
    attrs           jsonb       not null default '{}'::jsonb,
    primary key (payment_id, seq)
);

-- Deliberately a table, not a column on payments. Supersession is within kind.
-- Not named "references": that is a reserved word in Postgres.
create table payment_references (
    id            bigserial primary key,
    payment_id    text   not null references payments (id) on delete cascade,
    hop_seq       int    not null,
    kind          text   not null,
    value         text   not null,
    valid_from    timestamptz,
    superseded_by bigint references payment_references (id)
);

create index payment_references_payment_idx on payment_references (payment_id, kind, hop_seq);

create table ledger_entries (
    id           bigserial primary key,
    payment_id   text        not null references payments (id) on delete cascade,
    kind         text        not null,
    amount_minor bigint      not null,
    direction    text        not null,
    posted_at    timestamptz not null
);

create table deviations (
    id          bigserial primary key,
    payment_id  text        not null references payments (id) on delete cascade,
    type        text        not null,
    detected_at timestamptz not null,
    expected    text,
    actual      text,
    severity    text
);

create table explanations (
    id             bigserial primary key,
    payment_id     text        not null references payments (id) on delete cascade,
    level          text        not null,
    path           text        not null,
    fact_set_hash  text        not null,
    prompt_version text,
    root_cause     text,
    determinable   boolean     not null default true,
    confidence     numeric(4, 3),
    hypothesis     boolean     not null default false,
    abstained      boolean     not null default false,
    claims         jsonb,
    citations      jsonb,
    merchant_text  text,
    support_text   text,
    engineer_text  text,
    generated_at   timestamptz not null
);

-- Section 16: an explanation whose inputs have not changed is never regenerated.
-- The cache is the constraint, not a convention.
create unique index explanations_fact_set_idx on explanations (payment_id, fact_set_hash);

create table rule_hits (
    id         bigserial primary key,
    payment_id text        not null references payments (id) on delete cascade,
    rule_id    text        not null,
    matched_at timestamptz not null,
    inputs     jsonb
);

create table model_calls (
    id                bigserial primary key,
    payment_id        text    not null references payments (id) on delete cascade,
    job               text    not null,
    admitted          boolean not null,
    priority          int,
    reason            text,
    prompt_tokens     int,
    completion_tokens int,
    latency_ms        bigint,
    rejected_by       text
);
