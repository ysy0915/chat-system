#!/bin/bash
# 批量修复 toLowerCase()/toUpperCase() → toLowerCase(Locale.ROOT)/toUpperCase(Locale.ROOT)
set -e

FILES=(
  chat-common/src/main/java/com/example/chat/util/BaseUrlResolver.java
  chat-common/src/main/java/com/example/chat/security/IpRateLimitInterceptor.java
  chat-games/src/main/java/com/example/chat/controller/SqlExecutorController.java
  chat-core/src/main/java/com/example/chat/observability/TraceRecorder.java
  chat-core/src/main/java/com/example/chat/observability/ErrorType.java
  chat-core/src/main/java/com/example/chat/rag/service/DocumentParser.java
  chat-core/src/main/java/com/example/chat/langgraph4j/DebateNodes.java
  chat-core/src/main/java/com/example/chat/service/TreeHoleService.java
  chat-core/src/main/java/com/example/chat/service/FileContentExtractor.java
  chat-core/src/main/java/com/example/chat/service/ModelRouter.java
  chat-core/src/main/java/com/example/chat/service/ChatProcessor.java
  chat-core/src/main/java/com/example/chat/factory/LLMStrategyFactory.java
  chat-core/src/main/java/com/example/chat/router/TaskClassifier.java
  chat-core/src/main/java/com/example/chat/router/ModelRouter.java
)

for f in "${FILES[@]}"; do
  echo "Processing: $f"
  if ! grep -q 'import java.util.Locale;' "$f"; then
    sed -i '' 's/^import java\.util\./import java.util.Locale;\
import java.util./' "$f"
  fi
  sed -i '' 's/\.toLowerCase()/.toLowerCase(Locale.ROOT)/g' "$f"
  sed -i '' 's/\.toUpperCase()/.toUpperCase(Locale.ROOT)/g' "$f"
done
echo "All done"
