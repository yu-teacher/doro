# Build Stage
FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
# 선택: 관리자 앱 런처의 OpenAPI 문서 링크 (비어 있으면 항목 숨김)
ARG VITE_GUARD_DOCS_URL=
ARG VITE_IAM_DOCS_URL=
ENV VITE_GUARD_DOCS_URL=$VITE_GUARD_DOCS_URL \
    VITE_IAM_DOCS_URL=$VITE_IAM_DOCS_URL
RUN npm run build

# Production Nginx Stage
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
