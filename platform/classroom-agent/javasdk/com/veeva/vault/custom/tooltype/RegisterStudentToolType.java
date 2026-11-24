package com.veeva.vault.custom.tooltype;

import com.veeva.vault.sdk.api.ai.AiToolTypeExecuteContext;
import com.veeva.vault.sdk.api.ai.AiToolTypeExecutionResponse;
import com.veeva.vault.sdk.api.ai.AiToolTypeRuntimeHandler;
import com.veeva.vault.sdk.api.ai.AiToolTypeRuntimeHandlerInfo;
import com.veeva.vault.sdk.api.core.ServiceLocator;
import com.veeva.vault.sdk.api.core.VaultCollections;
import com.veeva.vault.sdk.api.data.Record;
import com.veeva.vault.sdk.api.data.RecordBatchSaveRequest;
import com.veeva.vault.sdk.api.data.RecordService;
import com.veeva.vault.sdk.api.executeas.ExecuteAs;
import com.veeva.vault.sdk.api.executeas.ExecuteAsUser;
import com.veeva.vault.sdk.api.json.JsonObject;
import com.veeva.vault.sdk.api.json.JsonObjectBuilder;
import com.veeva.vault.sdk.api.json.JsonService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Custom AI Tool Type that registers a known Student into an existing Course Section by creating
 * a {@code course_registration__c} record in the Enrolled status. It rejects the request when:
 * <ul>
 *   <li>the section or the active student is not found, or the name matches more than one record;</li>
 *   <li>the student is already Enrolled or on the Waitlist in the section, or has completed it
 *       (a Dropped or Cancelled registration does not block a new one);</li>
 *   <li>the section is full: its Enrolled registrations have reached {@code capacity__c}
 *       (a section with no capacity has no limit);</li>
 *   <li>the section's meeting time overlaps another section the student is Enrolled in during the
 *       same semester. Sections without a start and end time are not checked, and all sections
 *       are assumed to meet on the same days.</li>
 * </ul>
 * Returns a deterministic JSON envelope:
 *
 * <pre>
 *   SUCCESS: { "responseStatus": "SUCCESS", "responseMessage": "Student X is registered to section Y", ... }
 *   FAILURE: { "responseStatus": "FAILURE", "responseMessage": "Student X cannot be registered to section Y", "failureReason": "..." }
 * </pre>
 *
 * Failures are NOT returned via the SDK's error response builder: they are returned as
 * SUCCESS-at-the-SDK-level JSON with {@code responseStatus = "FAILURE"} so the calling agent
 * action sees the full envelope and can phrase the chat reply.
 *
 * The checks and the create are not atomic: two simultaneous requests could both pass them.
 * A production implementation should also enforce these rules on the object, for example with a
 * record trigger.
 */
@ExecuteAs(ExecuteAsUser.REQUEST_OWNER)
@AiToolTypeRuntimeHandlerInfo(dynamicToolSpec = false)
public class RegisterStudentToolType implements AiToolTypeRuntimeHandler {

    @Override
    public AiToolTypeExecutionResponse onExecute(AiToolTypeExecuteContext context) {
        JsonService jsonService = ServiceLocator.locate(JsonService.class);
        List<String> errors = VaultCollections.newList();

        // --- 1. Read and normalize the inputs.
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
                            + " records), so the tool cannot tell which one to register.");
        }
        Student student = students.get(0);

        // --- 4. Duplicate guard: Enrolled, Waitlist or Complete blocks a new registration.
        Map<String, String> registrations = ClassroomLookups.findRegistrations(student.getId(), section.getId(), errors);
        if (!errors.isEmpty()) {
            return buildFailure(context, jsonService, student.getName(), section.getName(), ClassroomLookups.join(errors));
        }
        for (String status : registrations.values()) {
            if (ClassroomLookups.ENROLLED.equals(status) || ClassroomLookups.WAITLIST.equals(status)) {
                return buildFailure(context, jsonService, student.getName(), section.getName(),
                        "Student " + student.getName() + " is already registered to section " + section.getName()
                                + " (" + ClassroomLookups.statusLabel(status) + ").");
            }
            if (ClassroomLookups.COMPLETE.equals(status)) {
                return buildFailure(context, jsonService, student.getName(), section.getName(),
                        "Student " + student.getName() + " has already completed section " + section.getName() + ".");
            }
        }

        // --- 5. Capacity guard.
        BigDecimal capacity = section.getCapacity();
        if (capacity != null) {
            long enrolled = ClassroomLookups.countEnrolled(section.getId(), errors);
            if (!errors.isEmpty()) {
                return buildFailure(context, jsonService, student.getName(), section.getName(), ClassroomLookups.join(errors));
            }
            if (enrolled >= capacity.longValue()) {
                return buildFailure(context, jsonService, student.getName(), section.getName(),
                        "Section " + section.getName() + " is full (capacity " + capacity.longValue() + ").");
            }
        }

        // --- 6. Schedule-conflict guard: same semester, overlapping meeting time.
        if (section.hasMeetingTime()) {
            List<CourseSection> enrolledSections = ClassroomLookups.findEnrolledSections(student.getId(), errors);
            if (!errors.isEmpty()) {
                return buildFailure(context, jsonService, student.getName(), section.getName(), ClassroomLookups.join(errors));
            }
            for (CourseSection other : enrolledSections) {
                if (!other.getId().equals(section.getId()) && section.overlaps(other)) {
                    return buildFailure(context, jsonService, student.getName(), section.getName(),
                            "Schedule conflict: section " + section.describe() + " overlaps section "
                                    + other.describe() + ", which " + student.getName()
                                    + " is already enrolled in this semester.");
                }
            }
        }

        // --- 7. Create the Course Registration record.
        RecordService recordService = ServiceLocator.locate(RecordService.class);
        Record newRegistration = recordService.newRecord("course_registration__c");
        newRegistration.setValue("student__c", student.getId());
        newRegistration.setValue("section__c", section.getId());
        newRegistration.setValue("registration_status__c", VaultCollections.asList(ClassroomLookups.ENROLLED));

        // Collect the save outcome in lists, because lambdas can only change effectively final objects.
        List<String> createdRecordIds = VaultCollections.newList();
        List<String> saveErrorMessages = VaultCollections.newList();
        RecordBatchSaveRequest saveRequest = recordService.newRecordBatchSaveRequestBuilder()
                .withRecords(VaultCollections.asList(newRegistration))
                .build();
        recordService.batchSaveRecords(saveRequest)
                .onSuccesses(successes -> createdRecordIds.add(successes.get(0).getRecordId()))
                .onErrors(batchErrors -> saveErrorMessages.add(batchErrors.get(0).getError().getMessage()))
                .execute();

        if (!saveErrorMessages.isEmpty()) {
            return buildFailure(context, jsonService, student.getName(), section.getName(), saveErrorMessages.get(0));
        }
        if (createdRecordIds.isEmpty()) {
            return buildFailure(context, jsonService, student.getName(), section.getName(),
                    "The registration was not saved and Vault returned no error.");
        }

        // --- 8. SUCCESS envelope.
        JsonObjectBuilder successEnvelope = jsonService.newJsonObjectBuilder();
        successEnvelope.setValue("responseStatus", "SUCCESS");
        successEnvelope.setValue("responseMessage",
                "Student " + student.getName() + " is registered to section " + section.getName());
        successEnvelope.setValue("registrationId", createdRecordIds.get(0));
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
                "Student " + studentLabel + " cannot be registered to section " + sectionLabel);
        envelope.setValue("failureReason", reason);
        return context.newSuccessBuilder(AiToolTypeExecutionResponse.JsonResultBuilder.class)
                .withJson(envelope.build())
                .build();
    }
}
