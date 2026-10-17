create table server_instance (
    instance_id  uuid        primary key,
    version      text        not null,
    git_commit   text        not null,
    profiles     text        not null,
    started_at   timestamptz not null
);

create index ix_server_instance_started_at on server_instance (started_at desc);
