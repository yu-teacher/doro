// 영구 탈퇴 동기화 시험(브라우저 없음). run-deleted-sync.sh 가 격리 스택·도로 파티·도로 게임을 띄운 뒤 실행한다.
// 흐름: 계정·OAuth 클라이언트 준비 → 파티/게임에서 데이터 만들기 → IAM 탈퇴 요청 → 유예 앞당기기(로컬 DB) → IAM 익명화 → 서비스 동기화 → 검증.
// 비밀번호는 실행마다 무작위이며 .state/deleted-sync.json(권한 600)에만 쓴다. 로그에는 이메일·ID 만 나온다.
import { randomBytes, createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, chmodSync, readFileSync, existsSync } from 'node:fs';

const env = process.env;
const need = (k) => env[k] ?? (() => { throw new Error(`환경변수 ${k} 가 없다(run-deleted-sync.sh 로 실행한다)`); })();
const IAM = `http://localhost:${need('E2E_AUTH_PORT')}`;
const GUARD = `http://localhost:${need('E2E_GUARD_HTTP_PORT')}`;
const S3 = `http://localhost:${need('E2E_S3_PORT')}`;
const PARTY = `http://localhost:${need('E2E_PARTY_PORT')}`;
const GAMES = `http://localhost:${need('E2E_GAMES_PORT')}`;
const STATE = new URL('./.state/', import.meta.url).pathname;
const PARTY_LOG = `${STATE}party.log`;
const GAMES_LOG = `${STATE}games.log`;
const SYNC_WAIT_MS = 120_000;

// ---------- 작은 시험 도구 ----------
const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok });
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${!ok && detail ? `  -> ${detail}` : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(label, fn, timeoutMs = 60_000, everyMs = 1000) {
  const end = Date.now() + timeoutMs;
  let last;
  while (Date.now() < end) {
    try { last = await fn(); if (last) return last; } catch (e) { last = e; }
    await sleep(everyMs);
  }
  throw new Error(`시간 초과: ${label}${last instanceof Error ? ` (${last.message})` : ''}`);
}
const section = (t) => console.log(`\n== ${t} ==`);

// ---------- 로컬 DB(격리 스택의 e2e-postgres 컨테이너 전용) ----------
function sql(db, query) {
  const out = execFileSync('docker', ['exec', 'e2e-postgres', 'psql', '-U', 'doro_admin', '-d', db, '-At', '-F', '|', '-c', query],
    { encoding: 'utf8' });
  return out.trim();
}
const rows = (db, query) => { const o = sql(db, query); return o ? o.split('\n').map((l) => l.split('|')) : []; };
const count = (db, query) => Number(sql(db, query));

// ---------- HTTP ----------
async function call(base, method, path, { token, body, form, multipart, headers = {} } = {}) {
  const h = { ...headers };
  if (token) h.Authorization = `Bearer ${token}`;
  let payload;
  if (body !== undefined) { h['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  if (form) { h['Content-Type'] = 'application/x-www-form-urlencoded'; payload = new URLSearchParams(form).toString(); }
  if (multipart) payload = multipart;
  const res = await fetch(base + path, { method, headers: h, body: payload });
  const text = await res.text();
  let json; try { json = text ? JSON.parse(text) : {}; } catch { json = { raw: text }; }
  return { status: res.status, json, text };
}
async function ok(base, method, path, opts, expect = [200, 201]) {
  const r = await call(base, method, path, opts);
  if (!expect.includes(r.status)) throw new Error(`${method} ${path} -> HTTP ${r.status} ${r.text.slice(0, 200)}`);
  return r.json.data ?? r.json;
}

const tag = randomBytes(3).toString('hex');
const randomPassword = () => `${randomBytes(12).toString('base64url')}Aa1!`;
const jwtSub = (jwt) => JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString()).sub;
const jwtCid = (jwt) => JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString()).cid;

// ---------- IAM: 계정·클라이언트·토큰 ----------
async function signup(email, password, name) {
  await ok(IAM, 'POST', '/api/v1/auth/signup', { body: { email, password, name } });
}
async function iamLogin(email, password) {
  const d = await ok(IAM, 'POST', '/api/v1/auth/login', { body: { email, password, deviceInfo: 'e2e-deleted-sync' } });
  return d.tokens.accessToken;
}
/** 브라우저 없이 인가 코드 + PKCE: 포털 동의 화면이 하는 일(Bearer 로 authorize → code)을 API 로 한다. */
async function oauthToken(clientId, redirectUri, email, password) {
  const userToken = await iamLogin(email, password);
  const verifier = randomBytes(48).toString('base64url');
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  const state = randomBytes(8).toString('hex');
  const q = new URLSearchParams({
    client_id: clientId, redirect_uri: redirectUri, response_type: 'code', scope: 'openid profile email',
    state, nonce: randomBytes(8).toString('hex'), code_challenge: challenge, code_challenge_method: 'S256',
  });
  const auth = await ok(IAM, 'GET', `/oauth2/authorize?${q}`, { token: userToken });
  if (auth.state !== state) throw new Error('authorize 응답의 state 가 다르다');
  const tok = await call(IAM, 'POST', '/oauth2/token', {
    form: { grant_type: 'authorization_code', code: auth.code, redirect_uri: redirectUri, client_id: clientId, code_verifier: verifier },
  });
  if (tok.status !== 200) throw new Error(`token 교환 실패 HTTP ${tok.status} ${tok.text.slice(0, 200)}`);
  return tok.json.access_token;
}

// ---------- 작은 PNG(1x1) ----------
const PNG_1x1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
function photoForm(seed) {
  // 내용이 서로 다른 PNG(끝에 주석 바이트 추가는 형식 판별에 영향이 없다)
  const fd = new FormData();
  fd.append('file', new Blob([PNG_1x1, Buffer.from(seed)], { type: 'image/png' }), `${seed}.png`);
  return fd;
}

async function s3Keys(bucket) {
  const res = await fetch(`${S3}/${bucket}?list-type=2`);
  const xml = await res.text();
  return [...xml.matchAll(/<Key>([^<]+)<\/Key>/g)].map((m) => m[1]);
}
async function guardAllowed(namespace, objectId, relation, subjectId, subjectNamespace = 'user', subjectRelation) {
  const r = await ok(GUARD, 'POST', '/api/v1/guard/check', {
    body: { namespace, objectId, relation, subjectNamespace, subjectId, ...(subjectRelation ? { subjectRelation } : {}) },
  });
  return r.allowed === true;
}
const logLines = (file) => (existsSync(file) ? readFileSync(file, 'utf8').split('\n') : []);
const errorLines = (file) => logLines(file).filter((l) => /\bERROR\b/.test(l));

// =====================================================================================================
const state = { startedAt: new Date().toISOString() };
const redirect = (port) => `http://localhost:${port}/cb`;
try {
  section('1. 계정·OAuth 클라이언트 준비');
  const admin = { email: `ds-admin-${tag}@doro.local`, password: randomPassword() };
  await signup(admin.email, admin.password, 'DS Admin');
  const first = await iamLogin(admin.email, admin.password);
  await ok(IAM, 'POST', '/api/v1/auth/bootstrap', { token: first, body: { token: need('E2E_BOOTSTRAP_TOKEN') } });
  const adminToken = await iamLogin(admin.email, admin.password);
  for (const [clientId, port, name] of [['doro-party', env.E2E_PARTY_PORT, 'E2E Party'], ['doro-games', env.E2E_GAMES_PORT, 'E2E Games']]) {
    await ok(IAM, 'POST', '/api/v1/admin/oauth/clients', {
      token: adminToken,
      body: { name, clientId, firstParty: true, redirectUris: [redirect(port)], scopes: ['openid', 'profile', 'email'] },
    });
  }
  const del = { email: `ds-del-${tag}@doro.local`, password: randomPassword(), username: `dsdel_${tag}`, nickname: 'DelNick' };
  const fr = { email: `ds-friend-${tag}@doro.local`, password: randomPassword(), username: `dsfriend_${tag}`, nickname: 'FriendNick' };
  await signup(del.email, del.password, 'DS Del');
  await signup(fr.email, fr.password, 'DS Friend');
  mkdirSync(STATE, { recursive: true });
  const stateFile = `${STATE}deleted-sync.json`;
  writeFileSync(stateFile, JSON.stringify({ admin, del, friend: fr }, null, 2));
  chmodSync(stateFile, 0o600);

  const delParty = await oauthToken('doro-party', redirect(env.E2E_PARTY_PORT), del.email, del.password);
  const delGames = await oauthToken('doro-games', redirect(env.E2E_GAMES_PORT), del.email, del.password);
  const frParty = await oauthToken('doro-party', redirect(env.E2E_PARTY_PORT), fr.email, fr.password);
  del.id = jwtSub(delParty); fr.id = jwtSub(frParty);
  check('브라우저 없이 OAuth(인가 코드+PKCE) 토큰 발급, cid 가 서비스 클라이언트',
    jwtCid(delParty) === 'doro-party' && jwtCid(delGames) === 'doro-games' && jwtCid(frParty) === 'doro-party', `cid=${jwtCid(delParty)}`);
  console.log(`  del=${del.email} (${del.id})  friend=${fr.email} (${fr.id})`);
  const PDB = 'service_party', GDB = 'service_games', ADB = 'doro_auth', GUARDDB = 'doro_guard';

  section('2. 도로 파티: 데이터 만들기 (del-user / friend-user)');
  for (const [u, t] of [[del, delParty], [fr, frParty]]) {
    await ok(PARTY, 'GET', '/api/v1/me', { token: t });
    await ok(PARTY, 'PUT', '/api/v1/me', { token: t, body: { nickname: u.nickname, username: u.username } });
  }
  // 친구: friend 가 초대 링크를 만들고 del 이 수락
  const invite = await ok(PARTY, 'POST', '/api/v1/friends/invite', { token: frParty });
  await ok(PARTY, 'POST', `/api/v1/friends/invite/${invite.code}/accept`, { token: delParty });
  const mkMap = (t, name) => ok(PARTY, 'POST', '/api/v1/maps', { token: t, body: { name, description: name } }).then((m) => m.id);
  const mkPin = (t, mapId, name) => ok(PARTY, 'POST', `/api/v1/maps/${mapId}/pins`, {
    token: t, body: { name, sharedMemo: `memo ${name}`, lat: 37.5665, lng: 126.978, status: 'WISH' },
  }).then((p) => p.id);
  const mapA = await mkMap(delParty, 'A solo (del only)');
  const mapB = await mkMap(delParty, 'B shared with friend');
  const mapD = await mkMap(delParty, 'D friend-access');
  const mapE = await mkMap(frParty, 'E friend map shared to del');
  const pinA = await mkPin(delParty, mapA, 'pin A');
  const pinB = await mkPin(delParty, mapB, 'pin B');
  await ok(PARTY, 'PUT', `/api/v1/maps/${mapB}/shares/${fr.id}`, { token: delParty, body: { role: 'EDITOR' } });
  const pinBf = await mkPin(frParty, mapB, 'pin B by friend');
  await ok(PARTY, 'PUT', `/api/v1/maps/${mapD}/friend-access`, { token: delParty, body: { access: 'EDITOR' } });
  const pinDf = await mkPin(frParty, mapD, 'pin D by friend');
  await ok(PARTY, 'PUT', `/api/v1/maps/${mapE}/shares/${del.id}`, { token: frParty, body: { role: 'VIEWER' } });
  const pinE = await mkPin(frParty, mapE, 'pin E by friend');
  // 사적 메모(del: A·B 핀, friend: E 핀 = 음성 대조)
  await ok(PARTY, 'PUT', `/api/v1/maps/${mapA}/pins/${pinA}/private-note`, { token: delParty, body: { body: 'del secret A' } });
  await ok(PARTY, 'PUT', `/api/v1/maps/${mapB}/pins/${pinB}/private-note`, { token: delParty, body: { body: 'del secret B' } });
  await ok(PARTY, 'PUT', `/api/v1/maps/${mapE}/pins/${pinE}/private-note`, { token: frParty, body: { body: 'friend secret E' } });
  // 사진: del 이 A·B 에, friend 가 E 에(음성 대조)
  const photoA = await ok(PARTY, 'POST', `/api/v1/maps/${mapA}/pins/${pinA}/photos`, { token: delParty, multipart: photoForm('del-a') });
  const photoB = await ok(PARTY, 'POST', `/api/v1/maps/${mapB}/pins/${pinB}/photos`, { token: delParty, multipart: photoForm('del-b') });
  const photoE = await ok(PARTY, 'POST', `/api/v1/maps/${mapE}/pins/${pinE}/photos`, { token: frParty, multipart: photoForm('friend-e') });
  // del 의 댓글(friend 지도 E 의 핀: viewer 로 쓴 댓글은 남아야 한다)
  await ok(PARTY, 'POST', `/api/v1/maps/${mapE}/pins/${pinE}/comments`, { token: delParty, body: { body: 'del comment on E' } });

  const photoKeys = (uid) => rows(PDB, `SELECT object_key FROM pin_photos WHERE uploaded_by='${uid}'`).map((r) => r[0]);
  const delKeys = photoKeys(del.id), frKeys = photoKeys(fr.id);
  const bucketBefore = await s3Keys('doro-party-media');
  check('사전: 사진 3장이 S3 목에 있다', delKeys.length === 2 && frKeys.length === 1 && [...delKeys, ...frKeys].every((k) => bucketBefore.includes(k)),
    `del=${delKeys.length} friend=${frKeys.length} bucket=${bucketBefore.length}`);
  check('사전: 친구 관계 1쌍, 공유 행(B→friend, E→del) 존재',
    count(PDB, `SELECT count(*) FROM friendships WHERE status='ACCEPTED'`) === 1 && count(PDB, `SELECT count(*) FROM map_user_shares`) === 2);
  const tuplesBefore = count(GUARDDB, `SELECT count(*) FROM relation_tuples WHERE subject_id='${del.id}' OR object_id='${del.id}'`);
  check('사전: del-user 의 Guard 튜플이 있다', tuplesBefore > 0, `count=${tuplesBefore}`);

  section('3. 도로 게임: 프로필·점수');
  await ok(GAMES, 'GET', '/api/v1/me', { token: delGames });
  await ok(GAMES, 'PUT', '/api/v1/me', { token: delGames, body: { nickname: 'DelGamer', username: `dsgame_${tag}` } });
  let scored = false;
  try {
    const run = await ok(GAMES, 'POST', '/api/v1/games/runner/runs', { token: delGames });
    await sleep(3000);
    await ok(GAMES, 'POST', `/api/v1/games/runner/runs/${run.runId}/score`, { token: delGames, body: { score: 15 } });
    scored = true;
  } catch (e) { console.log(`  (점수 기록 생략: ${e.message})`); }
  check('사전: games 프로필(닉네임)이 있다', count(GDB, `SELECT count(*) FROM game_users WHERE id='${del.id}' AND nickname='DelGamer'`) === 1);
  if (scored) check('사전: games 최고 기록이 있다', count(GDB, `SELECT count(*) FROM game_records WHERE user_id='${del.id}'`) === 1);

  section('4. IAM: 탈퇴 요청');
  const wrong = await call(IAM, 'POST', '/api/v1/users/me/deletion', { token: await iamLogin(del.email, del.password), body: { password: 'wrong-Password-1!' } });
  check('틀린 비밀번호는 탈퇴 요청이 거부된다(401)', wrong.status === 401, `HTTP ${wrong.status}`);
  const delLogin = await iamLogin(del.email, del.password);
  const req = await call(IAM, 'POST', '/api/v1/users/me/deletion', { token: delLogin, body: { password: del.password } });
  check('탈퇴 요청 성공(200, scheduledPurgeAt)', req.status === 200 && !!req.json.data?.scheduledPurgeAt, `HTTP ${req.status} ${req.text.slice(0, 120)}`);
  check('IAM 상태가 PENDING_DELETION', sql(ADB, `SELECT status FROM users WHERE id='${del.id}'`) === 'PENDING_DELETION');
  await sleep(2500); // 서비스의 폐기 확인 캐시(시험에서는 1초)가 지나도록
  const oldP = await call(PARTY, 'GET', '/api/v1/me', { token: delParty });
  const oldG = await call(GAMES, 'GET', '/api/v1/me', { token: delGames });
  const oldI = await call(IAM, 'GET', '/oauth2/userinfo', { token: delLogin });
  check('탈퇴 요청 뒤 기존 토큰이 파티·게임·IAM 에서 모두 거부된다',
    [oldP, oldG, oldI].every((r) => r.status === 401 || r.status === 403), `party=${oldP.status} games=${oldG.status} iam=${oldI.status}`);

  section('5. 유예 앞당기기(로컬 DB) → IAM 익명화');
  sql(ADB, `UPDATE users SET deletion_requested_at = now() - interval '2 days' WHERE id='${del.id}' AND status='PENDING_DELETION'`);
  await waitFor('IAM 익명화(DELETED)', () => sql(ADB, `SELECT status FROM users WHERE id='${del.id}'`) === 'DELETED', 60_000);
  const iamRow = rows(ADB, `SELECT email, name, (deleted_at IS NOT NULL) FROM users WHERE id='${del.id}'`)[0];
  check('IAM 계정이 DELETED 로 익명화(이메일·이름 대체, deleted_at 기록)',
    iamRow[0].startsWith('deleted-') && iamRow[0].endsWith('@deleted.invalid') && iamRow[1] === '탈퇴한 사용자' && iamRow[2] === 't', iamRow.join(','));
  const stillFriend = rows(ADB, `SELECT status FROM users WHERE id='${fr.id}'`)[0]?.[0];
  check('음성 대조: friend-user 의 IAM 계정은 ACTIVE 그대로', stillFriend === 'ACTIVE', stillFriend);

  section('6. 서비스 동기화 대기');
  await waitFor('party 동기화', () => count(PDB, `SELECT count(*) FROM party_users WHERE id='${del.id}' AND deleted_at IS NOT NULL`) === 1, SYNC_WAIT_MS);
  await waitFor('games 동기화', () => count(GDB, `SELECT count(*) FROM game_users WHERE id='${del.id}' AND deleted_at IS NOT NULL`) === 1, SYNC_WAIT_MS);
  // 한 주기 더 돌아 마무리(Guard 튜플 삭제 등)를 기다린다
  await sleep(4000);

  section('7. 도로 파티 검증');
  const pu = rows(PDB, `SELECT username, nickname, (deleted_at IS NOT NULL) FROM party_users WHERE id='${del.id}'`)[0];
  check('party_users 익명화: 사용자명 deleted-…, 닉네임 "탈퇴한 사용자", deleted_at',
    pu[0] === `deleted-${del.id.replaceAll('-', '')}` && pu[1] === '탈퇴한 사용자' && pu[2] === 't', pu.join(','));
  check('사적 메모가 지워졌다(del), friend 의 메모는 그대로',
    count(PDB, `SELECT count(*) FROM pin_private_notes WHERE user_id='${del.id}'`) === 0 &&
    count(PDB, `SELECT count(*) FROM pin_private_notes WHERE user_id='${fr.id}'`) === 1);
  check('친구 관계·초대 링크가 지워졌다',
    count(PDB, `SELECT count(*) FROM friendships WHERE user_low_id='${del.id}' OR user_high_id='${del.id}'`) === 0 &&
    count(PDB, `SELECT count(*) FROM friend_invites WHERE owner_id='${del.id}'`) === 0);
  check('friend 의 초대 링크는 그대로', count(PDB, `SELECT count(*) FROM friend_invites WHERE owner_id='${fr.id}'`) === 1);
  check('del 이 올린 사진 행이 지워졌다(A·B 모두), friend 의 사진 행은 그대로',
    count(PDB, `SELECT count(*) FROM pin_photos WHERE uploaded_by='${del.id}'`) === 0 &&
    count(PDB, `SELECT count(*) FROM pin_photos WHERE uploaded_by='${fr.id}'`) === 1);
  const bucketAfter = await s3Keys('doro-party-media');
  check('S3 목에서 del 의 사진 파일 2개가 지워졌다', delKeys.every((k) => !bucketAfter.includes(k)), `남은 키 ${bucketAfter.length}`);
  check('S3 목에 friend 의 사진 파일은 그대로', frKeys.every((k) => bucketAfter.includes(k)));
  const mapExists = (id) => count(PDB, `SELECT count(*) FROM party_maps WHERE id='${id}'`) === 1;
  check('del 만 쓰던 지도 A 는 핀과 함께 지워졌다', !mapExists(mapA) && count(PDB, `SELECT count(*) FROM pins WHERE id='${pinA}'`) === 0);
  check('공유받은 사람이 있는 지도 B 는 남고 friend 의 핀도 남는다',
    mapExists(mapB) && count(PDB, `SELECT count(*) FROM pins WHERE map_id='${mapB}' AND id='${pinBf}'`) === 1);
  check('지도 B 의 del 핀(주인이 올린 핀)은 남는다(기록은 남기고 작성자만 익명)', count(PDB, `SELECT count(*) FROM pins WHERE id='${pinB}'`) === 1);
  check('다른 사람의 기록만 있는 지도 D 는 남고 친구 전체 공개는 꺼졌다',
    mapExists(mapD) && sql(PDB, `SELECT friend_access FROM party_maps WHERE id='${mapD}'`) === 'NONE' &&
    count(PDB, `SELECT count(*) FROM pins WHERE id='${pinDf}'`) === 1);
  check('friend 지도 E 와 핀·댓글(del 이 쓴 댓글 포함)이 남아 있다',
    mapExists(mapE) && count(PDB, `SELECT count(*) FROM pins WHERE id='${pinE}'`) === 1 &&
    count(PDB, `SELECT count(*) FROM pin_comments WHERE pin_id='${pinE}'`) === 1);
  check('del 이 공유받은 행(E)이 지워졌다', count(PDB, `SELECT count(*) FROM map_user_shares WHERE user_id='${del.id}'`) === 0);

  // 남은 사람은 API 로 그대로 접근한다
  const frB = await call(PARTY, 'GET', `/api/v1/maps/${mapB}`, { token: frParty });
  const frBpins = await call(PARTY, 'GET', `/api/v1/maps/${mapB}/pins`, { token: frParty });
  const frE = await call(PARTY, 'GET', `/api/v1/maps/${mapE}`, { token: frParty });
  const frPhoto = await fetch(`${PARTY}/api/v1/maps/${mapE}/pins/${pinE}/photos/${photoE.id}/content`, { headers: { Authorization: `Bearer ${frParty}` } });
  check('friend-user 가 API 로 지도 B(남은 지도)·B 의 핀·자기 지도 E·자기 사진에 계속 접근한다',
    frB.status === 200 && frBpins.status === 200 && JSON.stringify(frBpins.json).includes(pinBf) && frE.status === 200 && frPhoto.status === 200,
    `B=${frB.status} pins=${frBpins.status} E=${frE.status} photo=${frPhoto.status}`);
  const frMaps = await ok(PARTY, 'GET', '/api/v1/maps', { token: frParty });
  check('friend-user 의 지도 목록에 B·E 가 보이고 A 는 없다',
    JSON.stringify(frMaps).includes(mapB) && JSON.stringify(frMaps).includes(mapE) && !JSON.stringify(frMaps).includes(mapA));
  check('음성 대조: friend 의 party_users 는 익명화되지 않았다',
    rows(PDB, `SELECT nickname, (deleted_at IS NULL) FROM party_users WHERE id='${fr.id}'`)[0].join(',') === `${fr.nickname},t`);

  section('8. Guard 검증');
  const left = count(GUARDDB, `SELECT count(*) FROM relation_tuples WHERE subject_id='${del.id}' OR object_id='${del.id}'`);
  check('Guard DB 에 del-user 가 주체·대상인 튜플이 하나도 없다', left === 0, `남은 ${left}`);
  const stillAllowed = [
    await guardAllowed('party_map', mapB, 'owner', del.id),
    await guardAllowed('party_map', mapB, 'viewer', del.id),
    await guardAllowed('party_map', mapE, 'viewer', del.id),
    await guardAllowed('party_map', mapD, 'viewer', del.id),
  ];
  check('Guard 검사: del-user 는 B(주인)·B·E·D 어디에도 권한이 없다', stillAllowed.every((a) => !a), JSON.stringify(stillAllowed));
  check('Guard 검사: friend-user 는 B(editor)·E(owner)·D 에 관해 자기 권한이 유지된다 (D 는 친구 공개가 꺼져 viewer 없음)',
    (await guardAllowed('party_map', mapB, 'editor', fr.id)) && (await guardAllowed('party_map', mapE, 'owner', fr.id)) &&
    !(await guardAllowed('party_map', mapD, 'viewer', fr.id)));

  section('9. 도로 게임 검증');
  const gu = rows(GDB, `SELECT username, nickname, (deleted_at IS NOT NULL) FROM game_users WHERE id='${del.id}'`)[0];
  check('game_users 익명화: 사용자명 deleted-…, 닉네임 "탈퇴한 사용자", deleted_at',
    gu[0] === `deleted-${del.id.replaceAll('-', '')}` && gu[1] === '탈퇴한 사용자' && gu[2] === 't', gu.join(','));
  if (scored) check('게임 최고 기록 행은 남는다(순위표에는 점수만, 이름은 익명)', count(GDB, `SELECT count(*) FROM game_records WHERE user_id='${del.id}'`) === 1);

  section('10. 동기화 상태·로그·멱등성');
  const cursor = (db) => rows(db, `SELECT name, cursor_at FROM iam_sync_state`);
  const cp = cursor(PDB), cg = cursor(GDB);
  check('party·games 의 iam_sync_state 커서가 기록되고 에포크 이후로 전진했다',
    cp.length === 1 && cg.length === 1 && new Date(cp[0][1]) > new Date('2020-01-01') && new Date(cg[0][1]) > new Date('2020-01-01'),
    `${cp.join(',')} / ${cg.join(',')}`);
  const snapshot = () => ({
    party: sql(PDB, `SELECT (SELECT count(*) FROM party_users),(SELECT count(*) FROM party_maps),(SELECT count(*) FROM pins),(SELECT count(*) FROM pin_photos),(SELECT count(*) FROM map_user_shares),(SELECT count(*) FROM friendships),(SELECT count(*) FROM pin_private_notes),(SELECT count(*) FROM pin_comments),(SELECT count(*) FROM friend_invites),(SELECT count(*) FROM guard_tuple_delete_outbox)`),
    games: sql(GDB, `SELECT (SELECT count(*) FROM game_users),(SELECT count(*) FROM game_records),(SELECT count(*) FROM game_runs)`),
    partyHash: sql(PDB, `SELECT md5(string_agg(id::text||username||nickname||coalesce(deleted_at::text,''), ',' ORDER BY id)) FROM party_users`),
    gamesHash: sql(GDB, `SELECT md5(string_agg(id::text||username||nickname||coalesce(deleted_at::text,''), ',' ORDER BY id)) FROM game_users`),
    guard: sql(GUARDDB, `SELECT count(*) FROM relation_tuples`),
    s3: JSON.stringify([]),
  });
  const s1 = snapshot(); s1.s3 = JSON.stringify((await s3Keys('doro-party-media')).sort());
  const infoBefore = logLines(PARTY_LOG).filter((l) => /anonymized after Doro account deletion/.test(l)).length
    + logLines(GAMES_LOG).filter((l) => /anonymized after Doro account deletion/.test(l)).length;
  await sleep(12_000); // 동기화 주기(3초)를 여러 번 더 돈다. 커서에 겹침(10분)이 있어 같은 사용자를 다시 받는다.
  const s2 = snapshot(); s2.s3 = JSON.stringify((await s3Keys('doro-party-media')).sort());
  check('두 번째 이후 주기는 아무것도 바꾸지 않는다(행 수·익명화 값·Guard 튜플·S3 동일)', JSON.stringify(s1) === JSON.stringify(s2),
    `${JSON.stringify(s1)} != ${JSON.stringify(s2)}`);
  const infoAfter = logLines(PARTY_LOG).filter((l) => /anonymized after Doro account deletion/.test(l)).length
    + logLines(GAMES_LOG).filter((l) => /anonymized after Doro account deletion/.test(l)).length;
  check('익명화 로그는 서비스마다 정확히 1번(다시 처리되지 않음)', infoBefore === 2 && infoAfter === 2, `전 ${infoBefore} 후 ${infoAfter}`);
  const pe = errorLines(PARTY_LOG), ge = errorLines(GAMES_LOG);
  check('도로 파티·게임 로그에 ERROR 가 없다', pe.length === 0 && ge.length === 0, `party=${pe.length} games=${ge.length} ${[...pe, ...ge][0] ?? ''}`.slice(0, 300));
  const syncWarn = [...logLines(PARTY_LOG), ...logLines(GAMES_LOG)].filter((l) => /Deleted-account sync failed/.test(l));
  check('동기화 실패(WARN) 로그가 없다', syncWarn.length === 0, syncWarn[0]?.slice(0, 200));
} catch (e) {
  check(`시험 진행 중 예외: ${e.message}`, false, e.stack?.split('\n').slice(0, 3).join(' | '));
}

section('결과');
const failed = results.filter((r) => !r.ok);
console.log(`${results.length - failed.length}/${results.length} 통과`);
if (failed.length) { console.log('실패:'); failed.forEach((f) => console.log(`  - ${f.name}`)); }
process.exit(failed.length ? 1 : 0);
