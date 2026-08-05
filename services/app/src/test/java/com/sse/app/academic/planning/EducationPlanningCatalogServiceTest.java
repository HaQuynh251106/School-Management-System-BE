package com.sse.app.academic.planning;

import com.sse.app.academic.planning.EducationPlanningCatalogDtos.AssignCombinationRequest;
import com.sse.app.academic.planning.EducationPlanningCatalogDtos.ProgramRequest;
import com.sse.app.academic.structure.SchoolClass;
import com.sse.app.academic.structure.StructureService;
import com.sse.app.identity.UserRepository;
import com.sse.app.event.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EducationPlanningCatalogServiceTest {
    @Mock EducationProgramRepository programs;
    @Mock EducationProgramSubjectRepository programSubjects;
    @Mock SubjectCombinationRepository combinations;
    @Mock SubjectCombinationSubjectRepository combinationSubjects;
    @Mock ClassSubjectCombinationRepository classCombinations;
    @Mock TeacherSubjectCapabilityRepository capabilities;
    @Mock StructureService structure;
    @Mock UserRepository users;
    @Mock DomainEventPublisher events;

    private EducationPlanningCatalogService service;

    @BeforeEach
    void setUp() {
        service = new EducationPlanningCatalogService(
                programs, programSubjects, combinations, combinationSubjects,
                classCombinations, capabilities, structure, users, events);
    }

    @Test
    void newProgramDefaultsToDraft() {
        when(programs.findByCodeIgnoreCase("NEW2028")).thenReturn(Optional.empty());
        when(programs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EducationProgram saved = service.saveProgram(null,
                new ProgramRequest(null, "NEW2028", "Chương trình mới", 2028, null, null));

        assertEquals("DRAFT", saved.getStatus());
    }

    @Test
    void activatingProgramArchivesPreviousActiveProgram() {
        EducationProgram current = EducationProgram.builder()
                .id("program-old").code("OLD").name("Chương trình cũ")
                .startYear(2018).status("ACTIVE").build();
        when(programs.findByCodeIgnoreCase("NEW2028")).thenReturn(Optional.empty());
        when(programs.findByStatus("ACTIVE")).thenReturn(List.of(current));
        when(programs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EducationProgram saved = service.saveProgram(null,
                new ProgramRequest("program-new", "NEW2028", "Chương trình mới",
                        2028, null, "ACTIVE"));

        assertEquals("ACTIVE", saved.getStatus());
        assertEquals("ARCHIVED", current.getStatus());
        verify(programs).flush();
    }

    @Test
    void assigningCombinationReplacesItsClassListAndKeepsClassesExclusive() {
        SubjectCombination combination = SubjectCombination.builder()
                .id("combo-science").academicYearId("ay-1").gradeLevel("K10").build();
        when(combinations.findById("combo-science")).thenReturn(Optional.of(combination));
        when(structure.getClass("class-1")).thenReturn(SchoolClass.builder()
                .id("class-1").academicYearId("ay-1").gradeLevel("K10").code("10A1").build());
        when(classCombinations.findByCombinationId("combo-science")).thenReturn(List.of(
                ClassSubjectCombination.builder().classId("class-old")
                        .combinationId("combo-science").build()));
        when(classCombinations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ClassSubjectCombination> saved = service.assignCombination(
                new AssignCombinationRequest("combo-science", List.of("class-1")), "admin-1");

        assertEquals(List.of("class-1"), saved.stream()
                .map(ClassSubjectCombination::getClassId).toList());
        ArgumentCaptor<List<ClassSubjectCombination>> removed = ArgumentCaptor.forClass(List.class);
        verify(classCombinations).deleteAll(removed.capture());
        assertEquals("class-old", removed.getValue().get(0).getClassId());
    }
}
