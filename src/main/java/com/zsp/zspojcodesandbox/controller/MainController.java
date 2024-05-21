package com.zsp.zspojcodesandbox.controller;

import com.zsp.zspojcodesandbox.CodeSandbox;
import com.zsp.zspojcodesandbox.JavaNativeCodeSandbox;
import com.zsp.zspojcodesandbox.model.ExecuteCodeRequest;
import com.zsp.zspojcodesandbox.model.ExecuteCodeResponse;
import com.zsp.zspojcodesandbox.model.JudgeInfo;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

//@RestController("/")
@RestController
@RequestMapping
public class MainController {

    //定义鉴权请求头和密钥
    private static final String AUTH_REQUEST_HEADER = "auth";

    private static final String AUTH_REQUEST_SECRET = "secretKey";

    @Resource
    private CodeSandbox javaNativeCodeSandbox;//这里先用原生的

    @Resource
    private RedissonClient redissonClient;

    @GetMapping("/health")
    public String healthCheck(HttpSession httpSession){
        // 获取限流对象
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(httpSession.getId());
        // 设置限流参数，每10秒获得1次令牌
        rateLimiter.trySetRate(RateType.OVERALL, 1, 10, RateIntervalUnit.SECONDS);
        //尝试获取令牌
        boolean permit = rateLimiter.tryAcquire();
        if (!permit) {
            return "操作过于频繁";
        }
        //此处执行后端登录操作
        return "ok";
    }

    /**
     * 提供代码沙箱API
     * @return
     */
    @PostMapping("/executeCode")
    public ExecuteCodeResponse executeCode(@RequestBody ExecuteCodeRequest executeCodeRequest,
                                           HttpServletRequest request,
                                           HttpServletResponse response){
        //先限速
        // 获取限流对象
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(request.getSession().getId());
        // 设置限流参数，每10秒获得1次令牌
        rateLimiter.trySetRate(RateType.OVERALL, 1, 10, RateIntervalUnit.SECONDS);
        //尝试获取令牌
        boolean permit = rateLimiter.tryAcquire();
        if (!permit) {
            return ExecuteCodeResponse.builder()
                    .message("操作过于频繁")
                    .status(3)
                    .judgeInfo(new JudgeInfo())
                    .build();
        }
        //则可以正常执行
        String authHeader = request.getHeader(AUTH_REQUEST_HEADER);
        if(!authHeader.equals(AUTH_REQUEST_SECRET)){
            response.setStatus(403);
            return null;
        }
        if(executeCodeRequest == null){
            throw new RuntimeException("请求参数为空");
        }
        return javaNativeCodeSandbox.executeCode(executeCodeRequest);
    }

}
