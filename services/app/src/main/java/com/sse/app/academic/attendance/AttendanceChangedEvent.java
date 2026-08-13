package com.sse.app.academic.attendance;

import java.util.List;

record AttendanceChangedEvent(List<String> recordIds) {}
