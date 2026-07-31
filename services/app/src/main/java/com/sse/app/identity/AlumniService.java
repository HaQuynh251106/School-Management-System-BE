package com.sse.app.identity;

import com.sse.app.common.ApiException;
import com.sse.app.common.PageResponse;
import com.sse.app.identity.AlumniDtos.AlumniRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AlumniService {
    private static final String SELECT = """
            SELECT u.id, u.student_code, u.full_name, u.date_of_birth, u.gender, u.email, u.phone,
                   u.cohort_id, cohort.code AS cohort_code, cohort.name AS cohort_name,
                   cohort.entry_year, cohort.graduation_year, u.graduated_at,
                   u.graduation_academic_year_id, academic_year.code AS graduation_year_code,
                   u.graduation_class_id, graduation_class.code AS graduation_class_code,
                   summary.average_score, summary.conduct_grade, u.status AS account_status
            FROM users u
            LEFT JOIN cohorts cohort ON cohort.id = u.cohort_id
            LEFT JOIN academic_years academic_year ON academic_year.id = u.graduation_academic_year_id
            LEFT JOIN classes graduation_class ON graduation_class.id = u.graduation_class_id
            LEFT JOIN student_yearly_summaries summary
              ON summary.student_id = u.id
             AND summary.academic_year_id = u.graduation_academic_year_id
            """;

    private final JdbcTemplate jdbc;

    public AlumniService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<AlumniRecord> page(String q, String cohortId, String graduationAcademicYearId,
                                           int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(5, Math.min(size, 100));
        List<Object> params = new ArrayList<>();
        String where = where(q, cohortId, graduationAcademicYearId, null, params);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM users u " + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(safeSize);
        pageParams.add(safePage * safeSize);
        List<AlumniRecord> items = jdbc.query(SELECT + where + " ORDER BY u.graduated_at DESC, u.full_name ASC LIMIT ? OFFSET ?",
                this::map, pageParams.toArray());
        long count = total == null ? 0 : total;
        int totalPages = count == 0 ? 0 : (int) Math.ceil(count / (double) safeSize);
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("total", count);
        Long cohortCount = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT cohort_id) FROM users WHERE role='STUDENT' AND student_status='GRADUATED'",
                Long.class);
        summary.put("cohorts", cohortCount == null ? 0 : cohortCount);
        return new PageResponse<>(items, safePage, safeSize, count, totalPages,
                safePage == 0, totalPages == 0 || safePage >= totalPages - 1, summary);
    }

    public AlumniRecord get(String studentId) {
        List<Object> params = new ArrayList<>();
        String where = where(null, null, null, studentId, params);
        return jdbc.query(SELECT + where, this::map, params.toArray()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Hồ sơ cựu học sinh"));
    }

    private String where(String q, String cohortId, String graduationAcademicYearId,
                         String studentId, List<Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE u.role='STUDENT' AND u.student_status='GRADUATED'");
        if (q != null && !q.isBlank()) {
            sql.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.student_code) LIKE ? OR LOWER(u.email) LIKE ?)");
            String pattern = "%" + q.trim().toLowerCase() + "%";
            params.add(pattern); params.add(pattern); params.add(pattern);
        }
        if (cohortId != null && !cohortId.isBlank()) {
            sql.append(" AND u.cohort_id=?");
            params.add(cohortId);
        }
        if (graduationAcademicYearId != null && !graduationAcademicYearId.isBlank()) {
            sql.append(" AND u.graduation_academic_year_id=?");
            params.add(graduationAcademicYearId);
        }
        if (studentId != null) {
            sql.append(" AND u.id=?");
            params.add(studentId);
        }
        return sql.toString();
    }

    private AlumniRecord map(ResultSet rs, int row) throws SQLException {
        var graduatedAt = rs.getTimestamp("graduated_at");
        return new AlumniRecord(
                rs.getString("id"), rs.getString("student_code"), rs.getString("full_name"),
                rs.getDate("date_of_birth") == null ? null : rs.getDate("date_of_birth").toLocalDate(),
                rs.getString("gender"), rs.getString("email"), rs.getString("phone"),
                rs.getString("cohort_id"), rs.getString("cohort_code"), rs.getString("cohort_name"),
                (Integer) rs.getObject("entry_year"), (Integer) rs.getObject("graduation_year"),
                graduatedAt == null ? null : graduatedAt.toInstant(),
                rs.getString("graduation_academic_year_id"), rs.getString("graduation_year_code"),
                rs.getString("graduation_class_id"), rs.getString("graduation_class_code"),
                (Double) rs.getObject("average_score"), rs.getString("conduct_grade"),
                rs.getString("account_status"));
    }
}
