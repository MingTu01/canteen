# 后端 Dockerfile - 多阶段构建
# C5 Java 版本统一为 25
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build
COPY settings-aliyun.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn dependency:go-offline -B -s /root/.m2/settings.xml
COPY src ./src
RUN mvn clean package -Dmaven.test.skip=true -B -s /root/.m2/settings.xml

FROM eclipse-temurin:25-jre
LABEL maintainer="Enterprise Canteen System"
RUN apt-get update && apt-get install -y --no-install-recommends tzdata curl && \
    ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /build/target/enterprise-canteen-0.0.1.jar app.jar
# 显式创建 uploads 目录:FileController 写入 ./uploads/(容器内 /app/uploads/)
# 运行时也会兜底创建,但显式创建可避免未来切换非 root 用户时权限问题
RUN mkdir -p /app/uploads
EXPOSE 8080
# S14 健康检查使用白名单接口 /api/system/health
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -f http://localhost:8080/api/system/health || exit 1
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-Duser.timezone=Asia/Shanghai", "-jar", "app.jar"]
