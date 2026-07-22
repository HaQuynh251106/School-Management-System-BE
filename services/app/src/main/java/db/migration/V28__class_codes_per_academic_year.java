package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Cho phép tái sử dụng mã lớp (10A1, 10A2...) ở các năm học khác nhau. */
public class V28__class_codes_per_academic_year extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        List<String> singleCodeConstraints = new ArrayList<>();
        String sql = """
                SELECT tc.constraint_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                 AND tc.table_name = kcu.table_name
                WHERE LOWER(tc.table_name) = 'classes'
                  AND tc.constraint_type = 'UNIQUE'
                GROUP BY tc.constraint_name
                HAVING COUNT(*) = 1 AND MAX(LOWER(kcu.column_name)) = 'code'
                """;
        try (PreparedStatement statement = context.getConnection().prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) singleCodeConstraints.add(result.getString(1));
        }

        String quote = context.getConnection().getMetaData().getIdentifierQuoteString().trim();
        if (quote.isEmpty()) quote = "\"";
        try (Statement statement = context.getConnection().createStatement()) {
            for (String constraint : singleCodeConstraints) {
                statement.execute("ALTER TABLE classes DROP CONSTRAINT " + quote + constraint + quote);
            }
            statement.execute("ALTER TABLE classes ALTER COLUMN academic_year_id SET NOT NULL");
            statement.execute("CREATE UNIQUE INDEX uk_classes_year_code ON classes (academic_year_id, code)");
        }
    }
}
