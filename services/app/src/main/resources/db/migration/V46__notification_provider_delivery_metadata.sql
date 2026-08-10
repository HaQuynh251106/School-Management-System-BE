ALTER TABLE public.notification_delivery_logs
    ADD COLUMN IF NOT EXISTS channel character varying(255),
    ADD COLUMN IF NOT EXISTS provider character varying(255);

UPDATE public.notification_delivery_logs
SET channel = COALESCE(channel, 'IN_APP'), provider = COALESCE(provider, 'DATABASE')
WHERE channel IS NULL OR provider IS NULL;

CREATE INDEX IF NOT EXISTS idx_notification_delivery_provider
    ON public.notification_delivery_logs (channel, provider, attempted_at DESC);
