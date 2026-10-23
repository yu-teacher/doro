import React, { useCallback, useEffect, useState } from 'react';
import { Bell, BellOff, ExternalLink, Loader2, RefreshCw, Smartphone, Trash2 } from 'lucide-react';
import { notifyApi, type NotifyAppPreferences, type NotifyConfig, type NotifyDevice } from '../../api/notifyApi';
import { getErrorMessage } from '../../utils/errorUtils';
import {
  appUrlFor,
  describePermission,
  describeTestResult,
  formatLastSuccess,
  isAppOnThisDevice,
  isNotifyUnavailable,
  readBrowserPermission,
  readLocalDeviceId,
} from '../../utils/notifySettings';

type Phase = 'loading' | 'ready' | 'unavailable' | 'error';

const PERMISSION_TONE: Record<'ok' | 'warn' | 'neutral', string> = {
  ok: 'text-emerald-700 bg-emerald-50 border-emerald-200',
  warn: 'text-red-700 bg-red-50 border-red-200',
  neutral: 'text-slate-600 bg-slate-50 border-slate-200',
};

const toggleKey = (app: string, type: string) => `${app}:${type}`;

/**
 * 내 계정의 "알림" 섹션(설계 9장). 이 브라우저의 권한, 앱별·종류별 설정, 내 기기 목록과 해제, 시험 알림.
 * 알림 서비스가 아직 없거나 내려가 있으면(404·502~504) 이 섹션만 "사용할 수 없음"으로 보이고, 계정 페이지의 다른 기능은 영향받지 않는다.
 * 다른 앱의 구독을 허브에서 직접 만드는 기능은 없다(설계 8장: 실기기 확인 뒤 부가 기능). 꺼진 앱은 앱 화면 링크로 안내한다.
 */
export const NotificationSettingsSection: React.FC = () => {
  const [phase, setPhase] = useState<Phase>('loading');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [config, setConfig] = useState<NotifyConfig | null>(null);
  const [apps, setApps] = useState<NotifyAppPreferences[]>([]);
  const [devices, setDevices] = useState<NotifyDevice[]>([]);
  const [pendingToggles, setPendingToggles] = useState<ReadonlySet<string>>(new Set());
  const [removingId, setRemovingId] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);
  const [testMessage, setTestMessage] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const permission = describePermission(readBrowserPermission());
  const localDeviceId = readLocalDeviceId();

  const load = useCallback(async (isCancelled: () => boolean) => {
    setPhase('loading');
    setLoadError(null);
    try {
      const [loadedConfig, loadedApps, loadedDevices] = await Promise.all([notifyApi.getConfig(), notifyApi.getPreferences(), notifyApi.getDevices()]);
      if (isCancelled()) return;
      setConfig(loadedConfig);
      setApps(loadedApps);
      setDevices(loadedDevices);
      setPhase('ready');
    } catch (err: unknown) {
      if (isCancelled()) return;
      if (isNotifyUnavailable(err)) {
        setPhase('unavailable');
        return;
      }
      setLoadError(getErrorMessage(err, '알림 설정을 불러오지 못했습니다.'));
      setPhase('error');
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    void load(() => cancelled);
    return () => {
      cancelled = true;
    };
  }, [load]);

  const setTypeEnabled = (app: string, type: string, enabled: boolean) =>
    setApps((current) =>
      current.map((a) => (a.app === app ? { ...a, types: a.types.map((t) => (t.type === type ? { ...t, enabled } : t)) } : a))
    );

  const handleToggle = async (app: string, type: string, next: boolean) => {
    const key = toggleKey(app, type);
    setActionError(null);
    setPendingToggles((current) => new Set(current).add(key));
    setTypeEnabled(app, type, next);
    try {
      await notifyApi.setPreference(app, type, next);
    } catch (err: unknown) {
      setTypeEnabled(app, type, !next);
      setActionError(getErrorMessage(err, '설정을 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.'));
    } finally {
      setPendingToggles((current) => {
        const rest = new Set(current);
        rest.delete(key);
        return rest;
      });
    }
  };

  const handleRemoveDevice = async (deviceId: string) => {
    setActionError(null);
    setRemovingId(deviceId);
    try {
      await notifyApi.removeDevice(deviceId);
      setDevices((current) => current.filter((d) => d.deviceId !== deviceId));
    } catch (err: unknown) {
      setActionError(getErrorMessage(err, '기기를 해제하지 못했습니다. 잠시 후 다시 시도해 주세요.'));
    } finally {
      setRemovingId(null);
    }
  };

  const handleTest = async () => {
    setActionError(null);
    setTestMessage(null);
    setTesting(true);
    try {
      const result = await notifyApi.sendTest();
      setTestMessage(describeTestResult(result.sent));
    } catch (err: unknown) {
      setActionError(getErrorMessage(err, '시험 알림을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.'));
    } finally {
      setTesting(false);
    }
  };

  return (
    <section aria-labelledby="notify-settings-title" className="glass-card google-card-shadow rounded-3xl p-6 sm:p-8" data-testid="notify-settings">
      <div className="flex items-start gap-3 mb-5">
        <div className="w-10 h-10 shrink-0 rounded-xl bg-indigo-50 text-indigo-600 flex items-center justify-center">
          <Bell className="w-5 h-5" aria-hidden="true" />
        </div>
        <div className="min-w-0">
          <h3 id="notify-settings-title" className="text-lg font-bold text-slate-800">알림</h3>
          <p className="text-xs text-slate-500 mt-1 leading-relaxed">서비스별로 어떤 알림을 받을지 정하고, 알림을 받는 기기를 관리해요.</p>
        </div>
      </div>

      {phase === 'loading' && (
        <div className="flex items-center gap-2 text-sm text-slate-500 py-6" role="status">
          <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" /> 알림 설정을 불러오는 중…
        </div>
      )}

      {phase === 'unavailable' && (
        <div className="flex items-start gap-2 text-sm text-slate-500 bg-slate-50 border border-slate-200 rounded-2xl p-4" role="status">
          <BellOff className="w-4 h-4 mt-0.5 shrink-0" aria-hidden="true" />
          <span>알림 서비스를 사용할 수 없습니다. 계정의 다른 기능은 그대로 쓸 수 있어요.</span>
        </div>
      )}

      {phase === 'error' && (
        <div className="space-y-3" role="alert">
          <p className="text-sm text-red-600 break-words">{loadError}</p>
          <button
            type="button"
            onClick={() => void load(() => false)}
            className="inline-flex items-center gap-1.5 min-h-[40px] px-4 bg-slate-100 hover:bg-slate-200 text-slate-700 text-xs font-bold rounded-xl cursor-pointer"
          >
            <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" /> 다시 시도
          </button>
        </div>
      )}

      {phase === 'ready' && (
        <div className="space-y-6">
          {/* 1. 이 브라우저의 권한과 서버 상태 */}
          <div className="space-y-2">
            <div className="flex flex-wrap items-center gap-2 text-xs">
              <span className="font-bold text-slate-700">이 브라우저의 알림 권한</span>
              <span className={`px-2.5 py-1 rounded-full border font-bold ${PERMISSION_TONE[permission.tone]}`}>{permission.label}</span>
              {config && !config.available && (
                <span className="px-2.5 py-1 rounded-full border font-bold text-amber-700 bg-amber-50 border-amber-200">서버 알림 꺼짐</span>
              )}
            </div>
            <p className="text-xs text-slate-500 leading-relaxed break-words">{permission.hint}</p>
            {config && !config.available && (
              <p className="text-xs text-amber-700 leading-relaxed">서버에서 알림 발송이 아직 준비되지 않았어요. 설정은 저장되지만 알림은 가지 않아요.</p>
            )}
          </div>

          {actionError && (
            <p className="text-xs text-red-600 bg-red-50 border border-red-100 rounded-xl p-3 break-words" role="alert">{actionError}</p>
          )}

          {/* 2. 앱별 카드 */}
          <div className="space-y-4">
            {apps.length === 0 && <p className="text-sm text-slate-500">설정할 수 있는 알림이 아직 없어요.</p>}
            {apps.map((app) => {
              const visibleTypes = app.types.filter((t) => !t.deprecated);
              const onThisDevice = isAppOnThisDevice(devices, localDeviceId, app.app);
              const appUrl = appUrlFor(app.app);
              return (
                <div key={app.app} className="rounded-2xl border border-slate-200 p-4" data-testid={`notify-app-${app.app}`}>
                  <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
                    <h4 className="text-sm font-bold text-slate-800 min-w-0 break-words">{app.displayName}</h4>
                    {onThisDevice ? (
                      <span className="text-xs font-bold text-emerald-700 bg-emerald-50 border border-emerald-200 px-2.5 py-1 rounded-full">이 기기에서 켜짐</span>
                    ) : (
                      <span className="flex flex-wrap items-center gap-2 text-xs">
                        <span className="font-bold text-slate-500 bg-slate-50 border border-slate-200 px-2.5 py-1 rounded-full">이 기기에서 꺼짐</span>
                        {appUrl && (
                          <a
                            href={appUrl}
                            aria-label={`${app.displayName}에서 알림 켜기`}
                            className="inline-flex items-center gap-1 min-h-[32px] font-semibold text-indigo-600 hover:text-indigo-700"
                          >
                            앱에서 켜기 <ExternalLink className="w-3 h-3" aria-hidden="true" />
                          </a>
                        )}
                      </span>
                    )}
                  </div>
                  {visibleTypes.length === 0 ? (
                    <p className="text-xs text-slate-500">이 앱에서 고를 수 있는 알림 종류가 없어요.</p>
                  ) : (
                    <ul className="divide-y divide-slate-100">
                      {visibleTypes.map((t) => {
                        const pending = pendingToggles.has(toggleKey(app.app, t.type));
                        return (
                          <li key={t.type} className="flex items-center justify-between gap-3 py-2.5">
                            <div className="min-w-0">
                              <p className="text-sm text-slate-700 break-words">{t.label}</p>
                              {t.description && <p className="text-xs text-slate-500 mt-0.5 break-words">{t.description}</p>}
                            </div>
                            <button
                              type="button"
                              role="switch"
                              aria-checked={t.enabled}
                              aria-label={`${app.displayName} ${t.label} 알림`}
                              disabled={pending}
                              onClick={() => void handleToggle(app.app, t.type, !t.enabled)}
                              className={`relative shrink-0 w-12 h-8 rounded-full transition-colors cursor-pointer disabled:opacity-60 ${t.enabled ? 'bg-indigo-600' : 'bg-slate-300'}`}
                            >
                              <span className={`absolute top-1 left-1 w-6 h-6 rounded-full bg-white shadow transition-transform ${t.enabled ? 'translate-x-4' : ''}`} />
                            </button>
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </div>
              );
            })}
          </div>

          {/* 3. 내 기기 목록 */}
          <div className="space-y-3">
            <h4 className="text-sm font-bold text-slate-800">알림을 받는 내 기기</h4>
            {devices.length === 0 ? (
              <p className="text-sm text-slate-500">알림을 받는 기기가 없어요. 앱에서 알림을 켜면 여기에 나타나요.</p>
            ) : (
              <ul className="space-y-3">
                {devices.map((device) => {
                  const isCurrent = localDeviceId !== null && device.deviceId === localDeviceId;
                  const browser = device.browser ?? '알 수 없는 브라우저';
                  return (
                    <li key={device.deviceId} className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 rounded-2xl border border-slate-200 p-4">
                      <div className="flex items-start gap-3 min-w-0">
                        <Smartphone className="w-5 h-5 mt-0.5 shrink-0 text-slate-400" aria-hidden="true" />
                        <div className="min-w-0 text-xs text-slate-500 space-y-0.5">
                          <p className="text-sm font-bold text-slate-800 break-words">
                            {browser}
                            {isCurrent && <span className="ml-2 text-xs font-bold text-indigo-600">이 기기</span>}
                          </p>
                          <p>마지막 성공: {formatLastSuccess(device.lastSuccessAt)}</p>
                          <p className="break-words">켜진 앱: {device.apps.length > 0 ? device.apps.map((a) => apps.find((x) => x.app === a)?.displayName ?? a).join(', ') : '없음'}</p>
                        </div>
                      </div>
                      <button
                        type="button"
                        onClick={() => void handleRemoveDevice(device.deviceId)}
                        disabled={removingId === device.deviceId}
                        aria-label={`${browser} 기기 해제`}
                        className="shrink-0 inline-flex items-center justify-center gap-1.5 min-h-[40px] px-3.5 bg-red-50 hover:bg-red-100 text-red-600 rounded-xl text-xs font-bold transition-colors cursor-pointer disabled:opacity-60"
                      >
                        {removingId === device.deviceId ? <Loader2 className="w-3.5 h-3.5 animate-spin" aria-hidden="true" /> : <Trash2 className="w-3.5 h-3.5" aria-hidden="true" />}
                        이 기기 해제
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>

          {/* 4. 시험 알림 */}
          <div className="space-y-2">
            <button
              type="button"
              onClick={() => void handleTest()}
              disabled={testing || devices.length === 0}
              className="inline-flex items-center justify-center gap-1.5 min-h-[44px] px-4 bg-indigo-50 hover:bg-indigo-100 text-indigo-700 text-xs font-bold rounded-xl transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
            >
              {testing ? <Loader2 className="w-3.5 h-3.5 animate-spin" aria-hidden="true" /> : <Bell className="w-3.5 h-3.5" aria-hidden="true" />}
              시험 알림 보내기
            </button>
            <p className="text-xs text-slate-600 break-words" role="status" aria-live="polite">{testMessage}</p>
          </div>
        </div>
      )}
    </section>
  );
};
