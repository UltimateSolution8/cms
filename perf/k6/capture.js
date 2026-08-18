// The capture path — the write path — against distinct subjects.
//
// Every capture appends to a hash-chained ledger. That means each write reads the previous event's
// hash for that subject, computes the next one, and appends: a read-modify-write that is serialised
// PER SUBJECT by construction. Against distinct subjects there is no contention at all and this
// should scale with the pool; that is the claim, and this scenario is the first thing that has ever
// tested it.
//
// Run this one BEFORE capture-hot.js. The pair only means something together: this establishes the
// uncontended cost, and capture-hot.js establishes what contention does to it. Either number alone
// is unreadable.
//
//   k6 run perf/k6/capture.js
//
// NOTE: this writes to the ledger. Run it against a load-test database only — perf/seed.sql says
// why at length, and it is the same reason: a ledger containing fabricated consent is a ledger
// with a fatal problem no integrity verification will ever catch, because the hashes are valid.

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import {
  BASE_URL, ENTITY, PURPOSE, NOTICE, APPLICATION, CAPTURE_AUTH, json, ramp,
  standardChecks,
} from './common.js';

const captureLatency = new Trend('capture_latency', true);

export const options = {
  // 100/s, not 1,000. Capture is a person filling in a form; the decision path is a dialer
  // pre-flighting a call. Loading them identically would be measuring a workload nobody has.
  scenarios: { capture: ramp(100) },
  thresholds: {
    // Deliberately NOT the decision SLO. OPERATIONS.md §6 publishes 30 ms for the decision API and
    // makes no commitment about capture, because a form submission that takes 200 ms is invisible
    // to the person who submitted it. 250 ms is a regression tripwire, not an objective — and it is
    // labelled as one here so nobody later quotes it as though it were published.
    'http_req_duration{expected_response:true}': ['p(95)<250'],
    http_req_failed: ['rate<0.001'],
  },
};

export function subject(id) {
  return {
    entityId: ENTITY,
    subjectId: id,
    jurisdiction: 'IN',
    languageTag: 'en',
    channel: 'WEB',
    applicationId: APPLICATION,
    captureMethod: 'CHECKBOX_OPT_IN',
    actorType: 'SUBJECT',
    actorId: id,
    noticeId: NOTICE,
    noticeVersion: 1,
    choices: [{
      purposeCode: PURPOSE, granted: true, preTicked: false, separateAction: true,
    }],
    rejectAllOffered: true,
    occurredAt: new Date().toISOString(),
    // Distinct per submission. An idempotency key that repeated would make the platform correctly
    // deduplicate the load away, and the run would report an excellent p95 for a workload of
    // no-ops — which is the most convincing wrong answer this whole directory can produce.
    idempotencyKey: `perf-${__VU}-${__ITER}-${Date.now()}`,
    evidenceRef: 'evidence://perf/k6',
  };
}

export default function () {
  // Distinct subject per iteration, which is the point of this scenario: no two virtual users ever
  // contend for the same chain head.
  const id = `load-${__VU}-${__ITER}`;

  const response = http.post(
    `${BASE_URL}/v1/consent`,
    JSON.stringify(subject(id)),
    json(CAPTURE_AUTH, 'capture'),
  );

  captureLatency.add(response.timings.duration);

  check(response, standardChecks({
    // accepted:false is a 200 carrying a refusal — a validation violation, not a slow request. It
    // would otherwise be counted as a success and would make a run where the platform rejected
    // every single submission look like the fastest run ever recorded.
    'accepted': (r) => r.json('accepted') === true,
  }));
}
