# AI Runtime Governance — Backend

AI Runtime Governance is a governance service for AI agents. It sits between a
supported agent and its model provider, checks each new prompt before it is sent,
and records the observable activity that follows so a person can review what
happened.

This repository contains the backend service. It is not the screen a person uses
to browse activity; that is the web app. The backend is the part that enforces
prompt decisions, stores activity, manages accounts, and serves data to the web
app and connected agents.

## Why use it

AI agents can send sensitive data, follow injected instructions, or fail in ways
that are hard to see after the fact. This service gives you two things in one
place:

- **A guard before the model.** Prompts can be allowed, denied, redacted, or held
  for the submitting person's approval.
- **A record of what happened next.** Agent steps, model calls, tool calls,
  retries, handoffs, and failures are collected into one activity timeline.

The goal is to make prompt decisions understandable and agent activity visible,
without pretending the service can see activity it was never told about.

## Who it is for

- **People running AI agents** who want a control point between the agent and the
  model provider.
- **Integrators** connecting a supported client such as OpenCode or a
  Copilot-compatible BYOK setup.
- **Host operators** who manage accounts and decide which accounts may use the
  gateway.

## What it does

### Guard prompts

Before forwarding a model request, the service evaluates the newly submitted
human message against a built-in rule catalog covering:

- credentials and API tokens;
- email addresses and phone numbers;
- payment-card and financial identifiers;
- prompt-injection indicators;
- unsafe-content indicators;
- clearly invalid or weak-prompt patterns.

A successful evaluation produces one of four outcomes:

| Outcome | What happens |
| :--- | :--- |
| **Allow** | The prompt is forwarded unchanged. |
| **Deny** | The prompt is not forwarded. |
| **Redact** | Sensitive matches are replaced with typed markers, then the redacted prompt is forwarded. |
| **Require approval** | Forwarding waits for the submitting person to decide. |

When several rules match, all sensitive-data redactions are applied. A deny wins
over everything else. Otherwise an approval requirement pauses the request.
Otherwise the prompt is allowed.

The guard looks at the new human message, not system/developer instructions or
earlier conversation history. Its rules identify configured indicators; they are
not a claim of complete semantic safety detection.

### Hold a prompt for approval

An approval-required prompt is tied to the exact message and the person who
submitted it. That person approves or rejects it; nobody else can approve it for
them. Approving does not bypass redaction: the person approves the redacted
message that would be forwarded.

If the request is cancelled, expires, or its approval cannot be delivered, it is
not forwarded. There is no silent fallback to the model provider.

### If the guard cannot decide

A failed, timed-out, or unavailable guard blocks forwarding. The service does not
treat an error as permission to continue. A guarded request never quietly
switches to a direct provider path.

### Monitor agent activity

The service records the observable execution loop:

- the prompt and its guard outcome;
- agent steps;
- model requests and responses;
- tool calls and results;
- retries, handoffs, errors, and completion.

Events are linked by trace and parent/child relationships where the integration
exposes them. Subagents can be attached to the trace that spawned them.

The service only records what was reported. Missing, unsupported, or lost telemetry
is labelled as such; it is never replaced with a guess, a zero, or the current
time.

### Serve the activity timeline

The web app reads private traces from the backend. A trace shows:

- the ordered activity sequence;
- prompt-guard outcome;
- approval history;
- execution status;
- telemetry completeness;
- integration coverage;
- missing-data notes.

The timeline is metadata-only. It does not expose prompt bodies, model outputs,
tool inputs/outputs, hidden reasoning, credentials, or another account's data.

### Manage human accounts

People sign in with an email address, a password, and an authenticator code.
Accounts support:

- email verification;
- mandatory authenticator enrollment;
- ten single-use recovery codes;
- persistent sessions;
- password reset;
- authenticator replacement;
- recovery-code regeneration;
- recent-authentication checks before sensitive account changes.

Each person has a private scope. Their traces, approvals, and client credentials
are not shared with other people.

### Administer the deployment

A host operator can:

- enable or disable an account;
- recover a lost authenticator setup for a named account;
- grant or revoke permission to use the model gateway;
- cancel a pending security operation;
- manage the encryption keys used for authenticator secrets.

These actions are local operator tasks, not a public admin website. Host operator
access does not grant permission to read traces, prompts, approval history, or
model-provider secrets.

## How the pieces fit

```text
Supported agent
  | model request + gateway credential
  v
Governance gateway
  -> prompt guard
  -> allow / deny / redact / require approval
  -> model provider
  -> activity timeline (metadata only)
  -> Langfuse, optional (redacted prompt + model output)

Supported agent
  | hook and lifecycle events + event credential
  v
Event collector
  -> trace and event store
  -> activity timeline API

Web app
  -> account management
  -> private trace reads
  -> submitter-only approval decisions
```

The gateway key, event-reporting credential, and upstream provider key are
separate on purpose. A key that reports activity cannot read traces or approve a
prompt. The provider key stays on the server.

## Key terms

| Term | Meaning |
| :--- | :--- |
| **Trace** | The record of one monitored request and the activity linked to it. |
| **Event** | One observable step in a trace, such as a model call or tool call. |
| **Guard decision** | The allow, deny, redact, or approval result for a prompt. |
| **Approval** | A decision by the submitting person on an exact prompt that was paused by the guard. |
| **Private scope** | The boundary that keeps one person's traces and approvals separate from another's. |
| **Coverage** | What the integration could actually observe: input, model response, and tool activity are reported separately. |
| **Telemetry status** | Whether the trace is complete, incomplete, unknown, or unsupported. |
| **Gateway credential** | The key a client uses to send model traffic through the governance gateway. |
| **Event credential** | The separate key a client uses to report activity. |
| **Upstream provider key** | The server-side key used to call the model provider. |

## Getting started as an operator

At a high level, a deployment needs:

- a PostgreSQL database;
- an SMTP relay for verification and password-reset email;
- the governance backend;
- the web app, served on the same origin as the API;
- HTTPS in front of both;
- a model-provider configuration for the selected upstream service.

A typical first setup is:

1. Start the database and mail relay.
2. Start the backend and web app.
3. Create an account through the web app.
4. Verify the email address and complete authenticator enrollment.
5. Grant the account permission to use the model gateway.
6. Connect a supported agent to the gateway.
7. Open the activity page and confirm that the trace appears.

Gateway permission is deliberately separate from account creation. A new account
does not automatically get access to the deployment's model-provider budget.

## Connecting an agent

A supported integration connects the agent to the governance service:

- The agent is configured with a custom model endpoint and a gateway credential.
- The operator keeps the upstream provider credential on the server.
- A supported hook or plugin reports tool calls, lifecycle events, and other
  activity that the model protocol does not carry.
- The integration is installed once per environment or profile. Existing sessions
  may need to reconnect or restart; earlier activity is not captured later.

Sessions that use the agent's own provider path instead of the gateway are not
guarded. They may be monitored only if hooks report their activity, and they must
be labelled as bypassed rather than guarded.

### Route model traffic through the gateway

The gateway is an OpenAI-compatible endpoint. A signed-in account creates a
gateway key, then points its agent at the gateway instead of the model provider:

```text
Agent (OpenCode, VS Code Copilot BYOK, any OpenAI-compatible client)
  -> POST /v1/chat/completions with an arg_gw_* key
  -> prompt guard (LangChain4j input guardrail over the built-in catalog)
  -> allow / redact / deny / require approval
  -> server-held upstream provider key
  -> selected OpenAI-compatible provider
```

The gateway key is separate from hook credentials and from the upstream provider
key. The provider key never leaves the server.

#### Create a key

Sign in, complete recent authentication, and create a named key:

```text
POST /api/v1/account/gateway-keys
Content-Type: application/json

{"name": "laptop"}
```

The response is the only time the token is shown. It also contains a ready-to-paste
OpenCode provider block and a VS Code custom-endpoint block. List key metadata
with `GET /api/v1/account/gateway-keys` and revoke with
`DELETE /api/v1/account/gateway-keys/{id}`. Key creation and revocation require
recent reauthentication, like hook credentials.

Creating a key requires `gateway_entitlement = GRANTED`, which a host operator
grants separately. An account without that entitlement can still sign in, browse
its own activity, and revoke keys, but gateway authentication returns
`GATEWAY_NOT_ENTITLED`.

#### Endpoints

| Method | Path | Purpose |
| :--- | :--- | :--- |
| `POST` | `/v1/chat/completions` | Guarded OpenAI-compatible chat completion, including SSE streaming |
| `GET` | `/v1/models` | Models listed by `GOVERNANCE_GATEWAY_MODELS` |

Send the key as `Authorization: Bearer arg_gw_...` or `x-api-key: arg_gw_...`.
The gateway returns OpenAI-shaped errors such as:

```json
{"error":{"message":"...","type":"permission_error","param":null,"code":"GUARD_APPROVAL_REQUIRED"}}
```

#### What the guard does

- The final message must be a plain-text `user` message for a new turn. The guard
  evaluates that message with the same deterministic detector catalog described
  above, exposed through a LangChain4j `InputGuardrail`.
- **Allow** forwards the original bytes unchanged.
- **Redact** rewrites the submitted message and every earlier user message in the
  conversation before forwarding. Every forwarded turn also re-checks earlier
  user messages for redactions, so a secret that appeared in history is not
  replayed in a later turn, where clients normally resend the original
  conversation.
- **Deny** and **require approval** are fail-closed: the gateway records the
  decision, forwards nothing, and returns `GUARD_DENIED` or
  `GUARD_APPROVAL_REQUIRED`. The interactive approval UI is a later slice.
- A guard failure returns `GUARD_EVALUATION_FAILED` and forwards nothing.
- Tool/assistant continuations are matched to the guarded turn with a conversation
  fingerprint. Unknown continuations return `GUARD_CONTEXT_UNKNOWN` unless
  `GOVERNANCE_GATEWAY_ALLOW_UNGUARDED_CONTINUATION=true`, which labels the trace
  `bypassed` and must not be used for guarded deployments.

#### Response handling and limits

- Non-streaming responses are proxied as received.
- Streaming responses are passed through as SSE chunks without reframing or
  reordering. The gateway accumulates a bounded copy for telemetry only.
- Upstream errors are returned with their original status. There is no retry and
  no fallback to a direct provider.
- Request body, capture size, concurrent upstream calls, and per-key request rate
  are bounded by configuration. The rate limiter is per node; a shared store is
  required before running multiple gateway instances.

#### Langfuse output logging

When enabled, each model call is exported to Langfuse over OTLP/HTTP:

```text
POST {LANGFUSE_BASE_URL}/api/public/otel/v1/traces
Authorization: Basic base64(public_key:secret_key)
x-langfuse-ingestion-version: 4
```

The exported trace contains the redacted new prompt, the full model output, token
usage, finish reason, duration, and a `guardrail` child observation with the
outcome and rule IDs. Continuations of a redacted turn do not re-export the
prompt. Export is asynchronous and bounded; a Langfuse outage never fails the
model request or changes the local timeline.

New gateway traces use a UUIDv7 identifier, and the same 128-bit value is used as
the OTLP trace ID. The Langfuse trace ID therefore equals `activity_trace.id`,
and every model call in one conversation is grouped under one Langfuse trace.

This is an explicit privacy change: the local activity timeline remains
metadata-only, but Langfuse receives prompt and output content. Disable
`LANGFUSE_ENABLED` if that is not acceptable for a deployment.

#### Model usage and cost metadata

Every upstream model call is recorded in `model_usage`, separately from the
timeline metadata. The stored fields include:

- requested and returned model, provider name, response id, system fingerprint,
  service tier, stream mode, and HTTP status
- input, output, cached-input, reasoning-output, and total tokens
- the raw provider `usage` object and selected provider response headers
  (request id, processing time, rate-limit headers)
- finish reasons, tool-call names, request/response byte counts, capture
  truncation, total duration, and time to first byte
- computed input/output/cached cost and any provider-reported cost
- outcome, error type/code/message, account scope, credential id, conversation
  key, client conversation id, and client user agent
- the Langfuse observation id when export is enabled, so each call's output can
  be fetched by id without storing content locally
- Langfuse remains the content store; `model_usage` never stores prompt or
  output bodies

Read it through:

```text
GET /api/v1/usage/traces/{traceId}
GET /api/v1/usage/traces/{traceId}/observations/{observationId}
GET /api/v1/usage/summary?from=<ISO instant>&to=<ISO instant>
```

The observation endpoint reads the generated output back from Langfuse on
demand. It verifies that the observation id belongs to a model call in the
scoped trace, so output content is never stored locally. It returns
`LANGFUSE_DISABLED` when export is off and `LANGFUSE_UNAVAILABLE` when the
Langfuse read fails.

The summary defaults to the last 30 days and returns totals plus breakdowns by
model and UTC day. The trace detail page shows a **Model usage** panel, and the
gateway keys page shows a **Last 30 days** usage summary.

Costs are calculated only when a price is configured. Add prices in
`application.yml` or a deployment-specific configuration file:

```yaml
governance:
  pricing:
    models:
      gpt-4o:
        input-per-million: 2.50
        output-per-million: 10.00
        cached-input-per-million: 1.25
        reasoning-output-per-million: 10.00
        currency: USD
```

If a model has no configured price, token counts are still stored and the cost
is shown as "Not priced". Provider credit balances and billing-account spend are
not queried; only per-call usage reported by the provider is recorded.

For streaming responses, usage is available only when the provider includes it
in the stream. Some OpenAI-compatible providers require:

```json
"stream_options": { "include_usage": true }
```

The gateway can inject that option for streaming requests when
`GOVERNANCE_GATEWAY_FORCE_STREAM_USAGE=true`. It is off by default because a
strict provider may reject the extra field; enable it when the selected provider
supports `stream_options`.

#### Configuration

| Variable | Default | Meaning |
| :--- | :--- | :--- |
| `GOVERNANCE_GATEWAY_ENABLED` | `true` | Enable the gateway endpoints |
| `GOVERNANCE_GATEWAY_PUBLIC_BASE_URL` | request origin | Public base URL used in setup snippets |
| `GOVERNANCE_GATEWAY_PROVIDER_NAME` | `openai-compatible` | Provider label stored in usage records |
| `GOVERNANCE_UPSTREAM_BASE_URL` | empty | OpenAI-compatible base URL, for example `https://api.openai.com/v1` |
| `GOVERNANCE_UPSTREAM_API_KEY` | empty | Server-held provider key |
| `GOVERNANCE_UPSTREAM_ORGANIZATION` | empty | Optional OpenAI organization header |
| `GOVERNANCE_UPSTREAM_PROJECT` | empty | Optional OpenAI project header |
| `GOVERNANCE_GATEWAY_MODELS` | empty | Comma-separated model IDs exposed by `/v1/models`; empty allows all models |
| `GOVERNANCE_GATEWAY_MAX_REQUEST_BYTES` | `2097152` | Maximum request body |
| `GOVERNANCE_GATEWAY_MAX_CAPTURE_BYTES` | `2097152` | Maximum response copy retained for telemetry |
| `GOVERNANCE_GATEWAY_MAX_CONCURRENT` | `8` | Maximum in-flight upstream calls |
| `GOVERNANCE_GATEWAY_REQUESTS_PER_MINUTE` | `60` | Per-key request limit |
| `GOVERNANCE_GATEWAY_REQUEST_TIMEOUT` | `120` | Upstream request timeout, in seconds |
| `GOVERNANCE_GATEWAY_ALLOW_UNGUARDED_CONTINUATION` | `false` | Development escape hatch; labels the trace bypassed |
| `GOVERNANCE_GATEWAY_FORCE_STREAM_USAGE` | `false` | Add `stream_options.include_usage` to streaming requests |
| `LANGFUSE_ENABLED` | `false` | Export model calls to Langfuse |
| `LANGFUSE_BASE_URL` | `https://cloud.langfuse.com` | Langfuse origin or self-hosted origin |
| `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | empty | Langfuse project keys |
| `LANGFUSE_CAPTURE_INPUT` / `LANGFUSE_CAPTURE_OUTPUT` | `true` | Include redacted prompt and output content |

#### Current gateway limits

- Only `chat/completions` is supported; the Responses API and Anthropic Messages
  are later adapters.
- User content must be a string. Multimodal/array content is rejected with
  `PROTOCOL_UNSUPPORTED` rather than forwarded unguarded.
- Approval is fail-closed, not interactive.
- The conversation fingerprint is a pragmatic correlation key, not a per-turn
  client contract. Concurrent identical conversations can collide; sending a
  stable `X-Governance-Conversation-Id` header avoids this.
- The local timeline records the guard decision and model metadata, not prompt or
  output bodies. Those exist only in memory and, when enabled, in Langfuse.
- The built-in catalog currently has no production `DENY` rule; the deny path is
  implemented and unit-tested but is exercised end to end only after the remaining
  invalid/weak-prompt catalog slice.

### Reporting Java hook events

The backend accepts metadata-only events from independent LangChain4j and
Langfuse integrations. It does not connect one integration to the other, pull
traces from a Langfuse project, or treat either callback as prompt enforcement.
Each client hook translates its own callback/span into the event envelope below
and posts it to that integration's endpoint.

After signing in and completing recent authentication, create one credential
for each integration:

```text
POST /api/v1/account/hook-credentials/langchain4j
POST /api/v1/account/hook-credentials/langfuse
```

Send `Content-Type: application/json` with the request. The response includes a
one-time bearer token and that integration's event path. The token is scoped to
the signed-in account and one integration; it cannot read traces or report as
the other integration. The API lists credential metadata but never returns a
token again. Revoke the current credential before issuing its replacement.

Post each event to the returned path with
`Authorization: Bearer <token>`:

```json
{
  "traceId": "c1c6aec4-8c29-4e8d-a730-e3abc7802bef",
  "event": {
    "eventId": "2e9b9715-9272-4c87-8fbd-7715c2424839",
    "kind": "model",
    "sourceAt": "2026-11-04T08:30:00Z",
    "durationMs": 420,
    "identity": "model-alias",
    "label": "chat",
    "observedStatus": "completed"
  }
}
```

Supported event kinds are `prompt`, `agent`, `model`, and `tool`. The backend
assigns receipt time and creates a trace on the first event. A prompt event is
reported as bypassed, not guarded; model and tool events describe observation
only. Unknown fields, prompt/model/tool bodies, reasoning, credentials, guard
decisions, approval facts, and arbitrary attributes are rejected. Send only
stable metadata such as operation names and status values; identity and label
values are short identifiers, not free-form text. Langfuse integrations must
map spans to this envelope; sending raw OTLP or asking the backend to fetch
Langfuse traces is not supported by this first slice.

Revoke credentials with `DELETE /api/v1/account/hook-credentials/{id}`. Both
credential creation and revocation require recent reauthentication.

## Reading a guard result

| Result | What a user should understand |
| :--- | :--- |
| **Allow** | No enabled rule blocked the prompt. This is not a claim that the prompt is safe or high quality. |
| **Deny** | A deny rule matched. The prompt was not sent. |
| **Redact** | Sensitive text was replaced with a typed marker before forwarding. |
| **Require approval** | The prompt is paused. Only the submitter can approve it, and only for the exact message. |
| **Unavailable** | The service could not evaluate the prompt. Guarded forwarding is blocked. |

A clean result does not mean the whole conversation is safe. The guard evaluates
the new human message, not hidden reasoning, later model output, or tool actions.

## Privacy and safety

- Traces are private to the account that owns them.
- Reading another account's trace is rejected without disclosing its metadata.
- The host operator does not gain trace or prompt access by granting gateway use.
- Prompt bodies, model outputs, tool payloads, and hidden reasoning are not part
  of the local activity timeline.
- When Langfuse export is enabled, the redacted submitted prompt and the full
  model output are sent to the configured Langfuse project. This is the only
  content-bearing path and is off by default.
- Secrets are redacted before storage where possible and before forwarding when a
  redaction rule matches.
- Authentication, gateway, event-reporting, and provider credentials are
  separate.
- Guard failure is fail-closed; observation failure is reported as incomplete,
  not as a healthy trace.

## Troubleshooting

**No verification email arrives**  
Check the SMTP configuration and the captured mail sink if one is in use. A
verification link is single-use and expires; request a new one if needed.

**Sign-in fails**  
Sign-in needs the password plus a current authenticator code or an unused
recovery code. Repeated failures cause a temporary delay, not a permanent lock.

**The gateway refuses a request**  
The account may not have gateway permission, or the permission may have been
revoked. The guard may also have denied the prompt or be waiting for approval.

**The activity page is empty**  
The agent may not be reporting through the configured integration, the trace may
belong to another account, or the activity may not have been committed yet.

**A trace says telemetry is incomplete**  
The integration could not report every event it was responsible for. The trace shows the known
cause and any known missing count or time range.

**A prompt is waiting for approval**  
Only the person who submitted it can decide. If that person cancels or the
request expires, the prompt is not forwarded.

## Scope

The first release now covers the guarded OpenAI-compatible gateway, prompt
guarding through a LangChain4j input guardrail, metadata-only activity
monitoring, native accounts with mandatory authenticator 2FA, the private
activity timeline, and optional Langfuse output logging.

It does not cover:

- private hidden chain-of-thought;
- local prompt or tool payload archives (Langfuse export is external and optional);
- output-content enforcement, buffering, or redaction;
- per-tool action governance;
- workspace tenancy or an enterprise role model;
- universal interception of agents that are not integrated;
- automatic policy changes based on monitor findings.

## Learn more

The product requirements, contracts, and acceptance material live in the sibling
`../ai-runtime-governance` repository:

- `README.md` — product direction and scope
- `REQUIREMENTS.md` — Phase 1 requirements
- `TECHNICAL_BASELINE.md` — architecture baseline
- `contracts/` — authentication and timeline contracts
- `specs/` — native-auth and activity-timeline plans
