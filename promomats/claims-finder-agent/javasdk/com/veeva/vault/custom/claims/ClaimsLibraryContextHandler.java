package com.veeva.vault.custom.claims;

import com.veeva.vault.sdk.api.ai.AiContextTypeOutputItem;
import com.veeva.vault.sdk.api.ai.AiContextTypeResolveContext;
import com.veeva.vault.sdk.api.ai.AiContextTypeResolveResponse;
import com.veeva.vault.sdk.api.ai.AiContextTypeRuntimeHandler;
import com.veeva.vault.sdk.api.ai.AiContextTypeRuntimeHandlerInfo;
import com.veeva.vault.sdk.api.ai.AiDocumentScopeSource;
import com.veeva.vault.sdk.api.ai.AiScope;
import com.veeva.vault.sdk.api.ai.AiScopeType;
import com.veeva.vault.sdk.api.core.ValueType;
import com.veeva.vault.sdk.api.core.VaultCollections;
import com.veeva.vault.sdk.api.document.DocumentVersion;
import com.veeva.vault.sdk.api.executeas.ExecuteAs;
import com.veeva.vault.sdk.api.executeas.ExecuteAsUser;
import com.veeva.vault.sdk.api.query.Query;
import com.veeva.vault.sdk.api.query.QueryExecutionResult;
import com.veeva.vault.sdk.api.token.TokenRequest;

import java.util.List;

/**
 * Claims Finder agent context: for the document the chat is scoped to,
 * resolves its primary product and country and lists every Claim
 * (annotation_keywords__sys, Claim object type) already in the Text Asset
 * library for that product + country pair, so the LLM can judge which
 * claims in the document text are new.
 *
 * The output shape is fixed: the action instructions and CreateClaimsToolHandler
 * depend on it:
 *   Source document: <name> [doc:<id>]
 *   Primary product: <name> [product:<id>]
 *   Primary country: <name> [country:<id>]
 *   Claim categories: <label> | <label> ...
 *   Existing claims for this product and country (N):
 *   [claim:<id>] <name> (<state>) <record url>: <match text>
 *
 * The record url is the Vault UI deep link the chat summary turns into a
 * clickable record name.
 *
 * If the document has no product or no country the block starts with
 * MISSING_METADATA so the instructions can stop the run instead of
 * creating claims that can never be filtered correctly.
 *
 * Errors: Vault Java SDK code can only catch RollbackException, so nothing is caught
 * here. A failed lookup is reported through the query's onError handler and the whole
 * output becomes "CONTEXT_ERROR: <message>", so the failure is visible in the agent
 * response instead of ending the chat turn with a generic error.
 *
 * Runs as the request owner: @ExecuteAs(REQUEST_OWNER).
 */
@ExecuteAs(ExecuteAsUser.REQUEST_OWNER)
@AiContextTypeRuntimeHandlerInfo(scope = AiScope.DOCUMENT)
public class ClaimsLibraryContextHandler implements AiContextTypeRuntimeHandler {

    @Override
    public AiContextTypeResolveResponse onResolve(AiContextTypeResolveContext context) {
        AiContextTypeOutputItem item = context.newOutputItemBuilder()
            .withText(buildContext(context))
            .build();
        return context.newSuccessResponseBuilder()
            .appendOutputItem(item)
            .build();
    }

    private static String buildContext(AiContextTypeResolveContext context) {
        AiDocumentScopeSource scope = context.getScopeSource(AiScopeType.DOCUMENT);
        DocumentVersion doc = scope.getDocumentVersion();

        String docName = ClaimsSchema.orEmpty(doc.getValue("name__v", ValueType.STRING));
        // "id" as NUMBER throws inside a context handler (kills the chat turn);
        // version_id "<id>_<major>_<minor>" as STRING works, so use that.
        String docId = documentIdOf(ClaimsSchema.orEmpty(doc.getValue("version_id", ValueType.STRING)));
        String productId = firstReference(doc, ClaimsSchema.DOC_PRODUCT_FIELD);
        String countryId = firstReference(doc, ClaimsSchema.DOC_COUNTRY_FIELD);

        List<String> errors = VaultCollections.newList();
        StringBuilder text = new StringBuilder();
        if (productId.length() == 0 || countryId.length() == 0) {
            text.append("MISSING_METADATA: the document has no ");
            if (productId.length() == 0) {
                text.append("Product");
            }
            if (productId.length() == 0 && countryId.length() == 0) {
                text.append(" and no ");
            }
            if (countryId.length() == 0) {
                text.append("Country");
            }
            text.append(". Claims cannot be matched or created until both are set on the document.\n");
        }

        text.append("Source document: ").append(docName).append(" [doc:").append(docId).append("]\n");
        text.append("Primary product: ").append(ClaimsSchema.lookupName("product__v", productId, errors))
            .append(" [product:").append(productId).append("]\n");
        text.append("Primary country: ").append(ClaimsSchema.lookupName("country__v", countryId, errors))
            .append(" [country:").append(countryId).append("]\n");
        text.append("Claim categories: ").append(ClaimsSchema.categoryLabels()).append("\n");

        String objectTypeId = "";
        if (productId.length() > 0 && countryId.length() > 0) {
            objectTypeId = ClaimsSchema.claimObjectTypeId(errors);
            if (errors.isEmpty() && objectTypeId.length() == 0) {
                errors.add("the Claim object type was not found on " + ClaimsSchema.TEXT_ASSET_OBJECT);
            }
        }

        if (productId.length() > 0 && countryId.length() > 0 && errors.isEmpty()) {
            List<String> lines = VaultCollections.newList();
            Query query = ClaimsSchema.newQueryBuilder()
                .withSelect(VaultCollections.asList("id", "name__v", "state__v", ClaimsSchema.MATCH_TEXT_FIELD))
                .withFrom(ClaimsSchema.TEXT_ASSET_OBJECT)
                .withWhere("object_type__v = ${Custom.object_type_id}"
                    + " AND " + ClaimsSchema.PRODUCT_FIELD + " = ${Custom.product_id}"
                    + " AND " + ClaimsSchema.COUNTRY_FIELD + " = ${Custom.country_id}")
                .withOrderBy(VaultCollections.asList("id"))
                .build();
            TokenRequest tokens = ClaimsSchema.newTokenRequestBuilder()
                .withValue("Custom.object_type_id", objectTypeId)
                .withValue("Custom.product_id", productId)
                .withValue("Custom.country_id", countryId)
                .build();
            String queryError = ClaimsSchema.runQuery(query, tokens,
                row -> lines.add("[claim:" + readString(row, "id") + "] " + readString(row, "name__v")
                    + " (" + readString(row, "state__v") + ") " + ClaimsSchema.recordUrl(readString(row, "id"))
                    + ": " + readString(row, ClaimsSchema.MATCH_TEXT_FIELD))
            );
            ClaimsSchema.addError(errors, "Claims Library query failed", queryError);
            text.append("Existing claims for this product and country (").append(lines.size()).append("):\n");
            if (lines.isEmpty()) {
                text.append("(none)\n");
            }
            for (int i = 0; i < lines.size(); i++) {
                text.append(lines.get(i)).append("\n");
            }
        }

        if (!errors.isEmpty()) {
            return "CONTEXT_ERROR: " + ClaimsSchema.joinErrors(errors);
        }
        return text.toString();
    }

    // documents' product__v / country__v are multi-value references: read as
    // ValueType.REFERENCES (List<String>), first value is the primary one.
    private static String firstReference(DocumentVersion doc, String field) {
        List<String> values = doc.getValue(field, ValueType.REFERENCES);
        if (values == null || values.isEmpty() || values.get(0) == null) {
            return "";
        }
        return values.get(0);
    }

    private static String documentIdOf(String versionId) {
        int underscore = versionId.indexOf("_");
        return underscore < 0 ? versionId : versionId.substring(0, underscore);
    }

    private static String readString(QueryExecutionResult row, String field) {
        String value = row.getValue(field, ValueType.STRING);
        return value == null ? "" : value;
    }
}
