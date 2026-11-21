# Classroom Agent

Sample Vault AI custom agents for a fictional **Classroom** application. They combine custom Java handlers with agents, tools and contexts defined in MDL.

- **Classroom Agent** (`classroom_agent__c`): an object agent on the Course (`course__c`) record. It answers questions about a course, looks up students and Classroom documents, lists a student's registered sections, and registers a student into a section.
- **Classroom Document Agent** (`classroom_doc_agent__c`): a document agent for the `classroom__c` document type. It answers questions about, summarizes, and reviews the document being viewed, and can add annotations to it.

## Components

| Component | Java handler | Description |
|---|---|---|
| `get_obj_env_info__c` | `GetObjEnvtContext` | **AI Context Type** that exposes the Vault's id, name and DNS |
| `register_student__c` | `RegisterStudentToolType` | **AI Tool Type** that writes registration records, with lookups, a duplicate guard and a structured result |
| `annotate_document__c` | `AnnotateDocumentToolType` | **AI Tool Type** that creates document annotations |
| `find_students__c`, `find_classroom_doc__c`, `all_registered_course_sections__c` | none | Read-only tools built on the platform `vql_query__sys` tool type |
| `classroom_agent__c`, `classroom_doc_agent__c` | none | Object agent and document agent, each with several actions and contexts |

## Handlers

For what a context type and a tool type are, see [Custom handlers](../../README.md#custom-handlers). This sample needs Java for three things:

| Handler | Type | Purpose |
|---|---|---|
| `GetObjEnvtContext` | AI Context Type | Supplies the Vault's id, name and DNS at request time, so agents can link to Course Sections and documents without a hard-coded host. |
| `RegisterStudentToolType` | AI Tool Type | Registers a student in a section. Enforces the rules (active `student__c`, existing section, no duplicate registration) and returns the same `SUCCESS` or `FAILURE` result each time, so the agent can phrase the reply. |
| `AnnotateDocumentToolType` | AI Tool Type | Creates note annotations on a document. The LLM supplies only the note text, and the handler works out the placement. |

The lookups (`find_students__c`, `find_classroom_doc__c`, `all_registered_course_sections__c`) only read data, so they use the standard `vql_query__sys` tool type and need no Java.

## Architecture

- The user chats with an agent on a Course record or a Classroom document.
- The agent's contexts (such as `get_obj_env_info__c`) are resolved first, and their text is added to the prompt.
- The LLM follows the action's instructions and calls tools when it needs data or must make a change.
- A tool returns its result, and the agent writes the reply.

Tool types return business failures (student not found, duplicate registration and so on) as a normal JSON result with `responseStatus = "FAILURE"` and a `failureReason`, rather than as an SDK error.

## Repository layout

```
├── javasdk/com/veeva/vault/custom/
│   ├── contexttype/GetObjEnvtContext.java       AI Context Type handler
│   └── tooltype/
│       ├── RegisterStudentToolType.java         AI Tool Type handler
│       └── AnnotateDocumentToolType.java        AI Tool Type handler
├── components/<Type>/<name>/<name>.mdl          MDL source in Vault CLI workspace format, one folder per component
│   ├── Object/ Objecttype/ Picklist/ Doctype/   Classroom data model
│   ├── Tab/ Pagelayout/                         Navigation tabs and page layouts
│   ├── Aicontexttype/ Aitooltype/ Aitool/       Context type, tool types, tools
│   └── Agent/                                   The two agents, with their actions and contexts
├── .vdxignore                                   Vault CLI ignore rules
├── deploy-sdk/
│   ├── classroom-agent-1-code.vpk               Pre-built package 1/2: the Java SDK code
│   ├── classroom-agent-2-config.vpk             Pre-built package 2/2: data model, tabs, AI components, agents
│   └── classroom-agent-test-data.vpk            Optional sample data: people, courses and sections
├── test-data/                                   Sample CSVs and PDFs (the source of the sample data package)
└── pom.xml
```

## Data model

| Component | Notes |
|---|---|
| `person__sys` with object types `student__c`, `teacher__c` | Requires `allow_types(true)` on `person__sys` (the `ALTER Object person__sys` MDL). Display name is the composite `name__v`. The object types list only `person__sys` fields that exist in every app family (Platform, Clinical and so on). Fields such as `middle_name__sys`, `prefix__sys`, `suffix__sys` and `last_initiated_by__sys` are not on every Vault's Person object and are left out. |
| `course__c` ("Course") | Has a `department__c` picklist. |
| `course_section__c` | The section number (for example `BIO101_F26_AM`) is `name__v`. Parent is `course__c`; the teacher field references a `teacher__c` person. |
| `course_registration__c` | Auto-numbered name; links a student to a section. `registration_status__c` is backed by the `status__c` picklist (Enrolled, Waitlist, Dropped, Cancelled, Complete). New registrations default to Enrolled. |
| `classroom__c` document type | Syllabi, brochures and similar course documents. |

## Prerequisites

- A Vault with Vault AI enabled (LLM connection configured) and permission to deploy Java SDK code and configuration (MDL).
- For the Classroom Document Agent's **Review Classroom Material** action: the standard `complete_workflow_task__sys` tool and a document workflow task to run it from. The other actions do not need a workflow.
- JDK 17 ([Amazon Corretto 17](https://docs.aws.amazon.com/corretto/latest/corretto-17-ug/downloads-list.html) recommended) and Maven, for local builds.

## Building

Set `vault.sdk.version` in [`pom.xml`](pom.xml) to match your Vault release (find it in **Admin > Settings > General Settings**; the sample is set for 26R2), then:

```
mvn clean package
```

Maven builds the classes for local verification only. To deploy, use the pre-built packages in [`deploy-sdk/`](deploy-sdk) (see below). The `.vpk` files are not rebuilt automatically, so if you change the Java code or the MDL, regenerate the matching package before redeploying, for example with `vault config package create` (see [Vault CLI workspace](#vault-cli-workspace)).

## Deploying

Deploy the two packages **in order**:

1. **`deploy-sdk/classroom-agent-1-code.vpk`**: the Java SDK code (the three handler classes). The `Aicontexttyperuntimecode` and `Aitooltyperuntimecode` components register automatically.
2. **`deploy-sdk/classroom-agent-2-config.vpk`**: the MDL, in dependency order: `Picklist`, `Doctype`, `Object` (including the `ALTER Object person__sys`), `Objecttype`, `Pagelayout`, `Tab`, `Aicontexttype`, `Aitooltype`, `Aitool`, then `Agent`.

> **Deploy the Java code before the config.** If the config is applied first, creating the `Aitooltype`, `Aicontexttype` and `Aitool` components fails with `GEN-EREC-UE-801` because the handler class is not yet live.

> **Note:** `ALTER Object person__sys` turns on object types for the Person object. This is not on by default in a new Vault. Review it before deploying to a Vault that already uses Person records.

> The `components/` directory is the source of truth for every component in package 2, kept here for review. It follows the [Vault CLI](#vault-cli-workspace) workspace format.

### Vault CLI workspace

This sample is laid out as a Vault CLI (Vault DX) workspace, the same format `vault config pull` writes: `components/` holds the MDL and `javasdk/` holds the Java classes.

- Each component is `components/<Type>/<name>/<name>.mdl`.
- Each subcomponent (an agent action, an object field, a picklist entry) is its own file under `<Sub>/<name>/<name>.mdl`, and the parent file references it, for example `Agentaction(@Agentaction/register_student__c.mdl)`.
- Large attribute values are sidecar files next to the `.mdl` that owns them and are referenced with `@`: XML as `.xml` (`page_markup`, `configuration`, `context_configuration`), and multi-line text and JSON as `.txt` (`objective`, `instructions`, `input`).

The files here are hand-written minimal MDL, so a fresh `vault config pull` of a deployed Vault also lists attributes the Vault fills in with defaults (for example `required(false)` or `order(10)`).

To compare the sample with what a Vault stores, pull into a separate empty folder and diff only the sample's components:

```sh
mkdir ../classroom-agent-pulled
vault config pull --dir ../classroom-agent-pulled
diff -r components/Agent/classroom_agent__c ../classroom-agent-pulled/components/Agent/classroom_agent__c
```

> **Don't pull into this folder.** Pull makes the folder match the Vault: it overwrites files and deletes anything the Vault doesn't have. A pull also brings down the Vault's whole configuration, so diff only the sample's components, as in the last line above.

> `vault config package create` builds a VPK from the differences between the workspace and the target Vault, so it only includes what that Vault is missing. Use a clean target Vault to produce a full package. `vault config package deploy <file.vpk>` deploys a package.

> **Don't run `vault config push` against a Vault you care about.** Push makes the Vault match the workspace and deletes anything the workspace doesn't contain. Run `vault config push --dry-run` first and read the `DELETED` lines.

### Post-deploy checklist

- [ ] Confirm both agents (`classroom_agent__c`, `classroom_doc_agent__c`) are **Active**.
- [ ] Give the relevant users access to the new objects, tabs and agents.
- [ ] Optional: load the sample data from `test-data/` (see Testing).

## Testing

> **The sample data is optional.** `test-data/` is a convenience. You can test the agents with your own students, courses and documents instead.

1. **Load the sample data**, either way:
   - **Import `deploy-sdk/classroom-agent-test-data.vpk`** at **Admin > Deployment > Inbound Packages**, then **Review & Deploy**. Untick any step you don't need.
   - **Or load the CSV files** from [`test-data/`](test-data) with Vault Loader (`create`), in numeric order because later files reference earlier ones: `01_person__sys.csv` (students and teachers), `02_course__c.csv`, then `03_course_section__c.csv`.

   The data is meant for sandbox Vaults. All names and email addresses are fictional. The records are created on every deploy, so deploy the data once.
   The sections are created without a teacher. A data load can only reference a record by a unique field, and `person__sys` has no suitable unique field (`email__sys` and `name__v` are not unique), so the teachers are loaded but not linked. To link one, set the **Teacher** field on a section.
2. Upload `BIO101_Syllabus.pdf` and `BIO101_Brochure.pdf` as `classroom__c` documents to try the Classroom Document Agent. Documents can't be included in a data package, so this step is always manual.
3. Open a Course record from its tab (**Courses**, deployed with this sample), start Vault AI Chat, and ask the Classroom Agent to register a student into a section, for example "Register Jordan Smith into BIO101_F26_AM", or to list a student's registered sections.
4. Open a Classroom document and ask the Classroom Document Agent to summarize it.

> **Open records from their tabs.** Vault AI Chat appears on records opened from their own tab (**Courses**, **Students**, **Teachers**), not on records opened from **Business Admin > Objects**. This is why the sample deploys the `Tab` components.

## Configuration

This sample has no admin-configurable values. Set the SDK version in `pom.xml` before building.

## Conventions

- Custom component API names end in `__c`.
- `runtime_code` in the MDL references the fully qualified Java class, for example `Aitooltyperuntimecode.com.veeva.vault.custom.tooltype.RegisterStudentToolType`.
- Handlers run with `@ExecuteAs(ExecuteAsUser.REQUEST_OWNER)` and declare their scope with `@AiContextTypeRuntimeHandlerInfo` or `@AiToolTypeRuntimeHandlerInfo`.

## License

[Apache License 2.0](../../LICENSE)
