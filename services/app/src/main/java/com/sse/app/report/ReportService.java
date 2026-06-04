package com.sse.app.report;

import com.sse.app.academic.attendance.AttendanceRecord;
import com.sse.app.academic.attendance.AttendanceService;
import com.sse.app.academic.grade.Grade;
import com.sse.app.academic.grade.GradeService;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.finance.FinanceService;
import com.sse.app.identity.UserService;
import org.springframework.stereotype.Service;

import java.util.*;

/** A8: Báo cáo & thống kê (tính trong bộ nhớ — phù hợp quy mô GĐ1). */
@Service
public class ReportService {

    private final GradeService grades;
    private final AttendanceService attendance;
    private final FinanceService finance;
    private final UserService users;
    private final StructureService structure;

    public ReportService(GradeService grades, AttendanceService attendance, FinanceService finance,
                         UserService users, StructureService structure) {
        this.grades = grades;
        this.attendance = attendance;
        this.finance = finance;
        this.users = users;
        this.structure = structure;
    }

    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("students", users.userIdsByRole("STUDENT").size());
        m.put("teachers", users.userIdsByRole("TEACHER").size());
        m.put("parents", users.userIdsByRole("PARENT").size());
        m.put("admins", users.userIdsByRole("ADMIN").size());
        m.put("classes", structure.listClasses(null, null).size());
        m.put("subjects", structure.listSubjects().size());
        return m;
    }

    public List<Map<String, Object>> gradeDistribution(String semesterId) {
        int[] bands = new int[4]; // <5, 5-6.4, 6.5-7.9, 8-10
        for (Grade g : grades.allGrades()) {
            if (semesterId != null && !semesterId.equals(g.getSemesterId())) continue;
            Double s = g.getScore();
            if (s == null) continue;
            if (s < 5) bands[0]++;
            else if (s < 6.5) bands[1]++;
            else if (s < 8) bands[2]++;
            else bands[3]++;
        }
        String[] labels = {"0–4.9", "5–6.4", "6.5–7.9", "8–10"};
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("band", labels[i]);
            b.put("count", bands[i]);
            out.add(b);
        }
        return out;
    }

    public Map<String, Object> attendanceSummary() {
        long present = 0, late = 0, excused = 0, unexcused = 0;
        for (AttendanceRecord r : attendance.allRecords()) {
            switch (r.getStatus() == null ? "" : r.getStatus()) {
                case "PRESENT" -> present++;
                case "LATE" -> late++;
                case "ABSENT_EXCUSED" -> excused++;
                case "ABSENT_UNEXCUSED" -> unexcused++;
                default -> { }
            }
        }
        long total = present + late + excused + unexcused;
        double rate = total == 0 ? 0 : Math.round((present + late * 0.5) / total * 1000) / 10.0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("present", present);
        m.put("late", late);
        m.put("absentExcused", excused);
        m.put("absentUnexcused", unexcused);
        m.put("total", total);
        m.put("attendanceRate", rate);
        return m;
    }

    public Map<String, Object> revenue() {
        return finance.revenueReport();
    }
}
