---
type: note
status: growing
created: 2026-10-17
updated: 2026-10-17
tags:
  - career/java
aliases: []
---

# 01 前端转 Java：用工单取消学会基础语法

你熟悉变量、函数、对象和接口，不需要重新学习“什么是编程”。Java 入门更值得练的是：用明确的类型和规则，把一次业务操作写完整。

这篇只做一件事：**只有创建人，才能取消仍处于 OPEN 状态的工单。**

学完后，你要能独立修改规则，解释失败原因，而不只是复制出运行结果。

## 一、先把需求写成人话

一张工单至少需要编号、创建人和状态。

取消操作必须遵守三条规则：

1. 工单存在。
2. 当前操作者就是创建人。
3. 当前状态是 OPEN，也就是尚未接单。

全部满足，才把状态改成 CANCELLED。失败时，状态保持原样。

此处的操作者编号由练习代码传入。**真实接口必须从服务端验证后的登录身份取得，不能信任前端提交的用户编号。** 登录与对象权限留到项目篇处理。

## 二、先认识最常用的几个写法

### 变量：类型写在变量名前

下面是放在方法内部的片段，不是单独可运行文件：

```java
long ticketId = 1L;
String title = "登录后页面空白";
boolean open = true;
```

`long` 表示整数类型，`1L` 中的 `L` 表示 long 字面量；`String` 表示字符串；`boolean` 只有真假两个值。

Java 的类型会参与编译检查，不是给 JavaScript 增加一层注释。先分清 `long` 与可为空的包装类型 `Long`；后者为 `null` 时自动转换成基本类型，可能抛异常。

比较字符串内容使用 `equals`，不要照搬 JavaScript 的比较习惯：

```java
boolean same = "OPEN".equals(statusText);
```

这行假定 `statusText` 是一个已有的字符串变量。把已知非空的字符串放前面，可以避免对 `null` 调用方法。

语言基础参考：[Java Language Basics](https://dev.java/learn/language/constructs/basics/)。

### 方法：先声明返回什么，再声明参数

```java
static boolean isOwner(long creatorId, long actorId) {
    return creatorId == actorId;
}
```

`boolean` 是返回值类型。两个参数都是基本类型整数，所以这里用 `==` 比较数值。

`static` 表示此方法属于类，不需要先创建实例。它不代表方法更快，也不意味着所有方法都应该加上它。

### 对象：数据和允许的操作放在一起

不要把状态字段公开，让任何地方都能随意改。把字段设成 `private`，只通过 `cancel` 方法修改，让业务规则有明确入口。

类、构造器和访问控制参考：[Java Classes and Objects](https://dev.java/learn/language/oop/classes-objects/)。

## 三、写出第一份完整程序

先安装 JDK 21，不要只安装运行环境。安装入口与基本流程见 [Java 官方入门](https://dev.java/learn/first-steps/)。

Windows 上先检查这三件事：

1. 选择与你的系统架构匹配的 JDK 安装包。记录实际安装目录，例如 `C:\Java\jdk-21`；这是示意路径，以你的电脑为准。
2. 若安装器没有配置环境变量，在用户环境变量中把 `JAVA_HOME` 指向 JDK 根目录，并在 `Path` 中新增 `%JAVA_HOME%\bin`。不要覆盖整个 `Path`，也不要把 `JAVA_HOME` 指到 `bin`。
3. 关闭旧终端，重新打开 PowerShell，执行下面的检查。IDE 的项目 SDK 也选同一个 JDK。

```powershell
java -version
javac -version
Get-Command java, javac | Select-Object Name, Source
$env:JAVA_HOME
```

两个版本命令都应指向 Java 21。若显示不同版本，先核对命令实际路径；若提示找不到命令，先修环境，不要急着改示例代码。

以下是建议的练习位置，不是本套笔记已创建的工程。手动建立 `C:\java-labs\basics`，将代码保存到 `C:\java-labs\basics\Main.java`。

```java
public class Main {
    enum Status { OPEN, CANCELLED }

    static class Ticket {
        private final long creatorId;
        private Status status = Status.OPEN;

        Ticket(long creatorId) {
            this.creatorId = creatorId;
        }

        void cancel(long actorId) {
            if (actorId != creatorId) {
                throw new IllegalArgumentException("只能取消自己的工单");
            }
            if (status != Status.OPEN) {
                throw new IllegalStateException("当前状态不能取消");
            }
            status = Status.CANCELLED;
        }

        Status status() {
            return status;
        }
    }

    public static void main(String[] args) {
        Ticket ticket = new Ticket(1001L);
        ticket.cancel(1001L);
        System.out.println(ticket.status());
    }
}
```

在 PowerShell 中进入练习目录，再执行：

```powershell
Set-Location 'C:\java-labs\basics'
javac -encoding UTF-8 Main.java
java Main
```

预期输出为 `CANCELLED`。这里演示的是内存对象，不包含数据库、HTTP、登录或线程安全保证。

### 这段代码要看懂哪些地方

`new Ticket(1001L)` 创建一个工单对象，构造器记下创建人。

`final` 限制创建人字段再次赋值；状态字段仍然可以变化。不要把 `final` 理解成所有对象内容都会自动变得不可修改。

`enum` 把状态限定为命名好的选项。比较枚举可以用 `==` 或 `!=`。

两个 `if` 都在写状态之前。这样任何一项检查失败，都不会先把状态改掉再报错。

`throw` 抛出异常，会打断当前正常执行过程。示例里的异常是学习用的分类，后续接口需要映射成合适的错误响应，而不是把异常栈直接发给用户。

## 四、把错误也跑一遍

不要一次把所有错误拼进 `main`，第一个未捕获异常会阻止后面的代码执行。每次替换一处，重新运行。

### 换一个操作者

把 `ticket.cancel(1001L)` 改为 `ticket.cancel(2002L)`。

预期拒绝取消。定位异常信息和第一条指向 `Main.java` 的栈记录，看看它对应哪一个条件。

### 连续取消两次

恢复正确操作者，再连续调用两次 `cancel`。

第一次成功，第二次抛出状态异常。当前练习选择“重复取消视为冲突”，这是业务约定，不是所有系统唯一正确的做法。

### 验证失败不改变状态

把 `main` 中的操作部分临时改成以下片段：

```java
try {
    ticket.cancel(2002L);
} catch (IllegalArgumentException e) {
    System.out.println(e.getMessage());
}
System.out.println(ticket.status());
```

预期打印错误原因，随后打印 `OPEN`。这里捕获异常是为了观察状态，不是让生产代码忽略错误。

异常机制参考：[Java Exceptions](https://dev.java/learn/language/annotations-exceptions/exceptions/)。

## 五、多张工单怎么管理

先记用途，不急着背全部集合实现：

- `List<Ticket>`：按列表管理多张工单，可以有重复元素。
- `Set<Long>`：管理不重复的编号。
- `Map<Long, Ticket>`：通过编号找到工单。

下面用 Map 补上“工单不存在”的情况。把导入语句放在 `Main.java` 顶部：

```java
import java.util.HashMap;
import java.util.Map;
```

再用以下代码替换 `main` 方法内部的原内容：

```java
Map<Long, Ticket> tickets = new HashMap<>();
tickets.put(1L, new Ticket(1001L));

Ticket target = tickets.get(1L);
if (target == null) {
    throw new IllegalArgumentException("工单不存在");
}
target.cancel(1001L);
System.out.println(target.status());
```

把查询编号改为 `99L`，应当得到“工单不存在”。这里 `Long` 是泛型参数，不能写成 `Map<long, Ticket>`。

普通 HashMap 不提供并发安全保证，而且程序退出后数据会消失。它适合当前单线程练习，不是数据库的替代品。

集合参考：[Java Collections Framework](https://dev.java/learn/api/collections-and-streams/collections-framework/)。

## 六、第一周这样练

**第 1 次：** 跑通程序，分清编译报错和运行异常。记录自己实际的 JDK 版本。

**第 2 次：** 练变量、条件和方法。写一个标题校验方法，拒绝 `null`、空字符串和纯空格。

**第 3 次：** 解释构造器、字段、方法。增加标题字段，保证创建后可读取。

**第 4 次：** 增加 IN_PROGRESS 状态，让取消规则明确拒绝它。

**第 5 次：** 用 Map 管理三张工单，处理编号不存在的情况。

**第 6 次：** 把成功、越权、状态错误写成三组独立检查；初次运行失败不算坏事，先定位原因。

**第 7 次：** 不看答案重写，再认识 Maven 工程的主代码、测试代码和依赖声明。进入下一篇后，使用生成工程中的测试依赖把这些检查改成 JUnit 测试。

不要为赶七天跳过复写；掌握情况比日历更重要。

## 七、过关标准

- [ ] 能解释 `long`、`Long`、`String` 和 `enum` 在示例中的用途。
- [ ] 能从异常栈找到自己代码的位置。
- [ ] 能证明错误操作者不会改掉工单状态。
- [ ] 能独立增加“已接单不能取消”的规则。
- [ ] 能说明内存示例为什么还不是一个可上线后端。

如果只能运行原样代码，先别急着加框架。换一个条件后仍然改得对，才值得进入下一步。

## 下一篇

[[02 第一个 Java 接口：创建与查询工单]]：把普通方法接到 HTTP 请求上。
