package com.example.ticket;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 加载 Spring 应用上下文，检查配置和组件装配；默认不启动监听端口的真实服务器。
@SpringBootTest
class TicketApiApplicationTests {

	@Test
	void contextLoads() {
		// 上下文加载失败会直接导致测试失败，因此这里不需要额外断言。
	}

}
