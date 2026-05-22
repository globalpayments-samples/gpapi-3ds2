FROM maven:3.9-eclipse-temurin-21

WORKDIR /app

# Download dependencies first (cached layer — only reruns when pom.xml changes)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src
RUN mvn -q -DskipTests package

EXPOSE 8080

CMD ["mvn", "cargo:run", "-DskipTests", "-Dquiet", "-Dgpapi.cargo.port=8080"]
