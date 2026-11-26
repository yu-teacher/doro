import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
import { applyProductManifest } from './utils/productManifest';
import './index.css';

// 첫 화면이 /dev 이면 렌더링 전에 Doro Dev 앱 정의로 바꿔 둔다(이후 이동은 App 의 ProductManifestSync 가 맞춘다).
applyProductManifest(window.location.pathname);

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
