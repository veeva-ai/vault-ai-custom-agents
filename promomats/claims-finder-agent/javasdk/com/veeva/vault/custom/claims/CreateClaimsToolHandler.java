package com.veeva.vault.custom.claims;

import com.veeva.vault.sdk.api.ai.AiToolTypeExecuteContext;
import com.veeva.vault.sdk.api.ai.AiToolTypeExecutionResponse;
import com.veeva.vault.sdk.api.ai.AiToolTypeGenerateInputContext;
import com.veeva.vault.sdk.api.ai.AiToolTypeGenerateInputResponse;
import com.veeva.vault.sdk.api.ai.AiToolTypeRuntimeHandler;
import com.veeva.vault.sdk.api.ai.AiToolTypeRuntimeHandlerInfo;
import com.veeva.vault.sdk.api.ai.LlmToolInputArray;
import com.veeva.vault.sdk.api.ai.LlmToolInputItem;
import com.veeva.vault.sdk.api.ai.LlmToolInputItemType;
import com.veeva.vault.sdk.api.ai.LlmToolInputObject;
import com.veeva.vault.sdk.api.core.LogService;
import com.veeva.vault.sdk.api.core.ServiceLocator;
import com.veeva.vault.sdk.api.core.ValueType;
import com.veeva.vault.sdk.api.core.VaultCollections;
import com.veeva.vault.sdk.api.executeas.ExecuteAs;
import com.veeva.vault.sdk.api.executeas.ExecuteAsUser;
import com.veeva.vault.sdk.api.http.HttpMethod;
import com.veeva.vault.sdk.api.http.HttpRequest;
import com.veeva.vault.sdk.api.http.HttpResponseBodyValueType;
import com.veeva.vault.sdk.api.http.HttpService;
import com.veeva.vault.sdk.api.json.JsonArrayBuilder;
import com.veeva.vault.sdk.api.json.JsonObjectBuilder;
import com.veeva.vault.sdk.api.json.JsonService;
import com.veeva.vault.sdk.api.json.JsonArray;
import com.veeva.vault.sdk.api.json.JsonObject;
import com.veeva.vault.sdk.api.json.JsonValueType;
import com.veeva.vault.sdk.api.picklist.PicklistValue;
import com.veeva.vault.sdk.api.query.Query;
import com.veeva.vault.sdk.api.token.TokenRequest;

import java.util.List;
import java.util.Map;

/**
 * AI tool: creates Claim records (annotation_keywords__sys, Claim object
 * type) for claims the LLM judged to be new. The LLM supplies only the
 * source document id and the claim texts; product and country come from
 * the document itself, so the tool never trusts model-copied ids.
 *
 * Input (generated dynamically in onGenerateInput):
 *   {"source_document_id": "123", "claims": [{"text": "...", "category": "Efficacy"}]}
 *
 * Three local REST calls, in order, all as the chatting user:
 *   1. POST /vobjects/annotation_keywords__sys (JSON batch) -- creates the
 *      claims in Draft. state__v cannot be set on create ("Cannot update
 *      field [state__v]", SDK and REST alike).
 *   2. POST .../actions/<claims_finder_ready_for_linking__c> per record --
 *      the direct state-change user action from
 *      components/Objectlifecycle/annotation_keywords_lifecycle__sys (the
 *      lifecycle-action package) moves each claim to
 *      PreLaunch (prelaunch_state__v), the state Vault
 *      reserves for claims that may be auto-linked but still await review.
 *      Link annotations only accept claims in that state or Available for
 *      Use (Draft is rejected).
 *   3. POST /services/ai/agent_actions/actions/execute -- starts Vault's own
 *      Claims Agent (promomats_claims_agent__v / identify_and_annotate_claims__v)
 *      on the document. It exact-matches the document text against the
 *      library (PreLaunch claims included) and places text-anchored
 *      auto_link__sys annotations to the new records -- proper highlighted
 *      links on the exact words, which this tool cannot compute itself: the
 *      viewer's per-page word indexes are not derivable from the document
 *      text endpoint (confirmed against a live vault). The run
 *      is asynchronous; the tool reports its execution id.
 *
 * REST rather than RecordService / ObjectLifecycleStateUserActionService on
 * purpose: SDK writes stay uncommitted until the tool returns, so a later
 * call could not see the new records ("does not resolve to an existing
 * record", confirmed live). Each local REST call commits on its own.
 *
 * Output: one line per claim, "CREATED <name> [claim:<id>] <record url> state
 * PreLaunch: <text>" (or "state Draft" when the promotion
 * failed) / "FAILED: <text> -- <reason>"; then, if any promotion failed, ONE
 * "STATE_CHANGE_FAILED (<n> of <m> created claims left in Draft ...): <distinct
 * reasons>" line -- the cause is per vault (missing user action, permission,
 * entry criterion), so it is reported once, not under every claim; then one
 * "CLAIMS_AGENT_STARTED execution <id>" / "CLAIMS_AGENT_FAILED: <reason>"
 * line. The action instructions turn these into the chat summary.
 *
 * Errors: Vault Java SDK code can only catch RollbackException, so nothing is caught
 * here. Service calls report failures through their onError handlers, and every
 * failure is returned as a "FAILED: ..." line instead of being thrown.
 *
 * @ExecuteAs(REQUEST_OWNER) so records are created as the chatting user.
 */
@ExecuteAs(ExecuteAsUser.REQUEST_OWNER)
@AiToolTypeRuntimeHandlerInfo
public class CreateClaimsToolHandler implements AiToolTypeRuntimeHandler {

    private static final int MIN_TEXT = 5;
    private static final int MAX_TEXT = 1500;
    /**
     * Resolved per vault from the claims_finder_api_version__c Vaulttoken
     * (components/Vaulttoken/claims_finder_api_version__c), deployed with the
     * config package. An admin can repoint this to a different REST API
     * version without a code redeploy. ${Vault.*} tokens resolve
     * automatically once setResolveTokens(true) is set on the request --
     * no TokenRequest needed, since this is not a Custom.* token.
     */
    private static final String API_VERSION = "${Vault.claims_finder_api_version__c}";
    private static final String CLAIMS_AGENT = "promomats_claims_agent__v";
    private static final String CLAIMS_AGENT_ACTION = "identify_and_annotate_claims__v";
    /**
     * Resolved per vault from the claims_finder_ready_for_linking_action__c
     * Vaulttoken (components/Vaulttoken/claims_finder_ready_for_linking_action__c).
     * A target vault with a customized Draft -> PreLaunch
     * action name can repoint this token instead of needing a code change.
     */
    private static final String READY_FOR_LINKING_ACTION =
        "Objectlifecyclestateuseraction." + ClaimsSchema.TEXT_ASSET_OBJECT
            + ".draft_state__sys.${Vault.claims_finder_ready_for_linking_action__c}";

    @Override
    public AiToolTypeGenerateInputResponse onGenerateInput(AiToolTypeGenerateInputContext context) {
        LlmToolInputItem documentId = context.newSimpleTypeLlmToolItemBuilder()
            .withType(LlmToolInputItemType.STRING)
            .withDescription("The source document id, copied from the [doc:<id>] marker in the Claims Library context.")
            .build();
        LlmToolInputItem text = context.newSimpleTypeLlmToolItemBuilder()
            .withType(LlmToolInputItemType.STRING)
            .withDescription("The claim wording copied character-for-character from the document text, including symbols such as ® and *, so Vault can find it on the page (5 to 1500 characters).")
            .build();
        LlmToolInputItem category = context.newSimpleTypeLlmToolItemBuilder()
            .withType(LlmToolInputItemType.STRING)
            .withDescription("Optional. One of the Claim categories listed in the Claims Library context, by label.")
            .build();
        LlmToolInputObject claim = (LlmToolInputObject) context.newObjectLlmToolItemBuilder()
            .addProperty("text", text)
            .addProperty("category", category)
            .addRequired("text")
            .withDescription("One new claim to create.")
            .build();
        LlmToolInputArray claims = context.newArrayLlmToolItemBuilder()
            .withItemsType(claim)
            .withDescription("The claims that do not already exist in the library for this document's product and country.")
            .build();
        LlmToolInputObject input = (LlmToolInputObject) context.newObjectLlmToolItemBuilder()
            .addProperty("source_document_id", documentId)
            .addProperty("claims", claims)
            .addRequired("source_document_id")
            .addRequired("claims")
            .withDescription("Creates Claim records in the Text Asset library for the source document's product and country.")
            .build();
        return context.newSuccessBuilder()
            .withInput(input)
            .build();
    }

    @Override
    public AiToolTypeExecutionResponse onExecute(AiToolTypeExecuteContext context) {
        LogService log = ServiceLocator.locate(LogService.class);
        JsonObject input = context.getInput();
        String documentId = readJsonString(input, "source_document_id");
        JsonArray claims = input != null && input.contains("claims")
            ? input.getValue("claims", JsonValueType.ARRAY) : null;
        log.info("CreateClaimsTool invoked for document {} with {} claim(s)",
            documentId, claims == null ? 0 : claims.getSize());

        if (documentId.length() == 0) {
            return text(context, "FAILED: source_document_id is required.");
        }
        if (claims == null || claims.getSize() == 0) {
            return text(context, "NO_NEW_CLAIMS: nothing to create; every claim in the document already exists in the library.");
        }

        // Product and country come from the document, never from the model.
        boolean[] documentFound = new boolean[] { false };
        String[] productId = new String[] { "" };
        String[] countryId = new String[] { "" };
        Query documentQuery = ClaimsSchema.newQueryBuilder()
            .withSelect(VaultCollections.asList("id", ClaimsSchema.DOC_PRODUCT_FIELD, ClaimsSchema.DOC_COUNTRY_FIELD))
            .withFrom("documents")
            .withWhere("id = ${Custom.document_id}")
            .build();
        TokenRequest documentTokens = ClaimsSchema.newTokenRequestBuilder()
            .withValue("Custom.document_id", digitsOnly(documentId))
            .build();
        String documentError = ClaimsSchema.runQuery(documentQuery, documentTokens,
            row -> {
                documentFound[0] = true;
                productId[0] = firstReference(row, ClaimsSchema.DOC_PRODUCT_FIELD);
                countryId[0] = firstReference(row, ClaimsSchema.DOC_COUNTRY_FIELD);
            }
        );
        if (documentError.length() > 0) {
            return text(context, "FAILED: could not read document " + documentId + ": " + documentError);
        }
        if (!documentFound[0]) {
            return text(context, "FAILED: document " + documentId + " was not found. "
                + "Copy source_document_id from the [doc:<id>] marker in the Claims Library context.");
        }
        if (productId[0].length() == 0 || countryId[0].length() == 0) {
            return text(context, "FAILED: document " + documentId
                + " has no Product or no Country set, so no claims were created.");
        }
        List<String> schemaErrors = VaultCollections.newList();
        String objectTypeId = ClaimsSchema.claimObjectTypeId(schemaErrors);
        if (!schemaErrors.isEmpty()) {
            return text(context, "FAILED: " + ClaimsSchema.joinErrors(schemaErrors));
        }
        if (objectTypeId.length() == 0) {
            return text(context, "FAILED: the Claim object type was not found on " + ClaimsSchema.TEXT_ASSET_OBJECT + ".");
        }

        JsonService json = ServiceLocator.locate(JsonService.class);
        JsonArrayBuilder batch = json.newJsonArrayBuilder();
        int batchSize = 0;
        List<String> texts = VaultCollections.newList();
        List<PicklistValue> categories = ClaimsSchema.categoryValues();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < claims.getSize(); i++) {
            JsonObject claim = claims.getValue(i, JsonValueType.OBJECT);
            // Trailing reference markers ("...chemotherapy1,2", "...AEs*") are
            // footnote pointers on the page, not claim text -- see ClaimText.
            String claimText = ClaimText.stripTrailingReferenceMarkers(readJsonString(claim, "text"));
            String categoryLabel = readJsonString(claim, "category");
            if (claimText.length() < MIN_TEXT || claimText.length() > MAX_TEXT) {
                out.append("FAILED: ").append(claimText).append(" -- claim text must be ")
                    .append(MIN_TEXT).append(" to ").append(MAX_TEXT).append(" characters.\n");
                continue;
            }
            JsonObjectBuilder record = json.newJsonObjectBuilder()
                .setValue("object_type__v", objectTypeId)
                .setValue(ClaimsSchema.MATCH_TEXT_FIELD, claimText)
                .setValue(ClaimsSchema.PRODUCT_FIELD, productId[0])
                .setValue(ClaimsSchema.COUNTRY_FIELD, countryId[0]);
            // No source-document link: harvested_source_document__v is a system
            // field ("Cannot update system field") and
            // its _unbound companion demands a specific version -- only Vault's own
            // Harvest Claims action can populate it.
            String categoryName = ClaimsSchema.categoryName(categories, categoryLabel);
            if (categoryName.length() > 0) {
                record.setValue(ClaimsSchema.CATEGORY_FIELD, categoryName);
            }
            batch.add(record.build());
            batchSize++;
            texts.add(claimText);
        }

        if (batchSize == 0) {
            return text(context, out.toString());
        }

        // Positional bookkeeping: the batch response reports by position.
        String[] createdIds = new String[batchSize];
        String[] errors = new String[batchSize];
        createRecords(batch.build(), createdIds, errors);

        String[] stateErrors = promoteToReadyForLinking(createdIds);

        List<String> createdIdList = VaultCollections.newList();
        for (int i = 0; i < batchSize; i++) {
            if (createdIds[i] != null) {
                createdIdList.add(createdIds[i]);
            }
        }
        Map<String, String> claimNames = ClaimsSchema.lookupNames(ClaimsSchema.TEXT_ASSET_OBJECT, createdIdList, schemaErrors);
        if (!schemaErrors.isEmpty()) {
            // The claims exist already; only their display names are missing, so fall back to ids.
            log.warn("Claim name lookup failed, using record ids: {}", ClaimsSchema.joinErrors(schemaErrors));
        }

        int created = 0;
        int leftInDraft = 0;
        List<String> distinctStateErrors = VaultCollections.newList();
        for (int i = 0; i < batchSize; i++) {
            if (createdIds[i] != null) {
                created++;
                String name = claimNames.containsKey(createdIds[i]) ? claimNames.get(createdIds[i]) : createdIds[i];
                out.append("CREATED ").append(name).append(" [claim:").append(createdIds[i]).append("] ")
                    .append(ClaimsSchema.recordUrl(createdIds[i])).append(" ")
                    .append(stateErrors[i] == null ? "state PreLaunch" : "state Draft")
                    .append(": ").append(texts.get(i)).append("\n");
                if (stateErrors[i] != null) {
                    leftInDraft++;
                    if (!distinctStateErrors.contains(stateErrors[i])) {
                        distinctStateErrors.add(stateErrors[i]);
                    }
                }
            } else {
                out.append("FAILED: ").append(texts.get(i)).append(" -- ")
                    .append(errors[i] == null ? "not saved (batch rolled back)" : errors[i]).append("\n");
            }
        }
        if (leftInDraft > 0) {
            // One line for the whole run: the cause is per vault (missing user
            // action, permission, entry criterion), never per claim.
            out.append("STATE_CHANGE_FAILED (").append(leftInDraft).append(" of ").append(created)
                .append(" created claims left in Draft, not promoted to PreLaunch): ");
            for (int i = 0; i < distinctStateErrors.size(); i++) {
                if (i > 0) {
                    out.append(" | ");
                }
                out.append(distinctStateErrors.get(i));
            }
            out.append("\n");
        }
        if (created > leftInDraft) {
            out.append(startClaimsAgent(documentId)).append("\n");
        }
        log.info("CreateClaimsTool result: {}", out.toString());
        return text(context, out.toString());
    }

    /**
     * Step 3: start Vault's Claims Agent on the document so it places
     * text-anchored link annotations to the (now PreLaunch) claims.
     * Asynchronous; returns a CLAIMS_AGENT_STARTED / CLAIMS_AGENT_FAILED line.
     */
    private static String startClaimsAgent(String documentId) {
        JsonService json = ServiceLocator.locate(JsonService.class);
        JsonObject body = json.newJsonObjectBuilder()
            .setValue("agent_name", CLAIMS_AGENT)
            .setValue("action_name", CLAIMS_AGENT_ACTION)
            .setValue("document_id", digitsOnly(documentId))
            .build();
        HttpService http = ServiceLocator.locate(HttpService.class);
        HttpRequest request = http.newLocalHttpRequest()
            .setMethod(HttpMethod.POST)
            .appendPath("/api/" + API_VERSION + "/services/ai/agent_actions/actions/execute")
            .setResolveTokens(true)
            .setBody(body);
        StringBuilder result = new StringBuilder();
        http.send(request, HttpResponseBodyValueType.JSONDATA)
            .onSuccess(response -> {
                JsonObject responseBody = response.getResponseBody().getJsonObject();
                if ("SUCCESS".equals(readJsonString(responseBody, "responseStatus")) && responseBody.contains("actionResponse")) {
                    JsonObject action = responseBody.getValue("actionResponse", JsonValueType.OBJECT);
                    result.append("CLAIMS_AGENT_STARTED execution ").append(readJsonString(action, "executionId"))
                        .append(": Vault's Claims Agent is now placing text-anchored link annotations on the document for the new claims (usually 1-2 minutes).");
                } else {
                    result.append("CLAIMS_AGENT_FAILED: ").append(firstErrorMessage(responseBody));
                }
            })
            .onError(error -> result.append("CLAIMS_AGENT_FAILED: ").append(error.getMessage()))
            .execute();
        return result.toString();
    }

    /** Step 1: JSON batch create; fills createdIds / errors by position. */
    private static void createRecords(JsonArray batch, String[] createdIds, String[] errors) {
        HttpService http = ServiceLocator.locate(HttpService.class);
        HttpRequest request = http.newLocalHttpRequest()
            .setMethod(HttpMethod.POST)
            .appendPath("/api/" + API_VERSION + "/vobjects/" + ClaimsSchema.TEXT_ASSET_OBJECT)
            .setResolveTokens(true)
            .setBody(batch);
        StringBuilder callError = new StringBuilder();
        http.send(request, HttpResponseBodyValueType.JSONDATA)
            .onSuccess(response -> {
                JsonObject body = response.getResponseBody().getJsonObject();
                if (!body.contains("data")) {
                    callError.append(body.asString());
                    return;
                }
                JsonArray data = body.getValue("data", JsonValueType.ARRAY);
                for (int i = 0; i < data.getSize() && i < createdIds.length; i++) {
                    JsonObject item = data.getValue(i, JsonValueType.OBJECT);
                    if ("SUCCESS".equals(readJsonString(item, "responseStatus")) && item.contains("data")) {
                        createdIds[i] = readJsonString(item.getValue("data", JsonValueType.OBJECT), "id");
                    } else {
                        errors[i] = firstErrorMessage(item);
                    }
                }
            })
            .onError(error -> callError.append(error.getMessage()))
            .execute();
        if (callError.length() > 0) {
            for (int i = 0; i < errors.length; i++) {
                if (createdIds[i] == null && errors[i] == null) {
                    errors[i] = callError.toString();
                }
            }
        }
    }

    /**
     * Step 2: Draft -> PreLaunch, one user-action call per
     * created record. Returns one entry per position: null on success, the
     * error message otherwise (null also for positions that were not created).
     */
    private static String[] promoteToReadyForLinking(String[] createdIds) {
        String[] errors = new String[createdIds.length];
        HttpService http = ServiceLocator.locate(HttpService.class);
        for (int i = 0; i < createdIds.length; i++) {
            if (createdIds[i] == null) {
                continue;
            }
            StringBuilder error = new StringBuilder();
            HttpRequest request = http.newLocalHttpRequest()
                .setMethod(HttpMethod.POST)
                .appendPath("/api/" + API_VERSION + "/vobjects/" + ClaimsSchema.TEXT_ASSET_OBJECT
                    + "/" + createdIds[i] + "/actions/" + READY_FOR_LINKING_ACTION)
                .setResolveTokens(true)
                .setBodyParam("claims_finder", "1");
            http.send(request, HttpResponseBodyValueType.JSONDATA)
                .onSuccess(response -> {
                    JsonObject body = response.getResponseBody().getJsonObject();
                    if (!"SUCCESS".equals(readJsonString(body, "responseStatus"))) {
                        error.append("Move to PreLaunch action failed -- if this vault's ")
                            .append(ClaimsSchema.TEXT_ASSET_OBJECT)
                            .append(" lifecycle uses a different action name, update the ")
                            .append("claims_finder_ready_for_linking_action__c Vaulttoken to match ")
                            .append("(Admin > Configuration > Vault Tokens). Vault error: ")
                            .append(firstErrorMessage(body));
                    }
                })
                .onError(httpError -> error.append(httpError.getMessage()))
                .execute();
            if (error.length() > 0) {
                errors[i] = error.toString();
            }
        }
        return errors;
    }

    private static String firstErrorMessage(JsonObject item) {
        if (item != null && item.contains("errors")) {
            JsonArray errs = item.getValue("errors", JsonValueType.ARRAY);
            if (errs.getSize() > 0) {
                JsonObject firstError = errs.getValue(0, JsonValueType.OBJECT);
                String type = readJsonString(firstError, "type");
                String message = readJsonString(firstError, "message");
                return type.length() > 0 ? type + ": " + message : message;
            }
        }
        return item == null ? "unknown error" : item.asString();
    }

    private static AiToolTypeExecutionResponse text(AiToolTypeExecuteContext context, String value) {
        return context.newSuccessBuilder(AiToolTypeExecutionResponse.TextResultBuilder.class)
            .withText(value)
            .build();
    }

    private static String readJsonString(JsonObject object, String key) {
        if (object == null || !object.contains(key)) {
            return "";
        }
        String value = object.getValue(key, JsonValueType.STRING);
        return value == null ? "" : value;
    }

    private static String firstReference(com.veeva.vault.sdk.api.query.QueryExecutionResult row, String field) {
        List<String> values = row.getValue(field, ValueType.REFERENCES);
        if (values == null || values.isEmpty() || values.get(0) == null) {
            return "";
        }
        return values.get(0);
    }

    private static String digitsOnly(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append(c);
            }
        }
        return out.length() == 0 ? "0" : out.toString();
    }
}
