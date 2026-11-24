package com.veeva.vault.custom.claims;

import com.veeva.vault.sdk.api.core.ServiceLocator;
import com.veeva.vault.sdk.api.core.UserDefinedClassInfo;
import com.veeva.vault.sdk.api.core.ValueType;
import com.veeva.vault.sdk.api.core.VaultCollections;
import com.veeva.vault.sdk.api.core.VaultInformationService;
import com.veeva.vault.sdk.api.picklist.PicklistService;
import com.veeva.vault.sdk.api.picklist.PicklistValue;
import com.veeva.vault.sdk.api.picklist.PicklistValueMetadataCollectionRequest;
import com.veeva.vault.sdk.api.picklist.PicklistValueMetadataCollectionResponse;
import com.veeva.vault.sdk.api.query.Query;
import com.veeva.vault.sdk.api.query.QueryExecutionRequest;
import com.veeva.vault.sdk.api.query.QueryExecutionResult;
import com.veeva.vault.sdk.api.query.QueryService;
import com.veeva.vault.sdk.api.token.TokenRequest;
import com.veeva.vault.sdk.api.token.TokenService;

import java.util.List;
import java.util.Map;

/**
 * The Text Asset / Claim schema both SDK classes depend on, plus the small
 * query helpers they share. Field names were confirmed against a live vault;
 * change them here, nowhere else.
 */
@UserDefinedClassInfo
public class ClaimsSchema {

    /** Text Asset object; Claims are one object type on it. */
    public static final String TEXT_ASSET_OBJECT = "annotation_keywords__sys";
    /** api_name__v of the Claim object type on the Text Asset object. */
    public static final String CLAIM_OBJECT_TYPE_API_NAME = "claims__v";
    /** Match Text -- the claim wording itself (5-1500 chars, enforced by Vault). */
    public static final String MATCH_TEXT_FIELD = "match_text__sys";
    /** Primary Product / Primary Country on the Text Asset. */
    public static final String PRODUCT_FIELD = "product__v";
    public static final String COUNTRY_FIELD = "country__v";
    /** Category picklist field on the Text Asset, and the picklist behind it. */
    public static final String CATEGORY_FIELD = "annotation_keyword_category__v";
    public static final String CATEGORY_PICKLIST = "annotation_keyword_category__v";
    /** Product / Country on the source document (multi-value references). */
    public static final String DOC_PRODUCT_FIELD = "product__v";
    public static final String DOC_COUNTRY_FIELD = "country__v";

    public interface RowHandler {
        void handle(QueryExecutionResult result);
    }

    private ClaimsSchema() {
    }

    /**
     * UI deep link to a Text Asset record: https://<dns>/ui/#object/annotation_keywords__sys/<id>.
     * Emitted next to every claim id so the chat can render record names as links;
     * the model copies these URLs verbatim rather than composing them.
     */
    public static String recordUrl(String id) {
        VaultInformationService vaultInfo = ServiceLocator.locate(VaultInformationService.class);
        String dns = vaultInfo.getLocalVaultInformation().getDns();
        return "https://" + dns + "/ui/#object/" + TEXT_ASSET_OBJECT + "/" + id;
    }

    /**
     * Record id of the Claim object type, resolved live (ids differ per vault).
     * A failed query is added to {@code errors} and "" is returned.
     */
    public static String claimObjectTypeId(List<String> errors) {
        String[] id = new String[] { "" };
        Query query = newQueryBuilder()
            .withSelect(VaultCollections.asList("id"))
            .withFrom("object_type__v")
            .withWhere("object_name__v = ${Custom.object_name} AND api_name__v = ${Custom.api_name}")
            .build();
        TokenRequest tokens = newTokenRequestBuilder()
            .withValue("Custom.object_name", TEXT_ASSET_OBJECT)
            .withValue("Custom.api_name", CLAIM_OBJECT_TYPE_API_NAME)
            .build();
        addError(errors, "Claim object type lookup failed",
            runQuery(query, tokens, row -> id[0] = readString(row, "id")));
        return id[0];
    }

    /** Active Category values as "label (name)" joined by " | ", or "(none)". */
    public static String categoryLabels() {
        List<PicklistValue> values = categoryValues();
        if (values == null || values.isEmpty()) {
            return "(none)";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(" | ");
            }
            out.append(values.get(i).getLabel()).append(" (").append(values.get(i).getName()).append(")");
        }
        return out.toString();
    }

    /** Active Category picklist values; fetch once per call and pass to {@link #categoryName}. */
    public static List<PicklistValue> categoryValues() {
        PicklistService picklists = ServiceLocator.locate(PicklistService.class);
        PicklistValueMetadataCollectionRequest request = picklists.newPicklistValueMetadataCollectionRequestBuilder()
            .withPicklistName(CATEGORY_PICKLIST)
            .build();
        PicklistValueMetadataCollectionResponse response = picklists.getPicklistValueMetadataCollection(request);
        return response == null ? null : response.getPicklistValues();
    }

    /** Resolves a picklist value label or name to its name; "" if not a valid value. */
    public static String categoryName(List<PicklistValue> values, String labelOrName) {
        if (labelOrName == null || labelOrName.length() == 0 || values == null) {
            return "";
        }
        for (int i = 0; i < values.size(); i++) {
            PicklistValue value = values.get(i);
            if (labelOrName.equalsIgnoreCase(value.getName()) || labelOrName.equalsIgnoreCase(value.getLabel())) {
                return value.getName();
            }
        }
        return "";
    }

    /**
     * name__v of each record by id, in one query; ids that are not found are absent from the map.
     * A failed query is added to {@code errors} and the map is left empty.
     */
    public static Map<String, String> lookupNames(String object, List<String> ids, List<String> errors) {
        Map<String, String> names = VaultCollections.newMap();
        if (ids.isEmpty()) {
            return names;
        }
        Query query = newQueryBuilder()
            .withSelect(VaultCollections.asList("id", "name__v"))
            .withFrom(object)
            .withWhere("id CONTAINS (${Custom.ids})")
            .build();
        TokenRequest tokens = newTokenRequestBuilder()
            .withValue("Custom.ids", ids)
            .build();
        addError(errors, "Name lookup on " + object + " failed",
            runQuery(query, tokens, row -> names.put(readString(row, "id"), readString(row, "name__v"))));
        return names;
    }

    /**
     * name__v of a record by id; falls back to the id so a line never blanks.
     * A failed query is added to {@code errors}.
     */
    public static String lookupName(String object, String id, List<String> errors) {
        if (id == null || id.length() == 0) {
            return "(not set)";
        }
        String[] name = new String[] { id };
        Query query = newQueryBuilder()
            .withSelect(VaultCollections.asList("name__v"))
            .withFrom(object)
            .withWhere("id = ${Custom.record_id}")
            .build();
        TokenRequest tokens = newTokenRequestBuilder()
            .withValue("Custom.record_id", id)
            .build();
        addError(errors, "Name lookup on " + object + " failed",
            runQuery(query, tokens, row -> name[0] = readString(row, "name__v")));
        return name[0];
    }

    /** Query builder for the shared QueryService. */
    public static Query.Builder newQueryBuilder() {
        return ServiceLocator.locate(QueryService.class).newQueryBuilder();
    }

    /**
     * Token request builder. User-controlled values go in tokens (${Custom.name} in the
     * WHERE clause), never into the query text; Vault escapes String tokens itself.
     */
    public static TokenRequest.Builder newTokenRequestBuilder() {
        return ServiceLocator.locate(TokenService.class).newTokenRequestBuilder();
    }

    /**
     * Runs the query and passes each row to the handler. Vault Java SDK code can only catch
     * RollbackException, so a failed query is reported through onError instead: this returns
     * the error message, or "" when the query succeeded.
     */
    public static String runQuery(Query query, TokenRequest tokens, RowHandler handler) {
        StringBuilder error = new StringBuilder();
        QueryService queryService = ServiceLocator.locate(QueryService.class);
        QueryExecutionRequest.Builder builder = queryService.newQueryExecutionRequestBuilder()
                .withQuery(query);
        if (tokens != null) {
            builder.withTokenRequest(tokens);
        }
        queryService.query(builder.build())
                .onSuccess(response -> response.streamResults().forEach(row -> handler.handle(row)))
                .onError(queryError -> error.append(queryError.getMessage()))
                .execute();
        return error.toString();
    }

    /** Adds "what: message" to errors when message is not empty. */
    public static void addError(List<String> errors, String what, String message) {
        if (message != null && message.length() > 0) {
            errors.add(what + ": " + message);
        }
    }

    /** Joins error messages with "; ". */
    public static String joinErrors(List<String> errors) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) {
                out.append("; ");
            }
            out.append(errors.get(i));
        }
        return out.toString();
    }

    public static String readString(QueryExecutionResult row, String field) {
        String value = row.getValue(field, ValueType.STRING);
        return value == null ? "" : value;
    }

    public static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
