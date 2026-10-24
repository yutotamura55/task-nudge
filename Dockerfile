# Build and host-test environment for scripts/check.sh. Run it through
# scripts/container-check.sh; emulator and device tests stay on the host.
# Update the versions together and rerun scripts/container-check.sh.
FROM eclipse-temurin:17.0.20.1_1-jdk-jammy@sha256:73ed442231ec66cfe48cae0b263ce619a75ab96d162ab325f38fc0a269ee3aab

ARG CMDLINE_TOOLS_ZIP=commandlinetools-linux-13114758_latest.zip
ARG CMDLINE_TOOLS_SHA256=7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl \
 && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
 && curl -fsSL -o /tmp/tools.zip "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP" \
 && echo "$CMDLINE_TOOLS_SHA256  /tmp/tools.zip" | sha256sum -c - \
 && unzip -q /tmp/tools.zip -d "$ANDROID_HOME/cmdline-tools" \
 && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
 && rm /tmp/tools.zip
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

# compileSdk 36 and the Build-Tools version AGP 8.13 uses by default. The SDK is
# read-only for the build user, so a missing package fails the build instead of
# being downloaded into a throwaway container on every run.
# sdkmanager cannot pin a platform revision, so a newer one fails the build here
# instead of silently changing android.jar between fresh and cached images.
ARG PLATFORM_REVISION=2
RUN yes | sdkmanager --licenses >/dev/null \
 && sdkmanager "platforms;android-36" "build-tools;35.0.0" >/dev/null \
 && grep -qx "Pkg.Revision=$PLATFORM_REVISION" "$ANDROID_HOME/platforms/android-36/source.properties" \
 && grep -qx "Pkg.Revision=35.0.0" "$ANDROID_HOME/build-tools/35.0.0/source.properties"

# The build runs as the host user, so generated files stay theirs. That UID has
# no passwd entry, and Java would otherwise resolve user.home to "?", which breaks
# Robolectric's download lock. HOME also keeps Robolectric's Android jars, so it
# lives in the cache volume, which scripts/container-check.sh gives to that user.
ENV GRADLE_USER_HOME=/cache/gradle \
    HOME=/cache/home \
    JAVA_TOOL_OPTIONS=-Duser.home=/cache/home
WORKDIR /work
