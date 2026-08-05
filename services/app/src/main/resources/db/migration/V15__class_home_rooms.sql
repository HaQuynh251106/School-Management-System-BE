ALTER TABLE public.classes
    ADD COLUMN IF NOT EXISTS home_room_id character varying(255);

UPDATE public.classes c
SET home_room_id = r.id
FROM public.academic_years ay, public.rooms r
WHERE ay.id = c.academic_year_id
  AND c.home_room_id IS NULL
  AND upper(r.code) = upper('G0-' || replace(ay.code, '-', '') || '-' || c.code);

ALTER TABLE public.classes
    DROP CONSTRAINT IF EXISTS fk_classes_home_room;

ALTER TABLE public.classes
    ADD CONSTRAINT fk_classes_home_room
    FOREIGN KEY (home_room_id) REFERENCES public.rooms(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_classes_home_room
    ON public.classes (home_room_id)
    WHERE home_room_id IS NOT NULL;
