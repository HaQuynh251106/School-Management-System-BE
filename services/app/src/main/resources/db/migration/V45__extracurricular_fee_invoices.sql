ALTER TABLE public.club_registrations
    ADD COLUMN IF NOT EXISTS fee_period_id character varying(255),
    ADD COLUMN IF NOT EXISTS invoice_id character varying(255);

CREATE UNIQUE INDEX IF NOT EXISTS uk_club_registration_invoice
    ON public.club_registrations (invoice_id)
    WHERE invoice_id IS NOT NULL;
