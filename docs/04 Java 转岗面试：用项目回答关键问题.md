---
type: note
status: growing
created: 2026-10-17
updated: 2026-10-17
tags:
  - career/java
aliases: []
---

# 04 Java 转岗面试：用项目回答关键问题

面试准备最容易走偏的地方，是背了很多术语，却回答不了自己项目里的一个异常。

**先把每个问题讲到“我在哪里用了、怎么验证、有什么限制”，再补定义。** 下面是本项目优先练习的问题，不是对所有公司面试范围的预测。

## 一、先练五分钟项目介绍

按这个顺序说，不必背整段稿件：

**30 秒：用户和问题。** 谁提交工单，谁处理，原来有什么不便。

**1 分钟：自己做了什么。** 前端、接口、数据表、测试和部署分别完成到哪里。区分独立完成、参考实现和 AI 辅助。

**2 分钟：讲两个具体难点。** 例如对象权限和竞争接单。说出错误方案为什么失败，自己的方案如何验证。

**1 分钟：演示。** 用两个账号操作，展示正常流程与一个失败场景。

**30 秒：限制和下一步。** 例如尚未实现转派、未做大规模压测。没有做过的部分不要用术语补齐。

项目介绍不是技术名词报菜名。每说一个能力，最好有对应文件、测试或操作结果。

## 二、Java 基础先回答这四个问题

### 1. 字符串为什么不用 == 比内容

回答要点：Java 引用类型的 `==` 比较引用是否相同，`String.equals` 比较字符串内容。基本类型数值和枚举的比较不要与它混为一谈。

自己验证：分别创建两个内容相同的字符串，比较 `==` 和 `equals`；不要只拿字符串字面量做实验后就下结论。

项目落点：工单状态用枚举时可以比较枚举值；从数据库读出的字符串要先按明确规则转换。

参考：[Java Object.equals](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Object.html#equals(java.lang.Object))、[String.equals](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#equals(java.lang.Object))。

### 2. List、Set、Map 分别解决什么问题

不要只说“有序、无序”。先说明 List 表达列表，Set 表达去重，Map 表达键到值的映射；具体顺序和性能要看实现。

项目落点：列表响应用 List，参与人员编号去重用 Set，第一版内存工单按编号查询用 Map。

追问准备：普通 HashMap 不是并发容器；更换容器也不自动解决跨多次操作的业务原子性。

参考：[Java Collections Framework](https://dev.java/learn/api/collections-and-streams/collections-framework/)。

### 3. 为什么不能捕获所有异常后返回成功

因为调用方会把失败当成成功，日志和业务状态也可能失去对应关系。异常处理应该保留失败语义，按接口约定转换响应。

项目证据：提交非法标题，得到 400；事件写入失败，业务没有被悄悄记作成功。

可以捕获特定异常后恢复，但必须说清楚恢复策略和后续状态，不是“catch 住就好了”。

参考：[Java Exceptions](https://dev.java/learn/language/annotations-exceptions/exceptions/)。

### 4. Java 的接口与 TypeScript 接口能否直接等同

不要只说语法相似。Java 的接口参与类型体系与运行时类实现关系；TypeScript 的类型接口用于静态检查，不能直接当作运行时对象使用。

练习：定义一个存储接口，分别提供内存实现和数据库实现。说明调用方依赖接口后，哪些地方不用跟着修改。

参考：[Java Interfaces](https://dev.java/learn/language/oop/interfaces/)、[TypeScript Everyday Types](https://www.typescriptlang.org/docs/handbook/2/everyday-types.html)。

## 三、Spring 先回答三个“为什么”

### 5. 为什么 Controller 不自己 new Service

Spring 管理组件及其依赖，构造器注入让依赖关系更明确，也方便测试时传入替代实现。

但“不能 new”不是普遍规则。第 2 篇单元测试就手动创建了不依赖框架能力的 Service。需要 Spring 代理、事务等行为时，必须考虑是否真的经过容器管理的对象。

参考：[Spring 依赖注入](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)。

### 6. 参数校验和业务校验有什么区别

标题非空、长度上限属于输入约束；只有创建人能取消 OPEN 工单，属于结合身份和当前数据的业务规则。

`@Valid` 不会自动证明操作者有权限。验证通过之后，仍需要检查业务状态。

项目证据：给一条格式完全合法、但针对他人工单的请求，确认它仍然被拒绝。

参考：[Spring MVC Validation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)。

### 7. 加了 @Transactional 为什么可能没有预期效果

先核对调用有没有经过代理，事务管理器与数据源是否正确，再检查异常是否被吞掉，以及回滚规则是否匹配。

默认代理模式下，同类内部直接调用的事务方法不因为标了注解就自动生效。默认运行时异常触发回滚，不要把所有异常一概而论。

项目证据：让操作记录插入失败，等请求结束后再查询数据库，证明状态真的回滚。只有异常截图不够。

参考：[Spring 事务注解规则](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)。

## 四、数据库和权限要讲出证据

### 8. 为什么这个查询需要这个索引

先说具体查询：“按创建人筛选，再按编号倒序分页”。然后说明候选索引的字段顺序，展示执行计划与测试数据规模。

不要说“加索引必然更快”。选择性、数据量、查询列和写入成本都会影响取舍。

项目证据：保留优化前后 SQL、执行计划和测试条件。小数据集测不出显著差异，可以如实说明。

参考：[MySQL 优化与索引](https://dev.mysql.com/doc/refman/8.4/en/optimization-indexes.html)。

### 9. 两个人同时接单怎么处理

说明先查后改存在时间窗口，再展示带 `status = 'OPEN'` 条件的 UPDATE，以及受影响行数判断。

不要说“用了事务，所以不会并发冲突”。事务需要结合具体隔离和锁行为分析。

项目证据：两个真实数据库事务并发执行，最终只有一个处理人、一条成功接单记录，失败方得到约定错误。

具体 SQL 与条件见 [[03 工单项目交付：数据库、权限、事务与部署]]。

### 10. 用户登录了，为什么还可能越权

登录只能说明请求代表某个身份，不能说明这个身份可以读写任意编号。

项目证据：用户 A 取得用户 B 的工单编号后，仍不能查询或取消；前端隐藏按钮不算验证。

身份与操作范围的实现参考：[Spring Security 授权](https://docs.spring.io/spring-security/reference/servlet/authorization/index.html)。

## 五、最后准备两个交付问题

### 11. 线上接口报 500，你先查什么

从一次可定位的请求开始：请求时间、路径、请求标识、脱敏后的关键输入和实际响应。

再按调用路径缩小范围：应用日志里的根因，数据库连接和 SQL，相关配置及最近变更。不要一看到 500 就先重启，也不要把完整敏感请求体复制进聊天工具。

练习一次“数据库地址配置错误”的故障。记录观察到的现象、排除的猜测、根因和恢复方法。

这是建议的排错流程，不代表每次错误都有相同原因。

### 12. 怎么证明你能独立开发，而不只是跟教程

增加一个原教程没有的需求，例如“列表按 OPEN 和 IN_PROGRESS 筛选”。

自己补输入校验、可见范围条件、SQL、响应和测试，再解释为什么不能把状态字符串直接拼进 SQL。

能独立改需求，比反复演示原功能更能检验是否掌握。这个练习不需要增加新框架。

## 六、面试前做一次不看笔记的检查

- [ ] 用五分钟讲清项目，不夸大完成范围。
- [ ] 从一个按钮追到接口、Service、SQL 和返回值。
- [ ] 解释一次错误、一次回滚、一次权限拒绝。
- [ ] 展示实际测试结果，而不是仅展示测试代码。
- [ ] 被问到没做过的场景时，能明确区分经验、理解与假设。

算法、JVM、线程池、缓存等题目是否追加，按目标岗位和真实面试反馈决定。上面这些项目问题也不能代替所有面试准备。

## 关联阅读

[[05 Java 快速转岗：投递、复盘与 AI 学习法]]：把准备落实到投递和反馈，而不一直停留在补课。
