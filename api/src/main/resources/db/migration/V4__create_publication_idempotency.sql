create table publication_idempotency (
    user_id bigint not null,
    request_key text not null,
    normalized_input jsonb not null,
    game_id bigint,
    record_expires_at timestamptz(6),
    constraint pk_publication_idempotency primary key (user_id, request_key),
    constraint fk_publication_idempotency_user
        foreign key (user_id) references users (id) on delete no action not deferrable,
    constraint fk_publication_idempotency_game
        foreign key (game_id) references games (id) on delete no action not deferrable,
    constraint uk_publication_idempotency_game unique (game_id),
    constraint ck_publication_idempotency_key_length
        check (octet_length(request_key) between 1 and 255),
    constraint ck_publication_idempotency_input_object
        check (jsonb_typeof(normalized_input) = 'object'),
    constraint ck_publication_idempotency_completion
        check ((game_id is null) = (record_expires_at is null))
);
