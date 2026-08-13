#!/bin/bash
# 清理重复的 import java.util.Locale;
set -e

DUPS=(
  chat-games/src/main/java/com/example/chat/controller/SqlExecutorController.java
  chat-core/src/main/java/com/example/chat/observability/TraceRecorder.java
  chat-core/src/main/java/com/example/chat/langgraph4j/DebateNodes.java
  chat-core/src/main/java/com/example/chat/service/TreeHoleService.java
  chat-core/src/main/java/com/example/chat/service/ModelRouter.java
  chat-core/src/main/java/com/example/chat/service/ChatProcessor.java
  chat-core/src/main/java/com/example/chat/router/ModelRouter.java
)

for f in "${DUPS[@]}"; do
  echo "Cleaning: $f"
  # Remove all Locale imports and add one back at the right position  
  python3 -c "
import re
with open('$f') as fh: content = fh.read()
lines = content.split('\n')
new_lines = []
locale_added = False
for line in lines:
    if line.strip() == 'import java.util.Locale;':
        if not locale_added:
            new_lines.append(line)
            locale_added = True
        continue
    new_lines.append(line)
with open('$f', 'w') as fh: fh.write('\n'.join(new_lines))
"
done
echo "Done"
