import React, { useState, useRef, useEffect, useCallback } from 'react';
import { authApi } from '../../api/authApi';
import { useAuthStore } from '../../store/authStore';
import {
  X,
  Upload,
  Trash2,
  Check,
  Loader2,
  Camera,
  Sparkles,
  ZoomIn,
  ZoomOut,
  RotateCcw,
  Crop,
} from 'lucide-react';
import { getErrorMessage } from '../../utils/errorUtils';

interface AvatarModalProps {
  isOpen: boolean;
  onClose: () => void;
  currentAvatarUrl?: string | null;
  onAvatarUpdated: (newUrl: string | null) => void;
}

const PRESET_AVATARS = [
  'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="50" fill="%234F46E5"/><circle cx="50" cy="40" r="18" fill="%23E0E7FF"/><path d="M22 84c0-16 12-28 28-28s28 12 28 28" fill="%23C7D2FE"/></svg>',
  'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="50" fill="%23F97316"/><circle cx="50" cy="40" r="18" fill="%23FFEDD5"/><path d="M22 84c0-16 12-28 28-28s28 12 28 28" fill="%23FED7AA"/></svg>',
  'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="50" fill="%2310B981"/><circle cx="50" cy="40" r="18" fill="%23D1FAE5"/><path d="M22 84c0-16 12-28 28-28s28 12 28 28" fill="%23A7F3D0"/></svg>',
  'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="50" fill="%238B5CF6"/><circle cx="50" cy="40" r="18" fill="%23EDE9FE"/><path d="M22 84c0-16 12-28 28-28s28 12 28 28" fill="%23DDD6FE"/></svg>',
  'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="50" fill="%23EC4899"/><circle cx="50" cy="40" r="18" fill="%23FCE7F3"/><path d="M22 84c0-16 12-28 28-28s28 12 28 28" fill="%23FBCFE8"/></svg>',
  'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="50" fill="%2306B6D4"/><circle cx="50" cy="40" r="18" fill="%23CFFAFE"/><path d="M22 84c0-16 12-28 28-28s28 12 28 28" fill="%23A5F3FC"/></svg>',
  'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="50" fill="%23F59E0B"/><circle cx="50" cy="40" r="18" fill="%23FEF3C7"/><path d="M22 84c0-16 12-28 28-28s28 12 28 28" fill="%23FDE68A"/></svg>',
  'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="50" fill="%23334155"/><circle cx="50" cy="40" r="18" fill="%23F1F5F9"/><path d="M22 84c0-16 12-28 28-28s28 12 28 28" fill="%23E2E8F0"/></svg>',
];

const CROP_SIZE = 260; // 뷰포트 및 캔버스 지름 (원형 통일)

export const AvatarModal: React.FC<AvatarModalProps> = ({
  isOpen,
  onClose,
  currentAvatarUrl,
  onAvatarUpdated,
}) => {
  const { updateActiveProfile } = useAuthStore();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [selectedAvatar, setSelectedAvatar] = useState<string | null>(currentAvatarUrl || null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 크롭 모드 상태
  const [isCropping, setIsCropping] = useState(false);
  const [imageEl, setImageEl] = useState<HTMLImageElement | null>(null);
  const [zoom, setZoom] = useState(1.0);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [isDragging, setIsDragging] = useState(false);
  const dragStartRef = useRef({ x: 0, y: 0 });
  const panStartRef = useRef({ x: 0, y: 0 });
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (isOpen) {
      setSelectedAvatar(currentAvatarUrl || null);
      setIsCropping(false);
      setError(null);
    }
  }, [isOpen, currentAvatarUrl]);

  // 로컬 파일 선택 시 크롭 모드로 진입
  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    if (!file.type.startsWith('image/')) {
      setError('이미지 파일(PNG, JPG, WebP)만 업로드할 수 있습니다.');
      return;
    }

    if (file.size > 8 * 1024 * 1024) {
      setError('이미지 파일 크기는 8MB 이하여야 합니다.');
      return;
    }

    setError(null);
    const reader = new FileReader();
    reader.onload = (event) => {
      const src = event.target?.result as string;
      const img = new Image();
      img.onload = () => {
        setImageEl(img);
        setZoom(1.0);
        setPan({ x: 0, y: 0 });
        setIsCropping(true);
      };
      img.src = src;
    };
    reader.readAsDataURL(file);
    e.target.value = '';
  };

  // 캔버스에 실시간 원형 가이드 렌더링 (빈 공간 없이 100% 꽉 차도록 계산)
  const drawCropPreview = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas || !imageEl) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    canvas.width = CROP_SIZE;
    canvas.height = CROP_SIZE;

    // 1. 캔버스 초기화
    ctx.clearRect(0, 0, CROP_SIZE, CROP_SIZE);

    // 2. 이미지가 원형 영역을 100% 가득 채우도록 기본 스케일 계산 (빈 공간 원천 차단)
    const baseScale = Math.max(CROP_SIZE / imageEl.width, CROP_SIZE / imageEl.height);
    const currentScale = baseScale * zoom;
    const drawWidth = imageEl.width * currentScale;
    const drawHeight = imageEl.height * currentScale;

    // 3. 사진이 뷰포트 밖으로 벗어나 빈 공간이 생기지 않도록 이동(Pan) 제한
    const maxPanX = Math.max(0, (drawWidth - CROP_SIZE) / 2);
    const maxPanY = Math.max(0, (drawHeight - CROP_SIZE) / 2);
    const clampedPanX = Math.min(maxPanX, Math.max(-maxPanX, pan.x));
    const clampedPanY = Math.min(maxPanY, Math.max(-maxPanY, pan.y));

    const drawX = (CROP_SIZE - drawWidth) / 2 + clampedPanX;
    const drawY = (CROP_SIZE - drawHeight) / 2 + clampedPanY;

    // 4. 이미지 렌더링
    ctx.drawImage(imageEl, drawX, drawY, drawWidth, drawHeight);
  }, [imageEl, zoom, pan]);

  useEffect(() => {
    if (isCropping && imageEl) {
      drawCropPreview();
    }
  }, [isCropping, imageEl, zoom, pan, drawCropPreview]);

  // 마우스 드래그 이동
  const handleMouseDown = (e: React.MouseEvent) => {
    setIsDragging(true);
    dragStartRef.current = { x: e.clientX, y: e.clientY };
    panStartRef.current = { ...pan };
  };

  const handleMouseMove = (e: React.MouseEvent) => {
    if (!isDragging || !imageEl) return;
    const dx = e.clientX - dragStartRef.current.x;
    const dy = e.clientY - dragStartRef.current.y;

    const baseScale = Math.max(CROP_SIZE / imageEl.width, CROP_SIZE / imageEl.height);
    const currentScale = baseScale * zoom;
    const drawWidth = imageEl.width * currentScale;
    const drawHeight = imageEl.height * currentScale;
    const maxPanX = Math.max(0, (drawWidth - CROP_SIZE) / 2);
    const maxPanY = Math.max(0, (drawHeight - CROP_SIZE) / 2);

    const targetX = panStartRef.current.x + dx;
    const targetY = panStartRef.current.y + dy;

    setPan({
      x: Math.min(maxPanX, Math.max(-maxPanX, targetX)),
      y: Math.min(maxPanY, Math.max(-maxPanY, targetY)),
    });
  };

  const handleMouseUp = () => {
    setIsDragging(false);
  };

  // 휠 스크롤 줌
  const handleWheel = (e: React.WheelEvent) => {
    e.preventDefault();
    const delta = e.deltaY > 0 ? -0.1 : 0.1;
    setZoom((prev) => Math.min(3.0, Math.max(1.0, parseFloat((prev + delta).toFixed(2)))));
  };

  // 크롭 완료 및 256x256 원형 Data URL 추출
  const handleApplyCrop = () => {
    if (!imageEl) return;
    const exportCanvas = document.createElement('canvas');
    const targetSize = 256;
    exportCanvas.width = targetSize;
    exportCanvas.height = targetSize;
    const ctx = exportCanvas.getContext('2d');
    if (!ctx) return;

    const baseScale = Math.max(CROP_SIZE / imageEl.width, CROP_SIZE / imageEl.height);
    const currentScale = baseScale * zoom;
    const drawWidth = imageEl.width * currentScale;
    const drawHeight = imageEl.height * currentScale;

    const maxPanX = Math.max(0, (drawWidth - CROP_SIZE) / 2);
    const maxPanY = Math.max(0, (drawHeight - CROP_SIZE) / 2);
    const clampedPanX = Math.min(maxPanX, Math.max(-maxPanX, pan.x));
    const clampedPanY = Math.min(maxPanY, Math.max(-maxPanY, pan.y));

    const drawX = (CROP_SIZE - drawWidth) / 2 + clampedPanX;
    const drawY = (CROP_SIZE - drawHeight) / 2 + clampedPanY;

    // 타겟 256x256 캔버스에 꽉 차게 렌더링
    const scale = targetSize / CROP_SIZE;
    ctx.drawImage(
      imageEl,
      drawX * scale,
      drawY * scale,
      drawWidth * scale,
      drawHeight * scale
    );

    // 고화질 JPEG 추출 (빈 공간 없이 꽉 차 있으므로 검은 여백 절대 없음)
    const croppedDataUrl = exportCanvas.toDataURL('image/jpeg', 0.92);
    setSelectedAvatar(croppedDataUrl);
    setIsCropping(false);
  };

  // 프로필 사진 저장
  const handleSave = async () => {
    setLoading(true);
    setError(null);
    try {
      await authApi.updateProfile({ profileImageUrl: selectedAvatar });
      updateActiveProfile(undefined, selectedAvatar);
      onAvatarUpdated(selectedAvatar);
      onClose();
    } catch (err: unknown) {
      setError(getErrorMessage(err, '프로필 사진 저장에 실패했습니다.'));
    } finally {
      setLoading(false);
    }
  };

  // 사진 삭제
  const handleRemove = async () => {
    setLoading(true);
    setError(null);
    try {
      await authApi.updateProfile({ profileImageUrl: null });
      updateActiveProfile(undefined, null);
      setSelectedAvatar(null);
      onAvatarUpdated(null);
      onClose();
    } catch (err: unknown) {
      setError(getErrorMessage(err, '프로필 사진 삭제에 실패했습니다.'));
    } finally {
      setLoading(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-xs"
      onClick={onClose}
    >
      <div
        className="glass-card w-full max-w-lg rounded-3xl p-6 sm:p-8 shadow-2xl animate-in fade-in zoom-in-95 duration-200"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between pb-4 border-b border-slate-100">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-xl bg-indigo-50 text-indigo-600 flex items-center justify-center">
              {isCropping ? <Crop className="w-4 h-4" /> : <Camera className="w-4 h-4" />}
            </div>
            <div>
              <h3 className="text-base font-bold text-slate-800">
                {isCropping ? '프로필 사진 자르기' : '프로필 사진 변경'}
              </h3>
              <p className="text-[11px] text-slate-400">
                {isCropping ? '원하는 위치로 드래그하고 확대해 보세요' : '모든 Doro 서비스에 표시될 사진입니다'}
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-slate-600 p-1.5 rounded-full hover:bg-slate-100 cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {error && (
          <div className="mt-4 p-3 bg-red-50 border border-red-200 rounded-2xl text-xs text-red-700">
            {error}
          </div>
        )}

        {/* CROP VIEW MODE - 완전 원형(Circle) 통일 */}
        {isCropping ? (
          <div className="mt-6 flex flex-col items-center">
            {/* Interactive Pure Circular Viewport */}
            <div
              className="relative w-[260px] h-[260px] rounded-full overflow-hidden shadow-2xl border-4 border-white ring-4 ring-indigo-500/20 cursor-grab active:cursor-grabbing select-none bg-slate-100 flex items-center justify-center transition-shadow hover:ring-indigo-500/30"
              onMouseDown={handleMouseDown}
              onMouseMove={handleMouseMove}
              onMouseUp={handleMouseUp}
              onMouseLeave={handleMouseUp}
              onWheel={handleWheel}
            >
              <canvas ref={canvasRef} className="block w-full h-full rounded-full" />
            </div>

            {/* Zoom Slider Control */}
            <div className="w-full max-w-xs mt-6 flex items-center gap-3">
              <button
                type="button"
                onClick={() => setZoom((prev) => Math.max(1.0, parseFloat((prev - 0.2).toFixed(2))))}
                className="p-1.5 text-slate-400 hover:text-indigo-600 rounded-lg hover:bg-slate-100 cursor-pointer"
                title="축소"
              >
                <ZoomOut className="w-4 h-4" />
              </button>

              <input
                type="range"
                min="1.0"
                max="3.0"
                step="0.05"
                value={zoom}
                onChange={(e) => setZoom(parseFloat(e.target.value))}
                className="flex-1 accent-indigo-600 cursor-pointer"
              />

              <button
                type="button"
                onClick={() => setZoom((prev) => Math.min(3.0, parseFloat((prev + 0.2).toFixed(2))))}
                className="p-1.5 text-slate-400 hover:text-indigo-600 rounded-lg hover:bg-slate-100 cursor-pointer"
                title="확대"
              >
                <ZoomIn className="w-4 h-4" />
              </button>

              <button
                type="button"
                onClick={() => {
                  setZoom(1.0);
                  setPan({ x: 0, y: 0 });
                }}
                className="p-1.5 text-slate-400 hover:text-indigo-600 rounded-lg hover:bg-slate-100 cursor-pointer"
                title="위치/줌 초기화"
              >
                <RotateCcw className="w-4 h-4" />
              </button>
            </div>

            <p className="text-[11px] text-slate-400 mt-2">
              사진을 잡고 끌어서 위치를 맞추거나 휠로 확대할 수 있습니다
            </p>

            {/* Crop Action Buttons */}
            <div className="w-full mt-6 pt-4 border-t border-slate-100 flex items-center justify-between gap-3">
              <button
                type="button"
                onClick={() => setIsCropping(false)}
                className="py-2.5 px-4 border border-slate-200 hover:bg-slate-50 rounded-xl text-xs font-bold text-slate-600 transition-colors cursor-pointer"
              >
                취소
              </button>

              <button
                type="button"
                onClick={handleApplyCrop}
                className="py-2.5 px-6 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition-all shadow-md shadow-indigo-500/20 flex items-center gap-1.5 cursor-pointer"
              >
                <Check className="w-4 h-4" />
                선택 영역 자르기 완료
              </button>
            </div>
          </div>
        ) : (
          /* STANDARD PREVIEW & PRESET VIEW */
          <>
            {/* Current Preview */}
            <div className="mt-6 flex flex-col items-center">
              <div className="relative group">
                <div className="w-28 h-28 rounded-full overflow-hidden border-4 border-white shadow-xl bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center text-white text-3xl font-black">
                  {selectedAvatar ? (
                    <img src={selectedAvatar} alt="Avatar Preview" className="w-full h-full object-cover" />
                  ) : (
                    'U'
                  )}
                </div>

                <button
                  onClick={() => fileInputRef.current?.click()}
                  className="absolute bottom-0 right-0 p-2 bg-indigo-600 text-white rounded-full hover:bg-indigo-700 shadow-md transition-transform hover:scale-105 cursor-pointer"
                  title="컴퓨터에서 사진 업로드"
                >
                  <Upload className="w-4 h-4" />
                </button>
              </div>
              <p className="text-xs text-slate-500 mt-2.5 font-medium">현재 미리보기</p>

              <input
                type="file"
                ref={fileInputRef}
                onChange={handleFileChange}
                accept="image/png, image/jpeg, image/webp"
                className="hidden"
              />
            </div>

            {/* Upload Button */}
            <div className="mt-6">
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                className="w-full py-2.5 px-4 border-2 border-dashed border-indigo-200 hover:border-indigo-500 bg-indigo-50/40 hover:bg-indigo-50/80 rounded-2xl text-xs font-bold text-indigo-700 flex items-center justify-center gap-2 transition-colors cursor-pointer"
              >
                <Upload className="w-4 h-4" />
                사진 파일 선택 및 영역 자르기 (PNG, JPG, WebP)
              </button>
            </div>

            {/* Preset Avatars Selection */}
            <div className="mt-6">
              <div className="flex items-center gap-1.5 mb-3 text-xs font-bold text-slate-700">
                <Sparkles className="w-3.5 h-3.5 text-indigo-600" />
                <span>또는 추천 프로필 아바타 선택</span>
              </div>

              <div className="grid grid-cols-4 sm:grid-cols-8 gap-3">
                {PRESET_AVATARS.map((preset, index) => {
                  const isSelected = selectedAvatar === preset;
                  return (
                    <button
                      key={index}
                      type="button"
                      onClick={() => setSelectedAvatar(preset)}
                      className={`relative w-11 h-11 rounded-full overflow-hidden border-2 transition-transform hover:scale-110 cursor-pointer ${
                        isSelected
                          ? 'border-indigo-600 ring-2 ring-indigo-500/30 scale-105'
                          : 'border-transparent hover:border-slate-300'
                      }`}
                    >
                      <img src={preset} alt={`Preset ${index + 1}`} className="w-full h-full object-cover" />
                      {isSelected && (
                        <div className="absolute inset-0 bg-indigo-600/40 flex items-center justify-center text-white">
                          <Check className="w-4 h-4 stroke-[3]" />
                        </div>
                      )}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Action Buttons */}
            <div className="mt-8 pt-4 border-t border-slate-100 flex items-center justify-between gap-3">
              {selectedAvatar ? (
                <button
                  type="button"
                  onClick={handleRemove}
                  disabled={loading}
                  className="py-2.5 px-4 rounded-xl border border-red-200 hover:bg-red-50 text-red-600 text-xs font-bold flex items-center gap-1.5 transition-colors cursor-pointer disabled:opacity-50"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  사진 삭제
                </button>
              ) : (
                <div></div>
              )}

              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={onClose}
                  className="py-2.5 px-4 border border-slate-200 hover:bg-slate-50 rounded-xl text-xs font-bold text-slate-600 transition-colors cursor-pointer"
                >
                  취소
                </button>
                <button
                  type="button"
                  onClick={handleSave}
                  disabled={loading}
                  className="py-2.5 px-6 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-xs font-bold transition-all shadow-md shadow-indigo-500/20 flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
                >
                  {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : '프로필 사진 적용'}
                </button>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
};
