# Vault AI Custom Agents

Sample Vault AI custom agents built with the Vault Java SDK and MDL. Each sample is a standalone Maven project with its own README.

Both samples target **Vault 26R2** (SDK `26.2.0`, API `v26.2`). Set `vault.sdk.version` in each `pom.xml` to match your Vault release.

| Sample | App family | What it shows |
|---|---|---|
| [`platform/classroom-agent`](platform/classroom-agent) | Platform | An object agent and a document agent for a fictional Classroom app, with a custom context type, two custom tool types and VQL-backed tools. |
| [`promomats/claims-finder-agent`](promomats/claims-finder-agent) | PromoMats | A document agent that finds new promotional claims, creates them in the Claims Library and triggers Vault's native Claims Agent. Includes pre-built VPKs. |

## Custom handlers

Agents are configured in MDL (see the [Vault AI overview](https://general.veevavault.dev/ai/overview)). Add Java only when a standard component can't do the job:

- **[AI Context Type](https://general.veevavault.dev/ai/ai-agents/agent-context/how-to-create-custom-context-type)**: read-only text the agent sees before it answers.
- **[AI Tool Type](https://general.veevavault.dev/ai/ai-agents/agent-tools/how-to-create-custom-tool-type/)**: an action the agent can call, such as validated writes or multi-step logic. Standard types like `vql_query__sys` need no Java.

Deploy the Java code before the MDL that references it.

## Layout

Each sample is a [Vault CLI](https://limited.veevavault.dev/vault-dx/cli/getting-started/managing-configuration-as-code) (Vault DX) workspace: MDL in `components/<Type>/<name>/`, Java in `javasdk/`. Each sample is also a standalone Maven project (`pom.xml`) for local builds.

## Requirements

- A Vault with Vault AI enabled, and permission to deploy Java SDK code and MDL
- JDK 17 and Maven

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
