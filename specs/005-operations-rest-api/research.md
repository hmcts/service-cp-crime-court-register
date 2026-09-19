# Research: operations REST API (increment 005)

Everything below was read out of the sources named, not out of a README where the two disagree.
**Three of the starters' own documents are stale, and following them produces a service that does
not start.** Each such case is marked ⚠ and says what the source actually does.

Sources:

- `/home/sachin/moj/service-cp-crime-hearing-results-validator` — the estate reference implementation
- `/home/sachin/moj/cp-auth-rules-filter` — the authorisation starter (package root
  `uk.gov.moj.cpp.authz`)
- `/home/sachin/moj/cp-audit-filter-springboot` — the audit starter (package root
  `uk.gov.hmcts.cp.filter.audit`)
- this repository's `batch/cli/*`, `config/SchedulingInfrastructureConfig`, `config/PublicEventsConfig`

---

## R1. The settings prefix is `authz.http.*`, not `auth.rules.*`

**Decision**: use `authz.http.*`.

`auth.rules.*` appears only in the auth starter's README and demo project, both stale. The live
prefix is `authz.http` (`HttpAuthzProperties`), and it is what the reference implementation sets.
The reference block, verbatim from
`service-cp-crime-hearing-results-validator/src/main/resources/application.yaml:65-78`:

```yaml
authz:
  http:
    enabled: ${AUTHZ_ENABLED:true}
    identity-url-template: ${CP_BASE_URL:http://localhost:8080}/usersgroups-query-api/query/api/rest/usersgroups/users/logged-in-user/permissions
    user-id-header: "CJSCPPUID"
    action-header: "CPP-ACTION"
    accept-header: "application/vnd.usersgroups.get-logged-in-user-permissions+json"
    drools-classpath-pattern: "classpath:/acl/**/*.drl"
    reload-on-each-request: ${AUTHZ_RELOAD_RULES:false}
    action-required: false
    deny-when-no-rules: true
    exclude-path-prefixes:
      - "/actuator"
      - "/error"
```

Defaults that matter: `enabled` defaults **false** (no `matchIfMissing`), so the filter does not
exist unless we set it true; `reload-on-each-request` defaults **true** and must be set false in
deployed environments; `deny-when-no-rules` defaults true and also governs "the DRL failed
verification" — both deny. `exclude-path-prefixes` **replaces** the library default wholesale, so
`/actuator` and `/error` have to be re-listed or the probes start answering 401.

## R2. ⚠ The auth filter does **not** derive our action names — we must supply them

**Decision**: clone the reference implementation's `ActionHeaderFilter` into `api/`, server-derived
and spoof-proof.

`RequestActionResolver` resolves the action in a strict priority: (1) a vendor token in
`Content-Type` (`application/vnd.<token>+json` → `<token>`), (2) a vendor token in `Accept`,
(3) the `CPP-ACTION` header verbatim, (4) the fallback `"<METHOD> <path>"`. Our endpoints take and
return plain `application/json`, so without help every request would fall to (4) — `"POST
/operations/batches/generate"` — which matches no rule and is therefore denied.

Priority (3) is caller-supplied and therefore spoofable: a caller authorised for
`list-batches` could send `CPP-ACTION: courtregister-operations.generate-register`. The reference
implementation closes exactly this hole with `filters/ActionHeaderFilter` at
`Ordered.HIGHEST_PRECEDENCE`, which maps path+method to an action name and wraps the request so
`getHeader("CPP-ACTION")` returns the **server's** value whatever the caller sent. We copy that
shape: a path-and-method → action map for our seven endpoints, unrecognised paths passed through
untouched.

Rejected alternative: vendor media types (`application/vnd.courtregister.<action>+json`). It would
work and needs no filter, but it makes every call from `curl` carry a hand-typed media type, changes
the endpoints' content negotiation, and makes the OpenAPI document describe a media type nothing
else in this repository uses. The filter is 60 lines and is the estate's own answer.

**Action names**: `courtregister-operations.<verb>`, one per endpoint:

| Endpoint | Action name |
|---|---|
| `GET /operations/flag` | `courtregister-operations.check-flag` |
| `GET /operations/batches` | `courtregister-operations.list-batches` |
| `GET /operations/registers/recorded-while-off` | `courtregister-operations.list-recorded-while-off` |
| `POST /operations/batches/generate` | `courtregister-operations.generate-register` |
| `POST /operations/batches/{batchId}/notify` | `courtregister-operations.notify-register` |
| `POST /operations/registers/supersede` | `courtregister-operations.supersede-before` |
| `POST /operations/exception-reports` | `courtregister-operations.report-exceptions` |

## R3. The drools rule shape, copied from the reference implementation

Two facts and one global. `Outcome` is a class with one `boolean success` starting **false**;
`Action` is a record `(String name, Map<String,Object> attributes)` whose attributes always carry
`method` and `path`; the global is
`uk.gov.moj.cpp.authz.http.providers.UserAndGroupProvider userAndGroupProvider` with the single
method `boolean isMemberOfAnyOfTheSuppliedGroups(Action, String...)`, **case-insensitive** and
any-match. There is no deny rule anywhere: **absence of a matching rule is a denial**, which is
what makes "one explicit allow rule per action" (FR-006) enforceable by reading the file.

`src/main/resources/acl/operations-rules.drl` takes the validator's exact form (no `package`
declaration — the engine synthesises a source path from the file name when there is none):

```drools
import uk.gov.moj.cpp.authz.drools.Outcome;
import uk.gov.moj.cpp.authz.drools.Action;

global uk.gov.moj.cpp.authz.http.providers.UserAndGroupProvider userAndGroupProvider;

rule "Allow - check-flag"
when
  $o: Outcome()
  $a: Action(name == "courtregister-operations.check-flag")
  eval(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups($a, "Second Line Support"))
then
  $o.setSuccess(true);
end
```

…seven of these, one per action, each naming `"Second Line Support"` and no other group.

The rules are unit-testable with no Spring, the way `acl/ValidationDroolsRulesTest` does it: build a
`KieContainer` from the classpath resource, mock the global, insert an `Outcome` and an `Action`,
fire, read `outcome.isSuccess()`. That gives us a case per action **and** the negative case (a group
that is not ours) without a context.

## R4. Identity: how it is fetched, and what happens when it cannot be

`IdentityClient` does a `GET` to `authz.http.identity-url-template` with `Accept:
application/vnd.usersgroups.get-logged-in-user-permissions+json` and the caller's id in the
`CJSCPPUID` header, and deserialises `{groups:[{groupId,groupName,prosecutingAuthority}],
switchableRoles:[],permissions:[]}`.

**It never throws.** Any exception — connection refused, timeout, 500 — is caught and answered with
an empty identity, so the caller has zero groups, every rule's `eval` is false, and the request is
**403**. Fail-closed, which is the direction we want, but indistinguishable in the response from a
genuine denial. Timeouts are hard-coded and not configurable (connect 20 s, response 21 s). There is
**no caching**: one upstream call per request.

Denial statuses, all via `HttpServletResponse.sendError`:

| Condition | Status |
|---|---|
| `CJSCPPUID` missing or blank | **401** |
| Drools said no — wrong group, no rule, DRL error, identity outage | **403** |
| `action-required=true` and the action fell through to the computed form | 400 |

## R5. ⚠ 401 and 403 bodies bypass `@RestControllerAdvice`

`sendError` forwards to `/error`, so the body is the container's, not ours: Spring Boot's default
`{"timestamp","status","error","path"}`. Two consequences:

1. The OpenAPI document must describe 401 and 403 as what they are, not as our `ProblemDetail`.
2. **The default body echoes the request path**, which for an unmapped path is a string the caller
   typed (FR-024, FR-026). So we supply an `ErrorAttributes` bean in `api/` that emits a
   `ProblemDetail`-shaped body carrying status, a bounded `reason` and nothing else — no `path`, no
   `trace`, no message. That is one small class and it closes both the echo and the stack-trace
   question in one place.

## R6. ⚠ The audit starter is **on by default** and will fail start-up

`ArtemisAuditAutoConfiguration` is gated `@ConditionalOnProperty(prefix="cp.audit", name="enabled",
havingValue="true", matchIfMissing = **true**)`. Adding the dependency therefore activates it, and
its connection-factory bean calls `validateProps`, which throws when `cp.audit.hosts` is empty or
`cp.audit.port` is not positive — **even when `audit.http.enabled=false`**.

So the settings are not optional:

- `cp.audit.hosts` / `cp.audit.port` MUST be set wherever the library is on the classpath, **or**
  `cp.audit.enabled=false` (a property with no backing field; it exists only as that conditional).
  The local and test profiles take the second route; deployed environments take the first.
- `audit.http.enabled` defaults false, and `audit.http.openapi-rest-spec` has **no default**.
  Turning HTTP audit on without that key set fails start-up: the loader globs
  `classpath*:**/*<value>` and an unset value globs for `*null`.

## R7. The OpenAPI file name is load-bearing

The audit filter resolves path parameters by matching `request.getServletPath()` against regexes
built from the spec's paths, and it finds the spec by a **suffix** glob:
`classpath*:**/*<audit.http.openapi-rest-spec>`, first match wins. `openapi.yaml` at
`src/main/resources/openapi.yaml` matches `**/*openapi.yaml`. (The reference implementation sets
`openapi.yaml` and has no such file — its spec is `openapi/openapi-spec.yml` inside a jar — so
`HTTP_AUDIT_ENABLED=true` would fail start-up there today. We avoid the trap by having the file the
setting names.)

Only paths that declare a path parameter are registered, and a path not in the spec gets
`Map.of()` — the event is published with its path parameters silently missing. That is why FR-002's
contract test runs in both directions: an endpoint missing from the document is an endpoint whose
audit event is wrong, and nothing fails to tell us.

Only one of our seven has a path parameter: `POST /operations/batches/{batchId}/notify`.

## R8. ⚠ The audit starter's `@Primary` JMS beans will hijack our `public.event` wiring

`auditConnectionFactory` (an `ActiveMQConnectionFactory`) and `auditJmsTemplate` are both
`@Primary`. This repository's `config/PublicEventsConfig` injects `ConnectionFactory` **by type**
(`PublicEventsConfig.java:125`), so once the library is on the classpath the public-event listener
container would be built on the **audit** connection factory.

**Decision**: make our own injection explicit rather than rely on ordering — `PublicEventsConfig`
takes the Boot-provided connection factory by name/qualifier, and a context test asserts that the
listener container's factory is the public-event one and not the audit one. This is the only change
005 makes to anything outside `api/`, the settings and the deletions, and it is a correctness fix
forced by the dependency rather than a design change.

Both audit beans are `@ConditionalOnMissingBean(name=…)`, so defining beans of the same names is
the alternative route; it was rejected because it would put this service in the business of building
an Artemis connection factory for somebody else's library.

## R9. ⚠ The component-scan clash is real here

The audit library's package root is `uk.gov.hmcts.cp.filter.audit`, and **this service's
`@SpringBootApplication` is at `uk.gov.hmcts.cp`** (`src/main/java/uk/gov/hmcts/cp/Application.java`).
Five Spring stereotypes survive in that library — `OpenApiSpecificationParser` (`@Component`),
`OpenApiParserProducer` (`@Configuration`), `ClasspathResourceLoader`, `PathParameterNameExtractor`
and `PathParameterValueExtractor` (`@Service`) — despite its README claiming there are none. They
would be created by **scanning**, bypassing both `cp.audit.enabled` and `audit.http.enabled`, and
`OpenApiSpecificationParser` has no no-arg constructor, so the context fails to start **even with
auditing switched off**.

**Decision**: add the reference implementation's exclude filter to `Application`, verbatim:

```java
@ComponentScan(
        basePackages = "uk.gov.hmcts.cp",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "uk\\.gov\\.hmcts\\.cp\\.filter\\.audit\\..*"
        )
)
```

`@AutoConfiguration` classes are not subject to `@ComponentScan` filters, so the auto-configuration
still runs in full. Moving `Application` down to `uk.gov.hmcts.cp.courtregister` would also work and
was rejected: it is a bigger change to a class every test context boots, and the estate convention
is the filter.

The auth starter needs no filter — its package root is `uk.gov.moj.cpp.authz`.

## R10. ⚠ The audit filter publishes request and response bodies, and every header, by default

`audit.http.include-payload-body` defaults **true** and is the library's only redaction control. It
suppresses the body alone: query parameters, path parameters and **all** request headers are
captured regardless.

**Decision**: leave `include-payload-body` at `true`, explicitly set rather than defaulted. Our
request bodies are a date, a court house, two ids, two instants and two booleans; our responses are
bounded codes, counts, identifiers and masked addresses. An audit event that did not say what was
asked would not be worth publishing — the whole point is that an override or a supersession can be
read back later.

**Flagged, not solved here**: the library captures every request header verbatim, including
`Authorization` and `Cookie`, and its own README says a header allowlist "should be agreed with the
Audit team before rolling this out broadly". That is an estate decision outside this repository; it
is recorded in the plan's risks and in the deployment gates rather than worked around locally.

Also relevant: the filter skips any URI **containing** `/health` or `/actuator` (a `contains`, not a
prefix), so actuator is out of the audit stream without configuration. It publishes a REQUEST event
before the chain and a RESPONSE event after, **and it swallows every publishing failure** — a broker
outage never fails the request and never surfaces anywhere but the log. A call that cannot be
audited therefore *does* proceed; that is the library's behaviour, we do not change it, and the
honest statement of it belongs in the plan's risks rather than in a requirement we cannot meet.

## R11. Filter order

Fixed by three numbers, and they interleave correctly by construction:

| Filter | Order |
|---|---|
| our `OperationsActionFilter` (action name) | `Ordered.HIGHEST_PRECEDENCE` |
| `HttpAuthzFilter` | `HIGHEST_PRECEDENCE + 30` (configurable, `authz.http.filter-order`) |
| `AuditFilter` | `HIGHEST_PRECEDENCE + 50` (**not** configurable) |

The audit filter runs **inside** the authorisation filter, so a denied request is not audited by
this library — the denial is in the log and in the access log, not in the audit context. Recorded
as a known limitation rather than papered over.

## R12. How SCHEDULE_RUNNING is observed

`config/SchedulingInfrastructureConfig` wires one `JdbcTemplateLockProvider` over a `shedlock`
table in the processed-log datasource, and `RegisterGenerationJob` holds
`@SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR)`.

**Decision**: read the lock, do not invent a flag. A new read-only repository asks the `shedlock`
row for the generation job's lock name whether `lock_until` is still in the future, at the clock the
rest of the service uses; the regeneration application service asks it first and refuses
`SCHEDULE_RUNNING` before anything is read or written. A second switch that said "a run is
happening" could disagree with whether one is (spec assumption 6).

## R13. Where the command classes' logic goes

Three of the six commands hold orchestration that a controller may not hold (design rules:
"Persistence … never from a listener"; a controller is an inbound adapter).

| Command | Orchestration | Lands in |
|---|---|---|
| `check-flag` | none — reads `FeatureFlagReader` | controller → existing port |
| `supersede-before` | none — one call on `RegisterStore` | controller → existing port |
| `notify-register` | none — one call on `RegisterNotifierService` | controller → existing service |
| `list-batches` | two repositories + `RegisterStore`, per-batch record counts, address masking | **new** `application/BatchListingService` |
| `generate-register` | gate, narrow, release, withhold, re-assemble, request, tally | **new** `application/RegisterRegenerationService` |
| `report-exceptions` | window computation, sink selection, e-mail refusals | **new** `application/OnDemandExceptionReportService` |

The three new services take the corresponding command class's body **unchanged** — same reads, same
order, same refusals, same values — with the argument parsing and the `Consumer<String>` printing
removed and a typed result record returned in their place. That is what "moves no logic" means here:
the diff is a move plus a return type, not a rewrite. The address masking rule moves with
`list-batches` and keeps its exact behaviour (first character of a local part longer than one, then
`***`, then the domain from the last `@`).

## R14. Testing approach

- **Drools**: `KieContainer` built from the classpath resource with the global mocked — a case per
  action for the allowed group, a case per action for a group that is not ours, and a case that a
  made-up action name is denied. No Spring.
- **Controllers**: `@WebMvcTest` slices with the application service mocked and the two filters
  **off** (`authz.http.enabled=false`, `cp.audit.enabled=false`) — the slice asserts mapping,
  status, body shape and that nothing supplied is echoed.
- **Authorisation end to end**: one `*IT` with the full context, the filters on, and the identity
  service stubbed with WireMock (`@DynamicPropertySource` over `authz.http.identity-url-template`,
  the `LoggedInUserPermissionsResponse` body shape). Cases: in the group → served; not in the group
  → 403; no `CJSCPPUID` → 401; identity service returning 500 → 403 (fail-closed); a caller sending
  a forged `CPP-ACTION` for an action they are not allowed → still refused; `/actuator/health` with
  no headers → 200.
- **Audit**: assert through the publisher seam — `AuditService` is a plain class with a
  `(JmsTemplate, ObjectMapper)` constructor and is `@ConditionalOnMissingBean`, so a test context
  can hold a stub and capture what was published. An embedded-Artemis suite is available in the
  library's own integration source set but is not published as a jar, so copying it is a cost we
  do not take: the seam proves the filter is engaged and the payload carries the caller and the
  action, which is what FR-008 asks.
- **Contract**: parse `src/main/resources/openapi.yaml` and compare against the Spring
  `RequestMappingHandlerMapping` in both directions.
- **Start-up**: `ApplicationContextRunner` cases for the two hard failures of R6 — an audit
  configuration with no hosts, and `audit.http.enabled=true` with no spec key.

## R15. Dependencies

```toml
cp-auth-rules-filter = "1.0.7"
cp-audit-filter-springboot = "1.0.5"
```

```gradle
implementation libs.cp.auth.rules.filter
implementation libs.cp.audit.filter.springboot
```

Coordinates `uk.gov.hmcts.cp:cp-auth-rules-filter:1.0.7` and
`uk.gov.hmcts.cp:cp-audit-filter-springboot:1.0.5` — the versions the reference implementation pins.
They resolve from the hmcts-lib ADO feed,
`https://pkgs.dev.azure.com/hmcts/Artifacts/_packaging/hmcts-lib/maven/v1`, which is readable
without credentials for pulls. Check for a newer version only if the build cannot resolve these.

The web starter is also required: this service currently has actuator but not
`spring-boot-starter-web` as a first-class dependency — confirm at the first build task and add it
if it is only transitive.
