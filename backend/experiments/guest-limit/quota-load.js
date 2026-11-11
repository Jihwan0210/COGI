import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const mode = __ENV.MODE || 'allow'; // allow: 새 토큰마다 1회, reject: 3회 사용한 토큰
const rate = Number(__ENV.RATE || 20);
const duration = __ENV.DURATION || '15s';
const quotaDuration = new Trend('quota_duration', true);
const quotaErrors = new Counter('quota_errors');

if (!['allow', 'reject'].includes(mode)) throw new Error('MODE must be allow or reject');

export const options = {
  discardResponseBodies: true,
  scenarios: {
    quota: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 40,
      maxVUs: 100,
    },
  },
  thresholds: {
    dropped_iterations: ['count==0'],
    quota_errors: ['count==0'],
  },
};

const params = {
  responseCallback: http.expectedStatuses(200, 403),
  tags: { path: 'quota', mode },
};

export function setup() {
  // setup에서 한 번만 만들어 모든 VU가 같은 실행 ID를 사용한다.
  const runId = __ENV.RUN_ID || String(Date.now());
  const exhaustedToken = `k6-${runId}-exhausted`;
  if (mode === 'reject') {
    for (let i = 0; i < 3; i++) {
      const response = http.post(`${baseUrl}/quota?token=${exhaustedToken}`, null, params);
      if (response.status !== 200) fail(`preload failed: expected 200, got ${response.status}`);
    }
  }
  return { runId, exhaustedToken };
}

export default function (data) {
  const token = mode === 'allow'
    ? `k6-${data.runId}-${exec.scenario.iterationInTest}`
    : data.exhaustedToken;
  const response = http.post(`${baseUrl}/quota?token=${token}`, null, params);
  const expected = mode === 'allow' ? 200 : 403;
  quotaDuration.add(response.timings.duration, { mode });
  if (!check(response, { 'expected quota status': (r) => r.status === expected })) {
    quotaErrors.add(1, { mode, status: String(response.status) });
  }
}
