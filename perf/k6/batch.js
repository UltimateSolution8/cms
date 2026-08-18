// The list scrub: 1,000 identifiers per request, ten requests per second.
//
// Ten thousand decisions per second through one route. This is the highest-throughput thing the
// platform does, and it is how a dialer actually works — a campaign list is scrubbed in bulk the
// night before, not one number at a time as the calls go out.
//
//   k6 run perf/k6/batch.js
//
// Two limits collide here and the run exists to find out which one binds first:
//
//   MAX_BATCH = 1000            (DecisionController.java:40)  — per request
//   batch: permits-per-second 10 (application.yml)            — per caller per second
//
// Which means the platform's own configuration caps one credential at 10,000 decisions/second, and
// that number was chosen without anyone measuring whether the platform can serve it. If this run
// meets the objective, the cap is well placed. If the platform saturates well below it, the cap is
// a limit that will never be reached and a false sense of protection.

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import {
  BASE_URL, ENTITY, PURPOSE, DECISION_AUTH, json, ramp, pick,
} from './common.js';

const subjects = new SharedArray('subjects', () => JSON.parse(open('./subjects.json')));

const BATCH_SIZE = Number(__ENV.BATCH_SIZE || 1000);

const batchLatency = new Trend('batch_latency', true);
const perDecision = new Trend('batch_per_decision_us');

export const options = {
  scenarios: { scrub: ramp(10) },
  thresholds: {
    // NOT the 30 ms decision objective, and the difference is the point. Nobody is waiting on a
    // scrub the way a dialer waits on a pre-flight — it is a batch job with a window. What matters
    // is throughput and that it finishes, so the threshold is a generous per-request ceiling and
    // the number actually worth reading is `batch_per_decision_us` below.
    'http_req_duration{expected_response:true}': ['p(95)<5000'],
    http_req_failed: ['rate<0.001'],
  },
};

export default function () {
  const batch = Array.from({ length: BATCH_SIZE }, () => ({
    entityId: ENTITY,
    subjectId: pick(subjects),
    purposeCode: PURPOSE,
    channel: 'VOICE_CALL',
    jurisdiction: 'IN',
    applicationId: 'ATHENA_DIALER',
  }));

  const response = http.post(
    `${BASE_URL}/v1/evaluate/batch`,
    JSON.stringify(batch),
    json(DECISION_AUTH, 'batch'),
  );

  batchLatency.add(response.timings.duration);
  // The comparable number. A batch is only worth having if the marginal decision inside it is
  // cheaper than a decision on its own — that is the entire justification for the route existing,
  // and it has never been checked. Compare this against decision.js's p50 in microseconds.
  perDecision.add((response.timings.duration * 1000) / BATCH_SIZE);

  check(response, {
    'answered 2xx': (r) => r.status >= 200 && r.status < 300,
    'not rate limited': (r) => r.status !== 429,
    // A short response is the failure mode that matters here and the one that looks like success:
    // a scrub that silently returns 900 answers for 1,000 numbers leaves 100 people uncontacted or
    // — depending on how the dialer treats a missing answer — contacted without a check.
    'answered every identifier': (r) => {
      const body = r.json();
      return Array.isArray(body) && body.length === BATCH_SIZE;
    },
  });
}
