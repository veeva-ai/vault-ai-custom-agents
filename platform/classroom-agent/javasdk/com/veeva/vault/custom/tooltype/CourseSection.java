package com.veeva.vault.custom.tooltype;

import com.veeva.vault.sdk.api.core.UserDefinedClassInfo;

import java.math.BigDecimal;

/**
 * A Course Section ({@code course_section__c}) as read by {@link ClassroomLookups}: its id, section
 * number, semester, capacity and meeting time. Used by the tool types for the capacity and
 * schedule-conflict checks.
 *
 * Times are the {@code start_time__c} / {@code end_time__c} values, for example "09:00:00". A section
 * without both times has no meeting time, and the schedule-conflict check skips it.
 */
@UserDefinedClassInfo
public class CourseSection {

    private final String id;
    private final String name;
    private final String semester;
    private final BigDecimal capacity;
    private final String startTime;
    private final String endTime;

    public CourseSection(String id, String name, String semester, BigDecimal capacity,
                         String startTime, String endTime) {
        this.id = id;
        this.name = name;
        this.semester = semester;
        this.capacity = capacity;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** Semester picklist value name, for example "fall_2027__c", or null when not set. */
    public String getSemester() {
        return semester;
    }

    /** Maximum number of Enrolled registrations, or null when the section has no limit. */
    public BigDecimal getCapacity() {
        return capacity;
    }

    /** True when both the start and the end time are set and can be read. */
    public boolean hasMeetingTime() {
        int start = minutesOf(startTime);
        int end = minutesOf(endTime);
        return start >= 0 && end > start;
    }

    /**
     * True when both sections are in the same semester and their meeting times overlap. All sections
     * are assumed to meet on the same days, because the sample data model has no meeting days.
     */
    public boolean overlaps(CourseSection other) {
        if (semester == null || !semester.equals(other.semester)
                || !hasMeetingTime() || !other.hasMeetingTime()) {
            return false;
        }
        return minutesOf(startTime) < minutesOf(other.endTime)
                && minutesOf(other.startTime) < minutesOf(endTime);
    }

    /** "BIO101_F27_AM (09:00-10:15)", or just the section number when it has no meeting time. */
    public String describe() {
        if (!hasMeetingTime()) {
            return name;
        }
        return name + " (" + hhmm(startTime) + "-" + hhmm(endTime) + ")";
    }

    /** Minutes after midnight for "HH:MM", "HH:MM:SS" or "H:MM AM/PM"; -1 when the value can't be read. */
    private static int minutesOf(String time) {
        if (time == null) {
            return -1;
        }
        String value = time.trim().toUpperCase();
        boolean pm = value.endsWith("PM");
        boolean am = value.endsWith("AM");
        if (pm || am) {
            value = value.substring(0, value.length() - 2).trim();
        }
        int colon = value.indexOf(':');
        if (colon < 1 || value.length() < colon + 3) {
            return -1;
        }
        int hours = digits(value.substring(0, colon));
        int minutes = digits(value.substring(colon + 1, colon + 3));
        if (hours < 0 || minutes < 0 || minutes > 59) {
            return -1;
        }
        if (pm && hours < 12) {
            hours += 12;
        } else if (am && hours == 12) {
            hours = 0;
        }
        return hours > 23 ? -1 : hours * 60 + minutes;
    }

    private static int digits(String value) {
        if (value.isEmpty()) {
            return -1;
        }
        int result = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            result = result * 10 + (c - '0');
        }
        return result;
    }

    private static String hhmm(String time) {
        int minutes = minutesOf(time);
        int h = minutes / 60;
        int m = minutes % 60;
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }
}
