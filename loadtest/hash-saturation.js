// 해싱 포화 시나리오: 틀린 비밀번호 로그인(해싱 비용이 큰 요청)을 폭주시키는 동안,
// 같은 서버의 싼 요청(/users/me)과 정상 로그인이 얼마나 영향을 받는지 잰다.
//   k6 run -e ATTACK_VUS=300 -e DURATION=25s loadtest/hash-saturation.js
// 로컬 auth 는 compose.loadtest.yml 로 띄운다(IP당 rate limit 해제).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { AUTH, JSONH, PASSWORD, bearer, makeUsers } from './lib.js';

const ATTACK_VUS = parseInt(__ENV.ATTACK_VUS || '300');
const USERS = parseInt(__ENV.USERS || '600');   // 5번 틀리면 잠기므로 공격이 한 계정에 몰리지 않게 많이 둔다
const DURATION = __ENV.DURATION || '25s';
// ATTACK_RATE 를 주면 응답과 무관하게 초당 그 건수를 보낸다(실제 공격과 같은 방식). 없으면 VU 가 응답을 받는 대로 반복한다.
// 즉시 거절(503)이 가능해지면 VU 방식은 공격 속도가 저절로 불어나 수정 전과 공정하게 비교할 수 없으므로 RATE 방식을 쓴다.
const ATTACK_RATE = parseInt(__ENV.ATTACK_RATE || '0');

const meMs = new Trend('probe_me_ms', true);
const meOk = new Rate('probe_me_ok');
const loginMs = new Trend('probe_login_ms', true);
const loginOk = new Rate('probe_login_ok');        // 200 이면 성공
const loginBusy = new Counter('probe_login_503');  // 혼잡으로 거절(503)된 정상 로그인
const attack503 = new Counter('attack_503');
const attack401 = new Counter('attack_401');

export const options = {
  scenarios: {
    attack: ATTACK_RATE > 0
      ? { executor: 'constant-arrival-rate', exec: 'attack', rate: ATTACK_RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 400, maxVUs: 1500 }
      : { executor: 'constant-vus', exec: 'attack', vus: ATTACK_VUS, duration: DURATION },
    // 공격이 자리 잡은 뒤(5초)부터 잰다
    probe_me: { executor: 'constant-vus', exec: 'probeMe', vus: 3, duration: DURATION, startTime: '5s' },
    probe_login: { executor: 'constant-vus', exec: 'probeLogin', vus: 1, duration: DURATION, startTime: '5s' },
  },
  setupTimeout: '600s',
};

export function setup() {
  return { users: makeUsers(USERS, 'hs'), probe: makeUsers(5, 'hp') };
}

export function attack(d) {
  const u = d.users[Math.floor(Math.random() * d.users.length)];
  const r = http.post(`${AUTH}/api/v1/auth/login`, JSON.stringify({ email: u.email, password: 'Wrong!Pass123' }),
    { headers: JSONH, tags: { name: 'attack' }, timeout: '60s', responseCallback: http.expectedStatuses({ min: 200, max: 599 }) });
  if (r.status === 503) attack503.add(1);
  if (r.status === 401) attack401.add(1);
}

export function probeMe(d) {
  const u = d.probe[(__VU - 1) % 4];   // 0~3 은 조회 전용
  const r = http.get(`${AUTH}/api/v1/users/me`, { ...bearer(u.access), tags: { name: 'probe_me' }, timeout: '10s', responseCallback: http.expectedStatuses(200) });
  meMs.add(r.timings.duration);
  meOk.add(r.status === 200);
  sleep(0.2);
}

export function probeLogin(d) {
  // 4번 계정은 로그인 전용이다. 같은 계정으로 계속 로그인하면(같은 IP+User-Agent) 서버가 이전 세션을 끝내서
  // 조회 프로브의 세션을 끊어 버리므로 계정을 분리한다.
  const u = d.probe[4];
  const r = http.post(`${AUTH}/api/v1/auth/login`, JSON.stringify({ email: u.email, password: PASSWORD, deviceInfo: 'probe' }),
    { headers: JSONH, tags: { name: 'probe_login' }, timeout: '60s', responseCallback: http.expectedStatuses(200, 503) });
  loginMs.add(r.timings.duration);
  loginOk.add(r.status === 200);
  if (r.status === 503) loginBusy.add(1);
  sleep(1);
}
