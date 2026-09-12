create table case_events (
    event_id uuid not null,
    tenant_id uuid not null,
    case_id uuid not null,
    stream_version bigint not null,
    event_type text not null,
    schema_version integer not null,
    payload jsonb not null,
    occurred_at timestamptz not null,
    recorded_at timestamptz not null default clock_timestamp(),
    constraint pk_case_events primary key (event_id),
    constraint uq_case_events_stream_version unique (tenant_id, case_id, stream_version),
    constraint uq_case_events_stream_identity unique (tenant_id, case_id, stream_version, event_id),
    constraint ck_case_events_stream_version_positive check (stream_version > 0),
    constraint ck_case_events_schema_version_positive check (schema_version > 0),
    constraint ck_case_events_event_type check (event_type in ('CaseOpened', 'ObservationRecorded')),
    constraint ck_case_events_payload_object check (jsonb_typeof(payload) = 'object')
);

create table cases (
    tenant_id uuid not null,
    case_id uuid not null,
    goal text not null,
    status text not null,
    stream_version bigint not null,
    opened_at timestamptz not null,
    updated_at timestamptz not null,
    constraint pk_cases primary key (tenant_id, case_id),
    constraint ck_cases_goal_length check (char_length(goal) between 1 and 500),
    constraint ck_cases_status check (status in ('OPEN')),
    constraint ck_cases_stream_version_positive check (stream_version > 0)
);

create table case_timeline_entries (
    tenant_id uuid not null,
    case_id uuid not null,
    stream_version bigint not null,
    event_id uuid not null,
    entry_type text not null,
    summary text not null,
    observation_id uuid not null,
    observation_origin_type text not null,
    observation_provider text not null,
    observation_reference text,
    observation_content text not null,
    occurred_at timestamptz not null,
    recorded_at timestamptz not null,
    constraint pk_case_timeline_entries primary key (tenant_id, case_id, stream_version),
    constraint uq_case_timeline_entries_event unique (event_id),
    constraint fk_case_timeline_entries_case foreign key (tenant_id, case_id)
        references cases (tenant_id, case_id),
    constraint fk_case_timeline_entries_event foreign key (tenant_id, case_id, stream_version, event_id)
        references case_events (tenant_id, case_id, stream_version, event_id),
    constraint ck_case_timeline_entries_stream_version_positive check (stream_version > 0),
    constraint ck_case_timeline_entries_type check (entry_type in ('CASE_OPENED', 'OBSERVATION_RECORDED')),
    constraint ck_case_timeline_entries_summary_length check (char_length(summary) between 1 and 200),
    constraint ck_case_timeline_entries_origin_type check (observation_origin_type in ('REQUESTER', 'CONNECTOR')),
    constraint ck_case_timeline_entries_provider_length check (char_length(observation_provider) between 1 and 100),
    constraint ck_case_timeline_entries_reference_length check (
        observation_reference is null or char_length(observation_reference) between 1 and 500
    ),
    constraint ck_case_timeline_entries_content_length check (
        char_length(observation_content) between 1 and 8000
    )
);
