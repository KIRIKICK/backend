create table signup_applications (
    id uuid primary key,
    provider varchar(20) not null,
    provider_subject varchar(255) not null,
    expires_at timestamp with time zone not null,
    completed_user_id bigint,
    completed_display_name text,
    completed_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint ck_signup_applications_provider check (provider in ('KAKAO', 'APPLE')),
    constraint ck_signup_applications_completion check (
        (completed_user_id is null and completed_display_name is null and completed_at is null)
        or (completed_user_id is not null and completed_display_name is not null and completed_at is not null)
    ),
    constraint fk_signup_applications_completed_user
        foreign key (completed_user_id) references users (id) on delete restrict
);

create index idx_signup_applications_provider_subject
    on signup_applications (provider, provider_subject);
create index idx_signup_applications_pending_expiry
    on signup_applications (expires_at)
    where completed_at is null;
