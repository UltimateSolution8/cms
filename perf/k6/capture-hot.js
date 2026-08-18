// Deliberately pathological: the same 100 writes per second, spread over TWENTY subjects.
//
// This is the most useful script in the directory, and the only one testing a claim the
// architecture makes rather than a number a document publishes.
//
// The ledger is hash-chained per subject. Appending an event reads the previous hash for that
// subject, computes the next, and writes — a read-modify-write that MUST be serialised, or two
// concurrent captures both chain off the same predecessor and the chain forks. The platform's
// answer is a per-subject lock (the unique constraint on (entity_id, subject_id, sequence_number)
// is the backstop; the lock is what stops it becoming an error path).
//
// "Per subject" is the entire claim. If it is really per subject, this run looks like capture.js
// with somewhat worse tail latency. If the serialisation is in fact coarser than advertised —
// per entity, or a table-level lock, or an unlucky index — this run collapses and capture.js
// would never have shown it, because capture.js gives every virtual user its own subject and so
// can never make two of them collide.
//
//   k6 run perf/k6/capture.js       # first: the uncontended cost
//   k6 run perf/k6/capture-hot.js   # then: what contention does to it
//
// Twenty subjects at 100/s is five writes per second per chain. Real traffic does not look like
// this. It is not meant to — it is meant to make the lock visible, and a load test that only ever
// exercises the comfortable case is a load test that will be surprised in production.
//
// WHAT A FAILURE HERE MEANS. Errors, not slowness, are the interesting outcome: a 409 or a 500
// from a constraint violation says two captures raced and the platform noticed, which is correct
// behaviour and a capacity limit at the same time. Record the rate. Silent success at the same
// latency as capture.js means the lock is doing its job.

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import {
  BASE_URL, CAPTURE_AUTH, json, ramp, pick, standardChecks,
} from './common.js';
import { subject } from './capture.js';

// Twenty. Small enough to guarantee collisions at any interesting rate, large enough that the run
// is not measuring one row's lock queue and calling it the system.
const HOT = Array.from({ length: Number(__ENV.HOT_SUBJECTS || 20) },
  (_, i) => `hot-${String(i).padStart(3, '0')}`);

const hotLatency = new Trend('capture_hot_latency', true);
const conflicts = new Rate('capture_conflicts');

export const options = {
  scenarios: { hot: ramp(100) },
  thresholds: {
    // Looser than capture.js, and honestly so: contention costs something and pretending otherwise
    // would make this scenario fail for doing exactly what it was written to do. What is NOT
    // relaxed is the error rate — a lock that serialises is fine, a lock that starts rejecting
    // writes is a different thing entirely and the reason this threshold is here.
    'http_req_duration{expected_response:true}': ['p(95)<1000'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const response = http.post(
    `${BASE_URL}/v1/consent`,
    JSON.stringify(subject(pick(HOT))),
    json(CAPTURE_AUTH, 'capture-hot'),
  );

  hotLatency.add(response.timings.duration);
  conflicts.add(response.status === 409 || response.status >= 500);

  check(response, standardChecks({
    // A fork would show up as a 500 from the unique constraint, or — far worse — as a 200 that
    // wrote a second event at the same sequence number. The first is a capacity finding; the
    // second would be an integrity finding, and the nightly integrity sweep would be the thing
    // that told us, days later.
    'no chain conflict': (r) => r.status !== 409 && r.status < 500,
  }));
}
