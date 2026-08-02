package com.sse.app.identity;

import com.sse.app.common.ApiException;
import com.sse.app.common.PageResponse;
import com.sse.app.identity.AlumniDtos.AlumniClassSummary;
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
        return page(q, cohortId, graduationAcademicYearId, null, page, size);
    }

    public PageResponse<AlumniRecord> page(String q, String cohortId, String graduationAcademicYearId,
                                           String graduationClassId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(5, Math.min(size, 100));
        List<Object> params = new ArrayList<>();
        String where = where(q, cohortId, graduationAcademicYearId, graduationClassId, null, params);
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

    public List<AlumniClassSummary> classes(String cohortId, String graduationAcademicYearId) {
        return queryClasses(cohortId, graduationAcademicYearId, null);
    }

    private List<AlumniClassSummary> queryClasses(String cohortId, String graduationAcademicYearId,
                                                   String excludedGraduationAcademicYearId) {
        List<Object> params = new ArrayList<>();
        String where = where(null, cohortId, graduationAcademicYearId, null, null, params);
        if (excludedGraduationAcademicYearId != null) {
            where += " AND u.graduation_academic_year_id<>?";
            params.add(excludedGraduationAcademicYearId);
        }
        String sql = """
                SELECT graduation_class.id AS class_id,
                       graduation_class.code AS class_code,
                       graduation_class.name AS class_name,
                       cohort.id AS cohort_id,
                       cohort.code AS cohort_code,
                       cohort.name AS cohort_name,
                       academic_year.id AS graduation_academic_year_id,
                       academic_year.code AS graduation_academic_year_code,
                       COUNT(u.id) AS student_count,
                       AVG(summary.average_score) AS average_score,
                       SUM(CASE WHEN summary.conduct_grade IN ('GOOD', 'EXCELLENT') THEN 1 ELSE 0 END) AS good_conduct_count,
                       SUM(CASE WHEN u.status = 'ACTIVE' THEN 1 ELSE 0 END) AS active_account_count
                FROM users u
                LEFT JOIN cohorts cohort ON cohort.id = u.cohort_id
                LEFT JOIN academic_years academic_year ON academic_year.id = u.graduation_academic_year_id
                LEFT JOIN classes graduation_class ON graduation_class.id = u.graduation_class_id
                LEFT JOIN student_yearly_summaries summary
                  ON summary.student_id = u.id
                 AND summary.academic_year_id = u.graduation_academic_year_id
                """ + where + """
                 AND u.graduation_class_id IS NOT NULL
                GROUP BY graduation_class.id, graduation_class.code, graduation_class.name,
                         cohort.id, cohort.code, cohort.name,
                         academic_year.id, academic_year.code
                ORDER BY academic_year.code DESC, cohort.entry_year DESC, graduation_class.code ASC
                """;
        return jdbc.query(sql, (rs, row) -> new AlumniClassSummary(
                rs.getString("class_id"), rs.getString("class_code"), rs.getString("class_name"),
                rs.getString("cohort_id"), rs.getString("cohort_code"), rs.getString("cohort_name"),
                rs.getString("graduation_academic_year_id"), rs.getString("graduation_academic_year_code"),
                rs.getLong("student_count"), nullableDouble(rs, "average_score"),
                rs.getLong("good_conduct_count"), rs.getLong("active_account_count")
        ), params.toArray());
    }

    public List<AlumniClassSummary> currentClasses() {
        String currentGraduationYearId = latestGraduationAcademicYearId();
        if (currentGraduationYearId == null) return List.of();
        return classes(null, currentGraduationYearId);
    }

    public List<AlumniClassSummary> archivedClasses(String cohortId, String graduationAcademicYearId) {
        String currentGraduationYearId = latestGraduationAcademicYearId();
        return queryClasses(cohortId, graduationAcademicYearId, currentGraduationYearId);
    }

    private String latestGraduationAcademicYearId() {
        return jdbc.query("""
                        SELECT u.graduation_academic_year_id
                        FROM users u
                        LEFT JOIN academic_years academic_year ON academic_year.id = u.graduation_academic_year_id
                        WHERE u.role='STUDENT'
                          AND u.student_status='GRADUATED'
                          AND u.graduation_academic_year_id IS NOT NULL
                        GROUP BY u.graduation_academic_year_id, academic_year.end_date
                        ORDER BY MAX(u.graduated_at) DESC, academic_year.end_date DESC
                        LIMIT 1
                        """,
                resultSet -> resultSet.next() ? resultSet.getString(1) : null);
    }

    public AlumniRecord get(String studentId) {
        List<Object> params = new ArrayList<>();
        String where = where(null, null, null, null, studentId, params);
        return jdbc.query(SELECT + where, this::map, params.toArray()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Hồ sơ cựu học sinh"));
    }

    private String where(String q, String cohortId, String graduationAcademicYearId,
                         String graduationClassId, String studentId, List<Object> params) {
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
        if (graduationClassId != null && !graduationClassId.isBlank()) {
            sql.append(" AND u.graduation_class_id=?");
            params.add(graduationClassId);
        }
        if (studentId != null) {
            sql.append(" AND u.id=?");
            params.add(studentId);
        }
        return sql.toString();
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).doubleValue();
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
