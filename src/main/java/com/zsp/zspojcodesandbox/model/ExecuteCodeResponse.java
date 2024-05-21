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
public class ExecuteCodeResponse {

    /**
     * 题目输出
     */
    private List<String> ouputList;

    /**
     * 接口信息
     */
    private String message;

    /**
     * 执行状态（同question_submit的status字段）
     */
    private Integer status;

    /**
     * 判题信息(记录运行的时间，内存等)
     */
    private JudgeInfo judgeInfo;
}
