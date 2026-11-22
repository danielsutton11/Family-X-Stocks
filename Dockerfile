FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/torn-stock-bot.jar app.jar
# A fixed, small heap instead of a share of the container, so memory (Railway's main cost) stays low.
# Serial GC and C1-only JIT suit a single-user bot that is idle most of the time; heap is handed back
# to the OS when it isn't needed. Override JAVA_OPTS on Railway if you ever need more.
ENV JAVA_OPTS="-Xms32m -Xmx192m -Xss512k -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 \
 -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
