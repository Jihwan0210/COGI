# 1단계: 빌드 (Vite로 정적 파일 dist 생성)
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# 2단계: 실행 (nginx가 정적 파일을 서빙하고 API 요청은 백엔드로 전달)
FROM nginx:1.27-alpine
# templates/*.template은 컨테이너 시작 시 환경변수가 치환되어 conf.d로 들어간다
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# 같은 ECS 작업 안이면 localhost, docker compose면 backend:8080으로 덮어쓴다
ENV BACKEND_HOST=localhost:8080
EXPOSE 80
