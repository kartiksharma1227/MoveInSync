-- Lets OutboxEvent share BaseTenantEntity's audit columns; updated when a row is published or a publish fails.
ALTER TABLE outbox_event ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
