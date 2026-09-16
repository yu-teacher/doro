import axios from 'axios';

export interface ParsedLogEntry {
  id: string;
  rawTimestamp: string;
  formattedTime: string;
  service: string;
  level: 'INFO' | 'WARN' | 'ERROR' | 'DEBUG';
  traceId?: string;
  userId?: string;
  clientIp?: string;
  message: string;
  raw: string;
}

export interface LogFilterParams {
  service?: string;
  level?: string;
  search?: string;
  limit?: number;
}

const lokiClient = axios.create({
  baseURL: '/loki/api/v1',
  timeout: 5000,
});

/**
 * 로그 한 줄을 정규식으로 파싱하여 구조화된 엔트리로 변환
 */
export function parseLogLine(service: string, timestampNs: string, logLine: string): ParsedLogEntry {
  // 나노초 타임스탬프를 밀리초 Date로 변환 (기본값)
  let formattedTime = '';
  try {
    const ms = Number(BigInt(timestampNs) / 1000000n);
    const date = new Date(ms);
    formattedTime = date.toLocaleTimeString('ko-KR', {
      hour12: false,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    formattedTime = new Date().toLocaleTimeString('ko-KR', { hour12: false });
  }

  // 로그 레벨 추출
  let level: 'INFO' | 'WARN' | 'ERROR' | 'DEBUG' = 'INFO';
  if (logLine.includes(' ERROR ') || logLine.includes(' [ERROR] ') || logLine.includes('level=error')) {
    level = 'ERROR';
  } else if (logLine.includes(' WARN ') || logLine.includes(' [WARN] ') || logLine.includes('level=warn')) {
    level = 'WARN';
  } else if (logLine.includes(' DEBUG ') || logLine.includes(' [DEBUG] ') || logLine.includes('level=debug')) {
    level = 'DEBUG';
  }

  // traceId 추출 ([traceId=xxx] 또는 traceId=xxx)
  const traceMatch = logLine.match(/\[traceId=([^\]]+)\]/);
  const traceId = traceMatch && traceMatch[1] !== 'NONE' ? traceMatch[1] : undefined;

  // userId 추출 ([userId=xxx])
  const userMatch = logLine.match(/\[userId=([^\]]+)\]/);
  const userId = userMatch && userMatch[1] !== 'ANON' ? userMatch[1] : undefined;

  // clientIp 추출 ([clientIp=xxx])
  const ipMatch = logLine.match(/\[clientIp=([^\]]+)\]/);
  const clientIp = ipMatch && ipMatch[1] !== 'LOCAL' ? ipMatch[1] : undefined;

  // 메시지 본문 가공 (구조화 메타데이터 이후의 실 메시지 추출)
  let cleanMessage = logLine;
  const splitIdx = logLine.indexOf(' - ');
  if (splitIdx !== -1) {
    cleanMessage = logLine.substring(splitIdx + 3).trim();
  }

  return {
    id: `${timestampNs}-${Math.random().toString(36).substring(2, 9)}`,
    rawTimestamp: timestampNs,
    formattedTime,
    service,
    level,
    traceId,
    userId,
    clientIp,
    message: cleanMessage,
    raw: logLine,
  };
}

export const logApi = {
  /**
   * Loki에서 로그 목록 조회
   */
  async getLogs(params: LogFilterParams = {}): Promise<ParsedLogEntry[]> {
    const { service, search, limit = 100 } = params;

    // LogQL 쿼리 스트림 구성
    let streamSelector = '{service=~".+"}';
    if (service && service !== 'all') {
      streamSelector = `{service="${service}"}`;
    }

    let query = streamSelector;
    if (search && search.trim()) {
      query += ` |= "${search.trim()}"`;
    }

    try {
      const response = await lokiClient.get('/query_range', {
        params: {
          query,
          limit,
        },
      });

      const results = response.data?.data?.result || [];
      const entries: ParsedLogEntry[] = [];

      for (const streamObj of results) {
        const svc = streamObj.stream?.service || streamObj.stream?.container || 'unknown';
        const values = streamObj.values || [];
        for (const [ts, line] of values) {
          entries.push(parseLogLine(svc, ts, line));
        }
      }

      // 시간순 내림차순 정렬 (최신 로그가 상단)
      entries.sort((a, b) => {
        try {
          const tA = BigInt(a.rawTimestamp);
          const tB = BigInt(b.rawTimestamp);
          return tB > tA ? 1 : tB < tA ? -1 : 0;
        } catch {
          return 0;
        }
      });

      // 클라이언트 측 레벨 필터링 (선택 시)
      if (params.level && params.level !== 'all') {
        return entries.filter((e) => e.level === params.level);
      }

      return entries;
    } catch (err: unknown) {
      console.error('Failed to fetch logs from Loki:', err);
      return [];
    }
  },

  /**
   * 수집 중인 서비스 목록 조회
   */
  async getServiceList(): Promise<string[]> {
    try {
      const response = await lokiClient.get('/label/service/values');
      return response.data?.data || [];
    } catch {
      return ['doro-auth-api', 'doro-guard-api', 'doro-web-portal', 'doro-postgres', 'doro-redis'];
    }
  },
};
