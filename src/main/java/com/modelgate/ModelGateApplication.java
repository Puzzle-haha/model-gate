package com.modelgate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ModelGate —— 多模型 LLM 调用网关。
 *
 * 目标：让上层应用只调一个接口，底下自动完成供应商路由、密钥轮询、
 * 限流、熔断、降级、缓存、成本核算。
 */
@SpringBootApplication
public class ModelGateApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModelGateApplication.class, args);
    }
}
