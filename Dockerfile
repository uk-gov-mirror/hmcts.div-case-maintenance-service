ARG APP_INSIGHTS_AGENT_VERSION=2.5.0
FROM hmctspublic.azurecr.io/base/java:openjdk-8-distroless-1.2

ENV APP div-case-maintenance-service.jar

COPY lib/AI-Agent.xml /opt/app/
COPY build/libs/$APP /opt/app/

CMD ["div-case-maintenance-service.jar"]
