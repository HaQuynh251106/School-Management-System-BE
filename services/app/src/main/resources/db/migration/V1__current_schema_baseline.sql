--
-- PostgreSQL database dump
--


-- Dumped from database version 16.14
-- Dumped by pg_dump version 16.14

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: academic_promotion_policies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.academic_promotion_policies (
    id character varying(255) NOT NULL,
    academic_year_id character varying(255) NOT NULL,
    maximum_subjects_below_minimum integer,
    minimum_attendance_rate double precision,
    minimum_conduct_grade character varying(255),
    minimum_yearly_average double precision,
    subject_minimum_score double precision,
    updated_at timestamp(6) with time zone,
    updated_by character varying(255)
);


--
-- Name: academic_result_locks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.academic_result_locks (
    id character varying(255) NOT NULL,
    academic_year_id character varying(255),
    class_id character varying(255),
    locked_at timestamp(6) with time zone,
    locked_by character varying(255),
    reason character varying(255),
    semester_id character varying(255)
);


--
-- Name: academic_years; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.academic_years (
    id character varying(255) NOT NULL,
    code character varying(255),
    end_date date,
    name character varying(255),
    start_date date,
    status character varying(255)
);


--
-- Name: announcements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.announcements (
    id character varying(255) NOT NULL,
    audience character varying(255),
    body character varying(4000),
    created_at timestamp(6) with time zone,
    created_by character varying(255),
    title character varying(255)
);


--
-- Name: assignment_submission_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.assignment_submission_versions (
    id character varying(255) NOT NULL,
    attachment_content_type character varying(255),
    attachment_file_id character varying(255),
    attachment_name character varying(255),
    attachment_size_bytes bigint,
    content character varying(4000),
    submission_id character varying(255),
    submitted_at timestamp(6) with time zone,
    submitted_by character varying(255),
    version_no integer
);


--
-- Name: assignment_submissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.assignment_submissions (
    id character varying(255) NOT NULL,
    assignment_id character varying(255),
    attachment_name character varying(255),
    content character varying(4000),
    feedback character varying(2000),
    graded_at timestamp(6) with time zone,
    graded_by character varying(255),
    score double precision,
    status character varying(255),
    student_id character varying(255),
    student_name character varying(255),
    submitted_at timestamp(6) with time zone,
    attachment_content_type character varying(255),
    attachment_file_id character varying(255),
    attachment_file_key character varying(700),
    attachment_size_bytes bigint,
    current_version integer
);


--
-- Name: assignments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.assignments (
    id character varying(255) NOT NULL,
    allow_late boolean NOT NULL,
    attachment_name character varying(255),
    class_id character varying(255),
    created_at timestamp(6) with time zone,
    deadline timestamp(6) with time zone,
    description character varying(4000),
    status character varying(255),
    subject_id character varying(255),
    subject_name character varying(255),
    teacher_id character varying(255),
    teacher_name character varying(255),
    title character varying(255),
    attachment_content_type character varying(255),
    attachment_file_id character varying(255),
    attachment_file_key character varying(700),
    attachment_size_bytes bigint,
    last_reminder_at timestamp(6) with time zone,
    reminder_count integer,
    updated_at timestamp(6) with time zone
);


--
-- Name: attendance_excuse_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.attendance_excuse_requests (
    id character varying(255) NOT NULL,
    attendance_record_id character varying(255),
    reason character varying(255),
    requested_at timestamp(6) with time zone,
    requested_by character varying(255),
    requester_role character varying(255),
    review_note character varying(255),
    reviewed_at timestamp(6) with time zone,
    reviewed_by character varying(255),
    status character varying(255),
    student_id character varying(255)
);


--
-- Name: attendance_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.attendance_records (
    id character varying(255) NOT NULL,
    class_id character varying(255),
    date date,
    note character varying(255),
    period_no integer,
    slot_id character varying(255),
    status character varying(255),
    student_id character varying(255),
    subject_name character varying(255),
    late_minutes integer
);


--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    id character varying(255) NOT NULL,
    action character varying(255),
    actor_id character varying(255),
    actor_name character varying(255),
    created_at timestamp(6) with time zone,
    detail character varying(1000),
    entity_id character varying(255),
    entity_type character varying(255),
    module character varying(255),
    role character varying(255)
);


--
-- Name: bank_statement_entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bank_statement_entries (
    id character varying(255) NOT NULL,
    amount bigint NOT NULL,
    bank_code character varying(255),
    import_batch_id character varying(255),
    imported_at timestamp(6) with time zone,
    imported_by character varying(255),
    matched_invoice_id character varying(255),
    matched_payment_id character varying(255),
    mismatch_reason character varying(255),
    status character varying(255),
    transaction_reference character varying(255),
    transfer_content character varying(1000),
    transferred_at timestamp(6) with time zone
);


--
-- Name: chat_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages (
    id character varying(255) NOT NULL,
    body character varying(2000),
    created_at timestamp(6) with time zone,
    read_flag boolean NOT NULL,
    recipient_id character varying(255),
    recipient_name character varying(255),
    sender_id character varying(255),
    sender_name character varying(255)
);


--
-- Name: classes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.classes (
    id character varying(255) NOT NULL,
    academic_year_id character varying(255),
    code character varying(255),
    grade_level character varying(255),
    homeroom_teacher_id character varying(255),
    name character varying(255),
    student_count integer NOT NULL,
    max_students integer
);


--
-- Name: club_registrations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.club_registrations (
    id character varying(255) NOT NULL,
    club_id character varying(255),
    club_name character varying(255),
    registered_at timestamp(6) with time zone,
    registered_by character varying(255),
    status character varying(255),
    student_id character varying(255),
    student_name character varying(255)
);


--
-- Name: clubs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.clubs (
    id character varying(255) NOT NULL,
    capacity integer NOT NULL,
    created_at timestamp(6) with time zone,
    description character varying(2000),
    fee bigint NOT NULL,
    name character varying(255),
    schedule character varying(255),
    status character varying(255)
);


--
-- Name: exam_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.exam_categories (
    id character varying(255) NOT NULL,
    code character varying(255),
    name character varying(255),
    weight double precision NOT NULL
);


--
-- Name: fee_period_item_targets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fee_period_item_targets (
    id character varying(255) NOT NULL,
    fee_period_item_id character varying(255),
    target_id character varying(255),
    target_type character varying(255)
);


--
-- Name: fee_period_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fee_period_items (
    id character varying(255) NOT NULL,
    amount bigint NOT NULL,
    fee_period_id character varying(255),
    grade_level character varying(255),
    name character varying(255),
    target_type character varying(255) DEFAULT 'ALL'::character varying NOT NULL,
    CONSTRAINT ck_fee_period_item_target_type CHECK (((target_type)::text = ANY ((ARRAY['ALL'::character varying, 'GRADE'::character varying, 'CLASS'::character varying, 'STUDENT'::character varying])::text[])))
);


--
-- Name: fee_period_targets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fee_period_targets (
    id character varying(255) NOT NULL,
    fee_period_id character varying(255),
    target_id character varying(255),
    target_type character varying(255)
);


--
-- Name: fee_periods; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fee_periods (
    id character varying(255) NOT NULL,
    academic_year_id character varying(255),
    apply_to_grades character varying(255),
    code character varying(255),
    created_at timestamp(6) with time zone,
    due_date date,
    name character varying(255),
    status character varying(255),
    cancellation_reason character varying(500),
    cancelled_at timestamp(6) with time zone,
    closed_at timestamp(6) with time zone,
    target_type character varying(255) DEFAULT 'ALL'::character varying NOT NULL,
    published_at timestamp(6) with time zone,
    fee_type character varying(255) DEFAULT 'OTHER'::character varying NOT NULL,
    semester_id character varying(255),
    CONSTRAINT ck_fee_period_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'OPEN'::character varying, 'PUBLISHED'::character varying, 'CLOSED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT ck_fee_period_target_type CHECK (((target_type)::text = ANY ((ARRAY['ALL'::character varying, 'GRADE'::character varying, 'CLASS'::character varying, 'STUDENT'::character varying])::text[])))
);


--
-- Name: grade_change_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.grade_change_logs (
    id character varying(255) NOT NULL,
    changed_at timestamp(6) with time zone,
    changed_by character varying(255),
    grade_id character varying(255),
    new_note character varying(255),
    new_score double precision,
    old_note character varying(255),
    old_score double precision,
    reason character varying(255)
);


--
-- Name: grades; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.grades (
    id character varying(255) NOT NULL,
    category character varying(255),
    category_name character varying(255),
    note character varying(255),
    recorded_at timestamp(6) with time zone,
    score double precision,
    semester_id character varying(255),
    student_id character varying(255),
    subject_id character varying(255),
    subject_name character varying(255)
);


--
-- Name: invoice_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.invoice_items (
    id character varying(255) NOT NULL,
    amount bigint NOT NULL,
    invoice_id character varying(255),
    name character varying(255),
    fee_period_item_id character varying(255),
    source_target_type character varying(255)
);


--
-- Name: invoices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.invoices (
    id character varying(255) NOT NULL,
    code character varying(255),
    due_date date,
    fee_period_id character varying(255),
    issued_at timestamp(6) with time zone,
    paid_amount bigint NOT NULL,
    parent_id character varying(255),
    status character varying(255),
    student_id character varying(255),
    student_name character varying(255),
    total_amount bigint NOT NULL,
    last_reminder_at timestamp(6) with time zone,
    reminder_count integer DEFAULT 0
);


--
-- Name: login_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.login_history (
    id character varying(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    failure_reason character varying(255),
    ip_address character varying(255),
    success boolean NOT NULL,
    user_agent character varying(1000),
    user_id character varying(255),
    username character varying(255)
);


--
-- Name: notification_delivery_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification_delivery_logs (
    id character varying(255) NOT NULL,
    attempt_no integer NOT NULL,
    attempted_at timestamp(6) with time zone NOT NULL,
    error_message character varying(1000),
    notification_id character varying(255) NOT NULL,
    provider_response character varying(2000),
    status character varying(255) NOT NULL
);


--
-- Name: notification_templates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification_templates (
    id character varying(255) NOT NULL,
    active boolean NOT NULL,
    body_template character varying(2000),
    channel character varying(255),
    code character varying(255),
    name character varying(255),
    title_template character varying(255)
);


--
-- Name: notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notifications (
    id character varying(255) NOT NULL,
    body character varying(2000),
    created_at timestamp(6) with time zone,
    read boolean NOT NULL,
    recipient_id character varying(255),
    ref_id character varying(255),
    ref_type character varying(255),
    title character varying(255),
    type character varying(255),
    channel character varying(255),
    error_message character varying(1000),
    sent_at timestamp(6) with time zone,
    status character varying(255),
    attempt_count integer,
    deep_link character varying(255),
    group_key character varying(255),
    read_at timestamp(6) with time zone
);


--
-- Name: parent_student; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.parent_student (
    id character varying(255) NOT NULL,
    parent_id character varying(255) NOT NULL,
    primary_contact boolean NOT NULL,
    student_id character varying(255) NOT NULL
);


--
-- Name: password_reset_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.password_reset_tokens (
    id character varying(255) NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    token_hash character varying(255) NOT NULL,
    used_at timestamp(6) with time zone,
    user_id character varying(255) NOT NULL
);


--
-- Name: payment_gateway_transactions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_gateway_transactions (
    id character varying(255) NOT NULL,
    callback_count integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    error_code character varying(255),
    error_message character varying(500),
    last_callback_at timestamp(6) with time zone,
    merchant_txn_ref character varying(255) NOT NULL,
    payment_id character varying(255),
    processed boolean NOT NULL,
    processed_at timestamp(6) with time zone,
    provider character varying(255) NOT NULL,
    provider_transaction_id character varying(255),
    request_payload text,
    response_payload text,
    signature_valid boolean,
    updated_at timestamp(6) with time zone,
    CONSTRAINT ck_gateway_tx_callback_count CHECK ((callback_count >= 0))
);


--
-- Name: payment_proofs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_proofs (
    id character varying(255) NOT NULL,
    amount bigint NOT NULL,
    content_type character varying(255),
    file_id character varying(255) NOT NULL,
    file_name character varying(255),
    invoice_code character varying(255),
    invoice_id character varying(255) NOT NULL,
    parent_id character varying(255),
    payment_id character varying(255) NOT NULL,
    review_reason character varying(500),
    reviewed_at timestamp(6) with time zone,
    reviewed_by character varying(255),
    size_bytes bigint NOT NULL,
    status character varying(255) NOT NULL,
    student_code character varying(255),
    student_id character varying(255) NOT NULL,
    student_name character varying(255),
    submitted_at timestamp(6) with time zone NOT NULL,
    submitted_by character varying(255) NOT NULL,
    transferred_at timestamp with time zone,
    bank_transaction_code character varying(100),
    CONSTRAINT ck_payment_proof_amount CHECK ((amount > 0)),
    CONSTRAINT ck_payment_proof_size CHECK (((size_bytes > 0) AND (size_bytes <= 5242880))),
    CONSTRAINT ck_payment_proof_status CHECK (((status)::text = ANY ((ARRAY['SUBMITTED'::character varying, 'APPROVED'::character varying, 'RETRY_REQUIRED'::character varying])::text[])))
);


--
-- Name: payment_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_receipts (
    id character varying(255) NOT NULL,
    amount bigint NOT NULL,
    file_id character varying(255),
    generated_at timestamp(6) with time zone,
    generation_attempts integer NOT NULL,
    generation_error character varying(500),
    invoice_code character varying(255),
    invoice_id character varying(255) NOT NULL,
    issued_at timestamp(6) with time zone NOT NULL,
    issued_by character varying(255) NOT NULL,
    method character varying(255),
    parent_id character varying(255),
    payment_id character varying(255) NOT NULL,
    receipt_number character varying(80) NOT NULL,
    status character varying(24) NOT NULL,
    student_code character varying(255),
    student_id character varying(255) NOT NULL,
    student_name character varying(255),
    previous_file_id character varying(255),
    revision integer,
    void_reason character varying(500),
    voided_at timestamp(6) with time zone,
    voided_by character varying(255),
    CONSTRAINT ck_payment_receipt_amount CHECK ((amount > 0)),
    CONSTRAINT ck_payment_receipt_attempts CHECK ((generation_attempts >= 0)),
    CONSTRAINT ck_payment_receipt_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ISSUED'::character varying, 'FAILED'::character varying, 'VOID'::character varying])::text[])))
);


--
-- Name: payment_reconciliation_issues; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_reconciliation_issues (
    id character varying(255) NOT NULL,
    actual_amount bigint,
    created_at timestamp(6) with time zone NOT NULL,
    entity_id character varying(255) NOT NULL,
    entity_type character varying(40) NOT NULL,
    expected_amount bigint,
    issue_type character varying(60) NOT NULL,
    message character varying(700) NOT NULL,
    run_id character varying(255) NOT NULL,
    severity character varying(16) NOT NULL,
    CONSTRAINT ck_payment_reconciliation_issue_severity CHECK (((severity)::text = ANY ((ARRAY['ERROR'::character varying, 'WARNING'::character varying])::text[])))
);


--
-- Name: payment_reconciliation_method_summaries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_reconciliation_method_summaries (
    id character varying(255) NOT NULL,
    gross_amount bigint NOT NULL,
    method character varying(40) NOT NULL,
    net_amount bigint NOT NULL,
    payment_count integer NOT NULL,
    refund_amount bigint NOT NULL,
    refund_count integer NOT NULL,
    run_id character varying(255) NOT NULL,
    CONSTRAINT ck_payment_reconciliation_method_counts CHECK (((payment_count >= 0) AND (refund_count >= 0)))
);


--
-- Name: payment_reconciliation_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_reconciliation_runs (
    id character varying(255) NOT NULL,
    discrepancy_count integer NOT NULL,
    gross_amount bigint NOT NULL,
    net_amount bigint NOT NULL,
    payment_count integer NOT NULL,
    reconciliation_date date NOT NULL,
    refund_amount bigint NOT NULL,
    refund_count integer NOT NULL,
    run_at timestamp(6) with time zone NOT NULL,
    run_by character varying(255) NOT NULL,
    run_count integer NOT NULL,
    status character varying(24) NOT NULL,
    from_date date NOT NULL,
    max_amount bigint,
    payment_method character varying(40),
    min_amount bigint,
    scope_key character varying(255) NOT NULL,
    to_date date NOT NULL,
    CONSTRAINT ck_payment_reconciliation_amount_range CHECK ((((min_amount IS NULL) OR (min_amount >= 0)) AND ((max_amount IS NULL) OR (max_amount >= 0)) AND ((min_amount IS NULL) OR (max_amount IS NULL) OR (min_amount <= max_amount)))),
    CONSTRAINT ck_payment_reconciliation_counts CHECK (((payment_count >= 0) AND (refund_count >= 0) AND (discrepancy_count >= 0) AND (run_count > 0))),
    CONSTRAINT ck_payment_reconciliation_method CHECK (((payment_method IS NULL) OR ((payment_method)::text = ANY ((ARRAY['VNPAY'::character varying, 'MOMO'::character varying, 'CASH'::character varying, 'MB_BANK_TRANSFER'::character varying])::text[])))),
    CONSTRAINT ck_payment_reconciliation_status CHECK (((status)::text = ANY ((ARRAY['BALANCED'::character varying, 'DISCREPANCY'::character varying])::text[])))
);


--
-- Name: payment_refunds; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_refunds (
    id character varying(255) NOT NULL,
    amount bigint NOT NULL,
    approved_at timestamp(6) with time zone,
    approved_by character varying(255),
    cancellation_reason character varying(500),
    cancelled_at timestamp(6) with time zone,
    cancelled_by character varying(255),
    completed_at timestamp(6) with time zone,
    invoice_code character varying(255),
    invoice_id character varying(255) NOT NULL,
    parent_id character varying(255),
    payment_id character varying(255) NOT NULL,
    reason character varying(500) NOT NULL,
    refund_method character varying(40),
    refund_number character varying(80) NOT NULL,
    refund_reference character varying(120),
    rejected_at timestamp(6) with time zone,
    rejected_by character varying(255),
    rejection_reason character varying(500),
    requested_at timestamp(6) with time zone NOT NULL,
    requested_by character varying(255) NOT NULL,
    status character varying(24) NOT NULL,
    student_code character varying(255),
    student_id character varying(255) NOT NULL,
    student_name character varying(255),
    updated_at timestamp(6) with time zone,
    invoice_paid_amount_after bigint,
    invoice_paid_amount_before bigint,
    invoice_status_after character varying(24),
    invoice_status_before character varying(24),
    payment_amount bigint,
    refund_type character varying(16),
    refunded_amount_after bigint,
    refunded_amount_before bigint,
    CONSTRAINT ck_payment_refund_amount CHECK ((amount > 0)),
    CONSTRAINT ck_payment_refund_method CHECK (((refund_method IS NULL) OR ((refund_method)::text = ANY ((ARRAY['MB_BANK_TRANSFER'::character varying, 'CASH'::character varying, 'OTHER'::character varying])::text[])))),
    CONSTRAINT ck_payment_refund_snapshots CHECK ((((payment_amount IS NULL) OR (payment_amount > 0)) AND ((refunded_amount_before IS NULL) OR (refunded_amount_before >= 0)) AND ((refunded_amount_after IS NULL) OR (refunded_amount_after >= 0)) AND ((invoice_paid_amount_before IS NULL) OR (invoice_paid_amount_before >= 0)) AND ((invoice_paid_amount_after IS NULL) OR (invoice_paid_amount_after >= 0)) AND ((refunded_amount_before IS NULL) OR (refunded_amount_after IS NULL) OR (refunded_amount_before <= refunded_amount_after)))),
    CONSTRAINT ck_payment_refund_status CHECK (((status)::text = ANY ((ARRAY['REQUESTED'::character varying, 'COMPLETED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT ck_payment_refund_type CHECK (((refund_type)::text = ANY ((ARRAY['PARTIAL'::character varying, 'FULL'::character varying])::text[])))
);


--
-- Name: payments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payments (
    id character varying(255) NOT NULL,
    amount bigint NOT NULL,
    created_at timestamp(6) with time zone,
    invoice_id character varying(255),
    method character varying(255),
    paid_at timestamp(6) with time zone,
    status character varying(255),
    txn_ref character varying(255),
    note character varying(500),
    updated_at timestamp(6) with time zone,
    bank_qr_url character varying(1000),
    bank_transfer_content character varying(255),
    auto_provisioned boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_payment_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'REVERSED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: refresh_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_tokens (
    id character varying(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    ip_address character varying(255),
    revoked_at timestamp(6) with time zone,
    token_hash character varying(128) NOT NULL,
    user_agent character varying(1000),
    user_id character varying(255) NOT NULL
);


--
-- Name: rooms; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rooms (
    id character varying(255) NOT NULL,
    capacity integer,
    code character varying(255),
    name character varying(255)
);


--
-- Name: school_holidays; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.school_holidays (
    id character varying(255) NOT NULL,
    date date,
    description character varying(255),
    name character varying(255)
);


--
-- Name: semesters; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.semesters (
    id character varying(255) NOT NULL,
    academic_year_id character varying(255),
    code character varying(255),
    end_date date,
    name character varying(255),
    sequence integer NOT NULL,
    start_date date,
    status character varying(255)
);


--
-- Name: stored_files; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stored_files (
    id character varying(255) NOT NULL,
    completed_at timestamp(6) with time zone,
    content_type character varying(160) NOT NULL,
    created_at timestamp(6) with time zone,
    file_key character varying(700) NOT NULL,
    original_name character varying(255) NOT NULL,
    scope character varying(24) NOT NULL,
    size_bytes bigint NOT NULL,
    status character varying(24) NOT NULL,
    uploaded_by character varying(255) NOT NULL
);


--
-- Name: student_class_enrollments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.student_class_enrollments (
    id character varying(255) NOT NULL,
    academic_year_id character varying(255),
    class_id character varying(255),
    enrolled_at timestamp(6) with time zone,
    enrolled_by character varying(255),
    enrollment_type character varying(255),
    source_academic_year_id character varying(255),
    source_class_id character varying(255),
    source_summary_id character varying(255),
    status character varying(255),
    student_code character varying(255),
    student_id character varying(255),
    student_name character varying(255),
    revert_reason text,
    reverted_at timestamp(6) with time zone,
    reverted_by character varying(255)
);


--
-- Name: student_yearly_summaries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.student_yearly_summaries (
    id character varying(255) NOT NULL,
    academic_year_id character varying(255),
    attendance_rate double precision,
    class_id character varying(255),
    finalized_at timestamp(6) with time zone,
    finalized_by character varying(255),
    reason character varying(255),
    result character varying(255),
    reviewed_at timestamp(6) with time zone,
    reviewed_by character varying(255),
    status character varying(255),
    student_code character varying(255),
    student_id character varying(255),
    student_name character varying(255),
    updated_at timestamp(6) with time zone,
    yearly_average double precision,
    conduct_grade character varying(255),
    next_class_id character varying(255),
    progressed_at timestamp(6) with time zone,
    progressed_by character varying(255),
    progression_status character varying(255),
    semester_results_json text,
    subject_results_json text,
    CONSTRAINT ck_yearly_summary_result CHECK (((result)::text = ANY ((ARRAY['PROMOTED'::character varying, 'RETAINED'::character varying, 'GRADUATED'::character varying, 'ELIGIBLE_FOR_GRADUATION'::character varying, 'INCOMPLETE'::character varying, 'PENDING_REVIEW'::character varying])::text[]))),
    CONSTRAINT ck_yearly_summary_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'FINALIZED'::character varying])::text[])))
);


--
-- Name: subjects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.subjects (
    id character varying(255) NOT NULL,
    code character varying(255),
    name character varying(255)
);


--
-- Name: submission_resubmission_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.submission_resubmission_requests (
    id character varying(255) NOT NULL,
    allowed_until timestamp(6) with time zone,
    assignment_id character varying(255),
    reason character varying(1000),
    requested_at timestamp(6) with time zone,
    requested_by character varying(255),
    status character varying(255),
    student_id character varying(255),
    submission_id character varying(255),
    used_at timestamp(6) with time zone
);


--
-- Name: teacher_class_subjects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.teacher_class_subjects (
    id character varying(255) NOT NULL,
    class_code character varying(255),
    class_id character varying(255) NOT NULL,
    created_at timestamp(6) with time zone,
    semester_id character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    subject_id character varying(255) NOT NULL,
    subject_name character varying(255),
    teacher_id character varying(255) NOT NULL,
    teacher_name character varying(255),
    updated_at timestamp(6) with time zone,
    weekly_periods integer
);


--
-- Name: timetable_slots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.timetable_slots (
    id character varying(255) NOT NULL,
    class_id character varying(255),
    day_of_week character varying(255),
    end_time character varying(255),
    period_no integer NOT NULL,
    room_code character varying(255),
    semester_id character varying(255),
    start_time character varying(255),
    subject_id character varying(255),
    subject_name character varying(255),
    teacher_id character varying(255),
    teacher_name character varying(255)
);


--
-- Name: user_devices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_devices (
    id character varying(255) NOT NULL,
    active boolean NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    device_name character varying(255),
    device_token character varying(1000) NOT NULL,
    last_seen_at timestamp(6) with time zone NOT NULL,
    platform character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL
);


--
-- Name: user_notification_preferences; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_notification_preferences (
    id character varying(255) NOT NULL,
    channel character varying(255),
    enabled boolean NOT NULL,
    notification_type character varying(255),
    updated_at timestamp(6) with time zone,
    user_id character varying(255)
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id character varying(255) NOT NULL,
    avatar_url character varying(255),
    class_id character varying(255),
    class_name character varying(255),
    created_at timestamp(6) with time zone,
    email character varying(255),
    full_name character varying(255),
    main_subject character varying(255),
    password_hash character varying(255) NOT NULL,
    phone character varying(255),
    role character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    student_code character varying(255),
    teacher_code character varying(255),
    username character varying(255) NOT NULL
);


--
-- Name: year_result_publication_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.year_result_publication_history (
    id character varying(255) NOT NULL,
    academic_year_id character varying(255),
    action character varying(255),
    actor_id character varying(255),
    class_id character varying(255),
    occurred_at timestamp(6) with time zone,
    publication_id character varying(255),
    publication_version integer,
    reason text,
    student_count integer
);


--
-- Name: year_result_publications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.year_result_publications (
    id character varying(255) NOT NULL,
    academic_year_id character varying(255),
    class_id character varying(255),
    published_at timestamp(6) with time zone,
    published_by character varying(255),
    status character varying(255),
    student_count integer,
    updated_at timestamp(6) with time zone,
    last_publish_reason text,
    publication_version integer DEFAULT 0 NOT NULL,
    withdrawal_reason text,
    withdrawn_at timestamp(6) with time zone,
    withdrawn_by character varying(255)
);


--
-- Name: academic_promotion_policies academic_promotion_policies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.academic_promotion_policies
    ADD CONSTRAINT academic_promotion_policies_pkey PRIMARY KEY (id);


--
-- Name: academic_result_locks academic_result_locks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.academic_result_locks
    ADD CONSTRAINT academic_result_locks_pkey PRIMARY KEY (id);


--
-- Name: academic_years academic_years_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.academic_years
    ADD CONSTRAINT academic_years_pkey PRIMARY KEY (id);


--
-- Name: announcements announcements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.announcements
    ADD CONSTRAINT announcements_pkey PRIMARY KEY (id);


--
-- Name: assignment_submission_versions assignment_submission_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assignment_submission_versions
    ADD CONSTRAINT assignment_submission_versions_pkey PRIMARY KEY (id);


--
-- Name: assignment_submissions assignment_submissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assignment_submissions
    ADD CONSTRAINT assignment_submissions_pkey PRIMARY KEY (id);


--
-- Name: assignments assignments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assignments
    ADD CONSTRAINT assignments_pkey PRIMARY KEY (id);


--
-- Name: attendance_excuse_requests attendance_excuse_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attendance_excuse_requests
    ADD CONSTRAINT attendance_excuse_requests_pkey PRIMARY KEY (id);


--
-- Name: attendance_records attendance_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attendance_records
    ADD CONSTRAINT attendance_records_pkey PRIMARY KEY (id);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: bank_statement_entries bank_statement_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_statement_entries
    ADD CONSTRAINT bank_statement_entries_pkey PRIMARY KEY (id);


--
-- Name: chat_messages chat_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_pkey PRIMARY KEY (id);


--
-- Name: payment_refunds ck_payment_refund_independent_reviewer; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.payment_refunds
    ADD CONSTRAINT ck_payment_refund_independent_reviewer CHECK (((((status)::text <> 'COMPLETED'::text) OR ((approved_by IS NOT NULL) AND ((approved_by)::text <> (requested_by)::text))) AND (((status)::text <> 'REJECTED'::text) OR ((rejected_by IS NOT NULL) AND ((rejected_by)::text <> (requested_by)::text))))) NOT VALID;


--
-- Name: classes classes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.classes
    ADD CONSTRAINT classes_pkey PRIMARY KEY (id);


--
-- Name: club_registrations club_registrations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.club_registrations
    ADD CONSTRAINT club_registrations_pkey PRIMARY KEY (id);


--
-- Name: clubs clubs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.clubs
    ADD CONSTRAINT clubs_pkey PRIMARY KEY (id);


--
-- Name: exam_categories exam_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_categories
    ADD CONSTRAINT exam_categories_pkey PRIMARY KEY (id);


--
-- Name: fee_period_item_targets fee_period_item_targets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_period_item_targets
    ADD CONSTRAINT fee_period_item_targets_pkey PRIMARY KEY (id);


--
-- Name: fee_period_items fee_period_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_period_items
    ADD CONSTRAINT fee_period_items_pkey PRIMARY KEY (id);


--
-- Name: fee_period_targets fee_period_targets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_period_targets
    ADD CONSTRAINT fee_period_targets_pkey PRIMARY KEY (id);


--
-- Name: fee_periods fee_periods_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_periods
    ADD CONSTRAINT fee_periods_pkey PRIMARY KEY (id);


--
-- Name: grade_change_logs grade_change_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.grade_change_logs
    ADD CONSTRAINT grade_change_logs_pkey PRIMARY KEY (id);


--
-- Name: grades grades_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.grades
    ADD CONSTRAINT grades_pkey PRIMARY KEY (id);


--
-- Name: refresh_tokens idx_rt_hash; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT idx_rt_hash UNIQUE (token_hash);


--
-- Name: stored_files idx_stored_file_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stored_files
    ADD CONSTRAINT idx_stored_file_key UNIQUE (file_key);


--
-- Name: invoice_items invoice_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoice_items
    ADD CONSTRAINT invoice_items_pkey PRIMARY KEY (id);


--
-- Name: invoices invoices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_pkey PRIMARY KEY (id);


--
-- Name: login_history login_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.login_history
    ADD CONSTRAINT login_history_pkey PRIMARY KEY (id);


--
-- Name: notification_delivery_logs notification_delivery_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_delivery_logs
    ADD CONSTRAINT notification_delivery_logs_pkey PRIMARY KEY (id);


--
-- Name: notification_templates notification_templates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_templates
    ADD CONSTRAINT notification_templates_pkey PRIMARY KEY (id);


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);


--
-- Name: parent_student parent_student_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.parent_student
    ADD CONSTRAINT parent_student_pkey PRIMARY KEY (id);


--
-- Name: password_reset_tokens password_reset_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_pkey PRIMARY KEY (id);


--
-- Name: payment_gateway_transactions payment_gateway_transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_gateway_transactions
    ADD CONSTRAINT payment_gateway_transactions_pkey PRIMARY KEY (id);


--
-- Name: payment_proofs payment_proofs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_proofs
    ADD CONSTRAINT payment_proofs_pkey PRIMARY KEY (id);


--
-- Name: payment_receipts payment_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_receipts
    ADD CONSTRAINT payment_receipts_pkey PRIMARY KEY (id);


--
-- Name: payment_reconciliation_issues payment_reconciliation_issues_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_reconciliation_issues
    ADD CONSTRAINT payment_reconciliation_issues_pkey PRIMARY KEY (id);


--
-- Name: payment_reconciliation_method_summaries payment_reconciliation_method_summaries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_reconciliation_method_summaries
    ADD CONSTRAINT payment_reconciliation_method_summaries_pkey PRIMARY KEY (id);


--
-- Name: payment_reconciliation_runs payment_reconciliation_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_reconciliation_runs
    ADD CONSTRAINT payment_reconciliation_runs_pkey PRIMARY KEY (id);


--
-- Name: payment_refunds payment_refunds_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_refunds
    ADD CONSTRAINT payment_refunds_pkey PRIMARY KEY (id);


--
-- Name: payments payments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_pkey PRIMARY KEY (id);


--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);


--
-- Name: rooms rooms_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rooms
    ADD CONSTRAINT rooms_pkey PRIMARY KEY (id);


--
-- Name: school_holidays school_holidays_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.school_holidays
    ADD CONSTRAINT school_holidays_pkey PRIMARY KEY (id);


--
-- Name: semesters semesters_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.semesters
    ADD CONSTRAINT semesters_pkey PRIMARY KEY (id);


--
-- Name: stored_files stored_files_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stored_files
    ADD CONSTRAINT stored_files_pkey PRIMARY KEY (id);


--
-- Name: student_class_enrollments student_class_enrollments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.student_class_enrollments
    ADD CONSTRAINT student_class_enrollments_pkey PRIMARY KEY (id);


--
-- Name: student_yearly_summaries student_yearly_summaries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.student_yearly_summaries
    ADD CONSTRAINT student_yearly_summaries_pkey PRIMARY KEY (id);


--
-- Name: subjects subjects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subjects
    ADD CONSTRAINT subjects_pkey PRIMARY KEY (id);


--
-- Name: submission_resubmission_requests submission_resubmission_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.submission_resubmission_requests
    ADD CONSTRAINT submission_resubmission_requests_pkey PRIMARY KEY (id);


--
-- Name: teacher_class_subjects teacher_class_subjects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.teacher_class_subjects
    ADD CONSTRAINT teacher_class_subjects_pkey PRIMARY KEY (id);


--
-- Name: timetable_slots timetable_slots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.timetable_slots
    ADD CONSTRAINT timetable_slots_pkey PRIMARY KEY (id);


--
-- Name: parent_student uk3q972v7wdk4h11775eumt4sur; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.parent_student
    ADD CONSTRAINT uk3q972v7wdk4h11775eumt4sur UNIQUE (parent_id, student_id);


--
-- Name: notification_templates uk_590i2e6i9u4i77xek1ohkbwak; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_templates
    ADD CONSTRAINT uk_590i2e6i9u4i77xek1ohkbwak UNIQUE (code);


--
-- Name: bank_statement_entries uk_bank_statement_txn; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_statement_entries
    ADD CONSTRAINT uk_bank_statement_txn UNIQUE (bank_code, transaction_reference);


--
-- Name: academic_years uk_e2c0heg6jll7dswpu6atlyfhb; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.academic_years
    ADD CONSTRAINT uk_e2c0heg6jll7dswpu6atlyfhb UNIQUE (code);


--
-- Name: exam_categories uk_fcopm8fap7smuixmp4acwefa9; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_categories
    ADD CONSTRAINT uk_fcopm8fap7smuixmp4acwefa9 UNIQUE (code);


--
-- Name: fee_period_item_targets uk_fee_period_item_target; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_period_item_targets
    ADD CONSTRAINT uk_fee_period_item_target UNIQUE (fee_period_item_id, target_type, target_id);


--
-- Name: fee_period_targets uk_fee_period_target; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_period_targets
    ADD CONSTRAINT uk_fee_period_target UNIQUE (fee_period_id, target_type, target_id);


--
-- Name: payment_gateway_transactions uk_gateway_tx_provider_ref; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_gateway_transactions
    ADD CONSTRAINT uk_gateway_tx_provider_ref UNIQUE (provider, merchant_txn_ref);


--
-- Name: academic_promotion_policies uk_ka2kcpyshdye1nmix1wta03ja; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.academic_promotion_policies
    ADD CONSTRAINT uk_ka2kcpyshdye1nmix1wta03ja UNIQUE (academic_year_id);


--
-- Name: user_notification_preferences uk_notification_preference; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notification_preferences
    ADD CONSTRAINT uk_notification_preference UNIQUE (user_id, notification_type, channel);


--
-- Name: payment_proofs uk_payment_proof_file; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_proofs
    ADD CONSTRAINT uk_payment_proof_file UNIQUE (file_id);


--
-- Name: payment_receipts uk_payment_receipt_number; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_receipts
    ADD CONSTRAINT uk_payment_receipt_number UNIQUE (receipt_number);


--
-- Name: payment_receipts uk_payment_receipt_payment; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_receipts
    ADD CONSTRAINT uk_payment_receipt_payment UNIQUE (payment_id);


--
-- Name: payment_reconciliation_method_summaries uk_payment_reconciliation_method; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_reconciliation_method_summaries
    ADD CONSTRAINT uk_payment_reconciliation_method UNIQUE (run_id, method);


--
-- Name: payment_refunds uk_payment_refund_number; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_refunds
    ADD CONSTRAINT uk_payment_refund_number UNIQUE (refund_number);


--
-- Name: rooms uk_pwsjifwofg0y1ux7gtd8sveqq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rooms
    ADD CONSTRAINT uk_pwsjifwofg0y1ux7gtd8sveqq UNIQUE (code);


--
-- Name: users uk_r43af9ap4edm43mmtq01oddj6; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uk_r43af9ap4edm43mmtq01oddj6 UNIQUE (username);


--
-- Name: academic_result_locks uk_result_lock_class_semester; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.academic_result_locks
    ADD CONSTRAINT uk_result_lock_class_semester UNIQUE (class_id, semester_id);


--
-- Name: subjects uk_rg7x1lyii7kdyycw98d45vep5; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subjects
    ADD CONSTRAINT uk_rg7x1lyii7kdyycw98d45vep5 UNIQUE (code);


--
-- Name: student_class_enrollments uk_student_enrollment_year; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.student_class_enrollments
    ADD CONSTRAINT uk_student_enrollment_year UNIQUE (academic_year_id, student_id);


--
-- Name: assignment_submission_versions uk_submission_version; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.assignment_submission_versions
    ADD CONSTRAINT uk_submission_version UNIQUE (submission_id, version_no);


--
-- Name: year_result_publications uk_year_result_publication_class; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.year_result_publications
    ADD CONSTRAINT uk_year_result_publication_class UNIQUE (academic_year_id, class_id);


--
-- Name: student_yearly_summaries uk_yearly_summary_student_year; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.student_yearly_summaries
    ADD CONSTRAINT uk_yearly_summary_student_year UNIQUE (academic_year_id, student_id);


--
-- Name: user_devices user_devices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_devices
    ADD CONSTRAINT user_devices_pkey PRIMARY KEY (id);


--
-- Name: user_notification_preferences user_notification_preferences_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_notification_preferences
    ADD CONSTRAINT user_notification_preferences_pkey PRIMARY KEY (id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: year_result_publication_history year_result_publication_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.year_result_publication_history
    ADD CONSTRAINT year_result_publication_history_pkey PRIMARY KEY (id);


--
-- Name: year_result_publications year_result_publications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.year_result_publications
    ADD CONSTRAINT year_result_publications_pkey PRIMARY KEY (id);


--
-- Name: idx_asg_class; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asg_class ON public.assignments USING btree (class_id);


--
-- Name: idx_asg_teacher; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asg_teacher ON public.assignments USING btree (teacher_id);


--
-- Name: idx_att_class_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_att_class_date ON public.attendance_records USING btree (class_id, date);


--
-- Name: idx_att_excuse_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_att_excuse_status ON public.attendance_excuse_requests USING btree (status);


--
-- Name: idx_att_excuse_student; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_att_excuse_student ON public.attendance_excuse_requests USING btree (student_id);


--
-- Name: idx_att_student; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_att_student ON public.attendance_records USING btree (student_id);


--
-- Name: idx_audit_actor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_actor ON public.audit_logs USING btree (actor_id);


--
-- Name: idx_audit_module; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_module ON public.audit_logs USING btree (module);


--
-- Name: idx_bank_statement_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bank_statement_status ON public.bank_statement_entries USING btree (status);


--
-- Name: idx_bank_statement_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bank_statement_time ON public.bank_statement_entries USING btree (transferred_at);


--
-- Name: idx_chat_recipient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_recipient ON public.chat_messages USING btree (recipient_id);


--
-- Name: idx_chat_sender; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_sender ON public.chat_messages USING btree (sender_id);


--
-- Name: idx_fee_period_item_target_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fee_period_item_target_item ON public.fee_period_item_targets USING btree (fee_period_item_id);


--
-- Name: idx_fee_period_target_period; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fee_period_target_period ON public.fee_period_targets USING btree (fee_period_id);


--
-- Name: idx_fee_period_type_semester; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fee_period_type_semester ON public.fee_periods USING btree (fee_type, semester_id);


--
-- Name: idx_gateway_tx_payment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_gateway_tx_payment ON public.payment_gateway_transactions USING btree (payment_id);


--
-- Name: idx_gcl_grade; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_gcl_grade ON public.grade_change_logs USING btree (grade_id);


--
-- Name: idx_grade_student; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_grade_student ON public.grades USING btree (student_id);


--
-- Name: idx_grade_subject_sem; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_grade_subject_sem ON public.grades USING btree (subject_id, semester_id);


--
-- Name: idx_inv_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inv_parent ON public.invoices USING btree (parent_id);


--
-- Name: idx_inv_student; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inv_student ON public.invoices USING btree (student_id);


--
-- Name: idx_lh_user_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lh_user_time ON public.login_history USING btree (user_id, created_at);


--
-- Name: idx_lh_username_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lh_username_time ON public.login_history USING btree (username, created_at);


--
-- Name: idx_ndl_notification; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ndl_notification ON public.notification_delivery_logs USING btree (notification_id);


--
-- Name: idx_noti_recipient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_noti_recipient ON public.notifications USING btree (recipient_id);


--
-- Name: idx_notification_preference_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_preference_user ON public.user_notification_preferences USING btree (user_id);


--
-- Name: idx_pay_invoice; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pay_invoice ON public.payments USING btree (invoice_id);


--
-- Name: idx_payment_proof_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_proof_parent ON public.payment_proofs USING btree (parent_id);


--
-- Name: idx_payment_proof_payment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_proof_payment ON public.payment_proofs USING btree (payment_id);


--
-- Name: idx_payment_proof_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_proof_status ON public.payment_proofs USING btree (status);


--
-- Name: idx_payment_receipt_invoice; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_receipt_invoice ON public.payment_receipts USING btree (invoice_id);


--
-- Name: idx_payment_receipt_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_receipt_parent ON public.payment_receipts USING btree (parent_id);


--
-- Name: idx_payment_receipt_student; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_receipt_student ON public.payment_receipts USING btree (student_id);


--
-- Name: idx_payment_reconciliation_issue_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_reconciliation_issue_entity ON public.payment_reconciliation_issues USING btree (entity_type, entity_id);


--
-- Name: idx_payment_reconciliation_issue_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_reconciliation_issue_run ON public.payment_reconciliation_issues USING btree (run_id);


--
-- Name: idx_payment_reconciliation_method_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_reconciliation_method_run ON public.payment_reconciliation_method_summaries USING btree (run_id);


--
-- Name: idx_payment_refund_invoice; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_refund_invoice ON public.payment_refunds USING btree (invoice_id);


--
-- Name: idx_payment_refund_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_refund_parent ON public.payment_refunds USING btree (parent_id);


--
-- Name: idx_payment_refund_payment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_refund_payment ON public.payment_refunds USING btree (payment_id);


--
-- Name: idx_payment_refund_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_refund_status ON public.payment_refunds USING btree (status);


--
-- Name: idx_payment_refund_student; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_refund_student ON public.payment_refunds USING btree (student_id);


--
-- Name: idx_reg_club; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reg_club ON public.club_registrations USING btree (club_id);


--
-- Name: idx_reg_student; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reg_student ON public.club_registrations USING btree (student_id);


--
-- Name: idx_resubmission_submission; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_resubmission_submission ON public.submission_resubmission_requests USING btree (submission_id);


--
-- Name: idx_result_lock_year_class; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_result_lock_year_class ON public.academic_result_locks USING btree (academic_year_id, class_id);


--
-- Name: idx_rt_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rt_user ON public.refresh_tokens USING btree (user_id);


--
-- Name: idx_stored_file_uploader; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stored_file_uploader ON public.stored_files USING btree (uploaded_by);


--
-- Name: idx_student_enrollment_class; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_student_enrollment_class ON public.student_class_enrollments USING btree (academic_year_id, class_id);


--
-- Name: idx_student_enrollment_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_student_enrollment_source ON public.student_class_enrollments USING btree (source_academic_year_id, source_class_id);


--
-- Name: idx_sub_asg; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sub_asg ON public.assignment_submissions USING btree (assignment_id);


--
-- Name: idx_sub_student; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sub_student ON public.assignment_submissions USING btree (student_id);


--
-- Name: idx_submission_version_submission; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_submission_version_submission ON public.assignment_submission_versions USING btree (submission_id);


--
-- Name: idx_tcs_class; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tcs_class ON public.teacher_class_subjects USING btree (class_id);


--
-- Name: idx_tcs_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tcs_scope ON public.teacher_class_subjects USING btree (class_id, subject_id, semester_id);


--
-- Name: idx_tcs_semester; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tcs_semester ON public.teacher_class_subjects USING btree (semester_id);


--
-- Name: idx_tcs_teacher; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tcs_teacher ON public.teacher_class_subjects USING btree (teacher_id);


--
-- Name: idx_tt_class; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tt_class ON public.timetable_slots USING btree (class_id);


--
-- Name: idx_tt_teacher; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tt_teacher ON public.timetable_slots USING btree (teacher_id);


--
-- Name: idx_ud_user_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ud_user_active ON public.user_devices USING btree (user_id, active);


--
-- Name: idx_users_class; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_class ON public.users USING btree (class_id);


--
-- Name: idx_users_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_role ON public.users USING btree (role);


--
-- Name: idx_year_result_history_class; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_year_result_history_class ON public.year_result_publication_history USING btree (academic_year_id, class_id, occurred_at);


--
-- Name: idx_year_result_publication_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_year_result_publication_status ON public.year_result_publications USING btree (academic_year_id, status);


--
-- Name: idx_yearly_summary_class; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_yearly_summary_class ON public.student_yearly_summaries USING btree (academic_year_id, class_id);


--
-- Name: idx_yearly_summary_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_yearly_summary_status ON public.student_yearly_summaries USING btree (status);


--
-- Name: uk_att_excuse_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_att_excuse_pending ON public.attendance_excuse_requests USING btree (attendance_record_id) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: uk_classes_academic_year_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_classes_academic_year_code ON public.classes USING btree (academic_year_id, code);


--
-- Name: uk_fee_period_code_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_fee_period_code_ci ON public.fee_periods USING btree (lower((code)::text));


--
-- Name: uk_gateway_tx_provider_transaction; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_gateway_tx_provider_transaction ON public.payment_gateway_transactions USING btree (provider, provider_transaction_id) WHERE (provider_transaction_id IS NOT NULL);


--
-- Name: uk_invoice_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_invoice_code ON public.invoices USING btree (code);


--
-- Name: uk_invoice_period_student; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_invoice_period_student ON public.invoices USING btree (fee_period_id, student_id);


--
-- Name: uk_payment_receipt_file; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_payment_receipt_file ON public.payment_receipts USING btree (file_id) WHERE (file_id IS NOT NULL);


--
-- Name: uk_payment_reconciliation_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_payment_reconciliation_scope ON public.payment_reconciliation_runs USING btree (scope_key);


--
-- Name: uk_payment_refund_completed_reference; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_payment_refund_completed_reference ON public.payment_refunds USING btree (refund_method, lower((refund_reference)::text)) WHERE (((status)::text = 'COMPLETED'::text) AND (refund_reference IS NOT NULL));


--
-- Name: uk_payment_txn_ref; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_payment_txn_ref ON public.payments USING btree (txn_ref) WHERE (txn_ref IS NOT NULL);


--
-- Name: fee_period_item_targets fk_fee_period_item_target_item; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_period_item_targets
    ADD CONSTRAINT fk_fee_period_item_target_item FOREIGN KEY (fee_period_item_id) REFERENCES public.fee_period_items(id) ON DELETE CASCADE;


--
-- Name: fee_period_targets fk_fee_period_target_period; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fee_period_targets
    ADD CONSTRAINT fk_fee_period_target_period FOREIGN KEY (fee_period_id) REFERENCES public.fee_periods(id) ON DELETE CASCADE;


--
-- Name: payment_gateway_transactions fk_gateway_tx_payment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_gateway_transactions
    ADD CONSTRAINT fk_gateway_tx_payment FOREIGN KEY (payment_id) REFERENCES public.payments(id) ON DELETE SET NULL;


--
-- Name: payment_proofs fk_payment_proof_file; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_proofs
    ADD CONSTRAINT fk_payment_proof_file FOREIGN KEY (file_id) REFERENCES public.stored_files(id) ON DELETE RESTRICT;


--
-- Name: payment_proofs fk_payment_proof_invoice; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_proofs
    ADD CONSTRAINT fk_payment_proof_invoice FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON DELETE CASCADE;


--
-- Name: payment_proofs fk_payment_proof_payment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_proofs
    ADD CONSTRAINT fk_payment_proof_payment FOREIGN KEY (payment_id) REFERENCES public.payments(id) ON DELETE CASCADE;


--
-- Name: payment_receipts fk_payment_receipt_file; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_receipts
    ADD CONSTRAINT fk_payment_receipt_file FOREIGN KEY (file_id) REFERENCES public.stored_files(id) ON DELETE RESTRICT;


--
-- Name: payment_receipts fk_payment_receipt_invoice; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_receipts
    ADD CONSTRAINT fk_payment_receipt_invoice FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON DELETE RESTRICT;


--
-- Name: payment_receipts fk_payment_receipt_payment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_receipts
    ADD CONSTRAINT fk_payment_receipt_payment FOREIGN KEY (payment_id) REFERENCES public.payments(id) ON DELETE RESTRICT;


--
-- Name: payment_reconciliation_issues fk_payment_reconciliation_issue_run; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_reconciliation_issues
    ADD CONSTRAINT fk_payment_reconciliation_issue_run FOREIGN KEY (run_id) REFERENCES public.payment_reconciliation_runs(id) ON DELETE CASCADE;


--
-- Name: payment_reconciliation_method_summaries fk_payment_reconciliation_method_run; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_reconciliation_method_summaries
    ADD CONSTRAINT fk_payment_reconciliation_method_run FOREIGN KEY (run_id) REFERENCES public.payment_reconciliation_runs(id) ON DELETE CASCADE;


--
-- Name: payment_refunds fk_payment_refund_invoice; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_refunds
    ADD CONSTRAINT fk_payment_refund_invoice FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON DELETE RESTRICT;


--
-- Name: payment_refunds fk_payment_refund_payment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_refunds
    ADD CONSTRAINT fk_payment_refund_payment FOREIGN KEY (payment_id) REFERENCES public.payments(id) ON DELETE RESTRICT;


--
-- PostgreSQL database dump complete
--


