# 1단계: 빌드 (JDK로 실행 가능한 jar 생성)
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
# 의존성 정의만 먼저 복사해 Gradle 캐시 레이어를 재사용
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q > /dev/null
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# 2단계: 실행 (JRE만 담아 이미지 크기 축소)
FROM eclipse-temurin:25-jre
WORKDIR /app
# 정기결제 작업과 시간 기록이 한국 시간 기준으로 동작하도록
ENV TZ=Asia/Seoul \
    JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Seoul"
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
