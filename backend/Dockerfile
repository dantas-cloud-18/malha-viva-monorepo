# Build multi-stage; imagens eclipse-temurin sao multi-arch (amd64 + arm64).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew && ./gradlew installDist --no-daemon -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home malhaviva
WORKDIR /app
COPY --from=build /src/build/install/malha-viva-backend/ /app/
USER malhaviva
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/app/bin/malha-viva-backend"]
