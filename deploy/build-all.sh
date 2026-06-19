FROM eclipse-temurin:21-jre

ENV TZ=Asia/Shanghai

WORKDIR /app

COPY *.jar /app/

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
