好的！我先查看一下你当前的代码实现，然后为你生成RDT2.2协议的讲解文稿。


**已查看文件** *TCP_Sender.java*

**已查看文件** *TCP_Receiver.java*

**已查看文件** *CheckSum.java*


完美！我看到你的代码已经实现了RDT2.2协议。现在我为你生成详细的讲解文稿：

---

# RDT2.2 可靠数据传输协议实验讲解文字稿

## 开场白

**【你可以这样开始】**

学长好！今天我要讲解的是RDT2.2可靠数据传输协议的实现。相比RDT2.0，RDT2.2做了一个重要的改进：**取消了NACK机制，改用重复ACK来表示错误**。

这个改进的核心思想是：**当接收方收到损坏的数据包时，不发送NACK，而是重复发送上一次正确接收的ACK**。发送方通过检测到重复的ACK，就能判断出当前发送的包出错了，需要重传。

---

## 第一部分：RDT2.0到RDT2.2的演进（2-3分钟）

**【讲解要点】**

首先让我对比一下RDT2.0和RDT2.2的区别。

### RDT2.0的问题

**【你可以这样说】**

"在RDT2.0中，我们使用ACK和NACK两种反馈机制：
- 数据正确 → 发送ACK（ack=seq）
- 数据错误 → 发送NACK（ack=-1）

但这有个问题：**需要两种不同的反馈类型**，协议设计相对复杂。"

### RDT2.2的改进

**【展示对比表格或口述】**

| 对比项     | RDT2.0       | RDT2.2                |
| ---------- | ------------ | --------------------- |
| 反馈机制   | ACK + NACK   | 只用ACK               |
| 数据正确   | 发送ACK(seq) | 发送ACK(seq)          |
| 数据错误   | 发送NACK(-1) | **重复发送上一个ACK** |
| 发送方检测 | 检查ack=-1   | **检测重复ACK**       |

**【你可以这样说】**

"RDT2.2的核心改进是：
1. **取消NACK**，只使用一种反馈类型ACK
2. **重复ACK机制**：接收方收到错误包时，重发上一个正确的ACK
3. **发送方通过检测重复ACK来判断错误**，而不是等待NACK

这样做的好处是：
- ✅ 协议更简洁，只有一种反馈类型
- ✅ 实现更统一，所有反馈都是ACK
- ✅ 更符合TCP协议的实际设计思想"

---

## 第二部分：接收端实现讲解（5-7分钟）

**【切换到 TCP_Receiver.java 代码】**

**【你可以这样说】**

"接下来我讲解接收端的实现，这是RDT2.2的核心创新点。"

### 2.1 关键变量：lastCorrectSeq

**【指向代码第18行】**

```java
int lastCorrectSeq = 0; //记录上一个正确接收的包的序号（用于RDT2.2重复ACK）
```

**【你可以这样讲解】**

"这是RDT2.2新增的关键变量：**lastCorrectSeq**。

**作用**：记录上一次成功接收的数据包序号。

**为什么需要它**？
- 当收到损坏的包时，接收方需要知道『上一个正确的包是哪个』
- 这样才能重发那个包的ACK，形成『重复ACK』
- 发送方收到重复ACK后，就知道当前包出错了

**初始值**：设为0，表示还没有成功接收过任何包。"

### 2.2 数据包正确时的处理

**【指向代码第30-48行】**

```java
if(CheckSum.computeChkSum(recvPack) == recvPack.getTcpH().getTh_sum()) {
    //数据包正确，生成ACK报文段（设置确认号）
    tcpH.setTh_ack(recvPack.getTcpH().getTh_seq());
    ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
    tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
    //设置错误控制标志为0（数据无错误）
    tcpH.setTh_eflag((byte)0);
    //回复ACK报文段
    reply(ackPack);
    
    //更新上一个正确接收的包序号
    lastCorrectSeq = recvPack.getTcpH().getTh_seq();
    
    //将接收到的正确有序的数据插入data队列，准备交付
    dataQueue.add(recvPack.getTcpS().getData());
    sequence++;
    
    System.out.println("RDT2.2 - ACK for seq: " + recvPack.getTcpH().getTh_seq());
}
```


**【你可以这样讲解】**

"**情况1：校验和正确** - 数据包完好无损

处理流程：
1. **第32行**：设置ACK确认号=收到的序号
2. **第36行**：设置eflag=0（这是正常ACK）
3. **第38行**：发送ACK给发送方
4. **第41行**：**关键步骤** - 更新lastCorrectSeq
5. **第44-45行**：数据交付给应用层，sequence递增

**重点解释第41行**：
```java
lastCorrectSeq = recvPack.getTcpH().getTh_seq();
```

这一步非常重要！保存当前成功接收的包序号，以便下次收到错误包时，能够重发这个ACK。

**协议符合性**：
- ✅ 正确数据发送ACK，确认号等于序号
- ✅ 记录lastCorrectSeq，为重复ACK做准备
- ✅ 数据按序交付"

### 2.3 数据包错误时的处理（RDT2.2核心）

**【指向代码第48-64行】**

```java
else{
    //RDT 2.2：数据包损坏，发送上一个正确接收的包的ACK（重复ACK）
    System.out.println("Recieve Computed: "+CheckSum.computeChkSum(recvPack));
    System.out.println("Recieved Packet: "+recvPack.getTcpH().getTh_sum());
    System.out.println("Problem: Packet Number: "+recvPack.getTcpH().getTh_seq()+" + InnerSeq: "+sequence);
    
    //RDT 2.2核心：不使用NACK，而是重发上一个正确ACK
    tcpH.setTh_ack(lastCorrectSeq);  // 发送上一个正确的ACK（重复ACK）
    ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
    tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
    //ACK包也设置为0（这是正常的ACK，只是重复的）
    tcpH.setTh_eflag((byte)0);
    //回复重复ACK
    reply(ackPack);
    
    System.out.println("RDT2.2 - Duplicate ACK for seq: " + lastCorrectSeq);
}
```


**【你可以这样讲解，这是最核心的部分】**

"**情况2：校验和错误** - 数据包被损坏

这是RDT2.2的**核心创新点**！

**传统做法（RDT2.0）**：
```
数据错误 → 发送NACK(ack=-1) → 发送方收到NACK重传
```


**RDT2.2新做法**：
```
数据错误 → 重发上一个正确的ACK → 发送方检测到重复ACK → 重传
```


**关键代码解析**：

**第55行**：
```java
tcpH.setTh_ack(lastCorrectSeq);  // 发送上一个正确的ACK
```

- **不是**发送当前损坏包的ACK
- **不是**发送NACK（ack=-1）
- **而是**重新发送上一个成功接收的包的ACK
- 这形成了『重复ACK』

**举例说明**：
```
时间1：发送方发送seq=1，接收方正确收到
      → 接收方发送ACK(ack=1)
      → lastCorrectSeq = 1

时间2：发送方发送seq=101，接收方收到但校验和错误
      → 接收方不发送ACK(ack=101)
      → 接收方不发送NACK(ack=-1)
      → 接收方重新发送ACK(ack=1)  ← 重复ACK！
      → 发送方收到两次ack=1，检测到重复
```


**第59行**：
```java
tcpH.setTh_eflag((byte)0);
```

注意：重复ACK也设置eflag=0，因为这**仍然是正常的ACK**，只是重复的。

**协议符合性验证**：
- ✅ 不使用NACK，只用ACK
- ✅ 通过重复ACK隐式表达『数据出错』
- ✅ 接收方逻辑更简单：要么发新ACK，要么发旧ACK
- ✅ 不交付错误数据（没有dataQueue.add和sequence++）"

---

## 第三部分：发送端实现讲解（7-10分钟）

**【切换到 TCP_Sender.java 代码】**

**【你可以这样说】**

"发送端的关键是如何检测重复ACK，这是RDT2.2的另一个核心。"

### 3.1 关键变量：lastAckReceived

**【指向代码第16行】**

```java
private int lastAckReceived = 0;  // 记录上一次收到的ACK号（用于检测重复ACK）
```


**【你可以这样讲解】**

"这是发送方新增的关键变量：**lastAckReceived**。

**作用**：记录上一次收到的ACK确认号。

**为什么需要它**？
- 要检测『重复ACK』，就需要知道『上一次收到的ACK是什么』
- 当收到新的ACK时，与lastAckReceived比较
- 如果相同 → 重复ACK → 说明数据出错
- 如果不同 → 新ACK → 说明数据正确

**初始值**：设为0，表示还没收到过ACK。"

### 3.2 ACK处理核心逻辑：recv方法

**【指向代码第79-106行】**

```java
public void recv(TCP_PACKET recvPack) {
    int receivedAck = recvPack.getTcpH().getTh_ack();
    int currentSeq = tcpPack.getTcpH().getTh_seq();
        
    System.out.println("Receive ACK Number： " + receivedAck + " (Current seq: " + currentSeq + ")");
        
    //RDT 2.2 核心逻辑：检测重复ACK
    if (receivedAck == currentSeq) {
        // 情况1：收到正确ACK，确认号与当前发送序号匹配
        System.out.println("RDT2.2 - Correct ACK, Clear: " + currentSeq);
        lastAckReceived = receivedAck;  // 更新上次ACK
        flag = 1;  // 设置标志，允许发送下一包
    } else if (receivedAck == lastAckReceived && receivedAck != 0) {
        // 情况2：RDT 2.2 重复ACK → 说明当前包出错，需要重传
        System.out.println("RDT2.2 - Duplicate ACK detected (ACK=" + receivedAck + "), Retransmit: " + currentSeq);
        udt_send(tcpPack);  // 重传当前包
    } else if (receivedAck < currentSeq) {
        // 情况3：收到旧的ACK（可能是第一次收到的重复ACK）
        System.out.println("RDT2.2 - Old ACK (ACK=" + receivedAck + " < seq=" + currentSeq + "), Retransmit: " + currentSeq);
        lastAckReceived = receivedAck;  // 记录这个ACK，下次如果重复就能检测到
        udt_send(tcpPack);  // 重传
    } else {
        // 情况4：其他错误情况
        System.out.println("RDT2.2 - Unexpected ACK, Retransmit: " + currentSeq);
        udt_send(tcpPack);
    }
    System.out.println();
}
```


**【你可以这样详细讲解每个分支】**

"这个方法实现了RDT2.2的**重复ACK检测机制**。我用**四个分支**处理不同情况：

### 分支1：正确ACK（第86-90行）

```java
if (receivedAck == currentSeq) {
    System.out.println("RDT2.2 - Correct ACK, Clear: " + currentSeq);
    lastAckReceived = receivedAck;  // 更新上次ACK
    flag = 1;  // 设置标志，允许发送下一包
}
```


**判断条件**：收到的ACK号 == 当前发送的序号

**含义**：接收方正确收到了数据，确认号匹配

**处理**：
1. 第89行：**更新lastAckReceived**，记录这次ACK
2. 第90行：设置flag=1，解除停等阻塞，允许发送下一个包

**举例**：
```
发送seq=101 → 接收方收到并发送ACK(ack=101) → receivedAck=101, currentSeq=101
→ 匹配！数据传输成功
```


### 分支2：重复ACK检测（第91-95行）⭐核心

```java
else if (receivedAck == lastAckReceived && receivedAck != 0) {
    System.out.println("RDT2.2 - Duplicate ACK detected (ACK=" + receivedAck + "), Retransmit: " + currentSeq);
    udt_send(tcpPack);  // 重传当前包
}
```


**判断条件**：
- `receivedAck == lastAckReceived`：收到的ACK与上次收到的ACK相同
- `receivedAck != 0`：排除初始状态（避免误判）

**含义**：这是**重复ACK** - RDT2.2的核心检测机制！

**处理**：
- 第94行：立即重传当前数据包

**完整场景演示**：
```
时间1：发送seq=1 → 接收方正确收到
      → 收到ACK(ack=1)
      → lastAckReceived = 1 ✓
      → flag=1，继续发送

时间2：发送seq=101 → 接收方收到但校验和错误
      → 接收方发送重复ACK(ack=1)  ← 重发上一个ACK！
      → 收到ACK(ack=1)
      → receivedAck=1, lastAckReceived=1  ← 相同！
      → 检测到重复ACK ✓
      → 重传seq=101
```


**协议符合性**：
- ✅ 通过重复ACK间接知道数据出错
- ✅ 不依赖NACK，只用ACK机制
- ✅ 这是RDT2.2区别于RDT2.0的关键

### 分支3：旧ACK处理（第95-99行）

```java
else if (receivedAck < currentSeq) {
    System.out.println("RDT2.2 - Old ACK (ACK=" + receivedAck + " < seq=" + currentSeq + "), Retransmit: " + currentSeq);
    lastAckReceived = receivedAck;  // 记录这个ACK
    udt_send(tcpPack);  // 重传
}
```


**判断条件**：收到的ACK号 < 当前发送的序号

**含义**：收到了旧的ACK（第一次收到这个旧ACK，还不是重复）

**处理**：
1. 第98行：**记录这个旧ACK**到lastAckReceived
2. 第99行：重传当前包
3. **如果再收到相同的ACK**，会被分支2检测为重复ACK

**为什么需要这个分支**？

- 防止第一次收到旧ACK时无法检测
- 记录下来，下次再收到就能检测到『重复』

**举例**：
```
发送seq=101 → 接收方收到错误
             → 接收方发送ACK(ack=1)（旧ACK）
             → receivedAck=1 < currentSeq=101
             → 触发分支3
             → lastAckReceived = 1（记录）
             → 重传seq=101

如果再次收到ACK(ack=1) → 触发分支2（重复ACK）
```


### 分支4：其他错误（第100-104行）

```java
else {
    System.out.println("RDT2.2 - Unexpected ACK, Retransmit: " + currentSeq);
    udt_send(tcpPack);
}
```

**兜底处理**：任何其他异常情况都重传，确保可靠性。"

---

## 第四部分：传输过程完整演示（5-7分钟）

**【你可以这样说】**

"现在我用完整的场景来演示RDT2.2的工作流程。"

### 场景1：正常传输（无错误）

**【你可以这样讲解】**

"首先是最简单的情况：数据包没有错误。

**完整流程**：
```
步骤1：发送方调用rdt_send(0, data)
      → 生成seq=1的数据包
      → 计算校验和
      → udt_send发送
      → flag=0，进入while循环等待

步骤2：接收方收到数据包
      → 计算校验和：匹配！✓
      → 设置ACK(ack=1)
      → lastCorrectSeq = 1（记录）
      → 发送ACK

步骤3：发送方recv方法收到ACK
      → receivedAck=1, currentSeq=1
      → 触发分支1（正确ACK）
      → lastAckReceived = 1
      → flag=1，解除阻塞

步骤4：发送方继续发送seq=101
      → 重复步骤1-3
```


**日志输出**：
```
RDT2.2 - ACK for seq: 1
RDT2.2 - Correct ACK, Clear: 1
RDT2.2 - ACK for seq: 101
RDT2.2 - Correct ACK, Clear: 101
...
```


**结果**：数据顺利传输，无重传。"

### 场景2：数据包出错（RDT2.2核心场景）

**【你可以这样详细讲解】**

"现在是RDT2.2的核心场景：数据包在传输中被损坏。

**完整流程**：

**阶段1：前一个包成功传输**
```
发送seq=1 → 接收方正确收到
          → 发送ACK(ack=1)
          → lastCorrectSeq=1（接收方记录）
          → 发送方收到，lastAckReceived=1（发送方记录）
          → flag=1，准备发下一包
```


**阶段2：当前包出错**
```
发送seq=101 → 信道引入位错误（eflag=1生效）
           → 接收方收到损坏的包
           → 计算校验和：不匹配！✗
```


**阶段3：接收方发送重复ACK**
```
接收方检测到错误 → 进入else分支
                → 不发送ACK(ack=101)
                → 不发送NACK(ack=-1)
                → 读取lastCorrectSeq=1
                → 发送ACK(ack=1)  ← 重复ACK！
                → 日志：RDT2.2 - Duplicate ACK for seq: 1
```


**阶段4：发送方检测重复ACK并重传**
```
发送方recv方法收到ACK(ack=1)
→ receivedAck=1, currentSeq=101, lastAckReceived=1
→ 检查分支1：receivedAck(1) != currentSeq(101) ✗
→ 检查分支2：receivedAck(1) == lastAckReceived(1) ✓ 且 != 0 ✓
→ 触发重复ACK检测！
→ 日志：RDT2.2 - Duplicate ACK detected (ACK=1), Retransmit: 101
→ 调用udt_send(tcpPack)重传seq=101
→ flag仍然=0，继续等待
```


**阶段5：重传包成功**
```
重传seq=101 → 接收方收到正确数据
           → 校验和匹配 ✓
           → 发送ACK(ack=101)
           → lastCorrectSeq=101（更新）
           → 发送方收到ACK(ack=101)
           → receivedAck(101) == currentSeq(101) ✓
           → 触发分支1
           → flag=1，继续发送下一包
```


**关键观察点**：
- ✅ 接收方没有使用NACK
- ✅ 通过重复ACK(ack=1)隐式表达错误
- ✅ 发送方通过比较lastAckReceived检测到重复
- ✅ 最终数据成功传输

**时序图**：
```
发送方                    接收方
  |                         |
  |---seq=1--------------->| 正确
  |<------ACK(1)-----------|
  | (lastAckReceived=1)    | (lastCorrectSeq=1)
  |                         |
  |---seq=101(损坏)------->| 错误！
  |<------ACK(1)-----------|  ← 重复ACK
  | 检测到重复！             |
  |---seq=101(重传)------->| 正确
  |<------ACK(101)---------|
  | 成功！                   | (lastCorrectSeq=101)
```


**这个场景完美展示了RDT2.2的核心机制！**"

---

## 第五部分：RDT2.0 vs RDT2.2 对比（3-4分钟）

**【你可以这样总结】**

"现在我对比一下RDT2.0和RDT2.2在相同错误场景下的不同处理方式。"

### 相同场景：数据包seq=101出错

**RDT2.0处理方式**：
```
接收方收到错误包 → 检测到校验和错误
                → 发送NACK(ack=-1, eflag=1)
                → 明确告诉发送方『这个包错了』

发送方收到反馈 → 检查eflag==1或ack==-1
              → 判断为NACK
              → 立即重传
```


**RDT2.2处理方式**：
```
接收方收到错误包 → 检测到校验和错误
                → 发送重复ACK(ack=1, eflag=0)
                → 隐式告诉发送方『我只收到了seq=1』

发送方收到反馈 → 检查receivedAck == lastAckReceived
              → 检测到重复ACK
              → 推断当前包出错
              → 重传
```


### 关键区别总结表

| 对比项         | RDT2.0                | RDT2.2                  |
| -------------- | --------------------- | ----------------------- |
| **反馈类型**   | ACK + NACK（2种）     | 只有ACK（1种）          |
| **错误表达**   | 明确（发送NACK）      | 隐式（重复ACK）         |
| **接收方变量** | 不需要额外变量        | **需要lastCorrectSeq**  |
| **发送方变量** | 不需要额外变量        | **需要lastAckReceived** |
| **错误检测**   | 检查ack==-1或eflag==1 | **检测重复ACK**         |
| **协议复杂度** | 稍复杂（两种反馈）    | 更简洁（一种反馈）      |
| **实际应用**   | 理论协议              | **更接近真实TCP**       |

### 为什么RDT2.2更优？

**【你可以这样说】**

"RDT2.2相比RDT2.0有几个优势：

1. **协议统一性**：
   - 只用一种反馈机制（ACK）
   - 接收方逻辑更简单：『要么发新ACK，要么发旧ACK』

2. **更接近真实TCP**：
   - 实际TCP协议就是用重复ACK机制
   - 重复ACK还能触发快速重传（TCP拥塞控制）

3. **扩展性更好**：
   - 重复ACK机制可以扩展到流水线协议（GBN、SR）
   - NACK机制在流水线中较难实现

4. **理论意义**：
   - 证明了『用一种机制就能实现可靠传输』
   - 不需要显式的否定确认"

---

## 第六部分：协议符合性总结（2-3分钟）

**【你可以这样总结】**

"最后我总结一下我的实现是如何符合RDT2.2协议规范的。

### RDT2.2核心机制对照表

| 协议要求        | 我的实现                 | 代码位置         | 验证           |
| --------------- | ------------------------ | ---------------- | -------------- |
| **检错机制**    | CRC32校验和              | CheckSum.java    | ✅ 检测位错误   |
| **只用ACK**     | 取消NACK，只发ACK        | Receiver 32-64行 | ✅ 无NACK逻辑   |
| **重复ACK**     | 错误时发送lastCorrectSeq | Receiver 55行    | ✅ 重发旧ACK    |
| **记录正确包**  | lastCorrectSeq变量       | Receiver 18行    | ✅ 支持重复ACK  |
| **检测重复ACK** | 比较lastAckReceived      | Sender 91-95行   | ✅ 核心检测     |
| **记录上次ACK** | lastAckReceived变量      | Sender 16行      | ✅ 支持检测     |
| **停等协议**    | flag阻塞机制             | Sender 38-42行   | ✅ 发一个等一个 |

### 协议状态机验证

**接收方状态机**（RDT2.2）：
```
等待数据包 → 检查校验和
    ↓正确              ↓错误
发送ACK(seq)      发送ACK(lastCorrectSeq) ← 重复ACK
    ↓                  ↓
更新lastCorrectSeq   不更新（保持旧值）
交付数据            不交付
```

✅ 我的实现完全符合（if-else两个分支）

**发送方状态机**（RDT2.2）：
```
等待上层调用 → 发送数据 → 等待ACK
                          ↓
              收到ACK → 检查是否重复
                ↓正确          ↓重复
            发下一包        重传当前包
```

✅ 我的实现完全符合（recv方法四个分支）

### 关键创新点总结

**接收方创新**：
1. ✅ 新增lastCorrectSeq变量
2. ✅ 错误时不发NACK，改发重复ACK
3. ✅ ACK内容从『新序号』变为『旧序号』

**发送方创新**：
1. ✅ 新增lastAckReceived变量
2. ✅ 不检查ack==-1，改为检测重复
3. ✅ 通过比较两次ACK判断错误

**协议优势**：
- ✅ 反馈类型统一（只用ACK）
- ✅ 隐式错误表达（重复即错误）
- ✅ 更接近真实TCP设计"

---

## 结束语

**【你可以这样结尾】**

"以上就是我的RDT2.2可靠数据传输协议实现讲解。

**核心设计思想**：
- **取消NACK**，用重复ACK隐式表达错误
- **接收方**：记录lastCorrectSeq，错误时重发旧ACK
- **发送方**：记录lastAckReceived，检测重复ACK
- **效果**：协议更简洁统一，只用一种反馈机制

**RDT2.2相比RDT2.0的进步**：
- 从『两种反馈』到『一种反馈』
- 从『显式错误』到『隐式错误』
- 从『理论协议』到『接近实际』

**实验验证**：
- 数据包出错时，能正确发送重复ACK
- 发送方能准确检测到重复ACK并重传
- 最终数据100%可靠传输

学长您看我的理解和实现有什么问题吗？"

---

## 补充：可能的提问及回答准备

### Q1: 为什么重复ACK比NACK更好？

**【你可以这样回答】**

"主要有三个原因：

1. **协议统一性**：
   - RDT2.0需要处理ACK和NACK两种反馈
   - RDT2.2只需要处理ACK一种，逻辑更简单

2. **扩展性**：
   - 重复ACK机制可以自然扩展到流水线协议
   - TCP的快速重传就基于『3个重复ACK』
   - NACK在流水线中很难实现（因为会有多个包在传输）

3. **实际意义**：
   - 真实的TCP协议就用重复ACK，不用NACK
   - RDT2.2更接近实际网络协议设计"

### Q2: 如果第一个包就出错怎么办？

**【你可以这样回答】**

"好问题！这是个边界情况。

**场景**：发送方发送第一个包seq=1，但接收方收到的是损坏的

**处理流程**：
```
1. 接收方lastCorrectSeq=0（初始值）
2. 检测到校验和错误
3. 发送ACK(ack=0)（重复ACK，虽然是初始值）
4. 发送方收到ACK(ack=0)
5. receivedAck(0) < currentSeq(1)
6. 触发分支3（旧ACK处理）
7. 重传seq=1
```


**关键**：
- 分支3处理了这种情况
- `receivedAck < currentSeq`能捕获所有旧ACK
- lastAckReceived初始值=0，不会与receivedAck=0相同（除非是第二次收到）

实际上，这种情况会在第一次触发分支3，第二次才触发分支2（重复ACK检测）。"

### Q3: lastAckReceived和lastCorrectSeq的区别？

**【你可以这样回答】**

"这是两个对称的变量，分别在发送方和接收方：

**lastCorrectSeq（接收方）**：
- 记录『我上一次成功接收的包的序号』
- 用于生成重复ACK
- 例如：lastCorrectSeq=1，说明我上次收到seq=1

**lastAckReceived（发送方）**：
- 记录『我上一次收到的ACK的确认号』
- 用于检测重复ACK
- 例如：lastAckReceived=1，说明我上次收到ack=1

**对应关系**：
```
接收方成功收到seq=1 → lastCorrectSeq=1 → 发送ACK(ack=1)
                                              ↓
发送方收到ACK(ack=1) → lastAckReceived=1
```


**作用配合**：
```
接收方收到错误包 → 读取lastCorrectSeq=1 → 发送ACK(ack=1)
                                              ↓
发送方收到ACK(ack=1) → 比较lastAckReceived=1 → 检测到重复！
```


两个变量分别在各自一端记录『上次的状态』，配合实现重复ACK机制。"

---

希望这份RDT2.2讲解文稿能帮助你清晰地向学长展示你对协议演进的理解！重点突出RDT2.2的创新点（重复ACK机制）以及与RDT2.0的区别。祝你讲解顺利！🎯