package com.sse.app.report;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class AcademicResultLockService {
    private final AcademicResultLockRepository locks;

    public AcademicResultLockService(AcademicResultLockRepository locks) {
        this.locks = locks;
    }

    public void assertGradeWritable(String classId, String semesterId) {
        if (classId != null && semesterId != null
                && locks.existsByClassIdAndSemesterId(classId, semesterId)) {
            throw ApiException.conflict(
                    "Kết quả năm học của lớp đã chốt; không thể nhập hoặc sửa điểm");
        }
    }

    @Transactional
    public void lock(String academicYearId, String classId, List<String> semesterIds,
                     String actorId) {
        for (String semesterId : semesterIds) {
            if (locks.existsByClassIdAndSemesterId(classId, semesterId)) continue;
            locks.save(AcademicResultLock.builder()
                    .id(Ids.gen("arl"))
                    .academicYearId(academicYearId)
                    .semesterId(semesterId)
                    .classId(classId)
                    .lockedBy(actorId)
                    .lockedAt(Instant.now())
                    .reason("Chốt kết quả năm học")
                    .build());
        }
    }

    public boolean classLocked(String academicYearId, String classId) {
        return !locks.findByAcademicYearIdAndClassId(academicYearId, classId).isEmpty();
    }

    @Transactional
    public void unlock(String academicYearId, String classId) {
        locks.deleteByAcademicYearIdAndClassId(academicYearId, classId);
    }
}
