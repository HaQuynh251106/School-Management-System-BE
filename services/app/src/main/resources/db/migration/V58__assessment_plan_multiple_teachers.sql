-- Một kế hoạch đánh giá có thể do nhiều giáo viên cùng phụ trách.
-- Giữ academic_assessment_plans.teacher_id làm người phụ trách chính để tương
-- thích với client cũ; bảng liên kết dưới đây là nguồn dữ liệu đầy đủ.
CREATE TABLE public.academic_assessment_plan_teachers (
    id character varying(255) PRIMARY KEY,
    assessment_plan_id character varying(255) NOT NULL,
    teacher_id character varying(255) NOT NULL,
    primary_teacher boolean NOT NULL DEFAULT false,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_assessment_plan_teacher UNIQUE (assessment_plan_id, teacher_id),
    CONSTRAINT fk_assessment_plan_teacher_plan FOREIGN KEY (assessment_plan_id)
        REFERENCES public.academic_assessment_plans(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_plan_teacher_user FOREIGN KEY (teacher_id)
        REFERENCES public.users(id) ON DELETE RESTRICT
);

CREATE INDEX idx_assessment_plan_teacher_user
    ON public.academic_assessment_plan_teachers(teacher_id, assessment_plan_id);

INSERT INTO public.academic_assessment_plan_teachers (
    id, assessment_plan_id, teacher_id, primary_teacher, created_at
)
SELECT 'apt-' || md5(plan.id || ':' || plan.teacher_id),
       plan.id, plan.teacher_id, true, coalesce(plan.created_at, now())
FROM public.academic_assessment_plans plan
WHERE plan.teacher_id IS NOT NULL
  AND btrim(plan.teacher_id) <> ''
ON CONFLICT (assessment_plan_id, teacher_id) DO NOTHING;
