package com.example.chat.agent.tool.impl;

import com.example.chat.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 计算器工具
 * 支持四则运算 + - * / 和括号
 *
 * 安全策略：只允许数字、运算符、括号、点、空格，其他字符一律拒绝，
 * 不依赖 ScriptEngine（避免注入风险），用递归下降解析器求值。
 */
@Component
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class CalculatorTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CalculatorTool.class);

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public String getDescription() {
        return "数学计算器，支持四则运算（+ - * /）和括号。当用户需要计算数学表达式时调用此工具。";
    }

    @Override
    public String getParameters() {
        return "{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\",\"description\":\"数学表达式，如 (1+2)*3 / 100/4\"}},\"required\":[\"expression\"]}";
    }

    @Override
    public String execute(Map<String, Object> params) {
        Object exprObj = params.get("expression");
        if (exprObj == null || exprObj.toString().isBlank()) {
            return "[缺少参数: expression]";
        }
        String expr = exprObj.toString().trim();

        // 安全校验：只允许数字、运算符、括号、小数点、空格
        for (char c : expr.toCharArray()) {
            if (!(Character.isDigit(c) || c == '+' || c == '-' || c == '*' || c == '/'
                    || c == '(' || c == ')' || c == '.' || c == ' ')) {
                return "[非法字符: '" + c + "'，只允许数字和 + - * / ( )]";
            }
        }

        try {
            double result = new ExprEvaluator(expr).parse();
            // 整数结果去掉小数点
            String resultStr = (result == Math.floor(result) && !Double.isInfinite(result))
                    ? String.valueOf((long) result)
                    : String.valueOf(result);
            log.info("[CalculatorTool] {} = {}", expr, resultStr);
            return expr + " = " + resultStr;
        } catch (Exception e) {
            log.warn("[CalculatorTool] 计算失败 expr={}: {}", expr, e.getMessage());
            return "[计算失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 简单的递归下降表达式求值器
     * 文法：
     *   expr   = term (('+'|'-') term)*
     *   term   = factor (('*'|'/') factor)*
     *   factor = number | '(' expr ')' | ('-'|'+') factor
     */
    private static class ExprEvaluator {
        private final String s;
        private int pos = 0;

        ExprEvaluator(String s) {
            this.s = s.replaceAll("\\s+", "");
        }

        double parse() {
            double v = expr();
            if (pos < s.length()) {
                throw new RuntimeException("未消费的字符: " + s.substring(pos));
            }
            return v;
        }

        private double expr() {
            double v = term();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '+') { pos++; v += term(); }
                else if (c == '-') { pos++; v -= term(); }
                else break;
            }
            return v;
        }

        private double term() {
            double v = factor();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '*') { pos++; v *= factor(); }
                else if (c == '/') {
                    pos++;
                    double divisor = factor();
                    if (divisor == 0) throw new RuntimeException("除零错误");
                    v /= divisor;
                } else break;
            }
            return v;
        }

        private double factor() {
            if (pos >= s.length()) throw new RuntimeException("表达式不完整");
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                double v = expr();
                if (pos >= s.length() || s.charAt(pos) != ')') {
                    throw new RuntimeException("缺少右括号");
                }
                pos++;
                return v;
            }
            if (c == '-') { pos++; return -factor(); }
            if (c == '+') { pos++; return factor(); }

            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) throw new RuntimeException("期望数字，但遇到: " + c);
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
