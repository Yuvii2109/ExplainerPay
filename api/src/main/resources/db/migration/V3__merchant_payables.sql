-- Section 8.1: money the platform holds and has not handed to the merchant yet.
--
-- Ordinary accounts payable, kept apart from explanation debt on purpose. A payable is discharged
-- by money and a debt is discharged by an answer, so a payment can close one while opening the
-- other. That crossing is the point of carrying the table at all.
create table merchant_payables (
    id              text        primary key,
    merchant_id     text        not null,
    description     text        not null,
    due_on          date        not null,
    currency        text        not null default 'INR',
    amount_minor    bigint      not null,
    remaining_minor bigint      not null,
    settled_at      timestamptz,
    last_payment_id text,

    -- A payable cannot owe less than nothing or more than it was raised for. Overpaying settles
    -- it; it does not turn into credit, because this system does not hold balances.
    constraint merchant_payables_remaining_in_range
        check (remaining_minor >= 0 and remaining_minor <= amount_minor)
);

-- The queue is always read open first, oldest due date at the top, which is the order an ops team
-- works it in.
create index merchant_payables_open_idx on merchant_payables (merchant_id, due_on)
    where settled_at is null;
