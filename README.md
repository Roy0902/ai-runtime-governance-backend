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
  of the activity timeline.
- Secrets are redacted before storage where possible.
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

The first release covers prompt guarding, metadata-only activity monitoring,
native accounts with mandatory authenticator 2FA, and the private activity
timeline.

It does not cover:

- private hidden chain-of-thought;
- prompt or tool payload archives;
- output-content enforcement;
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
