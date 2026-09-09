# Stage 1: build
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# Stage 2: runtime
FROM amazoncorretto:21-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# AWS Distro for OpenTelemetry Java agent — baked in but INERT unless the task activates it via
# JAVA_TOOL_OPTIONS (-javaagent:/opt/aws-opentelemetry-agent.jar), which only happens when
# enable_xray=true in Terraform. No effect on the default (no-tracing) deployment.
ADD https://github.com/aws-observability/aws-otel-java-instrumentation/releases/latest/download/aws-opentelemetry-agent.jar /opt/aws-opentelemetry-agent.jar
RUN chmod 644 /opt/aws-opentelemetry-agent.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
