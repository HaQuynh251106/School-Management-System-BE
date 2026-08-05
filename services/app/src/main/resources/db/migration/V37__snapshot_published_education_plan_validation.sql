ALTER TABLE public.academic_training_plans
    ADD COLUMN IF NOT EXISTS validation_snapshot text,
    ADD COLUMN IF NOT EXISTS validated_at timestamp with time zone;
