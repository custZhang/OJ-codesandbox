package com.zsp.zspojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.zsp.zspojcodesandbox.model.ExecuteCodeRequest;
import com.zsp.zspojcodesandbox.model.ExecuteCodeResponse;
import com.zsp.zspojcodesandbox.model.ExecuteMessage;
import com.zsp.zspojcodesandbox.model.JudgeInfo;
import com.zsp.zspojcodesandbox.utils.ProcessUtils;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Deprecated
public class JavaDockerCodeSandboxOld implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    private static final long TIME_OUT = 5 * 1000L;

    private static final List<String> blockList = Arrays.asList("Files", "exec");

    private static final boolean FIRST_INIT = true;

    public static void main(String[] args) {
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        List<String> inputList = Arrays.asList("1 2", "2 3");
        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
        executeCodeRequest.setInputList(inputList);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("java");
        JavaDockerCodeSandboxOld javaDockerCodeSandbox = new JavaDockerCodeSandboxOld();
        ExecuteCodeResponse executeCodeResponse = javaDockerCodeSandbox.executeCode(executeCodeRequest);
        for (String codeResponse : executeCodeResponse.getOuputList()) {
            System.out.println(codeResponse);
        }
    }


    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
//        System.setSecurityManager(new DefaultSecurityManager());
        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();

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

        //2.编译代码，得到class文件
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());//拿到绝对路径
        //执行cmd指令
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            System.out.println(executeMessage);
        } catch (Exception e) {
            return getErrorResponse(e);
        }
        //3.执行代码，得到运行结果
        // 获取默认的Docker Client
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        //拉取镜像
        String image = "openjdk:8-alpine";
        //如果是第一次执行，就要拉取镜像
        if (FIRST_INIT) {
            //拉取镜像
            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
            //创建回调函数
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
                @Override
                public void onNext(PullResponseItem item) {
                    System.out.println("下载镜像：" + item.getStatus());
                    super.onNext(item);
                }
            };
            //执行拉取镜像操作，完成后就会调用回调函数pullImageResultCallback.onNext
            try {
                pullImageCmd.exec(pullImageResultCallback)
                        .awaitCompletion();
            } catch (InterruptedException e) {
                System.out.println("拉取镜像异常");
                throw new RuntimeException(e);
            }
        }

        //创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        //设置容器创建配置类HostConfig，指定容器内存，cpu使用情况
        HostConfig hostConfig = new HostConfig();
        hostConfig.setBinds(new Bind(userCodeParentPath, new Volume("/app")));//创建数据卷，将userCodeParentPath目录映射到/app目录里，设置内存
        hostConfig.withMemory(100 * 1000 * 1000L);//设置100m的内存
        hostConfig.withCpuCount(1L);//设置只能使用1个cpu
//            hostConfig.withSecurityOpts(Arrays.asList("seccomp=安全管理配置字符串"));
        //安全管理字符串的位置是一个json字符串{
        //  "defaultAction": "SCMP_ACT_ALLOW",
        //  "syscalls": [
        //    {
        //      "name": "write",
        //      "action": "SCMP_ACT_ALLOW"
        //    },
        //    {
        //      "name": "read",
        //      "action": "SCMP_ACT_ALLOW"
        //    }
        //  ]
        //}

        //使用HostConfig创建容器，同时指定打开标准输入输出，错误输出，这样在我们linux的终端就可以看到容器终端的输出。withTty就是创建一个交互终端
        CreateContainerResponse createContainerResponse = containerCmd
                .withHostConfig(hostConfig)
                .withNetworkDisabled(true)
                .withReadonlyRootfs(true)
                .withAttachStdin(true)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withTty(true)
                .exec();
        String containerId = createContainerResponse.getId();//获取容器id
        //启动容器
        dockerClient.startContainerCmd(containerId).exec();

        //程序启动后，执行指令
        // docker exec keen_blackwell java -cp /app Main 1 3
        //每一组测试用例执行后，将结果保存到ExecuteMessage里
        //使用将所有测试用例的保存到一个数组executeMessageList
        ArrayList<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
            StopWatch stopWatch = new StopWatch();//记录每个测试用例执行用时，保存到ExecuteMessage里
            String[] inputArgsArray = inputArgs.split(" ");
            String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "/Main"}, inputArgsArray);
            //先创建命令
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmdArray)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();
            System.out.println("创建执行命令：" + execCreateCmdResponse);

            ExecuteMessage executeMessage = new ExecuteMessage();
            final String[] message = {null};
            final String[] errorMessage = {null};
            long time = 0L;
            // 判断是否超时
            final boolean[] timeout = {true};//记录该测试用例是否报错，默认报错
            String execId = execCreateCmdResponse.getId();
            //执行命令(先创建回调函数)
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {

                @Override
                public void onComplete() {
                    timeout[0] = false;//当没有被中断，就是没有超时正常结束，就会调用这个回调函数，就设置为不超时
                    super.onComplete();
                }

                @Override
                public void onNext(Frame frame) {
                    StreamType streamType = frame.getStreamType();
                    if (StreamType.STDERR.equals(streamType)) {//那就是运行报错
                        errorMessage[0] = new String(frame.getPayload());
                        System.out.println("输出错误结果" + errorMessage[0]);
                    } else {
                        message[0] = new String(frame.getPayload());
                        System.out.println("输出正常结果" + message[0]);
                    }
                    super.onNext(frame);
                }
            };
            //这里执行 获取命令行状态 的指令
            final long[] maxMemory = {0L};

            // 获取占用的内存
            StatsCmd statsCmd = dockerClient.statsCmd(containerId);
            //设置回调函数，获取当前所用内存
            ResultCallback<Statistics> statisticsResultCallback = statsCmd.exec(new ResultCallback<Statistics>() {

                @Override
                public void onNext(Statistics statistics) {
                    System.out.println("内存占用：" + statistics.getMemoryStats().getUsage());
                    maxMemory[0] = Math.max(statistics.getMemoryStats().getUsage(), maxMemory[0]);
                }

                @Override
                public void close() throws IOException {

                }

                @Override
                public void onStart(Closeable closeable) {

                }

                @Override
                public void onError(Throwable throwable) {

                }

                @Override
                public void onComplete() {

                }
            });
            statsCmd.exec(statisticsResultCallback);//执行 获取命令行状态 的指令


            //真正执行java指令运行class文件
            try {
                stopWatch.start();
                dockerClient.execStartCmd(execId)
                        .exec(execStartResultCallback)
                        .awaitCompletion(TIME_OUT, TimeUnit.MICROSECONDS);//设置该指令的超时时间
                stopWatch.stop();
                time = stopWatch.getLastTaskTimeMillis();
                statsCmd.close();
            } catch (InterruptedException e) {
                System.out.println("程序执行异常");
                throw new RuntimeException(e);
            }
            //java指令执行完成，将此次得到的信息写入ExecuteMessage对象里，并保存到executeMessageList
//                executeMessage.setExitValue();
            executeMessage.setMessage(message[0]);//正常执行结果
            executeMessage.setErrorMessage(errorMessage[0]);//异常，报错信息
            executeMessage.setTime(time);
            executeMessage.setMemory(maxMemory[0]);
            executeMessageList.add(executeMessage);
        }
        // 4、封装结果，跟原生实现方式完全一致
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
            if (StrUtil.isBlank(errorMessage)) {//执行中存在错误
                executeCodeResponse.setMessage(errorMessage);
                executeCodeResponse.setStatus(3);
            }
            outputList.add(executeMessage.getMessage());
            Long time = executeMessage.getTime();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
        }
        //如果正常运行，状态码就设置为2
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOuputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        //5.文件清理
        if (userCodeFile.getParentFile() != null) {
            boolean del = FileUtil.del(userCodeParentPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
        }
        return executeCodeResponse;


    }

    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOuputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        //用来表示代码沙箱错误
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}
