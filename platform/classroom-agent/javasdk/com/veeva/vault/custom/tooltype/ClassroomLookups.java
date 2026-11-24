package com.veeva.vault.custom.tooltype;

import com.veeva.vault.sdk.api.core.ServiceLocator;
import com.veeva.vault.sdk.api.core.UserDefinedClassInfo;
import com.veeva.vault.sdk.api.core.ValueType;
import com.veeva.vault.sdk.api.core.VaultCollections;
import com.veeva.vault.sdk.api.json.JsonObject;
import com.veeva.vault.sdk.api.json.JsonValueType;
import com.veeva.vault.sdk.api.query.Query;
import com.veeva.vault.sdk.api.query.QueryExecutionRequest;
import com.veeva.vault.sdk.api.query.QueryExecutionResult;
import com.veeva.vault.sdk.api.query.QueryService;
import com.veeva.vault.sdk.api.token.TokenRequest;
import com.veeva.vault.sdk.api.token.TokenService;

import java.util.List;
import java.util.Map;

/**
 * The lookups that RegisterStudentToolType and DropStudentToolType share: sections and students by
 * name, a student's registrations, and the Enrolled count of a section.
 *
 * User-supplied values always go into the query as tokens (${Custom.name}), never into the query
 * text, so Vault escapes them.
 *
 * Errors: Vault Java SDK code can only catch RollbackException, so nothing is caught here. A failed
 * query is reported through its onError handler and added to the caller's {@code errors} list, and
 * the tool turns it into a FAILURE response the agent can read.
 */
@UserDefinedClassInfo
public class ClassroomLookups {

    /** Registration Status picklist values ({@code registration_status__c}). */
    public static final String ENROLLED = "enrolled__c";
    public static final String WAITLIST = "waitlist__c";
    public static final String DROPPED = "dropped__c";
    public static final String CANCELLED = "cancelled__c";
    public static final String COMPLETE = "complete__c";

    public interface RowHandler {
        void handle(QueryExecutionResult row);
    }

    private ClassroomLookups() {
    }

    /** Course Sections whose section number matches, ignoring case (at most 5). */
    public static List<CourseSection> findSections(String sectionNumber, List<String> errors) {
        List<CourseSection> sections = VaultCollections.newList();
        Query query = newQueryBuilder()
                .withSelect(VaultCollections.asList("id", "name__v", "semester__c", "capacity__c",
                        "start_time__c", "end_time__c"))
                .withFrom("course_section__c")
                .withWhere("CASEINSENSITIVE(name__v) = ${Custom.section_number}")
                .withMaxRows(5)
                .build();
        TokenRequest tokens = newTokenRequestBuilder()
                .withValue("Custom.section_number", sectionNumber)
                .build();
        addError(errors, "Section lookup failed", runQuery(query, tokens, row -> sections.add(new CourseSection(
                row.getValue("id", ValueType.STRING),
                row.getValue("name__v", ValueType.STRING),
                firstValue(row.getValue("semester__c", ValueType.PICKLIST_VALUES)),
                row.getValue("capacity__c", ValueType.NUMBER),
                row.getValue("start_time__c", ValueType.STRING),
                row.getValue("end_time__c", ValueType.STRING)))));
        return sections;
    }

    /** Active Students whose full name matches, ignoring case (at most 25). */
    public static List<Student> findActiveStudents(String studentName, List<String> errors) {
        List<Student> students = VaultCollections.newList();
        Query query = newQueryBuilder()
                .withSelect(VaultCollections.asList("id", "name__v"))
                .withFrom("person__sys")
                .withWhere("CASEINSENSITIVE(name__v) = ${Custom.student_name}"
                        + " AND object_type__vr.api_name__v = 'student__c'"
                        + " AND status__v = 'active__v'")
                .withMaxRows(25)
                .build();
        TokenRequest tokens = newTokenRequestBuilder()
                .withValue("Custom.student_name", studentName)
                .build();
        addError(errors, "Student lookup failed", runQuery(query, tokens, row -> students.add(new Student(
                row.getValue("id", ValueType.STRING),
                row.getValue("name__v", ValueType.STRING)))));
        return students;
    }

    /**
     * The student's registrations in the section, in any status: registration id to Registration
     * Status value name ("" when the status is not set).
     */
    public static Map<String, String> findRegistrations(String studentId, String sectionId, List<String> errors) {
        Map<String, String> registrations = VaultCollections.newMap();
        Query query = newQueryBuilder()
                .withSelect(VaultCollections.asList("id", "registration_status__c"))
                .withFrom("course_registration__c")
                .withWhere("student__c = ${Custom.student_id} AND section__c = ${Custom.section_id}")
                .build();
        TokenRequest tokens = newTokenRequestBuilder()
                .withValue("Custom.student_id", studentId)
                .withValue("Custom.section_id", sectionId)
                .build();
        addError(errors, "Registration lookup failed", runQuery(query, tokens, row -> registrations.put(
                row.getValue("id", ValueType.STRING),
                orEmpty(firstValue(row.getValue("registration_status__c", ValueType.PICKLIST_VALUES))))));
        return registrations;
    }

    /** Number of Enrolled registrations in the section. */
    public static long countEnrolled(String sectionId, List<String> errors) {
        long[] count = {0};
        StringBuilder error = new StringBuilder();
        QueryService queryService = ServiceLocator.locate(QueryService.class);
        Query query = newQueryBuilder()
                .withSelect(VaultCollections.asList("id"))
                .withFrom("course_registration__c")
                .withWhere("section__c = ${Custom.section_id} AND registration_status__c = '" + ENROLLED + "'")
                .build();
        TokenRequest tokens = newTokenRequestBuilder()
                .withValue("Custom.section_id", sectionId)
                .build();
        queryService.query(queryService.newQueryExecutionRequestBuilder()
                        .withQuery(query)
                        .withTokenRequest(tokens)
                        .build())
                .onSuccess(response -> count[0] = response.getResultCount())
                .onError(queryError -> error.append(queryError.getMessage()))
                .execute();
        addError(errors, "Enrollment count failed", error.toString());
        return count[0];
    }

    /** The sections the student is Enrolled in, with their semester and meeting time. */
    public static List<CourseSection> findEnrolledSections(String studentId, List<String> errors) {
        List<CourseSection> sections = VaultCollections.newList();
        Query query = newQueryBuilder()
                .withSelect(VaultCollections.asList("section__c", "section__cr.name__v", "section__cr.semester__c",
                        "section__cr.capacity__c", "section__cr.start_time__c", "section__cr.end_time__c"))
                .withFrom("course_registration__c")
                .withWhere("student__c = ${Custom.student_id} AND registration_status__c = '" + ENROLLED + "'")
                .build();
        TokenRequest tokens = newTokenRequestBuilder()
                .withValue("Custom.student_id", studentId)
                .build();
        addError(errors, "Enrolled sections lookup failed", runQuery(query, tokens, row -> sections.add(new CourseSection(
                row.getValue("section__c", ValueType.STRING),
                row.getValue("section__cr.name__v", ValueType.STRING),
                firstValue(row.getValue("section__cr.semester__c", ValueType.PICKLIST_VALUES)),
                row.getValue("section__cr.capacity__c", ValueType.NUMBER),
                row.getValue("section__cr.start_time__c", ValueType.STRING),
                row.getValue("section__cr.end_time__c", ValueType.STRING)))));
        return sections;
    }

    /** Display label of a Registration Status value name, for messages. */
    public static String statusLabel(String status) {
        if (ENROLLED.equals(status)) {
            return "Enrolled";
        }
        if (WAITLIST.equals(status)) {
            return "Waitlist";
        }
        if (DROPPED.equals(status)) {
            return "Dropped";
        }
        if (CANCELLED.equals(status)) {
            return "Cancelled";
        }
        if (COMPLETE.equals(status)) {
            return "Complete";
        }
        return status == null || status.isEmpty() ? "no status" : status;
    }

    /** Trimmed string input, or "" when the key is missing or null. */
    public static String inputString(JsonObject input, String key) {
        if (input == null || !input.contains(key)) {
            return "";
        }
        String value = input.getValue(key, JsonValueType.STRING);
        return value == null ? "" : value.trim();
    }

    /**
     * Runs the query and passes each row to the handler. Returns the error message, or "" when the
     * query succeeded.
     */
    public static String runQuery(Query query, TokenRequest tokens, RowHandler handler) {
        StringBuilder error = new StringBuilder();
        QueryService queryService = ServiceLocator.locate(QueryService.class);
        QueryExecutionRequest request = queryService.newQueryExecutionRequestBuilder()
                .withQuery(query)
                .withTokenRequest(tokens)
                .build();
        queryService.query(request)
                .onSuccess(response -> response.streamResults().forEach(row -> handler.handle(row)))
                .onError(queryError -> error.append(queryError.getMessage()))
                .execute();
        return error.toString();
    }

    /** Joins values with ", ". */
    public static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(values.get(i));
        }
        return out.toString();
    }

    private static Query.Builder newQueryBuilder() {
        return ServiceLocator.locate(QueryService.class).newQueryBuilder();
    }

    private static TokenRequest.Builder newTokenRequestBuilder() {
        return ServiceLocator.locate(TokenService.class).newTokenRequestBuilder();
    }

    private static void addError(List<String> errors, String what, String message) {
        if (message != null && !message.isEmpty()) {
            errors.add(what + ": " + message);
        }
    }

    private static String firstValue(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
