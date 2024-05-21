package com.zsp.zspojcodesandbox.model;

import lombok.Data;

/**
 * cmd命令进程执行的结果信息(每执行一个测试用例就会有一个信息)
 */
@Data
public class ExecuteMessage {

    /**
     * 退出码 0正常 1异常
     */
    private Integer exitValue;

    /**
     * 正常信息
     */
    private String message;

    /**
     * 异常信息
     */
    private String errorMessage;

    /**
     * 当前执行用例所耗时间
     */
    private Long time;

    /**
     * 所用内存
     */
    private Long memory;

}
