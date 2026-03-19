FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/app.jar .
COPY --from=build /app/target/dependency ./dependency
ENV APP_MODE=cliente
CMD ["java", "-cp", "app.jar:dependency/*", "br.fei.cc7261.Main"]
