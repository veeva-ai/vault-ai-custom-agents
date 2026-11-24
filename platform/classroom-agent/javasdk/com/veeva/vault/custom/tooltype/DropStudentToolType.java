package com.veeva.vault.custom.tooltype;

import com.veeva.vault.sdk.api.ai.AiToolTypeExecuteContext;
import com.veeva.vault.sdk.api.ai.AiToolTypeExecutionResponse;
import com.veeva.vault.sdk.api.ai.AiToolTypeRuntimeHandler;
import com.veeva.vault.sdk.api.ai.AiToolTypeRuntimeHandlerInfo;
import com.veeva.vault.sdk.api.core.ServiceLocator;
import com.veeva.vault.sdk.api.core.VaultCollections;
import com.veeva.vault.sdk.api.data.Record;
import com.veeva.vault.sdk.api.data.RecordService;
import com.veeva.vault.sdk.api.executeas.ExecuteAs;
import com.veeva.vault.sdk.api.executeas.ExecuteAsUser;
import com.veeva.vault.sdk.api.json.JsonArrayBuilder;
import com.veeva.vault.sdk.api.json.JsonObject;
import com.veeva.vault.sdk.api.json.JsonObjectBuilder;
import com.veeva.vault.sdk.api.json.JsonService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Custom AI Tool Type that drops (withdraws) a Student from a Course Section: it sets the student's
 * Enrolled or Waitlist {@code course_registration__c} record to Dropped and its End Date to today.
 * The record is updated, not deleted, so the registration history stays in Vault.
 *
 * It rejects the request when the section or the active student is not found (or the name matches
 * more than one record), when the student has no Enrolled or Waitlist registration in the section,
 * or when the student has already completed it. Returns the same JSON envelope as
 * {@link RegisterStudentToolType}:
 *
 * <pre>
 *   SUCCESS: { "responseStatus": "SUCCESS", "responseMessage": "Student X is dropped from section Y", "registrationIds": [...] }
 *   FAILURE: { "responseStatus": "FAILURE", "responseMessage": "Student X cannot be dropped from section Y", "failureReason": "..." }
 * </pre>
 */
@ExecuteAs(ExecuteAsUser.REQUEST_OWNER)
@AiToolTypeRuntimeHandlerInfo(dynamicToolSpec = false)
public class DropStudentToolType implements AiToolTypeRuntimeHandler {

    @Override
    public AiToolTypeExecutionResponse onExecute(AiToolTypeExecuteContext context) {
        JsonService jsonService = ServiceLocator.locate(JsonService.class);
        List<String> errors = VaultCollections.newList();

        // --- 1. Read and normalize the inputs. Both are required: never guess the section.
        JsonObject input = context.getInput();
        String studentNameInput = ClassroomLookups.inputString(input, "student_name");
        String sectionNumberInput = ClassroomLookups.inputString(input, "section_number");
        if (studentNameInput.isEmpty()) {
            return buildFailure(context, jsonService, studentNameInput, sectionNumberInput,
                    "The student name was not provided.");
        }
        if (sectionNumberInput.isEmpty()) {
            return buildFailure(context, jsonService, studentNameInput, sectionNumberInput,
                    "The section number was not provided.");
        }

        // --- 2. Section lookup (case-insensitive, exactly one match).
        List<CourseSection> sections = ClassroomLookups.findSections(sectionNumberInput, errors);
        if (!errors.isEmpty()) {
            return buildFailure(context, jsonService, studentNameInput, sectionNumberInput, ClassroomLookups.join(errors));
        }
        if (sections.isEmpty()) {
            return buildFailure(context, jsonService, studentNameInput, sectionNumberInput,
                    "Section " + sectionNumberInput + " was not found.");
        }
        if (sections.size() > 1) {
            return buildFailure(context, jsonService, studentNameInput, sectionNumberInput,
                    "More than one section is named " + sectionNumberInput + ", so the tool cannot tell which one to use.");
        }
        CourseSection section = sections.get(0);

        // --- 3. Student lookup (case-insensitive, active student object type, exactly one match).
        List<Student> students = ClassroomLookups.findActiveStudents(studentNameInput, errors);
        if (!errors.isEmpty()) {
            return buildFailure(context, jsonService, studentNameInput, section.getName(), ClassroomLookups.join(errors));
        }
        if (students.isEmpty()) {
            return buildFailure(context, jsonService, studentNameInput, section.getName(),
                    "No active student named " + studentNameInput + " was found.");
        }
        if (students.size() > 1) {
            return buildFailure(context, jsonService, studentNameInput, section.getName(),
                    "More than one active student is named " + studentNameInput + " (" + students.size()
                            + " records), so the tool cannot tell which one to drop.");
        }
        Student student = students.get(0);

        // --- 4. Find the registrations to drop: Enrolled or Waitlist.
        Map<String, String> registrations = ClassroomLookups.findRegistrations(student.getId(), section.getId(), errors);
        if (!errors.isEmpty()) {
            return buildFailure(context, jsonService, student.getName(), section.getName(), ClassroomLookups.join(errors));
        }
        List<String> activeIds = VaultCollections.newList();
        String otherStatus = null;
        for (Map.Entry<String, String> registration : registrations.entrySet()) {
            String status = registration.getValue();
            if (ClassroomLookups.ENROLLED.equals(status) || ClassroomLookups.WAITLIST.equals(status)) {
                activeIds.add(registration.getKey());
            } else if (ClassroomLookups.COMPLETE.equals(status) || otherStatus == null) {
                otherStatus = status;
            }
        }
        if (activeIds.isEmpty()) {
            String reason;
            if (ClassroomLookups.COMPLETE.equals(otherStatus)) {
                reason = "Student " + student.getName() + " has already completed section " + section.getName()
                        + ", so the registration cannot be dropped.";
            } else if (otherStatus != null) {
                reason = "Student " + student.getName() + " is not currently registered to section " + section.getName()
                        + " (the registration is " + ClassroomLookups.statusLabel(otherStatus) + ").";
            } else {
                reason = "Student " + student.getName() + " is not registered to section " + section.getName() + ".";
            }
            return buildFailure(context, jsonService, student.getName(), section.getName(), reason);
        }

        // --- 5. Set the registrations to Dropped, with today as the End Date.
        RecordService recordService = ServiceLocator.locate(RecordService.class);
        List<Record> updates = VaultCollections.newList();
        for (String registrationId : activeIds) {
            Record update = recordService.newRecordWithId("course_registration__c", registrationId);
            update.setValue("registration_status__c", VaultCollections.asList(ClassroomLookups.DROPPED));
            update.setValue("end_date__c", LocalDate.now());
            updates.add(update);
        }
        List<String> saveErrorMessages = VaultCollections.newList();
        recordService.batchSaveRecords(recordService.newRecordBatchSaveRequestBuilder()
                        .withRecords(updates)
                        .build())
                .onErrors(batchErrors -> saveErrorMessages.add(batchErrors.get(0).getError().getMessage()))
                .execute();
        if (!saveErrorMessages.isEmpty()) {
            return buildFailure(context, jsonService, student.getName(), section.getName(), saveErrorMessages.get(0));
        }

        // --- 6. SUCCESS envelope.
        JsonArrayBuilder droppedIds = jsonService.newJsonArrayBuilder();
        for (String registrationId : activeIds) {
            droppedIds.add(registrationId);
        }
        JsonObjectBuilder successEnvelope = jsonService.newJsonObjectBuilder();
        successEnvelope.setValue("responseStatus", "SUCCESS");
        successEnvelope.setValue("responseMessage",
                "Student " + student.getName() + " is dropped from section " + section.getName());
        successEnvelope.setValue("registrationIds", droppedIds.build());
        successEnvelope.setValue("studentId", student.getId());
        successEnvelope.setValue("sectionId", section.getId());
        return context.newSuccessBuilder(AiToolTypeExecutionResponse.JsonResultBuilder.class)
                .withJson(successEnvelope.build())
                .build();
    }

    private static AiToolTypeExecutionResponse buildFailure(AiToolTypeExecuteContext context,
                                                            JsonService jsonService,
                                                            String studentLabel,
                                                            String sectionLabel,
                                                            String reason) {
        JsonObjectBuilder envelope = jsonService.newJsonObjectBuilder();
        envelope.setValue("responseStatus", "FAILURE");
        envelope.setValue("responseMessage",
                "Student " + studentLabel + " cannot be dropped from section " + sectionLabel);
        envelope.setValue("failureReason", reason);
        return context.newSuccessBuilder(AiToolTypeExecutionResponse.JsonResultBuilder.class)
                .withJson(envelope.build())
                .build();
    }
}
