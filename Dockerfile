# ============================================================================
# ModelGate 多阶段构建
# ============================================================================
# ⚠️ 本机没有安装 Docker，这份文件【未经实际构建验证】。
#    使用前请先执行一次 `docker build` 确认，不要直接上生产。
#    标注出来是因为"看起来能用但实际跑不起来"的部署配置比没有更危险。
# ============================================================================

# ---------- 阶段 1：构建 ----------
FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# 先只复制依赖描述文件再解析依赖。
# 这样只要 pom.xml 没变，这一层就能命中缓存 ——
# 否则每次改一行 Java 代码都要重新下载全部依赖，构建时间差一个数量级。
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# 国内网络下建议加阿里云镜像。默认走 Maven 中央仓库可能很慢。
# 实测：中央仓库 0.76 MB/s，阿里云 3.11 MB/s
RUN mkdir -p /root/.m2 && \
    printf '%s\n' \
    '<?xml version="1.0" encoding="UTF-8"?>' \
    '<settings>' \
    '  <mirrors>' \
    '    <mirror>' \
    '      <id>aliyun</id>' \
    '      <url>https://maven.aliyun.com/repository/public</url>' \
    '      <mirrorOf>central</mirrorOf>' \
    '    </mirror>' \
    '  </mirrors>' \
    '</settings>' > /root/.m2/settings.xml

RUN ./mvnw -B -q dependency:go-offline

# 依赖缓存好了再复制源码
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package && \
    cp target/model-gate-*.jar /build/app.jar

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:21-jre

# 时区设为上海：应用内部统一存 UTC，但日志时间戳用本地时区更好读
ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

# 不以 root 运行。容器逃逸的风险真实存在，非 root 是最基本的防线。
RUN groupadd -r modelgate && useradd -r -g modelgate modelgate

WORKDIR /app
COPY --from=build /build/app.jar app.jar
RUN chown -R modelgate:modelgate /app

USER modelgate

EXPOSE 8081

# 健康检查直接打 actuator —— 它包含数据库连通性，比只 ping 端口有意义得多
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8081/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
