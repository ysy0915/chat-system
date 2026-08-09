#!/bin/bash
# ============================================================
# 安全扫描脚本（本地化Harness STO等效方案）
# 扫描密钥泄露、SQL注入风险、依赖漏洞
# ============================================================

PROJECT_ROOT="/Users/apple/IdeaProjects/chat-system-project"
red()    { echo -e "\033[31m[VULN] $1\033[0m"; }
green()  { echo -e "\033[32m[ OK ] $1\033[0m"; }
yellow() { echo -e "\033[33m[WARN] $1\033[0m"; }
blue()   { echo -e "\033[34m[SCAN] $1\033[0m"; }

VULN_COUNT=0
WARN_COUNT=0

echo "============================================================"
echo "  安全扫描报告  $(date)
============================================================"

# ---------- 1. 密钥泄露检测 ----------
blue "========== 1. 密钥泄露检测 =========="

# 检查yml中的明文密码
echo "扫描yml配置文件..."
while IFS= read -r file; do
    while IFS= read -r line; do
        if echo "$line" | grep -qiE 'password|secret|api.?key|token' && ! echo "$line" | grep -q '#' && ! echo "$line" | grep -q '\$\{' ; then
            REDACTED=$(echo "$line" | sed 's/\(.\{20\}\).*/\1...REDACTED/')
            red "  $file: $REDACTED"
            VULN_COUNT=$((VULN_COUNT + 1))
        fi
    done < "$file"
done < <(find "$PROJECT_ROOT" -name "*.yml" -not -path "*/target/*" -not -path "*/node_modules/*")

# 检查硬编码IP
echo "扫描硬编码IP..."
HARDCODED_IPS=$(grep -rn '121.40.188.98\|112.124.106.108\|172.23.172.13' "$PROJECT_ROOT" --include="*.yml" --include="*.java" --include="*.sh" 2>/dev/null | grep -v target | grep -v node_modules | grep -v '.bak')
if [ -n "$HARDCODED_IPS" ]; then
    yellow "  发现硬编码IP（建议用环境变量替代）:"
    echo "$HARDCODED_IPS" | head -10
    WARN_COUNT=$((WARN_COUNT + 1))
fi

# 检查私钥文件
echo "扫描私钥文件..."
KEYS=$(find "$PROJECT_ROOT" -name "*.pem" -o -name "*.key" -o -name "id_rsa" 2>/dev/null | grep -v node_modules)
if [ -n "$KEYS" ]; then
    red "  发现私钥文件: $KEYS"
    VULN_COUNT=$((VULN_COUNT + 1))
fi

# ---------- 2. SQL注入风险检测 ----------
echo ""
blue "========== 2. SQL注入风险检测 =========="

# 检查MyBatis中的${}拼接（SQL注入风险）
echo "扫描MyBatis mapper XML..."
while IFS= read -r file; do
    INJECTION=$(grep -n '\${' "$file" 2>/dev/null | grep -v '--' | head -5)
    if [ -n "$INJECTION" ]; then
        yellow "  $file 中有 \${} 拼接（SQL注入风险）:"
        echo "$INJECTION"
        WARN_COUNT=$((WARN_COUNT + 1))
    fi
done < <(find "$PROJECT_ROOT" -name "*.xml" -path "*/mappers/*" -not -path "*/target/*")

# 检查Java中的字符串拼接SQL
echo "扫描Java中的SQL拼接..."
SQL_CONCAT=$(grep -rn '"SELECT.*" *+' "$PROJECT_ROOT" --include="*.java" -not -path "*/target/*" 2>/dev/null | head -10)
if [ -n "$SQL_CONCAT" ]; then
    red "  发现SQL字符串拼接:"
    echo "$SQL_CONCAT"
    VULN_COUNT=$((VULN_COUNT + 1))
fi

# 检查Statement（应该用PreparedStatement）
STATEMENT_USE=$(grep -rn 'createStatement\|Statement\.execute' "$PROJECT_ROOT" --include="*.java" -not -path "*/target/*" 2>/dev/null | head -5)
if [ -n "$STATEMENT_USE" ]; then
    red "  发现使用Statement（应该用PreparedStatement）:"
    echo "$STATEMENT_USE"
    VULN_COUNT=$((VULN_COUNT + 1))
fi

# ---------- 3. 依赖漏洞检测 ----------
echo ""
blue "========== 3. 依赖漏洞检测 =========="

# 检查Spring Boot版本
SPRING_VERSION=$(grep 'spring-boot.version' "$PROJECT_ROOT/pom.xml" 2>/dev/null | sed 's/.*<\(.*\)>.*/\1/' | head -1)
echo "Spring Boot版本: $SPRING_VERSION"

# 检查已知有漏洞的依赖
echo "扫描已知漏洞依赖..."
VULN_DEPS=$(cd "$PROJECT_ROOT" && mvn dependency:tree 2>/dev/null | grep -iE 'log4j|commons-collections|jackson-databind' | head -5)
if [ -n "$VULN_DEPS" ]; then
    yellow "  需要关注以下依赖版本:"
    echo "$VULN_DEPS"
    WARN_COUNT=$((WARN_COUNT + 1))
fi

# ---------- 4. XSS/CSRF风险检测 ----------
echo ""
blue "========== 4. XSS/CSRF风险检测 =========="

# 检查前端dangerouslySetInnerHTML
DANGEROUS_HTML=$(grep -rn 'dangerouslySetInnerHTML' "$PROJECT_ROOT/frontend/src" 2>/dev/null | head -5)
if [ -n "$DANGEROUS_HTML" ]; then
    red "  前端发现dangerouslySetInnerHTML（XSS风险）:"
    echo "$DANGEROUS_HTML"
    VULN_COUNT=$((VULN_COUNT + 1))
fi

# 检查后端CORS配置
CORS_CONFIG=$(grep -rn 'addAllowedOrigin\|@CrossOrigin' "$PROJECT_ROOT" --include="*.java" -not -path "*/target/*" 2>/dev/null | head -5)
if echo "$CORS_CONFIG" | grep -q '\*' ; then
    yellow "  CORS配置允许所有来源（生产环境应限制域名）"
    WARN_COUNT=$((WARN_COUNT + 1))
fi

# ---------- 5. 敏感端点暴露检测 ----------
echo ""
blue "========== 5. 敏感端点暴露检测 =========="

ACTUATOR=$(grep -rn 'management\|endpoints\|expose' "$PROJECT_ROOT" --include="*.yml" -not -path "*/target/*" 2>/dev/null | head -5)
if echo "$ACTUATOR" | grep -q '\*'; then
    red "  Actuator暴露了所有端点（应只暴露health）"
    VULN_COUNT=$((VULN_COUNT + 1))
else
    green "  Actuator端点配置安全"
fi

# ---------- 汇总 ----------
echo ""
echo "============================================================"
if [ $VULN_COUNT -gt 0 ]; then
    red "发现 $VULN_COUNT 个高危漏洞，$WARN_COUNT 个警告"
else
    green "未发现高危漏洞，$WARN_COUNT 个警告"
fi
echo "============================================================"
