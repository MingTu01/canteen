# 管理端 Dockerfile
# 注意:源码依赖仓库根 shared/ 目录(src/utils/* 再导出 ../../../shared/*),
# 构建上下文必须是仓库根,而非本目录:
#   docker build -f admin-web/Dockerfile -t canteen-admin-web .
# (仓库根 .dockerignore 已排除 backend/terminal 等无关目录,控制上下文体积)
FROM node:20-alpine AS builder
WORKDIR /build
COPY admin-web/package.json ./
RUN npm install --registry=https://registry.npmmirror.com
# shared/ 放到镜像内 /shared,使 /build/src/utils 的 ../../../shared 相对导入成立
COPY shared/ /shared/
COPY admin-web/ .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /build/dist /usr/share/nginx/html
COPY admin-web/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
