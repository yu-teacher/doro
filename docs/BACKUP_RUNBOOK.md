# Doro 백업 / 복원 런북

운영 서버(`ssh mini`)의 정기 백업과 복원 절차다. 스크립트는 `scripts/backup.sh`(서버에는 `~/ops/backup.sh`로 설치).

## 1. 무엇이 어디에 어떻게 백업되는가

| 대상 | 방식 | 파일 |
|---|---|---|
| PostgreSQL 전체 DB (`doro_auth`, `doro_guard`, `doro_iam`, `service_blog`) | `pg_dump -Fc` (DB별) | `<db>.dump` |
| DB 역할(roles) | `pg_dumpall --globals-only` | `globals.sql` (비밀번호 해시 포함, 600) |
| MinIO (블로그 이미지) | 데이터 볼륨 tar | `minio-data.tgz` |
| Redis (JWT 서명 키 등) | `BGSAVE` 후 RDB 복사 | `redis-dump.rdb` |
| 설정 | `.env`(2개), compose(2개), `gateway/nginx.conf`, certbot 갱신 설정 | `config.tgz` (비밀번호 포함, 600) |
| 부가 정보 | cron, 컨테이너 목록, 체크섬, 요약 | `crontab.txt`, `containers.txt`, `SHA256SUMS`, `MANIFEST.txt` |

- 위치: `~/backups/auto/{daily,weekly,monthly}/<타임스탬프>/` (디렉터리 700, 파일 600)
- 보관: 일간 7개, 주간 4개, 월간 6개 (주간/월간은 하드링크라 추가 공간을 거의 쓰지 않는다)
- 스케줄(cron, KST): 매일 **03:30** 백업, 매주 일요일 **05:00** 복원 테스트. 로그: `~/backups/auto/backup.log`
- 이전부터 있던 `~/scripts/backup_db.sh`(매일 03:00, `service_blog`만 SQL 덤프)는 그대로 둔다. 새 백업이 안정적으로 쌓이면 crontab 에서 제거해도 된다.
- 수동으로 만든 `~/backups/pre-*` 디렉터리는 배포/회전 전 안전망이며 자동으로 지워지지 않는다. 필요 없어지면 직접 정리한다.

## 2. 상태 확인

```bash
ssh mini '~/ops/backup.sh status'      # 마지막 성공/복원 테스트 시각과 경고. 경고가 있으면 종료 코드 1
ssh mini 'tail -20 ~/backups/auto/backup.log'
```

경고 기준: 마지막 성공 백업이 30시간보다 오래됨, 마지막 실행 실패, 복원 테스트가 성공한 기록이 없거나 9일보다 오래됨, 오프사이트 사본 없음.

## 3. 복원 테스트 (주 1회 자동, 수동 실행도 가능)

```bash
ssh mini '~/ops/backup.sh verify-restore'
```

체크섬을 검증하고, 네트워크가 없는 임시 PostgreSQL 컨테이너에 모든 DB 를 실제로 복원해 테이블 목록과 행 수를 현재 DB 와 비교하고, Redis RDB 무결성과 MinIO/설정 아카이브 읽기를 확인한다. 운영 DB 는 건드리지 않는다.

## 4. 복원 절차

먼저 복원할 백업 디렉터리를 정한다. (가장 최근: `ls -1d ~/backups/auto/daily/[0-9]* | sort | tail -1`)

```bash
B=~/backups/auto/daily/<타임스탬프>
( cd "$B" && sha256sum -c SHA256SUMS )          # 먼저 무결성 확인
```

### 4-1. DB 하나만 되돌리기 (예: service_blog)
영향받는 서비스(blog-backend 등)를 먼저 멈춘 뒤 실행한다.

```bash
docker exec -i doro-postgres sh -c 'dropdb -U "$POSTGRES_USER" --if-exists service_blog && createdb -U "$POSTGRES_USER" service_blog'
docker exec -i doro-postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d service_blog --no-owner --exit-on-error' < "$B/service_blog.dump"
# 서비스를 다시 시작하고 헬스를 확인한다
```

`doro_auth` / `doro_guard` 는 Flyway 이력 테이블(`auth_schema_history` / `guard_schema_history`)까지 덤프에 들어 있어 그대로 맞는다.

### 4-2. PostgreSQL 클러스터를 새로 만든 경우
1. 새 컨테이너를 같은 `POSTGRES_USER`/비밀번호로 기동한다 (`~/doro/.env`).
2. 역할: `docker exec -i doro-postgres sh -c 'psql -U "$POSTGRES_USER" -d postgres' < "$B/globals.sql"` (이미 있는 역할 오류는 무시)
3. 각 DB 를 4-1 과 같은 방식으로 복원한다.

### 4-3. MinIO (블로그 이미지)
```bash
docker stop doro-minio
docker run --rm -v doro_miniodata:/data -v "$B":/backup postgres:16-alpine sh -c 'cd /data && tar xzf /backup/minio-data.tgz'
docker start doro-minio
```

### 4-4. Redis (JWT 서명 키 등)
Redis 를 잃으면 서명 키가 새로 만들어져 **모든 사용자의 토큰이 무효**가 된다(전원 재로그인). 복원하면 유지된다.
```bash
docker stop doro-redis
docker run --rm -v doro_doro_redisdata:/data -v "$B":/backup postgres:16-alpine sh -c 'cp /backup/redis-dump.rdb /data/dump.rdb && chown 999:1000 /data/dump.rdb'
docker start doro-redis
```
(볼륨 이름은 `docker inspect doro-redis --format '{{range .Mounts}}{{.Name}}{{end}}'` 로 확인한다.)

### 4-5. 설정 파일
```bash
tar xzf "$B/config.tgz" -C ~          # ~/doro/.env, ~/doro-blog/.env, compose, gateway/nginx.conf, certbot 갱신 설정
```
인증서 개인키는 백업하지 않는다. 필요하면 certbot 으로 재발급한다.

## 5. 오프사이트 사본 (Raspberry Pi)

백업 직후 `rsync -aH`(삭제 전파 없음)로 pi 의 `~/doro-backups/` 에 복사한다. 설정은 `scripts/setup-offsite-backup.sh`(Mac 에서 실행, 여러 번 실행해도 안전, `--check` 는 상태만 점검).

- mini 전용 키 `~/.ssh/pi_backup_ed25519` → pi `authorized_keys` 에 `restrict,command="/usr/bin/rrsync /home/ysm/doro-backups"` 로 등록. 셸 접속·포트 포워딩·디렉터리 밖 접근 불가(설정 시 검증함).
- mini 의 `~/ops/backup.env` 에 `OFFSITE_TARGET=pi-backup:./` (연결 별칭 `pi-backup`은 `~/.ssh/config`).
- 삭제가 전파되지 않으므로 mini 가 침해되어도 pi 의 과거 사본은 지워지지 않는다. 오래된 세대는 pi 의 cron(`~/ops/prune-doro-backups.sh`, 매일 06:30)이 일 30 / 주 12 / 월 12 개만 남기고 정리한다.
- 오프사이트 복사가 실패하거나 사본이 오래되면 `status` 가 경고한다.
- pi 에서 복원할 때: `rsync -a pi:doro-backups/daily/<타임스탬프>/ ~/restore/` 후 4장 절차를 따른다. 체크섬은 `sha256sum -c SHA256SUMS`.
- 한계: pi 도 같은 집 안에 있다. 화재/도난/정전 같은 장소 단위 사고에는 여전히 약하다. 클라우드 등 다른 장소 사본은 아직 없다.

## 6. 알아둘 한계
- 백업은 하루 한 번이라 최대 24시간치 변경을 잃을 수 있다.
- 알림 채널이 없다. `status` 를 직접 확인하거나 주기적으로 확인을 요청해야 한다.
- 복원 테스트는 임시 컨테이너에서 한다. 운영 환경에 복원하는 절차(4장)는 실제 장애 상황에서만 실행된다.
