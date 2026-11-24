# Classroom Agent

Sample Vault AI custom agents for a fictional **Classroom** application. They combine custom Java handlers with agents, tools and contexts defined in MDL.

- **Classroom Agent** (`classroom_agent__c`): an object agent on the Course (`course__c`) record. It answers questions about a course, looks up students and Classroom documents, lists a student's registered sections, and registers a student into a section or drops them from one.
- **Classroom Document Agent** (`classroom_doc_agent__c`): a document agent for the `classroom__c` document type. It answers questions about, summarizes, and reviews the document being viewed, and can add annotations to it.

## Components

| Component | Java handler | Description |
|---|---|---|
| `get_obj_env_info__c` | `GetObjEnvInfoContext` | **AI Context Type** that exposes the Vault's id, name and DNS |
| `register_student__c` | `RegisterStudentToolType` | **AI Tool Type** that creates registration records, after checking for duplicates, capacity and schedule conflicts |
| `drop_student__c` | `DropStudentToolType` | **AI Tool Type** that sets a student's registration to Dropped |
| `annotate_document__c` | `AnnotateDocumentToolType` | **AI Tool Type** that creates document annotations |
| `find_students__c`, `find_classroom_doc__c`, `all_registered_course_sections__c` | none | Read-only tools built on the platform `vql_query__sys` tool type |
| none | `ClassroomLookups`, `CourseSection`, `Student` | Helper classes (user-defined classes) that the two registration tool types share |
| `classroom_agent__c`, `classroom_doc_agent__c` | none | Object agent and document agent, each with several actions and contexts |

## Handlers

For what a context type and a tool type are, see [Custom handlers](../../README.md#custom-handlers). This sample needs Java for four things:

| Handler | Type | Purpose |
|---|---|---|
| `GetObjEnvInfoContext` | AI Context Type | Supplies the Vault's id, name and DNS at request time, so agents can link to Course Sections and documents without a hard-coded host. |
| `RegisterStudentToolType` | AI Tool Type | Registers a student in a section. Enforces the rules (see [Registration rules](#registration-rules)) and returns the same `SUCCESS` or `FAILURE` result each time, so the agent can phrase the reply. |
| `DropStudentToolType` | AI Tool Type | Drops a student from a section: sets their Enrolled or Waitlist registration to Dropped and its End Date to today. The record is updated, not deleted, so the history stays in Vault. |
| `AnnotateDocumentToolType` | AI Tool Type | Creates note annotations on a document. The LLM supplies only the note text, and the handler works out the placement. |

The two registration tool types share their lookups through `ClassroomLookups`, with `CourseSection` and `Student` as small value classes. The read-only tools (`find_students__c`, `find_classroom_doc__c`, `all_registered_course_sections__c`) only read data, so they use the standard `vql_query__sys` tool type and need no Java.

### Registration rules

`RegisterStudentToolType` rejects a registration when:

- the section or the active student is not found, or the name matches more than one record;
- the student is already Enrolled or on the Waitlist in the section, or has completed it. A Dropped or Cancelled registration does not block a new one;
- the section is full: its Enrolled registrations have reached **Capacity**. A section without a capacity has no limit;
- the section's meeting time overlaps another section the student is Enrolled in during the same semester. Sections without both a start and an end time are not checked. The sample has no meeting days, so it treats all sections as meeting on the same days.

`DropStudentToolType` rejects a drop when the student has no Enrolled or Waitlist registration in the section, or has already completed it.

The checks and the save are not atomic, so two simultaneous requests could both pass. A production implementation should also enforce the rules on the object itself, for example with a record trigger.

## Architecture

- The user chats with an agent on a Course record or a Classroom document.
- The agent's contexts (such as `get_obj_env_info__c`) are resolved first, and their text is added to the prompt.
- The LLM follows the action's instructions and calls tools when it needs data or must make a change.
- A tool returns its result, and the agent writes the reply.

Tool types return business failures (student not found, section full and so on) as a normal JSON result with `responseStatus = "FAILURE"` and a `failureReason`, rather than as an SDK error.

## Repository layout

```
├── javasdk/com/veeva/vault/custom/
│   ├── contexttype/GetObjEnvInfoContext.java    AI Context Type handler
│   └── tooltype/
│       ├── RegisterStudentToolType.java         AI Tool Type handler
│       ├── DropStudentToolType.java             AI Tool Type handler
│       ├── AnnotateDocumentToolType.java        AI Tool Type handler
│       ├── ClassroomLookups.java                Shared lookups (user-defined class)
│       ├── CourseSection.java                   Value class (user-defined class)
│       └── Student.java                         Value class (user-defined class)
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
| `course_section__c` | The section number (for example `BIO101_F27_AM`) is `name__v`, and it must be unique. Parent is `course__c`; the teacher field references a `teacher__c` person. **Start Time** and **End Time** are the meeting time used by the schedule-conflict check. |
| `course_registration__c` | Auto-numbered name; links a student to a section. The `registration_status__c` field uses the picklist of the same name (Enrolled, Waitlist, Dropped, Cancelled, Complete). New registrations default to Enrolled, and the Drop Student tool sets them to Dropped. |
| `classroom__c` document type | Syllabi, brochures and similar course documents. |

The agents only see what they need. The Course Registration Data context leaves out grades, student comments and email addresses, and the Find Students tool returns names only.

## Prerequisites

- A Vault with Vault AI enabled (LLM connection configured) and permission to deploy Java SDK code and configuration (MDL).
- Optional, for the Classroom Document Agent's **Review Classroom Material** action to complete a workflow task: a document workflow for `classroom__c` documents (see [Testing the review action](#testing-the-review-action)). Without one, the action still reviews and annotates the document from Vault AI Chat.
- JDK 17 ([Amazon Corretto 17](https://docs.aws.amazon.com/corretto/latest/corretto-17-ug/downloads-list.html) recommended) and Maven, for local builds.

## Building

Set `vault.sdk.version` in [`pom.xml`](pom.xml) to match your Vault release (find it in **Admin > Settings > General Settings**; the sample is set for 26R2), then:

```
mvn clean package
```

Maven builds the classes for local verification only. To deploy, use the pre-built packages in [`deploy-sdk/`](deploy-sdk) (see below). The `.vpk` files are not rebuilt automatically, so if you change the Java code or the MDL, regenerate the matching package before redeploying, for example with `vault config package create` (see [Vault CLI workspace](#vault-cli-workspace)).

## Deploying

### Before you deploy

Deploy this sample to a sandbox Vault you can refresh, not to one used for validation or user acceptance testing. Package 2 uses `RECREATE`, so it **replaces** any existing component with the same name. It creates or replaces:

| Type | Components |
|---|---|
| Picklist | `department__c`, `semester__c`, `registration_status__c` |
| Doctype | `classroom__c` |
| Object | `course__c`, `course_section__c`, `course_registration__c` (and `ALTER Object person__sys`) |
| Objecttype | `person__sys.student__c`, `person__sys.teacher__c` |
| Pagelayout | `course_detail_page_layout__c`, `course_section_detail_page_layout__c`, `course_registration_detail_page_layout__c` |
| Tab | `courses__c`, `course_materials__c`, `students__c`, `teachers__c` |
| Aicontexttype | `get_obj_env_info__c` |
| Aitooltype | `register_student__c`, `drop_student__c`, `annotate_document__c` |
| Aitool | `find_students__c`, `find_classroom_doc__c`, `all_registered_course_sections__c`, `register_student__c`, `drop_student__c`, `annotate_document__c` |
| Agent | `classroom_agent__c`, `classroom_doc_agent__c` |

Package 1 adds Java classes in the `com.veeva.vault.custom.contexttype` and `com.veeva.vault.custom.tooltype` packages.

To check whether your Vault already has any of these names, run this query, for example in the Vault Toolbox VQL Editor:

```sql
SELECT component_type__v, component_name__v FROM vault_component__v
WHERE component_name__v CONTAINS ('department__c', 'semester__c', 'registration_status__c', 'classroom__c',
  'course__c', 'course_section__c', 'course_registration__c', 'courses__c', 'course_materials__c',
  'students__c', 'teachers__c', 'get_obj_env_info__c', 'register_student__c', 'drop_student__c',
  'annotate_document__c', 'find_students__c', 'find_classroom_doc__c', 'all_registered_course_sections__c',
  'classroom_agent__c', 'classroom_doc_agent__c')
```

### Deploy the packages

Deploy the two packages **in order**:

1. **`deploy-sdk/classroom-agent-1-code.vpk`**: the Java SDK code (the four handler classes and three helper classes). The `Aicontexttyperuntimecode` and `Aitooltyperuntimecode` components register automatically.
2. **`deploy-sdk/classroom-agent-2-config.vpk`**: the MDL, in dependency order: `Picklist`, `Doctype`, `Object` (including the `ALTER Object person__sys`), `Objecttype`, `Pagelayout`, `Tab`, `Aicontexttype`, `Aitooltype`, `Aitool`, then `Agent`.

> **Deploy the Java code before the config.** If the config is applied first, creating the `Aitooltype`, `Aicontexttype` and `Aitool` components fails with `GEN-EREC-UE-801` because the handler class is not yet live.

> **Note:** `ALTER Object person__sys` turns on object types for the Person object. This is not on by default in a new Vault. Review it before deploying to a Vault that already uses Person records.

> **Redeploying after Person gets a lifecycle.** If a lifecycle is added to Person after the first deploy, Vault adds its fields (such as `lifecycle__v` and `state__v`) to the Student and Teacher object types. Redeploying package 2 then fails on those two steps with `Cannot remove required field [lifecycle__v] from the object type`, because `RECREATE` tries to remove fields the MDL doesn't list. Untick the `Objecttype` steps when you redeploy, or add the extra fields to `components/Objecttype/` and rebuild the package. A first deploy is not affected: Vault adds the lifecycle fields itself.

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
- [ ] Give the relevant users access to the new objects, tabs and agents. The tools run as the user who is chatting, so to register and drop students a user needs **Create** and **Edit** on Course Registration, and **Read** on Course, Course Section and Person.
- [ ] Optional: load the sample data from `test-data/` (see Testing).

## Testing

> **The sample data is optional.** `test-data/` is a convenience. You can test the agents with your own students, courses and documents instead.

1. **Load the sample data**, either way:
   - **Import `deploy-sdk/classroom-agent-test-data.vpk`** at **Admin > Deployment > Inbound Packages**, then **Review & Deploy**. Untick any step you don't need.
   - **Or load the CSV files** from [`test-data/`](test-data) with Vault Loader (`create`), in numeric order because later files reference earlier ones: `01_person__sys.csv` (students and teachers), `02_course__c.csv`, then `03_course_section__c.csv`.

   The data is meant for sandbox Vaults. All names and email addresses are fictional. The records are created on every deploy, so deploy the data once.
   The sections are created without a teacher. A data load can only reference a record by a unique field, and `person__sys` has no suitable unique field (`email__sys` and `name__v` are not unique), so the teachers are loaded but not linked. To link one, set the **Teacher** field on a section.
2. Upload `BIO101_Syllabus.pdf` and `BIO101_Brochure.pdf` as `classroom__c` documents to try the Classroom Document Agent. Documents can't be included in a data package, so this step is always manual.
3. Open the **BIO101** Course record from the **Courses** tab, start Vault AI Chat, and try the requests in [What to expect](#what-to-expect).
4. Open a Classroom document and try the Classroom Document Agent's actions.

> **Open records from their tabs.** Vault AI Chat appears on records opened from their own tab (**Courses**, **Students**, **Teachers**), not on records opened from **Business Admin > Objects**. This is why the sample deploys the `Tab` components.

### What to expect

The sample sections run in Fall 2027 and each meets for 75 minutes: BIO101_F27_AM 09:00-10:15, CHEM201_F27_AM 10:00-11:15, HIST105_F27_AM 11:00-12:15 and BIO101_F27_PM 14:00-15:15. Try these in order, on the BIO101 Course record:

| Ask the Classroom Agent | Expected result |
|---|---|
| "Register Jordan Smith into BIO101_F27_AM" | Registered. The reply links the section and lists its dates, time, semester and capacity. |
| "Register Jordan Smith into BIO101_F27_AM" again | Fails: Jordan Smith is already registered (Enrolled). |
| "Register Jordan Smith into CHEM201_F27_AM" | Fails: schedule conflict, because 10:00-11:15 overlaps BIO101_F27_AM 09:00-10:15. |
| "Which sections is Jordan Smith registered in?" | A table with BIO101_F27_AM. |
| "Drop Jordan Smith from BIO101_F27_AM" | Dropped. The registration stays in Vault with the status Dropped. |
| "Drop Jordan Smith from BIO101_F27_AM" again | Fails: the registration is already Dropped. |
| "Drop Jordan Smith" | The agent asks which section, and does not guess. |
| "Register Jordan Smith into CHEM201_F27_AM" | Registered, because the conflicting registration was dropped. |

To check the capacity rule, set **Capacity** on BIO101_F27_PM to 1, register one student into it, then try to register a second student. The second registration fails because the section is full.

On a Classroom document, the Classroom Document Agent should:

- **Summarize Classroom Document:** a summary with Course content, Requirements and Grade policy for the syllabus, and only Course content for the brochure.
- **Answer a Question:** answers from the document only, for example "What is the late work policy?" on the syllabus.
- **Review Classroom Material:** on the syllabus, a verdict of Approved, because it meets all six criteria, and one annotation on the document. On the brochure, the agent replies that the review applies to syllabi only.

The wording the LLM produces varies between runs, so treat these as a guide, not an exact result.

### Testing the review action

From Vault AI Chat, **Review Classroom Material** reviews the document, adds annotations and returns the verdict in the chat. To let the agent complete a real workflow task as well:

1. Create a document workflow for the `classroom__c` lifecycle with a single review task. Name the task **Classroom Material Review**, give it the verdicts **Approved** and **Rejected**, and prompt for **Approval Comment** and **Rejection Comment**. The action's instructions use these exact labels.
2. Give the agent user of `classroom_doc_agent__c` the **Application: Workflow: Participate** permission and **Read** access to Classroom documents.
3. Start the workflow on the syllabus and assign the task to the agent user.
4. Run the **Agent Workflow Task Execution** job from **Admin > Operations > Job Status**. It runs hourly by default, so start it yourself when testing.

The agent user then completes the task with the verdict and comment. See [Configuring Agent Tools](https://platform.veevavault.help/en/gr/950647/) in Vault Help for the full setup.

## Configuration

This sample has no admin-configurable values. Set the SDK version in `pom.xml` before building.

## Conventions

- Custom component API names end in `__c`.
- `runtime_code` in the MDL references the fully qualified Java class, for example `Aitooltyperuntimecode.com.veeva.vault.custom.tooltype.RegisterStudentToolType`.
- Handlers run with `@ExecuteAs(ExecuteAsUser.REQUEST_OWNER)` and declare their scope with `@AiContextTypeRuntimeHandlerInfo` or `@AiToolTypeRuntimeHandlerInfo`.

## License

[Apache License 2.0](../../LICENSE)
