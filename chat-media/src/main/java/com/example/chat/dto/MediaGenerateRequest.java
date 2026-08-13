package com.example.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class MediaGenerateRequest {

    @NotBlank(message = "描述内容不能为空")
    @Size(max = 2000, message = "描述内容过长")
    private String prompt;

    @Size(max = 30, message = "生成类型非法")
    private String type;

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
