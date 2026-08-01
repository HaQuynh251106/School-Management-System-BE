package com.sse.app.academic.structure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

public final class RoomAllocationDtos {
    private RoomAllocationDtos() {}

    public record LockedAllocation(
            @NotBlank String classId,
            @NotBlank @Pattern(regexp = "MORNING|AFTERNOON") String studyShift,
            @NotBlank String roomId) {}

    public record PreviewRequest(
            @NotBlank String academicYearId,
            String name,
            Boolean balanceShifts,
            Boolean preserveExisting,
            List<@Valid LockedAllocation> lockedAllocations) {}

    public record CapacitySummary(
            int totalRooms,
            int mainRooms,
            int functionalRooms,
            int morningRoomSlots,
            int afternoonRoomSlots,
            int totalClassSlots,
            int totalClasses,
            int targetMorningClasses,
            int targetAfternoonClasses,
            int spareClassSlots) {}

    public record AllocationItem(
            String id,
            String classId,
            String classCode,
            int studentCount,
            int classCapacity,
            String previousShift,
            String previousRoomId,
            String previousRoomCode,
            String proposedShift,
            String proposedRoomId,
            String proposedRoomCode,
            boolean locked,
            String status,
            String message) {}

    public record AllocationPlan(
            String id,
            String academicYearId,
            String name,
            String status,
            int totalClasses,
            int assignedClasses,
            int unassignedClasses,
            int morningClasses,
            int afternoonClasses,
            CapacitySummary capacity,
            List<AllocationItem> items,
            List<String> warnings,
            String createdBy,
            Instant createdAt,
            String appliedBy,
            Instant appliedAt,
            String undoneBy,
            Instant undoneAt) {}
}
