FROM maven:3.8.6-eclipse-temurin-11 AS builder

WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests


FROM bitnami/kafka:latest

USER root

RUN apt-get update && apt-get install -y curl unzip

RUN mkdir -p /opt/bitnami/kafka/plugins/snowflake
WORKDIR /opt/bitnami/kafka/plugins/snowflake

RUN curl -LO https://repo1.maven.org/maven2/com/snowflake/snowflake-ingest-sdk/3.1.2/snowflake-ingest-sdk-3.1.2.jar && \
    curl -LO https://repo1.maven.org/maven2/com/snowflake/telemetry/0.1.0/telemetry-0.1.0.jar

COPY --from=builder /app/target/snowflake-kafka-connector-*.jar ./snowflake-kafka-connector.jar

USER 1001

CMD ["/opt/bitnami/kafka/bin/connect-distributed.sh", "/bitnami/kafka/config/connect-distributed.properties"]
