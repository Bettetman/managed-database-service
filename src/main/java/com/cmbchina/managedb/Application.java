package com.cmbchina.managedb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 内置数据库资源服务的独立启动入口。 */
@SpringBootApplication
public class Application {

    /** 启动资源服务，配置由 Spring 环境变量和当前 Profile 提供。 */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
