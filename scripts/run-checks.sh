#!/usr/bin/env bash
# Runs the plain-Java checks in src/checks against the compiled mod classes.
# Needs: a successful `gradle build` (so build/classes and the Loom-mapped Minecraft jar exist).
set -euo pipefail
cd "$(dirname "$0")/.."
MC=$(find .gradle/loom-cache/minecraftMaven -name "minecraft-merged-*-v2.jar" ! -name "*sources*" | head -1)
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
LIBS=$(find "$GRADLE_HOME/caches/modules-2/files-2.1" -name "*.jar" ! -name "*natives*" ! -name "*sources*" ! -path "*fabric-loom*" | tr '\n' ':')
OUT=$(mktemp -d)
CP="build/classes/java/main:$MC:$LIBS"
javac -d "$OUT" -cp "$CP" $(find src/checks/java -name "*.java")
java -cp "$OUT:$CP" RemyBrainCheck
java -cp "$OUT:$CP" com.brandopakel.remy.playerengine.village.PackCheck > /dev/null && echo "PackCheck ran"
# Bootstrapping vanilla registries outside Fabric needs package-private access opened up,
# as Fabric's runtime does; tools/Publicize.java makes such a copy of the mapped Minecraft jar.
PUB="${MC_PUBLIC_JAR:-}"
if [ -n "$PUB" ] && [ -f "$PUB" ]; then
  java -cp "$OUT:build/classes/java/main:$PUB:$LIBS" com.brandopakel.remy.playerengine.architect.BlueprintCheck designs/cottage.json
else
  echo "BlueprintCheck skipped (set MC_PUBLIC_JAR to a publicized Minecraft jar)"
fi
