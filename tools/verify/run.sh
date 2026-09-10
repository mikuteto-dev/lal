#!/bin/sh
# Repeatable verification for the changes made to this mod.
#
#   sh tools/verify/run.sh
#
# Uses the project's own compiled classes and dependency jars; adds nothing to the build.
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/tools/verify/out"
cd "$ROOT"

echo "== building =="
./gradlew -q classes

echo "== collecting classpath =="
cat > /tmp/lal-printcp.gradle <<'G'
allprojects { tasks.register('printMainCp') { doLast { println "CPSTART" + sourceSets.main.runtimeClasspath.asPath + "CPEND" } } }
G
./gradlew --init-script /tmp/lal-printcp.gradle printMainCp -q --no-daemon --console=plain 2>/dev/null \
  | tr -d '\n' | sed 's/.*CPSTART//; s/CPEND.*//' > /tmp/lal_cp.txt
CP="$(cat /tmp/lal_cp.txt)"
SJ=$(find "$HOME/.gradle/caches" -name 'securejarhandler-*.jar' 2>/dev/null | grep -v sources | head -1)
CP="$CP:$SJ"
# Forge's jar handling needs the same module opens the game launcher passes.
OPENS="--add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED \
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED \
--add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED \
--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"

rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -cp "$CP" -d "$OUT" "$ROOT"/tools/verify/*.java

echo "== Check: ASM operand derivation + owner precision =="
java -cp "$OUT:$CP" Check
echo "== RuntimeCheck: Runtime exit guard (native halt skipped) =="
java -cp "$OUT:$CP" RuntimeCheck
echo "== TargetCheck: ITransformer wiring and target names =="
java -cp "$OUT:$CP" TargetCheck

echo "== RealClassCheck: transform the real Minecraft classes and verify them =="
java -cp "$OUT:$CP" RealClassCheck

echo "== EarlyInitCheck: no injected method is reachable from a <clinit> =="
MCJAR=$(find "$HOME/.gradle/caches/forge_gradle" "$ROOT/build/fg_cache" -name 'forge-*_mapped_official_1.20.1.jar' 2>/dev/null | head -1)
java -cp "$OUT:$CP" EarlyInitCheck "$MCJAR"

echo "== DiscoveryCheck2: Forge's real mod-jar discovery against a simulated mods/ install =="
JAR=$(ls -t "$ROOT"/build/libs/lal-*.jar | grep -v no-transformer | head -1)
java $OPENS -cp "$OUT:$CP" DiscoveryCheck2 "$JAR"
