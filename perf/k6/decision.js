// The decision API at volume, against the SLO OPERATIONS.md §6 publishes.
//
// 30 ms p95 and 99.99% availability were commitments in a document long before anything measured
// either. DecisionLatencyIT is one round trip on a laptop against an almost empty table inside the
// same JVM as the server — a useful ceiling against a tenfold regression, and not evidence for
// anything below it.
//
//   k6 run perf/k6/decision.js                        # laptop shape, see perf/README.md
//   k6 run -e BASE_URL=https://consent.uds.internal \
//          -e DECISION_SECRET=… -e RATE=1000 perf/k6/decision.js
//
// Seed first (perf/seed.sql) and run from ANOTHER MACHINE for any number anyone intends to quote.
// A decision against an empty table measures an index that fits entirely in cache; an in-JVM run
// omits serialisation, the socket and the connection pool, which between them are most of what a
// p95 is made of; and a load generator sharing a laptop with the database and the JVM is measuring
// three things competing for four performance cores.

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import {
  BASE_URL, ENTITY, PURPOSE, DECISION_AUTH, json, ramp, pick,
  SLO_THRESHOLDS, standardChecks,
} from './common.js';

// Written by perf/seed.sql. Real subject ids rather than generated ones: a decision about a subject
// that does not exist takes a different path — no artefact row, an early denial — and measuring
// that would be measuring the cheapest case and calling it the SLO.
const subjects = new SharedArray('subjects', () => JSON.parse(open('./subjects.json')));

const decisionLatency = new Trend('decision_latency', true);

export const options = {
  scenarios: { steady: ramp(1000) },
  thresholds: SLO_THRESHOLDS,
};

export default function () {
  const response = http.post(
    `${BASE_URL}/v1/evaluate`,
    JSON.stringify({
      entityId: ENTITY,
      subjectId: pick(subjects),
      purposeCode: PURPOSE,
      channel: 'VOICE_CALL',
      jurisdiction: 'IN',
      applicationId: 'ATHENA_DIALER',
    }),
    json(DECISION_AUTH, 'decision'),
  );

  decisionLatency.add(response.timings.duration);

  check(response, standardChecks({
    // A decision that comes back without an outcome is a decision nobody can act on, and it would
    // otherwise pass as a fast 200 — which is the shape of load-test result that makes a platform
    // look best exactly as it stops working.
    'carries an outcome': (r) => r.json('outcome') !== undefined,
  }));
}
