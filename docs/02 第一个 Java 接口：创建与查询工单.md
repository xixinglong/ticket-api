---
type: note
status: growing
created: 2026-10-17
updated: 2026-10-17
tags:
  - career/java
aliases: []
---

# 02 第一个 Java 接口：创建与查询工单

上一节的工单只能在 Java 程序内部操作。这一节，把创建与查询变成 HTTP 接口，让前端能够调用。

**完成后，你会得到一个本机可运行的小服务：创建成功返回 201，查询成功返回 200，非法输入返回 400，不存在的工单返回 404。**

本节使用内存存储，重启会丢失数据，没有登录。它只用于练习，不要部署到公网。

建议分两次完成：第一次做到第七节，跑通创建与查询；第二次再补自动化测试和独立修改。第一次不要边复制边增加新功能。

## 一、先明确请求怎么走

先看这条路径，不用背框架定义：

```text
HTTP 请求
  → Controller：接收参数、返回 HTTP 响应
  → Service：执行业务、管理内存数据
  → 返回 JSON
```

Controller 像接口的门口，Service 放实际处理过程。接入数据库后，再增加 Mapper 层负责 SQL。

分层不是为了把文件数量变多。它让 HTTP 参数处理和业务逻辑可以分开修改、分开测试。

## 二、创建工程，只选需要的依赖

前置条件：已安装 JDK 21，并能完成上一篇练习。本文建议工程目录为 `C:\java-labs\ticket-api`；这套笔记本身不包含已经生成的工程。

打开 [Spring Initializr](https://start.spring.io/)，使用以下选项：

- Project：Maven。
- Language：Java。
- Spring Boot：选择稳定的 4.x 版本，不选 SNAPSHOT、M 或 RC 预览版本。
- Group：`com.example`。
- Artifact：`ticket-api`。
- Package name：明确设为 `com.example.ticket`。
- Packaging：Jar。
- Java：21。
- Dependencies：Spring Web、Validation。

下载、解压，把包含 `pom.xml` 的目录放到建议工程位置。先不要额外加入数据库、Redis、Lombok 或其他脚手架。

保留生成的 Maven Wrapper 和测试配置。测试依赖版本优先由该工程的 Spring Boot 依赖管理提供，不从另一篇教程复制版本号。

版本依据：[Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Spring Boot 测试支持](https://docs.spring.io/spring-boot/reference/testing/index.html)。

### 只认识四个位置

以下树以 `C:\java-labs\ticket-api` 为根，表示相对目录，不是另一个现成项目：

```text
pom.xml                  依赖与构建配置
mvnw.cmd                 Windows Maven Wrapper
src/main/java            主代码
src/main/resources       配置
src/test/java            测试代码
```

Maven 不等于 npm。此时先掌握“声明依赖、编译主代码、执行测试”这几个用途，按工程原有配置工作。

参考：[Maven 入门指南](https://maven.apache.org/guides/getting-started/)。

## 三、先让空工程启动

在 `C:\java-labs\ticket-api\src\main\resources\application.properties` 添加：

```properties
server.address=127.0.0.1
server.port=8080
```

绑定本机回环地址，防止这个没有登录的练习服务被直接对外访问。

生成的启动类应位于 `com.example.ticket` 包。名称可能不同，保留实际生成的名称，不额外创建第二个启动类。它的核心结构如下：

```java
package com.example.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TicketApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(TicketApiApplication.class, args);
    }
}
```

在工程根目录运行：

```powershell
Set-Location 'C:\java-labs\ticket-api'
.\mvnw.cmd spring-boot:run
```

首次运行可能需要下载依赖。看到启动完成日志后，打开另一个终端继续操作；服务终端暂时不要关闭。

如果 8080 已被占用，先确认是谁在使用，不随意结束不认识的进程；也可以把端口改成 8081，并同步修改后面的请求地址。

## 四、用两个对象说明输入和输出

在服务所在终端按 `Ctrl+C` 停止服务，再添加以下文件。所有 Java 文件都放在启动类的同一个包中，避免初学时遇到扫描范围问题。

### 接收标题

创建 `C:\java-labs\ticket-api\src\main\java\com\example\ticket\CreateTicketRequest.java`：

```java
package com.example.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTicketRequest(
    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题不能超过 100 个字符")
    String title
) {}
```

`record` 适合这里的简单数据载体，会生成构造器和访问方法，例如 `title()`。它不是数据库表，也不意味着内部引用的任何对象都会深度不可变。

本例按 Java 字符串长度校验，长度单位是 UTF-16 代码单元，不是严格的用户感知字符数。练习时用普通中文与英文测试即可，涉及表情符号计数的产品需求要另行定义。

参考：[Java Records](https://dev.java/learn/language/oop/records/)、[Jakarta Validation 的 Size 定义](https://jakarta.ee/specifications/bean-validation/3.0/apidocs/jakarta/validation/constraints/Size.html)。

### 返回创建结果

创建 `C:\java-labs\ticket-api\src\main\java\com\example\ticket\TicketResponse.java`：

```java
package com.example.ticket;

public record TicketResponse(
    String id,
    String title,
    String status
) {}
```

响应编号选用字符串，让前端把它当作标识，不参与数值计算；将来数据库编号很大时，也不依赖 JavaScript Number 的整数精度。

响应对象单独定义，避免以后把数据库里的内部字段或用户敏感字段直接全部返回。

## 五、让 Service 管数据

创建 `C:\java-labs\ticket-api\src\main\java\com\example\ticket\TicketService.java`：

```java
package com.example.ticket;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class TicketService {
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, TicketResponse> tickets =
        new ConcurrentHashMap<>();

    public TicketResponse create(String title) {
        if (title == null || title.isBlank() || title.length() > 100) {
            throw new IllegalArgumentException("标题不合法");
        }
        String id = Long.toString(sequence.incrementAndGet());
        TicketResponse ticket =
            new TicketResponse(id, title.strip(), "OPEN");
        tickets.put(id, ticket);
        return ticket;
    }

    public Optional<TicketResponse> find(String id) {
        return Optional.ofNullable(tickets.get(id));
    }
}
```

`@Service` 使这个类可以被 Spring 管理。Controller 稍后通过构造器取得它，不必手动创建。

`Optional` 明确表达“可能查不到”，没有查到时不要随便造一个空工单。

请求对象的校验用于 HTTP 入口；Service 也保留必要的业务输入检查，避免以后从其他代码调用时绕过约束。此处 Service 抛出的异常不自动等于某个 HTTP 状态，Controller 的输入校验负责本节正常请求路径的 400 响应。

`AtomicLong` 和 `ConcurrentHashMap` 处理当前示例的编号递增与单项存取。**它们不保证任意多步业务操作都是原子的，也不能跨进程共享或持久化数据。**

参考：[AtomicLong API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/AtomicLong.html)、[ConcurrentHashMap API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html)。

## 六、让 Controller 接住请求

创建 `C:\java-labs\ticket-api\src\main\java\com\example\ticket\TicketController.java`：

```java
package com.example.ticket;

import java.net.URI;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService service;

    public TicketController(TicketService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TicketResponse> create(
        @Valid @RequestBody CreateTicketRequest request
    ) {
        TicketResponse ticket = service.create(request.title());
        URI location = URI.create("/api/tickets/" + ticket.id());
        return ResponseEntity.created(location).body(ticket);
    }

    @GetMapping("/{id}")
    public TicketResponse find(@PathVariable("id") String id) {
        return service.find(id).orElseThrow(() ->
            new ResponseStatusException(
                HttpStatus.NOT_FOUND, "工单不存在"
            )
        );
    }
}
```

先看懂五个位置就够了：

- `@RestController`：把方法返回值作为响应内容处理。
- `@RequestMapping`：声明公共路径。
- `@RequestBody`：接收并转换 JSON 请求体。
- `@Valid`：触发请求对象上的校验约束。
- 构造器参数 `TicketService`：让 Spring 注入依赖。

构造器注入与请求参数是两回事。前者装配组件，后者接收每次请求的数据。

参考：[Spring REST 入门](https://spring.io/guides/gs/rest-service)、[RequestBody 与验证](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestbody.html)。

## 七、不要凭感觉，实际请求三次

重新运行 `mvnw.cmd spring-boot:run`。在第二个 PowerShell 窗口中准备变量：

```powershell
$base = 'http://127.0.0.1:8080'
$json = @{ title = 'Login page is blank' } | ConvertTo-Json
$created = Invoke-WebRequest -UseBasicParsing `
  -Method Post -Uri "$base/api/tickets" `
  -ContentType 'application/json' -Body $json
$created.StatusCode
$ticket = $created.Content | ConvertFrom-Json
$ticket
```

预期状态码为 201，响应包含 `id`、`title`、`status`，其中状态为 OPEN。复制的是 PowerShell 命令，不要粘贴到浏览器控制台。

### 查询刚创建的工单

```powershell
Invoke-RestMethod -Method Get `
  -Uri "$base/api/tickets/$($ticket.id)"
```

预期返回刚创建的数据。

### 查询不存在的编号

```powershell
try {
    Invoke-WebRequest -UseBasicParsing `
      -Uri "$base/api/tickets/not-found" -ErrorAction Stop
} catch {
    if ($null -ne $_.Exception.Response) {
        [int]$_.Exception.Response.StatusCode
    } else {
        $_.Exception.Message
    }
}
```

预期输出 404。如果没有 HTTP 响应，例如连接失败，应检查服务和端口，不把它误认为接口返回了 404。

### 提交纯空格标题

```powershell
try {
    Invoke-WebRequest -UseBasicParsing -Method Post `
      -Uri "$base/api/tickets" -ContentType 'application/json' `
      -Body '{"title":"   "}' -ErrorAction Stop
} catch {
    if ($null -ne $_.Exception.Response) {
        [int]$_.Exception.Response.StatusCode
    } else {
        $_.Exception.Message
    }
}
```

预期输出 400。默认错误正文可能随版本和配置变化，本节只约定状态码，不承诺一定显示注解中的中文消息。需要稳定业务错误结构时，再统一实现异常处理。

## 八、给核心逻辑补两个自动化测试

创建 `C:\java-labs\ticket-api\src\test\java\com\example\ticket\TicketServiceTest.java`：

```java
package com.example.ticket;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TicketServiceTest {
    @Test
    void createdTicketCanBeFound() {
        TicketService service = new TicketService();
        TicketResponse created = service.create("  Broken page  ");
        TicketResponse found = service.find(created.id()).orElseThrow();
        assertEquals("Broken page", found.title());
        assertEquals("OPEN", found.status());
    }

    @Test
    void blankTitleIsRejected() {
        TicketService service = new TicketService();
        assertThrows(IllegalArgumentException.class,
            () -> service.create("   "));
    }
}
```

在工程根目录执行 `mvnw.cmd test`。这是你学习时执行的验证步骤，不代表本套笔记的示例已经由作者编译测试通过。

这两个测试只验证 Service，不覆盖 JSON 解析、HTTP 状态、数据库或权限。后续必须增加接口测试和真实数据库测试。

接着自己补四个用例：`null` 标题被拒绝；101 个英文字母的标题被拒绝；查询不存在的编号得到空 Optional；连续创建两张工单，编号不同且分别可查。每个测试新建 Service，避免依赖上一个测试留下的数据。

参考：[JUnit 用户指南](https://docs.junit.org/current/user-guide/)。

## 九、遇到问题，先查这四处

**编译说找不到 jakarta 包：** 检查是否添加 Validation 依赖，IDE 是否已经重新加载 Maven；不要随手把导入改成旧教程里的 `javax`。

**调用接口一直是 404：** 检查 Controller 的 package，是否处于启动类包的扫描范围；再检查请求方法、路径和端口。

**前端跨域失败：** 先用终端验证接口是否正常，再检查前端开发代理或精确配置允许来源。不要把关闭认证或放开所有来源当作通用修复。

**服务重启查不到数据：** 本节存储在内存里，属于预期限制。下一阶段改为数据库持久化。

## 这篇的验收

- [ ] 不看说明，说出请求经过哪两个类。
- [ ] 创建成功、查不到、空标题三个场景都得到约定状态码。
- [ ] 能给请求增加一个字段，并同步修改输入、业务、输出和测试。
- [ ] 能解释为什么当前代码不能直接上线。

## 继续往下学

[[03 工单项目交付：数据库、权限、事务与部署]]：不再只做能返回 JSON 的接口，开始处理数据可靠性和权限。
