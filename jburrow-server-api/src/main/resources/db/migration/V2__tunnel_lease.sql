-- One server process's claims, written behind and read once at the next startup to seed holds.
-- No foreign key to server_instance: that row is written once the application is ready, and may not be.
create table tunnel_lease (
    instance_id uuid        not null,
    kind        text        not null check (kind in ('name', 'port')),
    lease_key   text        not null,
    principal   text        not null,
    active      boolean     not null,
    expires_at  timestamptz not null,
    primary key (instance_id, kind, lease_key)
);

create index ix_tunnel_lease_expires_at on tunnel_lease (expires_at);
