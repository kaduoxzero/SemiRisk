# SemiRisk Monolith

`semirisk-monolith` is a course-project style single-process application. It serves the static risk portal and the `/prod-api/risk/**` APIs in one Spring Boot service.

## Real Data Sources

The application does not seed fake business rows. On startup and on schedule it crawls:

- CISA Known Exploited Vulnerabilities JSON
- USGS Significant Earthquakes GeoJSON

Crawler URLs are configured in `src/main/resources/application.yml`. If a source fails, the system keeps existing real rows or shows empty states; it does not create fallback mock data.

## Run

```bash
./mvnw -pl semirisk-monolith -am package
java -jar semirisk-monolith/target/semirisk-monolith.jar
```

Open:

```text
http://localhost:8080/
```

Docker:

```bash
docker compose -f semirisk-monolith/docker-compose.yml up -d --build
```

Manual crawl endpoint:

```bash
curl -X POST http://localhost:8080/prod-api/risk/crawler/run
```
