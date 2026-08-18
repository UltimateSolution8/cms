// What every scenario in this directory shares: how to authenticate, how the load is shaped, and
// what "passing" means.
//
// It exists because the five scripts differ in exactly one interesting way each — which path they
// exercise — and duplicating the auth header and the threshold block across all five is how the
// thresholds quietly drift apart until one of them is the lenient one everybody runs.
//
// ---------------------------------------------------------------------------------------------
// The one rule this file encodes: the LOAD is configurable, the BAR is not.
//
// RATE, DURATION and VUS come from the environment so the same profile can run on a laptop and
// against production hardware. The thresholds do not, and must not. A profile whose thresholds are
// relaxed until a local run goes green is a profile that will pass forever and mean nothing — and
// the numbers it is asserting are the ones OPERATIONS.md §6 publishes to clients.
//
// So a laptop run is expected to FAIL the thresholds. That failure is the measurement. Read the
// p95 it printed; do not edit the number it was compared against.
// ---------------------------------------------------------------------------------------------

import encoding from 'k6/encoding';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const ENTITY = __ENV.ENTITY || 'DENAVE_IN';
export const PURPOSE = __ENV.PURPOSE || 'MKT_OUTBOUND_CALL';
export const NOTICE = __ENV.NOTICE || 'NOTICE_DENAVE_B2B';
export const APPLICATION = __ENV.APPLICATION || 'DENAVE_WEB';

// Basic, because that is what the platform ships with and what every current integrator uses. The
// OIDC resource server added in Phase 11 sits alongside it; when there is an IdP to mint against,
// swap this for a token and the rest of these scripts do not change — which is the point of the
// role mapping in JwtRoleConverter.
export function basic(user, password) {
  return `Basic ${encoding.b64encode(`${user}:${password}`)}`;
}

export const DECISION_AUTH = basic(
  __ENV.DECISION_CLIENT || 'athena-dialer',
  __ENV.DECISION_SECRET || 'dev',
);

export const CAPTURE_AUTH = basic(
  __ENV.CAPTURE_CLIENT || 'denave-web',
  __ENV.CAPTURE_SECRET || 'dev',
);

export function json(auth, scenario) {
  return {
    headers: { 'Content-Type': 'application/json', Authorization: auth },
    tags: { scenario },
    // A request that has been waiting a minute is not a slow request, it is a hung one, and
    // letting it sit inflates the VU count until k6 is measuring its own backlog.
    timeout: '60s',
  };
}

/**
 * Ramp, then hold.
 *
 * The plateau is what the SLO is about. The ramp is there to catch the platform that is fine right
 * up until the connection pool saturates and then is not — which is the shape almost every capacity
 * problem in a JDBC service actually has, and which a flat run at the target rate walks straight
 * past.
 *
 * `ramping-arrival-rate` rather than a VU loop on purpose: arrival rate is open-loop, so a slowing
 * server does NOT slow the load offered to it. A closed-loop VU test quietly reduces its own
 * pressure exactly when the server starts struggling, and reports a comfortable p95 for a system
 * that is falling over.
 */
export function ramp(target, overrides = {}) {
  const peak = Number(__ENV.RATE || target);
  const hold = __ENV.DURATION || '10m';
  // The lead-in is configurable for the same reason RATE is: a full four-minute ramp before every
  // one of five scenarios is twenty minutes of warm-up to collect twenty minutes of plateau, and
  // the shape it is there to expose — the pool saturating partway up — shows up in a shorter ramp
  // just as clearly. Shorten the ramp; never shorten the plateau below a minute, because the
  // plateau is the measurement.
  const rampIn = __ENV.RAMP || '1m';
  return {
    executor: 'ramping-arrival-rate',
    startRate: Math.max(1, Math.round(peak / 10)),
    timeUnit: '1s',
    preAllocatedVUs: Number(__ENV.VUS || Math.max(50, Math.round(peak / 5))),
    maxVUs: Number(__ENV.MAX_VUS || Math.max(200, peak)),
    stages: [
      { target: Math.round(peak / 4), duration: rampIn },
      { target: peak, duration: rampIn },
      { target: peak, duration: hold },
    ],
    ...overrides,
  };
}

/**
 * The published objective, asserted rather than reported.
 *
 * `p(95)<30` and `p(99)<100` are OPERATIONS.md §6 verbatim. `rate<0.0001` is the 99.99%
 * availability figure restated as a failure rate, so that changing the run length does not change
 * what is being claimed.
 *
 * A load test that prints a number and passes regardless is a report. This fails the run.
 */
export const SLO_THRESHOLDS = {
  'http_req_duration{expected_response:true}': ['p(95)<30', 'p(99)<100'],
  http_req_failed: ['rate<0.0001'],
};

/**
 * Checks every scenario wants.
 *
 * The 429 check is not defensive decoration. `uds.consent.rate-limit.decision` is 200/s per caller
 * (application.yml), so a single dialer credential offered a projected-volume load WILL be refused
 * by the platform's own limiter. That is a capacity finding about the configuration — one dialer
 * credential is not enough for one dialer — and it must not be allowed to look like a fast 200.
 */
export function standardChecks(extra = {}) {
  return {
    'answered 2xx': (r) => r.status >= 200 && r.status < 300,
    'not rate limited': (r) => r.status !== 429,
    ...extra,
  };
}

export function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}
