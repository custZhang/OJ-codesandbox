package com.zsp.zspojcodesandbox;


import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.zsp.zspojcodesandbox.model.ExecuteCodeRequest;
import com.zsp.zspojcodesandbox.model.ExecuteCodeResponse;
import com.zsp.zspojcodesandbox.model.ExecuteMessage;
import com.zsp.zspojcodesandbox.model.JudgeInfo;
import com.zsp.zspojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
public abstract class JavaCodeSandboxTemplate implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    private static final long TIME_OUT = 5 * 1000L;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
//        System.setSecurityManager(new DefaultSecurityManager());
        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();

        //1.保存代码文件
        File userCodeFile = saveCode(code);

        //2.编译代码，得到class文件
        ExecuteMessage compileFileExecuteMessage = compileFile(userCodeFile);

        if(compileFileExecuteMessage.getExitValue() != 0){//说明编译异常了，则之后不用执行了
            deleteFile(userCodeFile);
            return getErrorResponse(compileFileExecuteMessage);
        }

        //3.执行代码，得到运行结果
        List<ExecuteMessage> executeMessageList = runFile(userCodeFile, inputList);


        // 4、封装结果，跟原生实现方式完全一致
        ExecuteCodeResponse outputResponse = getOutputResponse(executeMessageList);

        //5.文件清理
        boolean b = deleteFile(userCodeFile);
        if (!b) {
            log.error("deleteFile error,userCodeFilePath = {}", userCodeFile.getAbsolutePath());
        }
        return outputResponse;

    }

    /**
     * 1.保存代码文件
     *
     * @param code 用户代码
     * @return 代码文件的File对象
     */
    public File saveCode(String code) {
        //1.保存代码文件
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        //判断全局代码目录是否存在，没有则新建
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }
        //把用户的代码隔离存放
        //文件夹目录userCodeParentPath
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();//文件夹
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;//实际文件名
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
        return userCodeFile;
    }

    /**
     * 2.编译代码，得到class文件
     *
     * @param userCodeFile
     * @return 这里用ExecuteMessage来接收，因为它也可以设置message或errorMessage
     */
    public ExecuteMessage compileFile(File userCodeFile) {
        //2.编译代码，得到class文件
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());//拿到绝对路径
        //执行cmd指令
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            if (executeMessage.getExitValue() != 0) {//在ProcessUtils里异常退出
//                 不能抛异常，否则无法正常返回，就无法往返回对象里设置信息
//                throw new RuntimeException("Compile error");

            }
            return executeMessage;
        } catch (Exception e) {
//            return getErrorResponse(e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 3.执行代码，获得结果集合
     *
     * @param userCodeFile 用户代码目录
     * @param inputList
     * @return
     */
    public List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();//文件夹
        //3.执行代码，得到运行结果
        //inputList里存放的是输入用例，每一组输入用例要执行一次
        ArrayList<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
            //旧版：从args[0]里获取数据
//            String runCmd = String.format("java -Dfile.encoding=UTF-8 -cp %s Main %s", userCodeParentPath, inputArgs);
//            try {
//                Process runProcess = Runtime.getRuntime().exec(runCmd);
            //新版：使用scanner.nextLine();读取数据
            String runCmd = String.format("java -Dfile.encoding=UTF-8 -cp %s Main", userCodeParentPath);
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(runCmd.split("\\s+"));// 创建ProcessBuilder对象
                Process runProcess = processBuilder.start();// 启动子进程
                //此时程序会阻塞等待用户输入，inputList里的每个元素inputArgs就是执行一次要输入的，如果里面有换行符，说明要分行输入
                //所以将inputArgs用\n分割
                String[] input = inputArgs.split("\n");
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(runProcess.getOutputStream()));//获取输入流
                for (String inputRow : input) {
                    writer.write(inputRow + "\n"); //输入单行
                    writer.flush();
                }
                //超时控制
                new Thread(() -> {
                    try {
                        Thread.sleep(TIME_OUT);
                        runProcess.destroy();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }).start();
                ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(runProcess, "运行");
                executeMessageList.add(executeMessage);
                System.out.println(executeMessage);
            } catch (IOException e) {
                throw new RuntimeException("执行错误" + e);
            }
        }
        return executeMessageList;
    }

    /**
     * 4.整理输出结果到ExecuteCodeResponse对象里
     *
     * @param executeMessageList
     * @return
     */
    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList) {
        //4.整理输出结果到ExecuteCodeResponse对象里
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        //4.1 outputList：从executeMessageList里的每个对象的message里获取
        //4.2 message（该题总结果）：不设置，由判题模块再去设置（因为这里只是代码沙箱）
        //4.3 status：如果中间有异常，则executeMessageList里的对象的errorMessage就会有值，就设置为3(在enum里为失败)
        //4.4 judgeInfo: time使用stopWatch（已经保存到executeMessageList里的每个对象的time里），取最大值
        //               message不填
        //               memory在java原生版不实现
        ArrayList<String> outputList = new ArrayList<>();
        long maxTime = 0;
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorMessage = executeMessage.getErrorMessage();
            if (!StrUtil.isBlank(errorMessage)) {//执行中存在错误
                executeCodeResponse.setMessage("运行异常：" + errorMessage);
                executeCodeResponse.setStatus(3);
                continue;//这个指令的结果报错了，则就不用继续把正常信息写入了，否则下面判断状态码设置为2就恒成立了
            }
            outputList.add(executeMessage.getMessage());
            Long time = executeMessage.getTime();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
        }
        //如果正常运行，状态码就设置为2
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(2);
        }
        executeCodeResponse.setOuputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        return executeCodeResponse;
    }

    /**
     * 5删除文件
     *
     * @param userCodeFile
     * @return
     */
    public boolean deleteFile(File userCodeFile) {
        if (userCodeFile.getParentFile() != null) {
            boolean del = FileUtil.del(userCodeFile.getParentFile().getAbsolutePath());
            System.out.println("删除" + (del ? "成功" : "失败"));
            return del;
        }
        return true;//本来就是空的，就直接当作删除成功

    }

    /**
     * 6.取出异常信息放到ExecuteCodeResponse里
     *
     * @param e
     * @return
     */
    private ExecuteCodeResponse getErrorResponse(ExecuteMessage e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOuputList(new ArrayList<>());
//        executeCodeResponse.setMessage(e.getMessage());
        executeCodeResponse.setMessage("编译异常:" + e.getErrorMessage());
        //用来表示代码沙箱错误
        executeCodeResponse.setStatus(3);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}
