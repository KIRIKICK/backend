-- Local experiment only: one transaction owns claim, business write, and success record.
create table publication_single_tx_records (
  user_id bigint not null references users (id),
  request_key uuid not null,
  input_text text not null,
  game_id bigint references games (id),
  expires_at timestamptz,
  constraint ck_publication_single_tx_completion
    check ((game_id is null) = (expires_at is null)),
  constraint uk_single_tx_user_key
    unique (user_id, request_key) not deferrable,
  constraint uk_single_tx_game
    unique (game_id) not deferrable
);
