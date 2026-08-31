FROM docker.m.daocloud.io/library/eclipse-temurin:21-jdk AS build
WORKDIR /src
RUN curl -fsSL -o /tmp/maven.tar.gz https://mirrors.huaweicloud.com/apache/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz \
    && tar -xzf /tmp/maven.tar.gz -C /opt \
    && ln -s /opt/apache-maven-3.9.9/bin/mvn /usr/local/bin/mvn
COPY pom.xml .
COPY .mvn .mvn
COPY src ./src
RUN mvn -s .mvn/settings.xml -q -DskipTests package

FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/sams-backend-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
