package com.zsp.zspojcodesandbox;


import com.zsp.zspojcodesandbox.model.ExecuteCodeRequest;
import com.zsp.zspojcodesandbox.model.ExecuteCodeResponse;

/**
 * 代码沙箱接口
 */
public interface CodeSandbox {

    /**
     * 执行代码
     * @param executeCodeRequest
     * @return
     */
    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);
}
