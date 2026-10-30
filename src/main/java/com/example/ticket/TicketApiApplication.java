package com.example.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 应用入口：启用自动配置，并扫描当前包及其子包中的 Spring 组件。
@SpringBootApplication
public class TicketApiApplication {

	public static void main(String[] args) {
		// 创建 Spring 应用上下文，并启动内嵌 Web 服务器接收 HTTP 请求。
		SpringApplication.run(TicketApiApplication.class, args);
	}

}
