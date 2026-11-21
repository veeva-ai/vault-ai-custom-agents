package com.veeva.vault.custom.tooltype;

import com.veeva.vault.sdk.api.ai.AiToolTypeExecuteContext;
import com.veeva.vault.sdk.api.ai.AiToolTypeExecutionResponse;
import com.veeva.vault.sdk.api.ai.AiToolTypeRuntimeHandler;
import com.veeva.vault.sdk.api.ai.AiToolTypeRuntimeHandlerInfo;
import com.veeva.vault.sdk.api.core.ServiceLocator;
import com.veeva.vault.sdk.api.core.ValueType;
import com.veeva.vault.sdk.api.core.VaultCollections;
import com.veeva.vault.sdk.api.data.Record;
import com.veeva.vault.sdk.api.data.RecordBatchSaveRequest;
import com.veeva.vault.sdk.api.data.RecordService;
import com.veeva.vault.sdk.api.executeas.ExecuteAs;
import com.veeva.vault.sdk.api.executeas.ExecuteAsUser;
import com.veeva.vault.sdk.api.json.JsonObject;
import com.veeva.vault.sdk.api.json.JsonObjectBuilder;
import com.veeva.vault.sdk.api.json.JsonProperty;
import com.veeva.vault.sdk.api.json.JsonService;
import com.veeva.vault.sdk.api.json.JsonValueType;
import com.veeva.vault.sdk.api.query.Query;
import com.veeva.vault.sdk.api.query.QueryService;
import com.veeva.vault.sdk.api.token.TokenRequest;
import com.veeva.vault.sdk.api.token.TokenService;

import java.util.List;
import java.util.Map;

/**
 * Custom AI Tool Type that registers a known Student into an existing Course Section by creating
 * a {@code course_registration__c} record. Returns a deterministic JSON envelope:
 *
 * <pre>
 *   SUCCESS: { "responseStatus": "SUCCESS", "responseMessage": "Student X is registered to section Y", ... }
 *   FAILURE: { "responseStatus": "FAILURE", "responseMessage": "Student X cannot be registered to section Y", "failureReason": "..." }
 * </pre>
 *
 * Failures are NOT returned via the SDK's error response builder — they are returned as
 * SUCCESS-at-the-SDK-level JSON with {@code responseStatus = "FAILURE"} so the calling agent
 * action sees the full envelope and can phrase the chat reply.
 */
@ExecuteAs(ExecuteAsUser.REQUEST_OWNER)
@AiToolTypeRuntimeHandlerInfo(dynamicToolSpec = false)
public class RegisterStudentToolType implements AiToolTypeRuntimeHandler {

    @Override
    public AiToolTypeExecutionResponse onExecute(AiToolTypeExecuteContext context) {
        JsonService jsonService = ServiceLocator.locate(JsonService.class);
        QueryService queryService = ServiceLocator.locate(QueryService.class);
        TokenService tokenService = ServiceLocator.locate(TokenService.class);

        // --- 1. Read & normalize inputs.
        JsonObject inputs = context.getInput();
        String studentNameInput = safeString(inputs, "student_name");
        String sectionNumberInput = safeString(inputs, "section_number");

        if (studentNameInput.isEmpty()) {
            return buildFailure(context, jsonService, studentNameInput, sectionNumberInput,
                    "Student Name not provided.");
        }
        if (sectionNumberInput.isEmpty()) {
            return buildFailure(context, jsonService, studentNameInput, sectionNumberInput,
                    "Section Number not provided.");
        }

        // --- 2. Section lookup (case-insensitive).
        List<String> sectionIds = VaultCollections.newList();
        List<String> sectionNames = VaultCollections.newList();
        Query sectionQuery = queryService.newQueryBuilder()
                .withSelect(VaultCollections.asList("id", "name__v"))
                .withFrom("course_section__c")
                .withWhere("CASEINSENSITIVE(name__v) = ${Custom.section_number}")
                .withMaxRows(5)
                .build();
        TokenRequest sectionTokens = tokenService.newTokenRequestBuilder()
                .withValue("Custom.section_number", sectionNumberInput)
                .build();
        queryService.query(queryService.newQueryExecutionRequestBuilder()
                        .withQuery(sectionQuery)
                        .withTokenRequest(sectionTokens)
                        .build())
                .onSuccess(resp -> resp.streamResults().forEach(row -> {
                    sectionIds.add(row.getValue("id", ValueType.STRING));
                    sectionNames.add(row.getValue("name__v", ValueType.STRING));
                }))
                .execute();

        if (sectionIds.isEmpty()) {
            return buildFailure(context, jsonService, studentNameInput, sectionNumberInput,
                    "Section Number not found.");
        }
        String sectionId = sectionIds.get(0);
        String resolvedSectionName = sectionNames.get(0);

        // --- 3. Student lookup (case-insensitive, active student object type).
        List<String> studentIds = VaultCollections.newList();
        List<String> studentDisplayNames = VaultCollections.newList();
        Query studentQuery = queryService.newQueryBuilder()
                .withSelect(VaultCollections.asList("id", "name__v"))
                .withFrom("person__sys")
                .withWhere("CASEINSENSITIVE(name__v) = ${Custom.student_name} "
                        + "AND object_type__vr.api_name__v = 'student__c' "
                        + "AND status__v = 'active__v'")
                .withMaxRows(25)
                .build();
        TokenRequest studentTokens = tokenService.newTokenRequestBuilder()
                .withValue("Custom.student_name", studentNameInput)
                .build();
        queryService.query(queryService.newQueryExecutionRequestBuilder()
                        .withQuery(studentQuery)
                        .withTokenRequest(studentTokens)
                        .build())
                .onSuccess(resp -> resp.streamResults().forEach(row -> {
                    studentIds.add(row.getValue("id", ValueType.STRING));
                    studentDisplayNames.add(row.getValue("name__v", ValueType.STRING));
                }))
                .execute();

        if (studentIds.isEmpty()) {
            return buildFailure(context, jsonService, studentNameInput, resolvedSectionName,
                    "Student Name not found.");
        }
        if (studentIds.size() > 1) {
            return buildFailure(context, jsonService, studentNameInput, resolvedSectionName,
                    "Multiple Student name found: [" + joinNames(studentDisplayNames) + "]");
        }
        String studentId = studentIds.get(0);
        String resolvedStudentName = studentDisplayNames.get(0);

        // --- 4. Duplicate-registration guard (any status).
        // Note: check-then-create is not atomic. Two simultaneous requests could both pass this
        // check. A production implementation should also enforce uniqueness on the object.
        List<String> existingRegistrationIds = VaultCollections.newList();
        Query duplicateQuery = queryService.newQueryBuilder()
                .withSelect(VaultCollections.asList("id"))
                .withFrom("course_registration__c")
                .withWhere("student__c = ${Custom.student_id} AND section__c = ${Custom.section_id}")
                .withMaxRows(5)
                .build();
        TokenRequest duplicateTokens = tokenService.newTokenRequestBuilder()
                .withValue("Custom.student_id", studentId)
                .withValue("Custom.section_id", sectionId)
                .build();
        queryService.query(queryService.newQueryExecutionRequestBuilder()
                        .withQuery(duplicateQuery)
                        .withTokenRequest(duplicateTokens)
                        .build())
                .onSuccess(resp -> resp.streamResults().forEach(row -> {
                    existingRegistrationIds.add(row.getValue("id", ValueType.STRING));
                }))
                .execute();

        if (!existingRegistrationIds.isEmpty()) {
            return buildFailure(context, jsonService, resolvedStudentName, resolvedSectionName,
                    "Student " + resolvedStudentName
                            + " already registered to section " + resolvedSectionName);
        }

        // --- 5. Create the Course Registration record.
        RecordService recordService = ServiceLocator.locate(RecordService.class);
        Record newRegistration = recordService.newRecord("course_registration__c");
        newRegistration.setValue("student__c", studentId);
        newRegistration.setValue("section__c", sectionId);
        newRegistration.setValue("registration_status__c",
                VaultCollections.asList("enrolled__c"));

        // Accumulate save outcome via mutable lists (SDK pattern — no AtomicReference).
        List<String> createdRecordIds = VaultCollections.newList();
        List<String> saveErrorMessages = VaultCollections.newList();

        RecordBatchSaveRequest saveRequest = recordService.newRecordBatchSaveRequestBuilder()
                .withRecords(VaultCollections.asList(newRegistration))
                .build();
        recordService.batchSaveRecords(saveRequest)
                .onSuccesses(successes -> {
                    createdRecordIds.add(successes.get(0).getRecordId());
                })
                .onErrors(batchOperationErrors -> {
                    saveErrorMessages.add(batchOperationErrors.get(0).getError().getMessage());
                })
                .execute();

        if (!saveErrorMessages.isEmpty()) {
            return buildFailure(context, jsonService, resolvedStudentName, resolvedSectionName,
                    saveErrorMessages.get(0));
        }
        if (createdRecordIds.isEmpty()) {
            return buildFailure(context, jsonService, resolvedStudentName, resolvedSectionName,
                    "Registration save returned no record id.");
        }

        // --- 6. SUCCESS envelope.
        JsonObjectBuilder successEnvelope = jsonService.newJsonObjectBuilder();
        successEnvelope.setValue("responseStatus", "SUCCESS");
        successEnvelope.setValue("responseMessage",
                "Student " + resolvedStudentName
                        + " is registered to section " + resolvedSectionName);
        successEnvelope.setValue("registrationId", createdRecordIds.get(0));
        successEnvelope.setValue("studentId", studentId);
        successEnvelope.setValue("sectionId", sectionId);
        return context.newSuccessBuilder(AiToolTypeExecutionResponse.JsonResultBuilder.class)
                .withJson(successEnvelope.build())
                .build();
    }

    private static String safeString(JsonObject inputs, String key) {
        Map<String, JsonProperty> properties = inputs.getProperties();
        if (!properties.containsKey(key)) {
            return "";
        }
        String value = inputs.getValue(key, JsonValueType.STRING);
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private static AiToolTypeExecutionResponse buildFailure(AiToolTypeExecuteContext context,
                                                            JsonService jsonService,
                                                            String studentLabel,
                                                            String sectionLabel,
                                                            String reason) {
        JsonObjectBuilder envelope = jsonService.newJsonObjectBuilder();
        envelope.setValue("responseStatus", "FAILURE");
        envelope.setValue("responseMessage",
                "Student " + studentLabel
                        + " cannot be registered to section " + sectionLabel);
        envelope.setValue("failureReason", reason);
        return context.newSuccessBuilder(AiToolTypeExecutionResponse.JsonResultBuilder.class)
                .withJson(envelope.build())
                .build();
    }

    private static String joinNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i));
        }
        return sb.toString();
    }
}
