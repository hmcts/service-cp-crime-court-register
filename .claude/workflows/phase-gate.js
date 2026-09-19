export const meta = {
  name: 'phase-gate',
  description: 'Implement one task range test-first, gate it with three read-only reviewers, remediate, repeat until every gate passes',
  whenToUse: 'One phase of an increment in this repo: a contiguous task range from specs/<n>/tasks.md, implemented in a named tree by a sole committer, then reviewed by code-reviewer, qa and spec-validator, then fixed, until PASS / PASS / COMPLIANT. Pass codex: true at whole-increment gates.',
  phases: [
    { title: 'Implement', detail: 'one implementer over the task range, TDD, sole committer', model: 'opus' },
    { title: 'Gate', detail: 'code-reviewer, qa and spec-validator, read-only, in parallel' },
    { title: 'Remediate', detail: 'one fixer per round over the surviving findings', model: 'opus' },
    { title: 'Codex', detail: 'external review of the whole range, when asked for' },
  ],
}

// ---------------------------------------------------------------------------------------------
// args (all paths absolute):
//   tree        the git tree to work in (main checkout or a worktree)        required
//   specDir     e.g. <tree>/specs/004-release-stale-batches                    required
//   tasks       task ids in order, e.g. ["T007","T008","T009"]                 required
//   baseCommit  the commit the range starts from (gate diffs baseCommit..HEAD) required
//   lock        the gradle lock file, e.g. <scratchpad>/gradle.lock            required
//   maxRounds   gate → remediate rounds before giving up (default 3)
//   codex       true to add the Codex review after the gates pass (default false)
//   contract    coordination contract text: files this tree may / may not touch (optional)
//   notes       anything else the implementer must know (optional)
// ---------------------------------------------------------------------------------------------

const a = args || {}
for (const k of ['tree', 'specDir', 'tasks', 'baseCommit', 'lock']) {
  if (!a[k]) throw new Error(`phase-gate: args.${k} is required`)
}
const TASKS = Array.isArray(a.tasks) ? a.tasks : String(a.tasks).split(/[,\s]+/).filter(Boolean)
const MAX_ROUNDS = a.maxRounds || 3
const RANGE = `${a.baseCommit}..HEAD`

const RULES = `
Work ONLY in the tree ${a.tree}: cd there for every command and use absolute paths.
Every Gradle invocation goes behind the lock: flock -w 7200 ${a.lock} ./gradlew <args>
  (two concurrent builds kill the Testcontainers workers; the lock lets several agents exist while one builds).
You are the SOLE COMMITTER in this tree: nobody else commits there while you run. Commit with Conventional
Commits, no AI attribution anywhere (no Co-Authored-By, no mention of Claude or automated generation).
Check git status for stray files (shell-redirection junk in the repo root) before every commit.
${a.contract ? `COORDINATION CONTRACT (files another tree owns - do not touch them):\n${a.contract}` : ''}
${a.notes ? `NOTES FROM THE ORCHESTRATOR:\n${a.notes}` : ''}
`

const FINDING = {
  type: 'object',
  properties: {
    severity: { type: 'string', enum: ['BLOCKER', 'HIGH', 'MEDIUM', 'LOW'] },
    file: { type: 'string' },
    line: { type: 'integer' },
    summary: { type: 'string' },
    fix_hint: { type: 'string' },
  },
  required: ['severity', 'file', 'summary'],
}
const REVIEW = {
  type: 'object',
  properties: {
    verdict: { type: 'string' },
    findings: { type: 'array', items: FINDING },
    notes: { type: 'string' },
  },
  required: ['verdict', 'findings'],
}
const WORK = {
  type: 'object',
  properties: {
    commits: { type: 'array', items: { type: 'object', properties: { sha: { type: 'string' }, subject: { type: 'string' } }, required: ['sha', 'subject'] } },
    tasks_done: { type: 'array', items: { type: 'string' } },
    build_exit_code: { type: 'integer' },
    build_summary: { type: 'string' },
    open_points: { type: 'array', items: { type: 'string' } },
  },
  required: ['commits', 'tasks_done', 'build_exit_code', 'build_summary'],
}

// ---- Implement -------------------------------------------------------------------------------
phase('Implement')
log(`phase-gate: ${TASKS[0]}..${TASKS[TASKS.length - 1]} in ${a.tree}`)

const implemented = await agent(`
You are the implementer for one phase of this repository. Read ${a.tree}/.claude/agents/software-engineer.md
first and obey it, then read ${a.specDir}/spec.md, plan.md, tasks.md (and research.md, data-model.md,
quickstart.md, contracts/ where present).

Implement EXACTLY these tasks, in this order, and nothing else: ${TASKS.join(', ')}.
For every task pair (test task, implementation task): land the compile-safe seams, write the failing test,
run it and record the RED run (a failing assertion, never a compile error) in tasks.md against the task,
then the minimum production code, then the GREEN run quoted the same way. One commit per task or task pair,
the test at or before the production code in every commit. Tick each task in tasks.md in the commit that
completes it.
When the range is done run the full build behind the lock: flock -w 7200 ${a.lock} ./gradlew build
(this runs the suite, Checkstyle, PMD and the JaCoCo gate). It must exit 0. If it does not, fix it before
returning, still test-first.
${RULES}
Return: the commits you made (sha + subject, oldest first), the task ids completed, the build exit code and a
two-line build summary (tests run / failed, coverage line+branch), and any open points you could not settle.
`, { label: `implement ${TASKS[0]}-${TASKS[TASKS.length - 1]}`, phase: 'Implement', model: 'opus', agentType: 'general-purpose', schema: WORK })

if (!implemented) throw new Error('phase-gate: the implementer returned nothing')
log(`implemented ${implemented.tasks_done.length}/${TASKS.length} tasks in ${implemented.commits.length} commits; build exit ${implemented.build_exit_code}`)

// ---- Gate → Remediate loop ---------------------------------------------------------------------
const REVIEWERS = [
  {
    key: 'code-reviewer', agentFile: '.claude/agents/code-reviewer.md', pass: 'PASS',
    ask: 'Review the range for logic errors, null safety, ports-and-adapters violations, swallowed exceptions, unsettled message paths, secrets, PII in logs, System.out, and the repo rules. Verdict PASS or NEEDS CHANGES.',
  },
  {
    key: 'qa', agentFile: '.claude/agents/qa.md', pass: 'PASS',
    ask: 'Judge the tests that exist against your test matrix, verify TDD discipline from the commit order and the recorded red/green runs in tasks.md, and name every coverage gap. Verdict PASS or FAIL.',
  },
  {
    key: 'spec-validator', agentFile: '.claude/agents/spec-validator.md', pass: 'COMPLIANT',
    ask: 'Check the range against the message contracts, the vendored schemas, settlement discipline, state-machine completeness, the defect-fix register and the constitution. Verdict COMPLIANT or DRIFT DETECTED.',
  },
]

function reviewPrompt(r, round) {
  return `
You are the ${r.key} for this repository: read ${a.tree}/${r.agentFile} first and obey it. You are READ-ONLY:
never create, modify or delete a file. Work in ${a.tree} (cd there; absolute paths).
Scope: the commits ${RANGE} (git log --oneline ${RANGE}; git diff ${a.baseCommit}..HEAD), which implement tasks
${TASKS.join(', ')} of ${a.specDir}/tasks.md. Read spec.md and plan.md there for intent.
${r.ask}
The implementer's build has just finished: read its results from ${a.tree}/build/test-results/test/*.xml and
${a.tree}/build/reports/jacoco/test/jacocoTestReport.xml FIRST, and do not run any Gradle for the first
10 minutes of your review so those results survive for the other reviewers. If you must run Gradle after that,
use: flock -w 7200 ${a.lock} ./gradlew <args>.
${round > 1 ? `This is gate round ${round}: earlier findings were remediated in the newest commits of the range; re-check them and look for regressions.` : ''}
Return the verdict word exactly as your agent file defines it, and every finding with severity, file, line,
a one-sentence summary and a fix hint. No finding without a file.
`
}

async function gate(round) {
  const results = await parallel(REVIEWERS.map(r => () =>
    agent(reviewPrompt(r, round), { label: `${r.key} r${round}`, phase: 'Gate', agentType: 'general-purpose', schema: REVIEW })
      .then(v => ({ ...r, result: v }))))
  const out = results.filter(Boolean)
  for (const r of out) {
    const n = (r.result && r.result.findings || []).length
    log(`gate r${round} ${r.key}: ${r.result ? r.result.verdict : 'no result'} (${n} findings)`)
  }
  return out
}

function blocking(findings) {
  return findings.filter(f => f.severity !== 'LOW')
}

let round = 1
let reviews = await gate(round)
let history = [{ round, reviews: reviews.map(r => ({ key: r.key, verdict: r.result && r.result.verdict, findings: r.result ? r.result.findings.length : null })) }]
let commits = [...implemented.commits]

function allPass(rs) {
  return rs.length === REVIEWERS.length && rs.every(r => r.result && r.result.verdict.trim().toUpperCase().startsWith(r.pass)
    && blocking(r.result.findings).length === 0)
}

while (!allPass(reviews) && round < MAX_ROUNDS) {
  const findings = reviews.flatMap(r => (r.result ? r.result.findings : []).map(f => ({ ...f, from: r.key })))
  const must = blocking(findings)
  const may = findings.filter(f => f.severity === 'LOW')
  phase('Remediate')
  const fixed = await agent(`
You are the remediation implementer for one gate round. Read ${a.tree}/.claude/agents/software-engineer.md first
and obey it. Work in ${a.tree}. The range ${RANGE} implements tasks ${TASKS.join(', ')} of ${a.specDir}/tasks.md.
Three read-only reviewers returned these findings. Fix EVERY one marked BLOCKER, HIGH or MEDIUM; fix a LOW one
only if it is cheap and safe. A finding you disagree with is not skipped silently: leave it and say why in
open_points, quoting the reviewer.
MUST FIX:
${JSON.stringify(must, null, 2)}
MAY FIX:
${JSON.stringify(may, null, 2)}
Every fix that changes behaviour lands test-first (red then green, recorded in tasks.md when it belongs to a
task). Commit with Conventional Commits; small commits, one concern each. When done run
flock -w 7200 ${a.lock} ./gradlew build and make it exit 0.
${RULES}
Return the commits you made, the task ids touched, the build exit code and summary, and open_points for
anything left unfixed with the reason.
`, { label: `remediate r${round}`, phase: 'Remediate', model: 'opus', agentType: 'general-purpose', schema: WORK })
  if (fixed) commits.push(...fixed.commits)
  log(`remediate r${round}: ${fixed ? fixed.commits.length : 0} commits, build exit ${fixed ? fixed.build_exit_code : 'n/a'}`)
  round += 1
  reviews = await gate(round)
  history.push({ round, reviews: reviews.map(r => ({ key: r.key, verdict: r.result && r.result.verdict, findings: r.result ? r.result.findings.length : null })) })
}

// ---- Codex (optional, whole-increment gates) -----------------------------------------------------
let codex = null
if (a.codex && allPass(reviews)) {
  phase('Codex')
  codex = await agent(`
Load the Codex MCP tool with ToolSearch ("select:mcp__codex__codex") and ask Codex to review the commits
${RANGE} in ${a.tree} (give it: cd ${a.tree}; git diff ${a.baseCommit}..HEAD; the files
${a.specDir}/spec.md and plan.md; and ${a.tree}/.claude/rules/design_rules.md). Ask specifically for: swallowed
or misclassified exceptions, state written after settling, races between the scheduler and any other writer,
window/boundary errors, telemetry that claims something before it happened, and anything the constitution
forbids. You are read-only. Return Codex's findings in the schema, verdict PASS if it found nothing above LOW.
`, { label: 'codex review', phase: 'Codex', agentType: 'general-purpose', schema: REVIEW })
  if (codex) log(`codex: ${codex.verdict} (${codex.findings.length} findings)`)
  if (codex && blocking(codex.findings).length > 0 && round < MAX_ROUNDS + 1) {
    phase('Remediate')
    const fixed = await agent(`
You are the remediation implementer after the Codex review. Read ${a.tree}/.claude/agents/software-engineer.md first
and obey it. Work in ${a.tree}; the range is ${RANGE} (tasks ${TASKS.join(', ')} of ${a.specDir}/tasks.md).
Fix every finding below marked BLOCKER, HIGH or MEDIUM, test-first, small Conventional Commits, then
flock -w 7200 ${a.lock} ./gradlew build exiting 0. Disagreements go in open_points with the reason.
${JSON.stringify(blocking(codex.findings), null, 2)}
${RULES}
Return commits, task ids touched, build exit code and summary, open_points.
`, { label: 'remediate codex', phase: 'Remediate', model: 'opus', agentType: 'general-purpose', schema: WORK })
    if (fixed) commits.push(...fixed.commits)
    round += 1
    reviews = await gate(round)
    history.push({ round, reviews: reviews.map(r => ({ key: r.key, verdict: r.result && r.result.verdict, findings: r.result ? r.result.findings.length : null })) })
  }
}

const unresolved = reviews.flatMap(r => (r.result ? blocking(r.result.findings) : []).map(f => ({ ...f, from: r.key })))
if (codex) unresolved.push(...blocking(codex.findings).map(f => ({ ...f, from: 'codex' })))
log(`phase-gate done: ${allPass(reviews) ? 'ALL GATES PASS' : 'GATES NOT PASSED'} after ${round} round(s); ${unresolved.length} unresolved blocking findings`)

return {
  tasks: TASKS,
  tree: a.tree,
  range: RANGE,
  passed: allPass(reviews) && unresolved.length === 0,
  rounds: round,
  history,
  commits,
  implementer_open_points: implemented.open_points || [],
  unresolved,
  codex: codex ? { verdict: codex.verdict, findings: codex.findings } : null,
}
