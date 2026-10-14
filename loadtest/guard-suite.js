// Guard 인가 엔진 부하 테스트. 시나리오는 SCENARIO 로 고른다.
//   k6 run -e SCENARIO=check-miss -e VUS=100 -e DURATION=20s guard-suite.js
// SCENARIO: check-hit | check-miss | check-deep | check-cycle | write-race | write-check | schema | grpc-check | grpc-expand | mixed
// 기본 스키마(document/folder/group)를 쓴다. 로컬 Guard 는 보안 모드 OFF 라 토큰이 필요 없다.
import http from 'k6/http';
import grpc from 'k6/net/grpc';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const GUARD = __ENV.GUARD || 'http://localhost:28081';
const GRPC = __ENV.GRPC || 'localhost:9090';
const TOKEN = __ENV.GUARD_TOKEN || '';
const SC = __ENV.SCENARIO || 'check-hit';
const VUS = parseInt(__ENV.VUS || '50');
const N = parseInt(__ENV.SEED || '2000');
const H = { 'Content-Type': 'application/json', ...(TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {}) };
const wrong = new Counter('wrong_answer');
const bad5xx = new Counter('server_5xx');
const SETUP_RUN = __ENV.RUN_ID || String(Date.now());   // setup 에서만 쓴다. VU 는 d.run 을 쓴다(VU마다 init 시각이 달라 값이 어긋난다)

export const options = {
  scenarios: { s: { executor: 'constant-vus', vus: VUS, duration: __ENV.DURATION || '15s' } },
  setupTimeout: '600s',
  thresholds: { server_5xx: ['count==0'], wrong_answer: ['count==0'] },
};

const T = (ns, id, rel, sns, sid, srel) => ({ namespace: ns, objectId: id, relation: rel, subjectNamespace: sns, subjectId: sid, ...(srel ? { subjectRelation: srel } : {}) });
const writeTuples = (list) => { for (let i = 0; i < list.length; i += 500) { const r = http.post(`${GUARD}/api/v1/guard/tuples`, JSON.stringify(list.slice(i, i + 500)), { headers: H }); if (r.status !== 200) throw new Error(`write ${r.status} ${r.body}`); } };
const deleteTuples = (list) => { for (let i = 0; i < list.length; i += 500) http.request('DELETE', `${GUARD}/api/v1/guard/tuples`, JSON.stringify(list.slice(i, i + 500)), { headers: H }); };
const checkReq = (ns, id, rel, sid, sns = 'user') => http.post(`${GUARD}/api/v1/guard/check`, JSON.stringify({ namespace: ns, objectId: id, relation: rel, subjectNamespace: sns, subjectId: sid }), { headers: H, tags: { name: 'check' } });
const ok5 = (r) => { if (r.status >= 500 || r.status === 0) bad5xx.add(1); };

const DEPTH = parseInt(__ENV.DEPTH || '12');
function seedTuples(RUN) {
  const t = [];
  // 평면: 폴더 owner → 문서 parent (3단계 판정)
  for (let i = 0; i < N; i++) {
    t.push(T('folder', `${RUN}-f${i}`, 'owner', 'user', `${RUN}-u${i}`));
    t.push(T('document', `${RUN}-d${i}`, 'parent', 'folder', `${RUN}-f${i}`));
  }
  // 깊은 계층: 폴더 DEPTH 단계 체인, 맨 위 폴더 owner 만 사용자
  for (let k = 0; k < DEPTH; k++) t.push(T('folder', `${RUN}-deep${k}`, 'parent', 'folder', `${RUN}-deep${k + 1}`));
  t.push(T('folder', `${RUN}-deep${DEPTH}`, 'owner', 'user', `${RUN}-deepuser`));
  t.push(T('document', `${RUN}-deepdoc`, 'parent', 'folder', `${RUN}-deep0`));
  // 순환: 그룹 a ↔ b ↔ c ↔ a, 문서 editor 는 group#member
  t.push(T('group', `${RUN}-ga`, 'member', 'group', `${RUN}-gb`, 'member'));
  t.push(T('group', `${RUN}-gb`, 'member', 'group', `${RUN}-gc`, 'member'));
  t.push(T('group', `${RUN}-gc`, 'member', 'group', `${RUN}-ga`, 'member'));
  t.push(T('document', `${RUN}-cycdoc`, 'editor', 'group', `${RUN}-ga`, 'member'));
  return t;
}

export function setup() {
  const d = { run: SETUP_RUN, n: N };
  if (['check-hit', 'check-miss', 'check-deep', 'check-cycle', 'grpc-check', 'grpc-expand', 'mixed'].includes(SC)) writeTuples(seedTuples(SETUP_RUN));
  return d;
}

const gc = new grpc.Client();
if (SC.startsWith('grpc')) gc.load(['../guard/src/main/proto'], 'doro_guard.proto');   // init 컨텍스트에서만 가능
let connected = false;
const grpcClient = () => { if (!connected) { gc.connect(GRPC, { plaintext: true }); connected = true; } return gc; };

export default function (d) {
  const RUN = d.run;
  const i = Math.floor(Math.random() * d.n);
  const vu = exec.vu.idInTest;
  switch (SC) {
    case 'check-hit': {
      // 좁은 키 공간(50개) → 캐시 적중. 판정 정확성 확인
      const k = i % 50; const allow = Math.random() < 0.7;
      const r = checkReq('document', `${RUN}-d${k}`, 'viewer', allow ? `${RUN}-u${k}` : `${RUN}-u${(k + 1) % 50}`);
      ok5(r); if (check(r, { 200: (x) => x.status === 200 }) && r.json('data.allowed') !== allow) wrong.add(1);
      break;
    }
    case 'check-miss': {
      // 넓은 키 공간 + 허용/거부 섞음. 캐시가 거의 안 맞는 최악에 가까운 경로
      const allow = Math.random() < 0.5;
      const r = checkReq('document', `${RUN}-d${i}`, 'viewer', allow ? `${RUN}-u${i}` : `${RUN}-u${(i + 7) % d.n}`);
      ok5(r); if (check(r, { 200: (x) => x.status === 200 }) && r.json('data.allowed') !== allow) wrong.add(1);
      break;
    }
    case 'check-deep': {
      const r = checkReq('document', `${RUN}-deepdoc`, 'viewer', `${RUN}-deepuser`);
      ok5(r); if (check(r, { 200: (x) => x.status === 200 }) && r.json('data.allowed') !== true) wrong.add(1);
      const n = checkReq('document', `${RUN}-deepdoc`, 'viewer', `${RUN}-nobody${i}`);
      ok5(n); if (n.status === 200 && n.json('data.allowed') !== false) wrong.add(1);
      break;
    }
    case 'check-cycle': {
      // 순환 그룹: 멤버가 아닌 사용자는 반드시 거부(무한 루프 없이 종료), 서버가 멈추지 않아야 한다
      const r = checkReq('document', `${RUN}-cycdoc`, 'viewer', `${RUN}-stranger${i}`);
      ok5(r); if (check(r, { 200: (x) => x.status === 200 }) && r.json('data.allowed') !== false) wrong.add(1);
      break;
    }
    case 'write-race': {
      // 모든 VU 가 같은 튜플 20개를 반복해서 쓴다 → 멱등이어야 하고 5xx 가 없어야 한다
      const k = i % 20;
      const r = http.post(`${GUARD}/api/v1/guard/tuples`, JSON.stringify([T('document', `${RUN}-race${k}`, 'owner', 'user', `${RUN}-ru${k}`)]), { headers: H, tags: { name: 'write' } });
      ok5(r); check(r, { 'write 200': (x) => x.status === 200 });
      break;
    }
    case 'write-check': {
      // 쓰기 직후 읽기 일관성: 쓰면 허용, 지우면 거부. 캐시 무효화가 깨지면 wrong_answer
      const id = `${RUN}-wc-${vu}-${exec.vu.iterationInScenario}`;
      const tup = T('document', id, 'owner', 'user', `${id}-u`);
      let r = checkReq('document', id, 'viewer', `${id}-u`);   // 먼저 거부를 캐시에 심는다
      ok5(r); if (r.status === 200 && r.json('data.allowed') !== false) wrong.add(1);
      const w = http.post(`${GUARD}/api/v1/guard/tuples`, JSON.stringify([tup]), { headers: H, tags: { name: 'write' } });
      ok5(w); check(w, { 'write 200': (x) => x.status === 200 });
      r = checkReq('document', id, 'viewer', `${id}-u`);
      ok5(r); if (r.status === 200 && r.json('data.allowed') !== true) wrong.add(1);
      const dl = http.request('DELETE', `${GUARD}/api/v1/guard/tuples`, JSON.stringify([tup]), { headers: H, tags: { name: 'delete' } });
      ok5(dl);
      r = checkReq('document', id, 'viewer', `${id}-u`);
      ok5(r); if (r.status === 200 && r.json('data.allowed') !== false) wrong.add(1);   // 삭제 후에도 허용이면 낡은 캐시
      break;
    }
    case 'schema': {
      const r = http.get(`${GUARD}/api/v1/guard/schema`, { headers: H, tags: { name: 'schema' } });
      ok5(r); check(r, { 'schema 200': (x) => x.status === 200 });
      break;
    }
    case 'grpc-check': {
      const c = grpcClient(); const allow = Math.random() < 0.5;
      const r = c.invoke('doro.guard.v1.GuardService/Check', { namespace: 'document', objectId: `${RUN}-d${i}`, relation: 'viewer', subjectNamespace: 'user', subjectId: allow ? `${RUN}-u${i}` : `${RUN}-u${(i + 7) % d.n}` });
      if (!check(r, { 'grpc OK': (x) => x && x.status === grpc.StatusOK })) bad5xx.add(1);
      else if (r.message.allowed !== allow && !(r.message.allowed === undefined && !allow)) wrong.add(1);
      break;
    }
    case 'grpc-expand': {
      const c = grpcClient();
      const r = c.invoke('doro.guard.v1.GuardService/Expand', { namespace: 'document', objectId: `${RUN}-d${i}`, relation: 'viewer' });
      if (!check(r, { 'expand OK': (x) => x && x.status === grpc.StatusOK })) bad5xx.add(1);
      break;
    }
    case 'mixed': {
      // 실제와 비슷하게: 판정 85%, 쓰기 10%, 스키마 조회 5%
      const p = Math.random();
      if (p < 0.85) { const r = checkReq('document', `${RUN}-d${i}`, 'viewer', `${RUN}-u${i}`); ok5(r); if (r.status === 200 && r.json('data.allowed') !== true) wrong.add(1); }
      else if (p < 0.95) { const r = http.post(`${GUARD}/api/v1/guard/tuples`, JSON.stringify([T('document', `${RUN}-mx-${vu}`, 'owner', 'user', `${RUN}-mxu${i}`)]), { headers: H, tags: { name: 'write' } }); ok5(r); }
      else { const r = http.get(`${GUARD}/api/v1/guard/schema`, { headers: H, tags: { name: 'schema' } }); ok5(r); }
      break;
    }
  }
}
