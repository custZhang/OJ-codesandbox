package com.zsp.zspojcodesandbox.config;

import lombok.Data;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Data
public class RedissonConfig {

    @Value("${redisson.database}")
    private Integer database;

    @Value("${redisson.host}")
    private String host;

    @Value("${redisson.port}")
    private Integer port;

    @Value("${redisson.password}")
    private String password;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setDatabase(database)
                .setAddress("redis://" + host + ":" + port)
                .setPassword(password);//如果redis未设置密码可删除此行
        RedissonClient redisson = Redisson.create(config);
        return redisson;
    }
}

