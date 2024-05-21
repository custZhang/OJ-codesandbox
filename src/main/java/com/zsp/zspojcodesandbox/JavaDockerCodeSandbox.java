package com.zsp.zspojcodesandbox;

import cn.hutool.core.util.ArrayUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.zsp.zspojcodesandbox.model.ExecuteMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 代码沙箱的实现
 */
@Service
public class JavaDockerCodeSandbox extends JavaCodeSandboxTemplate {

    private static final boolean FIRST_INIT = true;

    private static final long TIME_OUT = 5 * 1000L;

    @Override
    public List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
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
        return executeMessageList;
    }
}
