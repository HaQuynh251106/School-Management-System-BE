package com.sse.app.report;

import com.sse.app.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AcademicResultLockServiceTest {
    @Test
    void blocksGradeWriteAfterClassSemesterIsLocked() {
        AcademicResultLockRepository repository = mock(AcademicResultLockRepository.class);
        when(repository.existsByClassIdAndSemesterId("class", "semester")).thenReturn(true);
        AcademicResultLockService service = new AcademicResultLockService(repository);

        ApiException error = assertThrows(ApiException.class,
                () -> service.assertGradeWritable("class", "semester"));

        assertEquals(409, error.getStatus().value());
    }
}
