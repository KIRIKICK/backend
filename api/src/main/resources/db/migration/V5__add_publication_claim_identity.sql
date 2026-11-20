alter table publication_idempotency
    add column claim_id uuid not null default gen_random_uuid();
