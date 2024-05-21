package com.zsp.zspojcodesandbox;

import com.zsp.zspojcodesandbox.model.ExecuteCodeRequest;
import com.zsp.zspojcodesandbox.model.ExecuteCodeResponse;
import org.springframework.stereotype.Service;

/**
 * 原生实现方法，同模板方法
 */
@Service
public class JavaNativeCodeSandbox extends JavaCodeSandboxTemplate {

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        return super.executeCode(executeCodeRequest);
    }
}
