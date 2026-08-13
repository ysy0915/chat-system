package com.example.chat.agent.tool.impl;

import com.example.chat.agent.tool.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 时间查询工具
 * 返回当前时间和日期
 */
@Component
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class TimeTool implements Tool {

    @Override
    public String getName() {
        return "time";
    }

    @Override
    public String getDescription() {
        return "查询当前的时间和日期。当用户询问现在几点、今天日期、当前时间等问题时调用此工具。";
    }

    @Override
    public String getParameters() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public String execute(Map<String, Object> params) {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy年MM月dd日 EEEE");
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH时mm分ss秒");
        return "当前时间：" + now.format(timeFmt) + "\n"
                + "今天日期：" + now.format(dateFmt);
    }
}
