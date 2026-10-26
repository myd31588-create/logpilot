# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom，让依赖层能缓存住；改代码不用重新拉依赖
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 不用 root 跑应用
RUN useradd -r -u 1001 appuser
COPY --from=build /build/target/logpilot.jar app.jar
USER appuser

EXPOSE 8080

# 容器里堆内存要显式限制，否则 JVM 会按宿主机内存算，容易 OOM Kill
ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8"

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD bash -c 'echo > /dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
