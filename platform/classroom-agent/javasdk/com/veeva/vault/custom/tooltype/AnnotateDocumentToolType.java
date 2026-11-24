package com.veeva.vault.custom.tooltype;

import com.veeva.vault.sdk.api.ai.AiToolTypeExecuteContext;
import com.veeva.vault.sdk.api.ai.AiToolTypeExecutionResponse;
import com.veeva.vault.sdk.api.ai.AiToolTypeRuntimeHandler;
import com.veeva.vault.sdk.api.ai.AiToolTypeRuntimeHandlerInfo;
import com.veeva.vault.sdk.api.annotation.Annotation;
import com.veeva.vault.sdk.api.annotation.AnnotationBatchSaveError;
import com.veeva.vault.sdk.api.annotation.AnnotationService;
import com.veeva.vault.sdk.api.core.ServiceLocator;
import com.veeva.vault.sdk.api.core.VaultCollections;
import com.veeva.vault.sdk.api.executeas.ExecuteAs;
import com.veeva.vault.sdk.api.executeas.ExecuteAsUser;
import com.veeva.vault.sdk.api.json.JsonArray;
import com.veeva.vault.sdk.api.json.JsonObject;
import com.veeva.vault.sdk.api.json.JsonObjectBuilder;
import com.veeva.vault.sdk.api.json.JsonService;
import com.veeva.vault.sdk.api.json.JsonValueType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Creates one note__sys annotation per annotation text, each with a yellow, solid rectangle
 * placemark on page 1 of the given document version (stacked vertically by index, since no
 * placemark location is supplied by the LLM). Always returns a structured responseStatus/
 * responseMessage(/failureReason) envelope rather than throwing.
 */
@ExecuteAs(ExecuteAsUser.REQUEST_OWNER)
@AiToolTypeRuntimeHandlerInfo(dynamicToolSpec = false)
public class AnnotateDocumentToolType implements AiToolTypeRuntimeHandler {

    @Override
    public AiToolTypeExecutionResponse onExecute(AiToolTypeExecuteContext context) {
        JsonObject input = context.getInput();
        JsonService jsonService = ServiceLocator.locate(JsonService.class);
        // getValue throws when a key is missing, so check first and return a FAILURE envelope instead.
        String documentVersionId = input != null && input.contains("document_version_id")
                ? input.getValue("document_version_id", JsonValueType.STRING) : null;
        JsonArray annotationsInput = input != null && input.contains("annotations")
                ? input.getValue("annotations", JsonValueType.ARRAY) : null;

        if (documentVersionId == null || documentVersionId.isEmpty()) {
            return buildFailure(context, jsonService, "document_version_id is required.");
        }
        if (annotationsInput == null || annotationsInput.getSize() == 0) {
            return buildFailure(context, jsonService, "At least one annotation text is required.");
        }

        AnnotationService annotationService = ServiceLocator.locate(AnnotationService.class);
        List<Annotation> annotations = VaultCollections.newList();
        int count = annotationsInput.getSize();
        for (int i = 0; i < count; i++) {
            String text = annotationsInput.getValue(i, JsonValueType.STRING);
            if (text == null || text.trim().isEmpty()) {
                return buildFailure(context, jsonService, "Annotation " + (i + 1) + " is empty.");
            }
            // Stack placemarks 15% of the page height apart, wrapping back to the top so the
            // coordinate always stays within the page (0-100).
            BigDecimal yCoordinate = BigDecimal.valueOf(5.0 + 15.0 * (i % 6));

            annotations.add(annotationService.newAnnotationBuilder()
                    .withTypeName("note__sys")
                    .withValue("document_version_id__sys", documentVersionId)
                    .withValue("comment__sys", text)
                    .withValue("color__sys", "yellow_light__sys")
                    .withValue("state__sys", "open__sys")
                    .withPlacemark(annotationService.newPlacemarkBuilder()
                            .withTypeName("rectangle__sys")
                            .withValue("page_number__sys", BigDecimal.ONE)
                            .withValue("x_coordinate__sys", BigDecimal.valueOf(5.0))
                            .withValue("y_coordinate__sys", yCoordinate)
                            .withValue("height__sys", BigDecimal.valueOf(2.0))
                            .withValue("width__sys", BigDecimal.valueOf(2.0))
                            .withValue("style__sys", "rectangle_solid__sys")
                            .build())
                    .build());
        }

        String[] firstErrorMessage = {null};
        annotationService.batchSaveAnnotations(annotationService.newBatchSaveRequestBuilder()
                        .withAnnotations(annotations)
                        .build())
                .onErrors(errors -> {
                    for (AnnotationBatchSaveError error : errors) {
                        if (firstErrorMessage[0] == null) {
                            firstErrorMessage[0] = error.getError().getMessage();
                        }
                    }
                })
                .ignoreErrors()
                .execute();

        if (firstErrorMessage[0] != null) {
            return buildFailure(context, jsonService, firstErrorMessage[0]);
        }

        JsonObjectBuilder success = jsonService.newJsonObjectBuilder();
        success.setValue("responseStatus", "SUCCESS");
        success.setValue("responseMessage", count + (count == 1 ? " annotation" : " annotations") + " created on document version " + documentVersionId);
        return context.newSuccessBuilder(AiToolTypeExecutionResponse.JsonResultBuilder.class)
                .withJson(success.build())
                .build();
    }

    private AiToolTypeExecutionResponse buildFailure(AiToolTypeExecuteContext context, JsonService jsonService,
            String failureReason) {
        JsonObjectBuilder failure = jsonService.newJsonObjectBuilder();
        failure.setValue("responseStatus", "FAILURE");
        failure.setValue("responseMessage", "Cannot create annotation on the document");
        failure.setValue("failureReason", failureReason);
        return context.newSuccessBuilder(AiToolTypeExecutionResponse.JsonResultBuilder.class)
                .withJson(failure.build())
                .build();
    }
}
