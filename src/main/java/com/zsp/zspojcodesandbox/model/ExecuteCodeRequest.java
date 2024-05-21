package com.zsp.zspojcodesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeRequest {

    /**
     * 题目输入（测试用例）
     */
    private List<String> inputList;

    /**
     * 执行的代码
     */
    private String code;

    /**
     * 执行的编程语言
     */
    private String language;
}
