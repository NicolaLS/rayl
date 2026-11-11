# Packages the distribution from `./gradlew :backend:rayl:installDist`; see README.md.
FROM eclipse-temurin:21-jre
COPY build/install/rayl /app
ENV HOST=0.0.0.0
# The server needs no privileges: it only reads /app and listens on a high port.
USER 65534:65534
CMD ["/app/bin/rayl"]
