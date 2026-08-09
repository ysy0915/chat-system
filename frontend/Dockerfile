# 前端独立部署 Dockerfile（多阶段构建）
# 构建：docker build -t chat-frontend .
# 运行：docker run -d -p 80:80 -e API_PROXY_TARGET=http://172.23.172.13:8081 chat-frontend

# Stage 1: 构建前端
FROM node:18-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 2: Nginx部署
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html/chat
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
