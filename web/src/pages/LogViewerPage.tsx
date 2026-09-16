import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { logApi, ParsedLogEntry } from '../api/logApi';
import {
  Terminal,
  Search,
  RefreshCw,
  AlertCircle,
  CheckCircle2,
  AlertTriangle,
  Copy,
  Check,
  ChevronDown,
  ChevronUp,
  Radio,
  ExternalLink,
  ShieldAlert,
  ArrowLeft,
} from 'lucide-react';

const SERVICE_LABELS: Record<string, { name: string; color: string }> = {
  all: { name: '전체 서비스', color: 'bg-slate-100 text-slate-700' },
  'doro-auth-api': { name: 'Doro IAM (인증)', color: 'bg-indigo-50 text-indigo-700 border border-indigo-200' },
  'doro-guard-api': { name: 'Doro Guard (인가)', color: 'bg-violet-50 text-violet-700 border border-violet-200' },
  'doro-web-portal': { name: 'Doro Web (포털)', color: 'bg-blue-50 text-blue-700 border border-blue-200' },
  'doro-postgres': { name: 'PostgreSQL DB', color: 'bg-emerald-50 text-emerald-700 border border-emerald-200' },
  'doro-redis': { name: 'Redis Cache', color: 'bg-rose-50 text-rose-700 border border-rose-200' },
};

export const LogViewerPage: React.FC = () => {
  const navigate = useNavigate();
  const { getActiveAccount } = useAuthStore();
  const activeAccount = getActiveAccount();

  const [logs, setLogs] = useState<ParsedLogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedService, setSelectedService] = useState<string>('all');
  const [selectedLevel, setSelectedLevel] = useState<string>('all');
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [isLive, setIsLive] = useState<boolean>(true);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [expandedLogId, setExpandedLogId] = useState<string | null>(null);

  const isAdmin = activeAccount?.role === 'ADMIN' || activeAccount?.role === 'SUPER_ADMIN';

  const fetchLogs = useCallback(async () => {
    setLoading(true);
    try {
      const data = await logApi.getLogs({
        service: selectedService,
        level: selectedLevel,
        search: searchQuery,
        limit: 150,
      });
      setLogs(data);
    } catch {
      setLogs([]);
    } finally {
      setLoading(false);
    }
  }, [selectedService, selectedLevel, searchQuery]);

  // 최초 로딩 및 수동 필터 변경 시 조회
  useEffect(() => {
    fetchLogs();
  }, [fetchLogs]);

  // 실시간 갱신 (3초 주기)
  useEffect(() => {
    if (!isLive) return;
    const interval = setInterval(() => {
      fetchLogs();
    }, 3000);
    return () => clearInterval(interval);
  }, [isLive, fetchLogs]);

  // 클립보드 복사 핸들러
  const handleCopy = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 1500);
  };

  // TraceId 클릭 시 해당 TraceId로 즉시 필터링
  const handleTraceClick = (traceId: string) => {
    setSearchQuery(traceId);
    setSelectedService('all');
    setSelectedLevel('all');
  };

  // 통계 계산
  const totalCount = logs.length;
  const errorCount = logs.filter((l) => l.level === 'ERROR').length;
  const warnCount = logs.filter((l) => l.level === 'WARN').length;
  const infoCount = logs.filter((l) => l.level === 'INFO').length;

  if (!activeAccount) {
    return (
      <div className="max-w-md mx-auto my-24 p-8 glass-card google-card-shadow rounded-3xl text-center space-y-4">
        <ShieldAlert className="w-12 h-12 text-rose-500 mx-auto" />
        <h2 className="text-lg font-bold text-slate-800">로그인이 필요합니다</h2>
        <p className="text-xs text-slate-500">시스템 로그 모니터링은 관리자 로그인 후 이용 가능합니다.</p>
        <button
          onClick={() => navigate('/login')}
          className="py-2.5 px-5 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition-all cursor-pointer"
        >
          로그인하러 가기
        </button>
      </div>
    );
  }

  if (!isAdmin) {
    return (
      <div className="max-w-md mx-auto my-24 p-8 glass-card google-card-shadow rounded-3xl text-center space-y-4">
        <ShieldAlert className="w-12 h-12 text-amber-500 mx-auto" />
        <h2 className="text-lg font-bold text-slate-800">관리자 전용 페이지입니다</h2>
        <p className="text-xs text-slate-500 leading-relaxed">
          시스템 관제 및 중앙 로그는 Doro 플랫폼 관리자(ADMIN) 또는 최고 관리자(SUPER_ADMIN) 등급의 계정만 열람할 수 있습니다.
        </p>
        <div className="p-3 bg-slate-50 rounded-xl text-xs font-semibold text-slate-600">
          현재 계정 등급: <span className="text-indigo-600 font-bold">{activeAccount.role || 'USER'}</span>
        </div>
        <button
          onClick={() => navigate('/account')}
          className="py-2.5 px-5 bg-slate-900 hover:bg-slate-800 text-white rounded-xl text-xs font-bold transition-all flex items-center gap-1.5 mx-auto cursor-pointer"
        >
          <ArrowLeft className="w-4 h-4" /> 내 계정 홈으로 돌아가기
        </button>
      </div>
    );
  }

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 py-8">
      {/* 1. Header Banner */}
      <div className="mb-6 p-6 sm:p-8 bg-gradient-to-r from-slate-900 via-indigo-950 to-slate-900 rounded-3xl text-white shadow-xl relative overflow-hidden">
        <div className="absolute right-0 top-0 w-96 h-96 bg-indigo-500/10 rounded-full blur-3xl pointer-events-none"></div>
        <div className="relative z-10 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-6">
          <div className="flex items-center gap-4">
            <div className="w-14 h-14 rounded-2xl bg-indigo-600/30 border border-indigo-400/30 flex items-center justify-center text-white text-2xl shadow-inner">
              <Terminal className="w-7 h-7 text-indigo-300" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-2xl font-black tracking-tight">시스템 관제 및 중앙 로그</h1>
                <span className="px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-indigo-500/30 border border-indigo-400/40 text-indigo-200">
                  Loki & Promtail 연동
                </span>
              </div>
              <p className="text-xs text-slate-300/80 mt-1">
                Doro 플랫폼 마이크로서비스의 실시간 표준 출력 로그와 분산 추적(TraceId)을 조회합니다.
              </p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            {/* Live Toggle */}
            <button
              onClick={() => setIsLive(!isLive)}
              className={`px-3.5 py-2 rounded-2xl text-xs font-bold flex items-center gap-2 border transition-all cursor-pointer ${
                isLive
                  ? 'bg-emerald-500/20 border-emerald-400/40 text-emerald-300 shadow-sm'
                  : 'bg-slate-800 border-slate-700 text-slate-400'
              }`}
            >
              <Radio className={`w-3.5 h-3.5 ${isLive ? 'animate-pulse text-emerald-400' : ''}`} />
              {isLive ? '실시간 수신 중 (3s)' : '일시정지됨'}
            </button>

            {/* Manual Refresh */}
            <button
              onClick={fetchLogs}
              disabled={loading}
              className="p-2.5 rounded-2xl bg-white/10 hover:bg-white/20 border border-white/20 text-white transition-all cursor-pointer"
              title="새로고침"
            >
              <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
            </button>

            {/* Grafana External Link */}
            <a
              href="http://localhost:3001"
              target="_blank"
              rel="noreferrer"
              className="px-3.5 py-2 rounded-2xl bg-white/10 hover:bg-white/20 border border-white/20 text-xs font-bold text-white flex items-center gap-1.5 transition-all"
              title="Grafana 원본 콘솔 열기"
            >
              <span>Grafana</span>
              <ExternalLink className="w-3.5 h-3.5" />
            </a>
          </div>
        </div>

        {/* Quick KPI Badges */}
        <div className="mt-6 pt-5 border-t border-white/10 grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs font-semibold">
          <div className="bg-white/5 border border-white/10 rounded-2xl p-3 flex items-center justify-between">
            <span className="text-slate-300">수집된 로그</span>
            <span className="font-bold text-white text-sm">{totalCount}건</span>
          </div>
          <div className="bg-emerald-500/10 border border-emerald-500/20 rounded-2xl p-3 flex items-center justify-between">
            <span className="text-emerald-300 flex items-center gap-1">
              <CheckCircle2 className="w-3.5 h-3.5" /> 정상 (INFO)
            </span>
            <span className="font-bold text-emerald-300 text-sm">{infoCount}건</span>
          </div>
          <div className="bg-amber-500/10 border border-amber-500/20 rounded-2xl p-3 flex items-center justify-between">
            <span className="text-amber-300 flex items-center gap-1">
              <AlertTriangle className="w-3.5 h-3.5" /> 경고 (WARN)
            </span>
            <span className="font-bold text-amber-300 text-sm">{warnCount}건</span>
          </div>
          <div className="bg-rose-500/10 border border-rose-500/20 rounded-2xl p-3 flex items-center justify-between">
            <span className="text-rose-300 flex items-center gap-1">
              <AlertCircle className="w-3.5 h-3.5" /> 오류 (ERROR)
            </span>
            <span className="font-bold text-rose-300 text-sm">{errorCount}건</span>
          </div>
        </div>
      </div>

      {/* 2. Controls & Filters Card */}
      <div className="glass-card google-card-shadow rounded-3xl p-5 mb-6 space-y-4">
        {/* Service Filters */}
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs font-bold text-slate-500 mr-1">서비스:</span>
          {Object.entries(SERVICE_LABELS).map(([key, label]) => {
            const isSelected = selectedService === key;
            return (
              <button
                key={key}
                onClick={() => setSelectedService(key)}
                className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer ${
                  isSelected
                    ? 'bg-indigo-600 text-white shadow-sm shadow-indigo-500/20'
                    : 'bg-slate-100 hover:bg-slate-200 text-slate-700'
                }`}
              >
                {label.name}
              </button>
            );
          })}
        </div>

        {/* Level & Search bar row */}
        <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-3 pt-2 border-t border-slate-100">
          {/* Level Filter Tabs */}
          <div className="flex items-center gap-1.5">
            <span className="text-xs font-bold text-slate-500 mr-1">등급:</span>
            {[
              { id: 'all', label: '전체' },
              { id: 'ERROR', label: '🔴 오류만 보기', activeClass: 'bg-rose-600 text-white' },
              { id: 'WARN', label: '🟡 경고', activeClass: 'bg-amber-600 text-white' },
              { id: 'INFO', label: '🟢 정상', activeClass: 'bg-emerald-600 text-white' },
            ].map((lvl) => {
              const isSelected = selectedLevel === lvl.id;
              return (
                <button
                  key={lvl.id}
                  onClick={() => setSelectedLevel(lvl.id)}
                  className={`px-2.5 py-1 rounded-xl text-xs font-bold transition-all cursor-pointer ${
                    isSelected
                      ? lvl.activeClass || 'bg-slate-900 text-white'
                      : 'bg-slate-100 hover:bg-slate-200 text-slate-600'
                  }`}
                >
                  {lvl.label}
                </button>
              );
            })}
          </div>

          {/* Search Input */}
          <div className="relative flex-1 max-w-md">
            <Search className="w-4 h-4 text-slate-400 absolute left-3.5 top-2.5" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Trace-Id, 사용자 ID, IP, 키워드 검색..."
              className="w-full pl-10 pr-8 py-2 bg-slate-50 border border-slate-200 rounded-xl text-xs focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 font-medium"
            />
            {searchQuery && (
              <button
                onClick={() => setSearchQuery('')}
                className="absolute right-2.5 top-2 text-xs text-slate-400 hover:text-slate-600 font-bold"
              >
                ✕
              </button>
            )}
          </div>
        </div>
      </div>

      {/* 3. Log Stream Feed */}
      <div className="glass-card google-card-shadow rounded-3xl overflow-hidden border border-slate-100">
        <div className="p-4 bg-slate-50/80 border-b border-slate-100 flex items-center justify-between text-xs font-bold text-slate-600">
          <span>실시간 로그 피드 ({logs.length}건)</span>
          <span className="text-[11px] text-slate-400">최신순 정렬</span>
        </div>

        {logs.length === 0 ? (
          <div className="py-16 text-center text-slate-400 space-y-2">
            <Terminal className="w-10 h-10 mx-auto text-slate-300" />
            <p className="text-sm font-bold text-slate-600">수집된 로그가 없거나 조건에 일치하지 않습니다.</p>
            <p className="text-xs text-slate-400">서비스 필터를 [전체]로 변경하거나 검색어를 초기화해 보세요.</p>
          </div>
        ) : (
          <div className="divide-y divide-slate-100">
            {logs.map((log) => {
              const isExpanded = expandedLogId === log.id;
              const svcMeta = SERVICE_LABELS[log.service] || {
                name: log.service,
                color: 'bg-slate-100 text-slate-700',
              };

              return (
                <div
                  key={log.id}
                  className={`p-4 transition-colors hover:bg-slate-50/70 text-xs font-mono ${
                    log.level === 'ERROR' ? 'bg-rose-50/30' : ''
                  }`}
                >
                  <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2.5 mb-1.5">
                    <div className="flex flex-wrap items-center gap-2">
                      {/* Time */}
                      <span className="text-slate-400 font-semibold">{log.formattedTime}</span>

                      {/* Service Badge */}
                      <span className={`px-2 py-0.5 rounded-md font-bold text-[11px] font-sans ${svcMeta.color}`}>
                        {svcMeta.name}
                      </span>

                      {/* Level Badge */}
                      <span
                        className={`px-1.5 py-0.5 rounded-md font-bold text-[10px] ${
                          log.level === 'ERROR'
                            ? 'bg-rose-100 text-rose-700 border border-rose-200'
                            : log.level === 'WARN'
                            ? 'bg-amber-100 text-amber-700 border border-amber-200'
                            : 'bg-emerald-100 text-emerald-700 border border-emerald-200'
                        }`}
                      >
                        {log.level}
                      </span>

                      {/* TraceId Pill (Clickable Filter) */}
                      {log.traceId && (
                        <button
                          onClick={() => handleTraceClick(log.traceId!)}
                          title="이 Trace-Id의 전 구간 로그만 필터링"
                          className="px-2 py-0.5 rounded-md font-mono text-[11px] font-bold bg-indigo-50 hover:bg-indigo-100 text-indigo-700 border border-indigo-200 transition-colors flex items-center gap-1 cursor-pointer"
                        >
                          <Search className="w-2.5 h-2.5" />
                          trace:{log.traceId.length > 12 ? `${log.traceId.substring(0, 12)}...` : log.traceId}
                        </button>
                      )}

                      {/* Client IP */}
                      {log.clientIp && (
                        <span className="text-slate-400 text-[10px]">ip:{log.clientIp}</span>
                      )}
                    </div>

                    {/* Actions */}
                    <div className="flex items-center gap-2 self-end sm:self-auto font-sans">
                      <button
                        onClick={() => handleCopy(log.raw, log.id)}
                        className="px-2 py-1 rounded-lg hover:bg-slate-100 text-slate-500 hover:text-slate-800 transition-colors flex items-center gap-1 text-[11px] cursor-pointer"
                        title="로그 원문 복사"
                      >
                        {copiedId === log.id ? (
                          <Check className="w-3.5 h-3.5 text-emerald-600" />
                        ) : (
                          <Copy className="w-3.5 h-3.5" />
                        )}
                        <span>{copiedId === log.id ? '복사됨' : '복사'}</span>
                      </button>

                      <button
                        onClick={() => setExpandedLogId(isExpanded ? null : log.id)}
                        className="p-1 rounded-lg hover:bg-slate-100 text-slate-400 hover:text-slate-600 cursor-pointer"
                        title={isExpanded ? '접기' : '원문 펼치기'}
                      >
                        {isExpanded ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
                      </button>
                    </div>
                  </div>

                  {/* Message Body */}
                  <div
                    className={`font-mono text-xs leading-relaxed break-all ${
                      log.level === 'ERROR' ? 'text-rose-900 font-semibold' : 'text-slate-800'
                    }`}
                  >
                    {log.message}
                  </div>

                  {/* Expanded Raw Log Details */}
                  {isExpanded && (
                    <div className="mt-3 p-3 bg-slate-900 text-slate-200 rounded-xl text-[11px] font-mono whitespace-pre-wrap break-all select-all animate-in fade-in">
                      {log.raw}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};
