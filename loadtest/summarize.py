import json, glob, os, sys, re
raw = sys.argv[1]
rows = []
for f in sorted(glob.glob(os.path.join(raw, '*.json'))):
    name = os.path.basename(f)[:-5]
    sc, vus = name.rsplit('-', 1)
    try:
        m = json.load(open(f))['metrics']
    except Exception:
        rows.append((sc, int(vus), None)); continue
    d = m.get('http_req_duration', {}); r = m.get('http_reqs', {}); fl = m.get('http_req_failed', {})
    if sc.startswith('grpc'):   # gRPC 는 HTTP 지표가 아니라 grpc_req_duration 과 iterations 를 쓴다
        d = m.get('grpc_req_duration', {}); r = m.get('iterations', {}); fl = {'value': 0}
    rows.append((sc, int(vus), dict(
        rps=r.get('rate', 0), avg=d.get('avg', 0), p95=d.get('p(95)', 0), p99=d.get('p(99)', 0), mx=d.get('max', 0),
        fail=fl.get('value', fl.get('rate', 0)) * 100,
        s5=m.get('server_5xx', {}).get('count', 0), wa=m.get('wrong_answer', {}).get('count', 0),
        chk=m.get('checks', {}).get('value', 1) * 100)))
print('| 시나리오 | 동시성 | req/s | 평균 ms | p95 ms | p99 ms | 최대 ms | 실패율 | 5xx | 오답 | 체크 통과 |')
print('|---|---|---|---|---|---|---|---|---|---|---|')
for sc, v, x in sorted(rows, key=lambda t: (t[0], t[1])):
    if x is None:
        print(f'| {sc} | {v} | (실행 실패) | | | | | | | | |'); continue
    print(f"| {sc} | {v} | {x['rps']:.0f} | {x['avg']:.0f} | {x['p95']:.0f} | {x['p99']:.0f} | {x['mx']:.0f} | {x['fail']:.2f}% | {int(x['s5'])} | {int(x['wa'])} | {x['chk']:.1f}% |")
