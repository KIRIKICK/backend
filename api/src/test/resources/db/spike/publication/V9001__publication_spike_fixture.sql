-- Local experiment only: not a product migration or FAILED classification policy.
create table publication_spike_records (
    id uuid primary key,
    user_id bigint not null references users(id),
    request_key uuid not null,
    state text not null check (state in ('PROCESSING', 'SUCCEEDED', 'FAILED', 'RETRYABLE')),
    attempt bigint not null,
    started_at timestamptz not null,
    terminal_at timestamptz,
    blocked_until timestamptz,
    expires_at timestamptz,
    game_id bigint references games(id),
    constraint uk_publication_spike_user_key unique (user_id, request_key)
);
