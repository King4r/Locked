create extension if not exists pgcrypto;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  email text,
  role text not null default 'user' check (role in ('user','admin')),
  created_at timestamptz not null default now()
);

create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, email)
  values (new.id, new.email)
  on conflict (id) do update set email = excluded.email;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
after insert on auth.users
for each row execute procedure public.handle_new_user();

create or replace function public.is_admin()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.profiles
    where id = auth.uid() and role = 'admin'
  );
$$;

create table if not exists public.enrollment_codes (
  id uuid primary key default gen_random_uuid(),
  code_hash text not null unique,
  created_by uuid not null references auth.users(id),
  expires_at timestamptz not null,
  claimed_at timestamptz,
  claimed_device_id uuid,
  created_at timestamptz not null default now()
);

create table if not exists public.devices (
  id uuid primary key default gen_random_uuid(),
  installation_id text not null unique,
  name text not null,
  manufacturer text,
  model text,
  android_version text,
  sdk_int integer,
  battery_percent integer,
  status text not null default 'online' check (status in ('online','offline','revoked')),
  last_seen_at timestamptz not null default now(),
  enrolled_at timestamptz not null default now(),
  revoked_at timestamptz
);

create table if not exists public.device_events (
  id bigint generated always as identity primary key,
  device_id uuid references public.devices(id) on delete cascade,
  event_type text not null,
  details jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

alter table public.profiles enable row level security;
alter table public.enrollment_codes enable row level security;
alter table public.devices enable row level security;
alter table public.device_events enable row level security;

create policy "profiles read own or admin" on public.profiles
for select using (id = auth.uid() or public.is_admin());
create policy "admins update profiles" on public.profiles
for update using (public.is_admin()) with check (public.is_admin());

create policy "admins manage enrollment codes" on public.enrollment_codes
for all using (public.is_admin()) with check (public.is_admin());

create policy "admins read devices" on public.devices
for select using (public.is_admin());
create policy "admins update devices" on public.devices
for update using (public.is_admin()) with check (public.is_admin());
create policy "admins read events" on public.device_events
for select using (public.is_admin());

-- Device enrollment and heartbeat are exposed through narrowly scoped RPCs.
-- Clients cannot directly list devices or enrollment hashes.
create or replace function public.claim_enrollment_code(
  p_code_hash text,
  p_installation_id text,
  p_name text,
  p_manufacturer text,
  p_model text,
  p_android_version text,
  p_sdk_int integer,
  p_battery_percent integer
) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  v_code public.enrollment_codes%rowtype;
  v_device_id uuid;
begin
  select * into v_code
  from public.enrollment_codes
  where code_hash = p_code_hash
    and claimed_at is null
    and expires_at > now()
  for update;

  if not found then
    raise exception 'Invalid, expired, or already-used enrollment code';
  end if;

  insert into public.devices (
    installation_id, name, manufacturer, model, android_version,
    sdk_int, battery_percent, status, last_seen_at
  ) values (
    p_installation_id, p_name, p_manufacturer, p_model, p_android_version,
    p_sdk_int, p_battery_percent, 'online', now()
  )
  on conflict (installation_id) do update set
    name = excluded.name,
    manufacturer = excluded.manufacturer,
    model = excluded.model,
    android_version = excluded.android_version,
    sdk_int = excluded.sdk_int,
    battery_percent = excluded.battery_percent,
    status = 'online',
    last_seen_at = now(),
    revoked_at = null
  returning id into v_device_id;

  update public.enrollment_codes
  set claimed_at = now(), claimed_device_id = v_device_id
  where id = v_code.id;

  insert into public.device_events(device_id, event_type, details)
  values (v_device_id, 'enrolled', jsonb_build_object('name', p_name));

  return v_device_id;
end;
$$;

create or replace function public.device_heartbeat(
  p_installation_id text,
  p_battery_percent integer
) returns boolean
language plpgsql security definer set search_path = public as $$
begin
  update public.devices
  set battery_percent = p_battery_percent,
      status = 'online',
      last_seen_at = now()
  where installation_id = p_installation_id
    and status <> 'revoked';
  return found;
end;
$$;

grant execute on function public.claim_enrollment_code(text,text,text,text,text,text,integer,integer) to anon, authenticated;
grant execute on function public.device_heartbeat(text,integer) to anon, authenticated;

-- Realtime updates for the admin's device list.
alter publication supabase_realtime add table public.devices;
