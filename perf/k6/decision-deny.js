// The same route, against subjects that will be refused — which is the branch that writes.
//
// This is the scenario CAPACITY.md §2 predicts will be the slower one, and the prediction is worth
// testing because it is the one that decides how the platform is sized. An allowance answers from
// consent_artefact and writes nothing. A denial writes an enforcement_decision row: the evidence
// that the platform refused, which is the whole reason the enforcement plane exists and is not
// something that can be made optional to go faster.
//
// So the suppressed fraction of a real dialer's list is secretly a write workload, and a capacity
// model built on the allow path alone under-sizes the database by whatever that fraction is. The
// seed's 15% is Denave's rough number; this scenario runs it at 100% to isolate the cost.
//
//   k6 run perf/k6/decision-deny.js
//
// Expect a higher p95 than decision.js. If it comes back the SAME, that is the finding — either the
// enforcement write is not happening or it is being deferred somewhere nobody documented, and both
// are worth knowing before an auditor asks for the denial evidence.

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import {
  BASE_URL, ENTITY, PURPOSE, DECISION_AUTH, json, ramp, pick,
  SLO_THRESHOLDS, standardChecks,
} from './common.js';

const suppressed = new SharedArray('suppressed', () =>
  JSON.parse(open('./subjects-suppressed.json')));

const denialLatency = new Trend('denial_latency', true);

export const options = {
  // Deliberately the same objective. A denial is not a slower class of request that gets its own
  // allowance — the dialer is waiting on it exactly as it waits on an allowance, and a 200 ms
  // refusal stalls a campaign just as effectively as a 200 ms permission.
  scenarios: { deny: ramp(1000) },
  thresholds: SLO_THRESHOLDS,
};

export default function () {
  const response = http.post(
    `${BASE_URL}/v1/evaluate`,
    JSON.stringify({
      entityId: ENTITY,
      subjectId: pick(suppressed),
      purposeCode: PURPOSE,
      channel: 'VOICE_CALL',
      jurisdiction: 'IN',
      applicationId: 'ATHENA_DIALER',
    }),
    json(DECISION_AUTH, 'decision-deny'),
  );

  denialLatency.add(response.timings.duration);

  check(response, standardChecks({
    // The assertion that makes this scenario the thing it claims to be. If these subjects start
    // coming back ALLOW, the seed's suppression rows are not being read and this run has been
    // measuring the allow path under a different filename.
    'refused, as seeded': (r) => r.json('outcome') === 'DENY',
    // Named, because "why" is the field an auditor asks for and the field a cache would drop.
    'carries a reason': (r) => !!r.json('reason'),
  }));
}
