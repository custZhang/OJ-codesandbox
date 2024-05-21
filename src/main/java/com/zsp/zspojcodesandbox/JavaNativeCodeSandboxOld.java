package com.zsp.zspojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.FoundWord;
import cn.hutool.dfa.WordTree;
import com.zsp.zspojcodesandbox.model.ExecuteCodeRequest;
import com.zsp.zspojcodesandbox.model.ExecuteCodeResponse;
import com.zsp.zspojcodesandbox.model.ExecuteMessage;
import com.zsp.zspojcodesandbox.model.JudgeInfo;
import com.zsp.zspojcodesandbox.security.DefaultSecurityManager;
import com.zsp.zspojcodesandbox.utils.ProcessUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
@Deprecated
public class JavaNativeCodeSandboxOld implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    private static final long TIME_OUT = 5 * 1000L;

    private static final List<String> blockList = Arrays.asList("Files","exec");

    public static void main(String[] args) {
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        List<String> inputList = Arrays.asList("1 2","2 3");
        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
        executeCodeRequest.setInputList(inputList);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("java");
        JavaNativeCodeSandboxOld javaNativeCodeSandbox = new JavaNativeCodeSandboxOld();
        ExecuteCodeResponse executeCodeResponse = javaNativeCodeSandbox.executeCode(executeCodeRequest);
        for (String codeResponse :executeCodeResponse.getOuputList()) {
            System.out.println(codeResponse);
        }
    }


    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        System.setSecurityManager(new DefaultSecurityManager());
        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();
        //校验代码，防止危险代码
        WordTree wordTree = new WordTree();
        wordTree.addWords(blockList);
        FoundWord foundWord = wordTree.matchWord(code);
        if(foundWord != null){
            System.out.println(foundWord.getFoundWord());
            return null;
        }

        //1.保存代码文件
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        //判断全局代码目录是否存在，没有则新建
        if(!FileUtil.exist(globalCodePathName)){
            FileUtil.mkdir(globalCodePathName);
        }
        //把用户的代码隔离存放
        //文件夹目录userCodeParentPath
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();//文件夹
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;//实际文件名
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
        //2.编译代码，得到class文件
        String compileCmd = String.format("javac -encoding utf-8 %s",userCodeFile.getAbsolutePath());//拿到绝对路径
        //执行cmd指令
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            System.out.println(executeMessage);
        } catch (Exception e) {
            return getErrorResponse(e);
        }
        //3.执行代码，得到运行结果
        //inputList里存放的是输入用例，每一组输入用例要执行一次
        ArrayList<ExecuteMessage> executeMessageList = new ArrayList<>();
        for(String inputArgs: inputList){
            String runCmd = String.format("java -Dfile.encoding=UTF-8 -cp %s Main %s", userCodeParentPath, inputArgs);
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);
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
                return getErrorResponse(e);
            }
        }
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
            if(StrUtil.isBlank(errorMessage)){//执行中存在错误
                executeCodeResponse.setMessage(errorMessage);
                executeCodeResponse.setStatus(3);
            }
            outputList.add(executeMessage.getMessage());
            Long time = executeMessage.getTime();
            if(time != null){
                maxTime = Math.max(maxTime,time);
            }
        }
        //如果正常运行，状态码就设置为2
        if(outputList.size() == executeMessageList.size()){
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOuputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        //5.文件清理
        if(userCodeFile.getParentFile() != null){
            boolean del = FileUtil.del(userCodeParentPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
        }
        //6.

        return executeCodeResponse;

    }

    private ExecuteCodeResponse getErrorResponse(Throwable e){
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOuputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        //用来表示代码沙箱错误
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}
