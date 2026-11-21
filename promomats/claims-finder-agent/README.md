# Claims Finder Agent

A sample Vault AI custom agent for PromoMats, built on the Vault Java SDK and the Vault AI Platform. Given a promotional document, the agent identifies claims that are not yet in the Vault's Claims Library, creates them as records, and triggers Vault's native Claims Agent to anchor link annotations on the document.

## Components

| Component | Java handler | Description |
|---|---|---|
| `claims_library_context__c` | `ClaimsLibraryContextHandler` | **AI Context Type** that gives the agent the document's product and country, the valid claim categories and the existing claims |
| `create_claims_tool__c` | `CreateClaimsToolHandler` | **AI Tool Type** that creates Claim records, moves them through the lifecycle and starts Vault's Claims Agent |
| `claims_finder__c` | none | Document agent with one action that combines the document text, the custom context and the custom tool |
| `claims_finder_api_version__c`, `claims_finder_ready_for_linking_action__c` | none | Admin-editable configuration in `Vaulttoken` records |

## Handlers

For what a context type and a tool type are, see [Custom handlers](../../README.md#custom-handlers). This sample needs Java for two things:

| Handler | Type | Purpose |
|---|---|---|
| `ClaimsLibraryContextHandler` | AI Context Type | Gives the agent the document's product and country, the valid claim categories and the claims already in the library for that pair, so it can tell which claims are new. Reports `MISSING_METADATA` when the document has no product or country, so the agent stops instead of creating claims that can't be filtered later, and `CONTEXT_ERROR` when one of its lookups fails. |
| `CreateClaimsToolHandler` | AI Tool Type | Creates the Claim records, moves each from Draft to PreLaunch and starts Vault's native Claims Agent. Reads product and country from the document, never from values the LLM copied, and strips citation markers from the claim text. |

## Architecture

- The user opens Vault AI Chat on a document and runs **Find New Claims** (`find_new_claims__c`).
- The agent reads the document text and the Claims Library context (`ClaimsLibraryContextHandler`): the document's product and country, and the claims already in the library.
- The LLM extracts the claims from the document and compares them with the existing ones.
- It calls `create_claims__c` once, with the source document id and the new claims.
- `CreateClaimsToolHandler` creates the claims in Draft, moves each to PreLaunch, and starts Vault's native Claims Agent (`promomats_claims_agent__v`), which adds the link annotations asynchronously.
- The tool returns a `CREATED` / `FAILED` summary, and the agent replies with links to the new records.

`CreateClaimsToolHandler` makes its three calls as direct REST requests rather than through `RecordService`, because SDK writes are not visible to later calls in the same tool execution. Each REST call commits on its own, so every step can see the results of the one before it.

## Repository layout

```
├── javasdk/com/veeva/vault/custom/claims/           Vault Java SDK classes
├── components/<Type>/<name>/<name>.mdl              MDL source in Vault CLI workspace format, one folder per component
│                                                    (large XML/JSON attributes sit beside it as sidecar files)
├── .vdxignore                                       Vault CLI ignore rules
├── deploy-sdk/
│   ├── claims-finder-agent-1-code.vpk               Pre-built package 1/3: the Java SDK code
│   ├── claims-finder-agent-2-config.vpk             Pre-built package 2/3: context type, tool type, tool, agent, Vault Tokens
│   ├── claims-finder-agent-3-lifecycle-action.vpk   Pre-built package 3/3: the lifecycle user action (one-time setup)
│   └── claims-finder-agent-test-data.vpk            Optional sample data: countries, products and claims
├── test-data/                                       Sample CSVs and a PDF (the source of the sample data package)
└── pom.xml
```

## Data model

| Component | Notes |
|---|---|
| Text Asset object `annotation_keywords__sys`, object type `claims__v` | Holds the Claims Library. |
| `annotation_keywords_lifecycle__sys` | The sample adds a Draft to PreLaunch user action. Its API name, `claims_finder_ready_for_linking__c`, keeps its original wording so Vaults that already deployed the sample keep working. |

## Prerequisites

- A PromoMats Vault with Vault AI enabled (LLM connection configured) and the standard Claims Agent (`promomats_claims_agent__v`) available.
- Text Asset object type `claims__v` with the fields `product__v`, `country__v`, `match_text__sys` and `annotation_keyword_category__v`.
- JDK 17 ([Amazon Corretto 17](https://docs.aws.amazon.com/corretto/latest/corretto-17-ug/downloads-list.html) recommended) and Maven, for local builds.
- Configuration packages enabled in your Vault (**Admin > Deployment**, look for **Inbound** / **Outbound Packages**).

## Building

Set `vault.sdk.version` in [`pom.xml`](pom.xml) to match your Vault release (find it in **Admin > Settings > General Settings**; the sample is set for 26R2), then:

```
mvn clean package
```

The handlers call live Vault services (`QueryService`, `HttpService`, `PicklistService`), so verify changes by deploying to a sandbox Vault and running the agent.

## Deploying

Deploy the three packages **in order**:

1. **`deploy-sdk/claims-finder-agent-1-code.vpk`**: the Java SDK code. Its `Aicontexttyperuntimecode` and `Aitooltyperuntimecode` components register automatically.
2. **`deploy-sdk/claims-finder-agent-2-config.vpk`**: the context type, tool type, tool, agent and the two `Vaulttoken` records. These reference the code from package 1. Package 2 is safe to deploy again at any time.
3. **`deploy-sdk/claims-finder-agent-3-lifecycle-action.vpk`**: the Draft → PreLaunch user action on the Text Asset lifecycle. This is a one-time setup step. The agent needs the action to move new claims out of Draft.

> **Skip package 3 if the user action already exists.** Adding it a second time fails with `GEN-FADDS-II-1008 ... already exists`. To check, run `RETRIEVE Objectlifecycle annotation_keywords_lifecycle__sys` and look for `claims_finder_ready_for_linking__c`. If your Vault already has an equivalent action under a different name, skip package 3 and set the `claims_finder_ready_for_linking_action__c` token to that name (see Configuration).

> The `components/` directory is the source of truth for every component in packages 2 and 3, kept here for review. It follows the [Vault CLI](#vault-cli-workspace) workspace format. The VPKs are not rebuilt automatically; if you edit the MDL or Java, regenerate the matching package before redeploying, for example with `vault config package create` (see [Vault CLI workspace](#vault-cli-workspace)).

### Vault CLI workspace

This sample is laid out as a Vault CLI (Vault DX) workspace, the same format `vault config pull` writes: `components/<Type>/<name>/<name>.mdl` for MDL, with subcomponents in their own files and long text or XML in `.txt` / `.xml` sidecars, and `javasdk/` for the Java classes.

To compare the sample with what a Vault stores, pull into a separate empty folder and diff only the sample's components:

```sh
mkdir ../claims-finder-pulled
vault config pull --dir ../claims-finder-pulled
diff -r components/Agent/claims_finder__c ../claims-finder-pulled/components/Agent/claims_finder__c
```

> **Don't pull into this folder.** Pull makes the folder match the Vault: it overwrites files and deletes anything the Vault doesn't have. A pull also brings down the Vault's whole configuration, so diff only the sample's components, as in the last line above.

> `vault config package create` builds a VPK from the differences between the workspace and the target Vault, so it only includes what that Vault is missing. Use a clean target Vault to produce a full package. `vault config package deploy <file.vpk>` deploys a package.

> **Don't run `vault config push` against a Vault you care about.** Push makes the Vault match the workspace and deletes anything the workspace doesn't contain. Run `vault config push --dry-run` first and read the `DELETED` lines. Also, `Objectlifecycle.annotation_keywords_lifecycle__sys` is an incremental `ALTER ... ADD`, not a full `RECREATE`, so deploy it with package 3 rather than with push.

### Post-deploy checklist

- [ ] Confirm the `claims_finder__c` agent is **Active**.
- [ ] Give the relevant users **Create** on Text Assets, the new **Draft → PreLaunch** user action, and **Execute** on the Claims Agent.
- [ ] Update the two `Vaulttoken` values under **Admin > Configuration > Vault Tokens** if your Vault is on a different API version or uses a customized lifecycle action name (see Configuration below).

## Testing

> **The sample data is optional.** `test-data/` is a convenience. You can test the agent with your own products, countries, claims and documents instead.

### Sample data

[`test-data/`](test-data) has a list of countries, a set of invented products, existing claims and a brochure, so you can try the agent without real promotional content. The products, claims and brochure are invented, and any resemblance to a real product is coincidental.

| File | What it is |
|---|---|
| `01_country__v.csv` | 84 countries (name, abbreviation, expiration and reapproval periods, language, status), matching the Country records of the Vault this sample was tested on. |
| `02_product__v.csv` | Eight invented products with 3-letter abbreviations (`Zephyrol`, `Aurelix`, `Brevazen`, `Calmora`, `Dermavia`, `Lumivane`, `Orlenta`, `Tarvexa`). The claims and brochure use `Zephyrol`. |
| `03_annotation_keywords__sys.csv` | Four existing Claims for Zephyrol in United States (the Claims Library the agent compares against). |
| `Zephyrol_Brochure.pdf` | A one-page brochure that makes six claims, with reference markers and a reference list. |

1. **Load the sample data**, either way:
   - **Import `deploy-sdk/claims-finder-agent-test-data.vpk`** at **Admin > Deployment > Inbound Packages**, then **Review & Deploy**. Untick any step you don't need.
   - **Or load the CSV files** with Vault Loader, in numeric order: `upsert` on `name__v` for countries and products, and `create` for claims.

   Notes on the sample data:
   - It is meant for sandbox Vaults. Countries and products are upserted by name, so records with the same names in your Vault are updated. The four claims are created on every deploy, so deploy the data once.
   - The files use the required fields of the Country and Product objects in the Vault this sample was tested on, including the custom `abbreviation__c`, `expiration_period_months__c` and `reapproval_expiration_period_x_month__c`. If your Vault requires different fields, adjust the columns.
   - The claims reference Product and Country **by name** (`product__v.name__v`, `country__v.name__v`) and the object type by API name (`object_type__v.api_name__v`). They load in the Draft state, which is enough for the agent to count them.
   - The `annotation_keyword_category__v` values (`efficacy__c`, `mechanism_of_action__c`, `tolerability__c`, `safety__c`) must exist in your Vault's picklist. If they don't, change them or clear the column.
   - Vault Loader and data packages silently ignore a column header they don't recognize, so after loading, open a claim and confirm that **Product** and **Country** are set.
2. **Upload `Zephyrol_Brochure.pdf`**, classify it with any document type except Base Document itself, and set its **Product** to `Zephyrol` and **Country** to `United States`. Set both before you look for the chat (see the note below). Documents can't be included in a data package, so this step is always manual.
3. Open **Vault AI Chat** on the document and ask, for example, "Find new claims in this document."

> **Set Product and Country on the document first.** In testing, a document with an empty **Product** or **Country** did not show the Vault AI Chat icon at all, so the **Find New Claims** action could not be found. If you can't see the chat or the action, check these two fields on the document before anything else.

> **Use a specific document type, not Base Document.** The agent is defined on the Base Document type (`document_type('Doctype.base_document__v')`), so it applies to any document type under it. Don't classify the brochure as Base Document itself. Pick any other document type, subtype or classification, and make sure the **Product** and **Country** fields are available on it.

### What to expect

The brochure makes six claims. Three match the library and three do not, so you should see roughly:

- **New claims created (3):** `Zephyrol provides 24-hour relief with a single daily dose.`, `In a 12-week study, 8 in 10 patients reported fewer nasal symptoms.` and `Zephyrol does not interact with common over-the-counter pain relievers.`, with no trailing `1`, `2` or `3`.
- **Already in the library (3):** the 30-minute relief claim and the histamine claim (verbatim matches), and the headache and drowsiness side-effect claim (the same point in different words).

The wording the agent extracts can vary between runs, so treat this as a guide and not an exact result. The new claims are created in **PreLaunch**, and after a minute or two Vault's Claims Agent adds link annotations to the document.

Product and Country are required on the document. The agent reads both from the document, never guesses them and never takes them from the chat message. If either is empty, it can't create claims and says which one is missing.

To check the "document not found" path, invoke the Create Claims tool with a `source_document_id` that does not exist (for example, `999999999`) and confirm the response says the document "was not found".

## Configuration

Two values live as `Vaulttoken` records, so an administrator can change them without a code redeploy:

| Vaulttoken | Default | Purpose |
|---|---|---|
| `claims_finder_api_version__c` | `v26.2` | REST API version used by the tool's three local HTTP calls. |
| `claims_finder_ready_for_linking_action__c` | `claims_finder_ready_for_linking__c` | The Draft → PreLaunch lifecycle user action name, in case your Vault's `annotation_keywords__sys` lifecycle is customized. |

## Conventions

- Custom component API names end in `__c`.
- `runtime_code` in the MDL references the fully qualified Java class, for example `Aitooltyperuntimecode.com.veeva.vault.custom.claims.CreateClaimsToolHandler`.
- Handlers run with `@ExecuteAs(ExecuteAsUser.REQUEST_OWNER)` and declare their scope with `@AiContextTypeRuntimeHandlerInfo` or `@AiToolTypeRuntimeHandlerInfo`.
- Vault Java SDK code can only catch `RollbackException` (see [Limits & Restrictions](https://general.veevavault.dev/vault-sdk/references/limits-restrictions)), so the handlers don't use `try/catch`. Service calls report failures through `.onError(...)`, and the handlers turn them into text the agent can read: `CONTEXT_ERROR: ...` from the context handler and `FAILED: ...` lines from the tool.
- The tool returns one `CREATED` or `FAILED` line per claim, which the action instructions turn into the chat summary.

## License

[Apache License 2.0](../../LICENSE)
