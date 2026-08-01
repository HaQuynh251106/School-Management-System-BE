\set ON_ERROR_STOP on
SET client_encoding TO 'UTF8';

-- Repair UTF-8 text that was decoded as Windows-1252 and then persisted.
-- Examples: "Nguyá»…n" -> "Nguyễn", "GiÃ¡o viÃªn" -> "Giáo viên".
DO $$
DECLARE
    column_info record;
    affected bigint;
BEGIN
    FOR column_info IN
        SELECT table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND data_type IN ('text', 'character varying')
          AND table_name <> 'flyway_schema_history'
        ORDER BY table_name, ordinal_position
    LOOP
        EXECUTE format(
            'SELECT count(*) FROM %I WHERE %I ~ %L',
            column_info.table_name,
            column_info.column_name,
            '(Ã|Ä|Æ|Â|áº|á»|â€)'
        ) INTO affected;

        IF affected > 0 THEN
            EXECUTE format(
                'UPDATE %I SET %I = convert_from(convert_to(%I, %L), %L) WHERE %I ~ %L',
                column_info.table_name,
                column_info.column_name,
                column_info.column_name,
                'WIN1252',
                'UTF8',
                column_info.column_name,
                '(Ã|Ä|Æ|Â|áº|á»|â€)'
            );
            RAISE NOTICE 'Repaired %.%: % row(s)', column_info.table_name, column_info.column_name, affected;
        END IF;
    END LOOP;
END $$;

-- Fail verification if any typical mojibake marker remains.
DO $$
DECLARE
    column_info record;
    remaining bigint;
BEGIN
    FOR column_info IN
        SELECT table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND data_type IN ('text', 'character varying')
          AND table_name <> 'flyway_schema_history'
    LOOP
        EXECUTE format(
            'SELECT count(*) FROM %I WHERE %I ~ %L',
            column_info.table_name,
            column_info.column_name,
            '(Ã|Ä|Æ|Â|áº|á»|â€)'
        ) INTO remaining;
        IF remaining > 0 THEN
            RAISE EXCEPTION 'Mojibake remains in %.%: % row(s)',
                column_info.table_name, column_info.column_name, remaining;
        END IF;
    END LOOP;
END $$;
