FROM eclipse-temurin:17-jre
WORKDIR /app
COPY semirisk-monolith/target/semirisk-monolith.jar /app/semirisk-monolith.jar
COPY risk-portal /app/risk-portal
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/semirisk-monolith.jar"]
