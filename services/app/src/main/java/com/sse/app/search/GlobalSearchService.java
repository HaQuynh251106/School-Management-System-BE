package com.sse.app.search;

import com.sse.app.common.ApiException;
import com.sse.app.search.SearchDtos.SearchItem;
import com.sse.app.search.SearchDtos.SearchResponse;
import com.sse.app.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class GlobalSearchService {
    private final JdbcTemplate jdbc;

    public GlobalSearchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SearchResponse search(CurrentUser current, String rawQuery, int requestedLimit) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < 2) throw ApiException.badRequest("Nhập ít nhất 2 ký tự để tìm kiếm");
        int limit = Math.max(5, Math.min(requestedLimit, 30));
        String pattern = "%" + query.toLowerCase(Locale.ROOT) + "%";
        List<SearchItem> items = new ArrayList<>();

        switch (current.role()) {
            case "ADMIN" -> admin(items, pattern, limit);
            case "TEACHER" -> teacher(items, pattern, current.id(), limit);
            case "STUDENT" -> student(items, pattern, current.id(), limit);
            case "PARENT" -> parent(items, pattern, current.id(), limit);
            default -> throw ApiException.forbidden("Vai trò không hỗ trợ tìm kiếm");
        }

        Map<String, SearchItem> unique = new LinkedHashMap<>();
        items.forEach(item -> unique.putIfAbsent(item.type() + ":" + item.id(), item));
        List<SearchItem> result = unique.values().stream().limit(limit).toList();
        return new SearchResponse(query, result.size(), result);
    }

    private void admin(List<SearchItem> out, String pattern, int limit) {
        add(out, limit, """
                select id, full_name as title,
                       concat('@', username, ' · ', case role
                           when 'STUDENT' then 'Học sinh'
                           when 'TEACHER' then 'Giáo viên'
                           when 'PARENT' then 'Phụ huynh'
                           else 'Quản trị viên' end) as subtitle,
                       case role when 'STUDENT' then 'A1S' when 'TEACHER' then 'A1T'
                           when 'PARENT' then 'A1P' else 'dashboard' end as page_id
                from users
                where lower(coalesce(full_name, '') || ' ' || coalesce(username, '') || ' '
                    || coalesce(student_code, '') || ' ' || coalesce(teacher_code, '') || ' '
                    || coalesce(email, '') || ' ' || coalesce(phone, '')) like ?
                order by full_name limit ?
                """, "USER", "Người dùng", pattern);
        add(out, limit, """
                select id, code as title,
                       concat(coalesce(name, 'Lớp học'), ' · ', coalesce(grade_level, '')) as subtitle,
                       'A2' as page_id
                from classes
                where lower(coalesce(code, '') || ' ' || coalesce(name, '') || ' ' || coalesce(grade_level, '')) like ?
                order by code limit ?
                """, "CLASS", "Lớp học", pattern);
        add(out, limit, """
                select id, name as title, concat('Mã môn: ', code) as subtitle, 'A2' as page_id
                from subjects
                where lower(coalesce(code, '') || ' ' || coalesce(name, '')) like ?
                order by name limit ?
                """, "SUBJECT", "Môn học", pattern);
        add(out, limit, """
                select id, code as title,
                       concat(student_name, ' · ', status, ' · ', total_amount, '₫') as subtitle,
                       'A7' as page_id
                from invoices
                where lower(coalesce(code, '') || ' ' || coalesce(student_name, '') || ' ' || coalesce(status, '')) like ?
                order by issued_at desc nulls last limit ?
                """, "INVOICE", "Tài chính", pattern);
        add(out, limit, """
                select id, name as title, concat(code, ' · ', status) as subtitle, 'A4' as page_id
                from exam_periods
                where lower(coalesce(code, '') || ' ' || coalesce(name, '') || ' ' || coalesce(status, '')) like ?
                order by start_date desc limit ?
                """, "EXAM", "Khảo thí", pattern);
    }

    private void teacher(List<SearchItem> out, String pattern, String teacherId, int limit) {
        add(out, limit, """
                select distinct c.id, c.code as title,
                       concat(c.name, ' · ', c.grade_level) as subtitle, 'B1' as page_id
                from classes c
                where (c.homeroom_teacher_id = ? or exists (
                    select 1 from teaching_assignments ta where ta.class_id = c.id and ta.teacher_id = ?
                ))
                and lower(coalesce(c.code, '') || ' ' || coalesce(c.name, '') || ' ' || coalesce(c.grade_level, '')) like ?
                order by title limit ?
                """, "CLASS", "Lớp phụ trách", teacherId, teacherId, pattern);
        add(out, limit, """
                select u.id, u.full_name as title,
                       concat(coalesce(u.student_code, u.username), ' · ', coalesce(u.class_name, 'Chưa xếp lớp')) as subtitle,
                       'B1' as page_id
                from users u
                where u.role = 'STUDENT' and (
                    exists (select 1 from classes c where c.id = u.class_id and c.homeroom_teacher_id = ?)
                    or exists (select 1 from teaching_assignments ta where ta.class_id = u.class_id and ta.teacher_id = ?)
                )
                and lower(coalesce(u.full_name, '') || ' ' || coalesce(u.username, '') || ' '
                    || coalesce(u.student_code, '')) like ?
                order by u.full_name limit ?
                """, "STUDENT", "Học sinh", teacherId, teacherId, pattern);
        add(out, limit, """
                select id, title, concat(subject_name, ' · Hạn ', deadline) as subtitle, 'B5' as page_id
                from assignments
                where teacher_id = ?
                  and lower(coalesce(title, '') || ' ' || coalesce(subject_name, '') || ' ' || coalesce(description, '')) like ?
                order by deadline desc nulls last limit ?
                """, "ASSIGNMENT", "Bài tập", teacherId, pattern);
        add(out, limit, """
                select distinct ep.id, ep.name as title,
                       concat(es.subject_name, ' · ', es.exam_date, ' ', es.start_time) as subtitle,
                       'B12' as page_id
                from exam_periods ep
                join exam_schedules es on es.exam_period_id = ep.id
                where ep.schedule_published = true
                  and (
                    exists (select 1 from exam_rooms er where er.schedule_id = es.id
                        and (er.proctor_one_id = ? or er.proctor_two_id = ?))
                    or exists (select 1 from teaching_assignments ta
                        join exam_schedule_classes esc on esc.class_id = ta.class_id
                        where esc.schedule_id = es.id and ta.subject_id = es.subject_id and ta.teacher_id = ?)
                  )
                  and lower(coalesce(ep.name, '') || ' ' || coalesce(ep.code, '') || ' '
                      || coalesce(es.subject_name, '')) like ?
                order by ep.id limit ?
                """, "EXAM", "Lịch thi", teacherId, teacherId, teacherId, pattern);
    }

    private void student(List<SearchItem> out, String pattern, String studentId, int limit) {
        add(out, limit, """
                select a.id, a.title,
                       concat(a.subject_name, ' · Hạn ', a.deadline) as subtitle, 'C4' as page_id
                from assignments a
                where a.class_id = (select class_id from users where id = ?)
                  and a.status = 'PUBLISHED'
                  and lower(coalesce(a.title, '') || ' ' || coalesce(a.subject_name, '') || ' '
                    || coalesce(a.description, '')) like ?
                order by a.deadline desc nulls last limit ?
                """, "ASSIGNMENT", "Bài tập", studentId, pattern);
        add(out, limit, """
                select distinct ta.subject_id as id, ta.subject_name as title,
                       concat('Giáo viên: ', ta.teacher_name) as subtitle, 'C2' as page_id
                from teaching_assignments ta
                where ta.class_id = (select class_id from users where id = ?)
                  and lower(coalesce(ta.subject_name, '') || ' ' || coalesce(ta.teacher_name, '')) like ?
                order by title limit ?
                """, "SUBJECT", "Môn học", studentId, pattern);
        add(out, limit, """
                select n.id, n.title, left(n.body, 120) as subtitle, 'C5' as page_id
                from notifications n
                where n.recipient_id = ?
                  and lower(coalesce(n.title, '') || ' ' || coalesce(n.body, '') || ' ' || coalesce(n.type, '')) like ?
                order by n.created_at desc limit ?
                """, "NOTIFICATION", "Thông báo", studentId, pattern);
        add(out, limit, """
                select distinct ep.id, ep.name as title,
                       concat(es.subject_name, ' · ', es.exam_date, ' ', es.start_time) as subtitle,
                       'C10' as page_id
                from exam_candidates ec
                join exam_periods ep on ep.id = ec.exam_period_id
                join exam_schedules es on es.id = ec.schedule_id
                where ec.student_id = ? and ep.schedule_published = true
                  and lower(coalesce(ep.name, '') || ' ' || coalesce(ep.code, '') || ' '
                    || coalesce(es.subject_name, '')) like ?
                order by ep.id limit ?
                """, "EXAM", "Lịch thi", studentId, pattern);
    }

    private void parent(List<SearchItem> out, String pattern, String parentId, int limit) {
        add(out, limit, """
                select u.id, u.full_name as title,
                       concat(coalesce(u.student_code, u.username), ' · ', coalesce(u.class_name, 'Chưa xếp lớp')) as subtitle,
                       'D2' as page_id
                from parent_student ps join users u on u.id = ps.student_id
                where ps.parent_id = ?
                  and lower(coalesce(u.full_name, '') || ' ' || coalesce(u.username, '') || ' '
                    || coalesce(u.student_code, '') || ' ' || coalesce(u.class_name, '')) like ?
                order by u.full_name limit ?
                """, "STUDENT", "Con của tôi", parentId, pattern);
        add(out, limit, """
                select i.id, i.code as title,
                       concat(i.student_name, ' · ', i.status, ' · ', i.total_amount, '₫') as subtitle,
                       'D4' as page_id
                from invoices i
                where (i.parent_id = ? or exists (
                    select 1 from parent_student ps where ps.parent_id = ? and ps.student_id = i.student_id
                ))
                  and lower(coalesce(i.code, '') || ' ' || coalesce(i.student_name, '') || ' '
                    || coalesce(i.status, '')) like ?
                order by i.issued_at desc nulls last limit ?
                """, "INVOICE", "Học phí", parentId, parentId, pattern);
        add(out, limit, """
                select distinct a.id, a.title,
                       concat(a.subject_name, ' · Hạn ', a.deadline) as subtitle, 'D2' as page_id
                from assignments a
                join users s on s.class_id = a.class_id
                join parent_student ps on ps.student_id = s.id
                where ps.parent_id = ? and a.status = 'PUBLISHED'
                  and lower(coalesce(a.title, '') || ' ' || coalesce(a.subject_name, '') || ' '
                    || coalesce(a.description, '')) like ?
                order by a.title limit ?
                """, "ASSIGNMENT", "Bài tập của con", parentId, pattern);
        add(out, limit, """
                select distinct ep.id, ep.name as title,
                       concat(es.subject_name, ' · ', es.exam_date, ' ', es.start_time) as subtitle,
                       'D9' as page_id
                from parent_student ps
                join exam_candidates ec on ec.student_id = ps.student_id
                join exam_periods ep on ep.id = ec.exam_period_id
                join exam_schedules es on es.id = ec.schedule_id
                where ps.parent_id = ? and ep.schedule_published = true
                  and lower(coalesce(ep.name, '') || ' ' || coalesce(ep.code, '') || ' '
                    || coalesce(es.subject_name, '')) like ?
                order by ep.id limit ?
                """, "EXAM", "Lịch thi của con", parentId, pattern);
    }

    private void add(List<SearchItem> out, int totalLimit, String sql, String type, String category,
                     Object... parametersWithoutLimit) {
        if (out.size() >= totalLimit) return;
        int sourceLimit = Math.min(5, totalLimit - out.size());
        Object[] parameters = new Object[parametersWithoutLimit.length + 1];
        System.arraycopy(parametersWithoutLimit, 0, parameters, 0, parametersWithoutLimit.length);
        parameters[parameters.length - 1] = sourceLimit;
        out.addAll(jdbc.query(sql, (rs, rowNum) -> new SearchItem(
                type,
                category,
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("page_id")
        ), parameters));
    }
}
